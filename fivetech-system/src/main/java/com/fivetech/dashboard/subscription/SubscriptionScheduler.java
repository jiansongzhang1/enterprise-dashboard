package com.fivetech.dashboard.subscription;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.fivetech.dashboard.mapper.DashboardSubscriptionMapper;

/**
 * 订阅简报的定时发送：每 5 分钟（{@code dashboard.subscription.cron}）检查一遍启用中的订阅，
 * 按各自站点的时区判断是否到了发送时刻。
 *
 * <ul>
 *   <li>发送时刻按频率算（{@link SubscriptionSchedule}：每日、每週、每 N 小時）；整点后再等 {@code send-delay-minutes}
 *       （默认 10 分钟）才发，给 UDS 落数留时间；</li>
 *   <li>过了发送时刻 {@code grace-hours}（默认 3 小时）还没发出去（服务重启、取数失败）就不再补发，等第二天；</li>
 *   <li>先取数，取数成功再用 {@code last_slot} 抢占（UPDATE … WHERE last_slot &lt;&gt; 该时刻），抢到的实例才发——
 *       多实例部署也只发一次；取数失败不抢占，下一轮再试；补发期内最后一轮仍失败时抢占并记「資料取數失敗」；</li>
 *   <li>截图失败整次不发，记「截圖生成失敗」；每轮先把超过 10 分钟仍在发送中的批次记为「發送中斷」。</li>
 * </ul>
 *
 * @author fivetech
 */
@Component
@ConditionalOnProperty(prefix = "dashboard.subscription", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SubscriptionScheduler
{
    private static final Logger log = LoggerFactory.getLogger(SubscriptionScheduler.class);

    private final DashboardSubscriptionMapper mapper;

    private final SubscriptionSender sender;

    private final SubscriptionProperties properties;

    public SubscriptionScheduler(DashboardSubscriptionMapper mapper, SubscriptionSender sender, SubscriptionProperties properties)
    {
        this.mapper = mapper;
        this.sender = sender;
        this.properties = properties;
    }

    @Scheduled(cron = "${dashboard.subscription.cron:0 */5 * * * *}")
    public void tick()
    {
        java.util.List<DashboardSubscription> subs;
        try
        {
            subs = mapper.selectEnabled();
        }
        catch (RuntimeException e)
        {
            // 表还没建（没执行 SQL）时只记一次错误，不影响其他功能
            log.error("[subscription] 读取订阅失败（确认已执行 sql/add_dashboard_subscription_postgresql.sql）：{}", e.getMessage());
            return;
        }
        try
        {
            int n = mapper.failStaleRuns(STALE_MINUTES);
            if (n > 0)
            {
                log.warn("[subscription] {} 个推送批次超过 {} 分钟未结束，已记为失败（發送中斷）", n, STALE_MINUTES);
            }
        }
        catch (RuntimeException e)
        {
            log.warn("[subscription] 检查中断的推送批次失败（确认已执行 release_next ⑭）：{}", e.getMessage());
        }
        for (DashboardSubscription s : subs)
        {
            try
            {
                runIfDue(s);
            }
            catch (RuntimeException e)
            {
                log.error("[subscription] 订阅 {} 本轮处理失败", s.getId(), e);
            }
        }
    }

    void runIfDue(DashboardSubscription s)
    {
        if (s.getSendHour() == null)
        {
            return;
        }
        LocalDateTime now = LocalDateTime.now(sender.zone(s.getSiteCode())).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime slot = SubscriptionSchedule.due(s, now, properties.getSendDelayMinutes(), properties.getGraceHours(),
                s.getLastSlot());
        if (slot == null)
        {
            return;
        }
        String slotText = slot.format(SubscriptionSender.SLOT);
        SubscriptionBrief brief;
        try
        {
            brief = sender.prepare(s, slot, false);
        }
        catch (SubscriptionSender.PrepareException e)
        {
            // 补发期内的最后一轮仍失败：抢占这个时刻、记一次「資料取數失敗」，之后不再重试；之前只记最近状态，下一轮再试
            if (lastTry(now, slot))
            {
                if (mapper.claimSlot(s.getId(), slotText) == 1)
                {
                    sender.failWhole(s, null, sender.receivers(s), SubscriptionSender.SCHEDULE, "system", slotText, slot,
                        SubscriptionReason.DATA, e.getMessage());
                }
                return;
            }
            log.warn("[subscription] 订阅 {} 发送时刻 {} 取数失败，下一轮再试（{} 小时内）：{}", s.getId(), slotText,
                    properties.getGraceHours(), e.getMessage());
            sender.markPrepareFailed(s, e.getMessage());
            return;
        }
        if (mapper.claimSlot(s.getId(), slotText) != 1)
        {
            log.info("[subscription] 订阅 {} 发送时刻 {} 已由其他实例发送", s.getId(), slotText);
            return;
        }
        // 抢到发送权之后才调 AI、截图：多实例时只调一次；截图失败整次不发（shot）
        sender.addAi(s, brief, "system");
        sender.addSnapshot(s, brief, true);
        sender.deliverChecked(s, brief, sender.receivers(s), SubscriptionSender.SCHEDULE, "system", slotText, slot);
    }

    /** 这一轮之后（cron 每 5 分钟）就过了补发期：是补发期内的最后一次尝试 */
    boolean lastTry(LocalDateTime now, LocalDateTime slot)
    {
        return !now.plusMinutes(TICK_MINUTES).isBefore(slot.plusHours(Math.max(1, properties.getGraceHours())));
    }

    /** cron 的间隔（默认每 5 分钟）；只用来判断「最后一次尝试」 */
    static final int TICK_MINUTES = 5;

    /** RUNNING 超过多少分钟算中断 */
    static final int STALE_MINUTES = 10;

    /**
     * 到期的发送时刻：今天或昨天（跨零点补发）的 sendHour:00，要求已过等待分钟、未过补发期、且还没发过；没有返回 null。
     */
    static LocalDateTime due(LocalDateTime now, int sendHour, int delayMinutes, int graceHours, String lastSlot)
    {
        DashboardSubscription daily = new DashboardSubscription();
        daily.setFreqType(SubscriptionSchedule.DAY);
        daily.setSendHour(sendHour);
        return SubscriptionSchedule.due(daily, now, delayMinutes, graceHours, lastSlot);
    }
}
