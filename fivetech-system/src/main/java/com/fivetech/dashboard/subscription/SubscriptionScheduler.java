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
 *   <li>发送时刻 = 当天 send_hour:00；整点后再等 {@code send-delay-minutes}（默认 10 分钟）才发，给 UDS 落数留时间；</li>
 *   <li>过了发送时刻 {@code grace-hours}（默认 3 小时）还没发出去（服务重启、取数失败）就不再补发，等第二天；</li>
 *   <li>先取数，取数成功再用 {@code last_slot} 抢占（UPDATE … WHERE last_slot &lt;&gt; 该时刻），抢到的实例才发——
 *       多实例部署也只发一次；取数失败不抢占，下一轮再试。</li>
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
        LocalDateTime slot = due(now, s.getSendHour(), properties.getSendDelayMinutes(), properties.getGraceHours(),
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
        // 抢到发送权之后才调 AI：多实例时只调一次
        sender.addAi(s, brief, "system");
        sender.addSnapshot(s, brief, true);
        sender.deliver(s, brief, sender.receivers(s), SubscriptionSender.SCHEDULE, "system", slotText);
    }

    /**
     * 到期的发送时刻：今天或昨天（跨零点补发）的 sendHour:00，要求已过等待分钟、未过补发期、且还没发过；没有返回 null。
     */
    static LocalDateTime due(LocalDateTime now, int sendHour, int delayMinutes, int graceHours, String lastSlot)
    {
        LocalDateTime today = now.toLocalDate().atTime(sendHour, 0);
        for (LocalDateTime c : new LocalDateTime[] { today, today.minusDays(1) })
        {
            if (now.isBefore(c.plusMinutes(Math.max(0, delayMinutes))))
            {
                continue;
            }
            if (!now.isBefore(c.plusHours(Math.max(1, graceHours))))
            {
                return null;
            }
            return c.format(SubscriptionSender.SLOT).equals(lastSlot) ? null : c;
        }
        return null;
    }
}
