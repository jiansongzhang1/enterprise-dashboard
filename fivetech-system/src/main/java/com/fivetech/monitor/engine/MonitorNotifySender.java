package com.fivetech.monitor.engine;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import com.fivetech.common.lark.LarkClient;
import com.fivetech.common.lark.LarkErrorCategory;
import com.fivetech.common.lark.LarkResult;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.engine.domain.MonitorPendingNotify;
import com.fivetech.monitor.mapper.MonitorNotifyMapper;
import com.fivetech.monitor.service.IMonitorLarkChatService;

/**
 * 发送任务：把 monitor_notify_log 里到期的 pending 记录投递到 Lark 群（富文本 post，每条告警单独一条；
 * 正文里的网址和 [文字](网址) 转成可点击的链接），回写结果。
 *
 * <ul>
 *   <li>成功 → 记录 sent；事件 notify_status = sent、notified_at、notify_count + 1；规则 last_notified_at（静默期从这里算）。</li>
 *   <li>可重试（超时、限流、5xx、未识别的错误码）→ 1 / 5 / 30 分钟后重试，共重试 3 次；仍失败 → failed，事件显示「发送失败」。</li>
 *   <li>目标不可用（机器人不在群里、群已解散、无权限）→ 直接 failed；机器人被移出或群解散时把该群置为失效。</li>
 *   <li>凭证无效（App ID / Secret 错误、应用停用、缺权限）→ 暂停发送：记录 failed、事件 paused（「僅記錄」，悬停
 *       「Lark 機器人憑證失效，未發送」），本轮剩下的不再请求 Lark；告警到研发值班。不补发（FY-30 R13②、R14、R15）。</li>
 *   <li>没配凭证（本站没有可用的 Lark 机器人）→ 不调 Lark，事件 unset（「未配置」，FY-30 R4）。</li>
 *   <li>事件已停止（规则在发送前被暂停或删除）→ 不发送，记录与事件都为 failed，原因「規則已暫停或刪除，取消發送」。</li>
 * </ul>
 * 调用方负责加锁（多实例只有一个在发）；一轮超过时间预算时剩下的留到下一轮。
 *
 * @author fivetech
 */
@Component
public class MonitorNotifySender
{
    private static final Logger log = LoggerFactory.getLogger(MonitorNotifySender.class);

    /** 第 1、2、3 次重试前等待的分钟数 */
    static final int[] RETRY_DELAY_MINUTES = { 1, 5, 30 };

    static final String STOPPED_ERROR = "規則已暫停或刪除，取消發送";

    /** FY-30 R13②：僅記錄的悬停文案 */
    public static final String PAUSED_REASON = "Lark 機器人憑證失效，未發送";

    /** FY-30 R4：本站没有可用的 Lark 机器人（一期 = 没配 MONITOR_LARK_APP_ID / SECRET） */
    static final String NO_BOT_REASON = "本站尚未接入 Lark 機器人，未發送";

    private final MonitorNotifyMapper mapper;

    private final MonitorNotifyWriter writer;

    private final LarkClient larkClient;

    private final IMonitorLarkChatService chatService;

    private final MonitorProperties properties;

    private final ObjectProvider<MonitorOpsAlerter> opsAlerter;

    public MonitorNotifySender(MonitorNotifyMapper mapper, MonitorNotifyWriter writer, LarkClient larkClient,
            IMonitorLarkChatService chatService, MonitorProperties properties, ObjectProvider<MonitorOpsAlerter> opsAlerter)
    {
        this.mapper = mapper;
        this.writer = writer;
        this.larkClient = larkClient;
        this.chatService = chatService;
        this.properties = properties;
        this.opsAlerter = opsAlerter;
    }

    /** 有到期记录的站点 */
    public List<String> dueSites(LocalDateTime now)
    {
        return mapper.selectNotifySites(now);
    }

    /**
     * 投递一个站点的到期记录。
     *
     * @param now      本轮时间（IST），写入 sent_at / next_retry_at 等
     * @param deadline 本轮截止的系统时间（毫秒，System.currentTimeMillis 口径）；过了就停，剩下的下一轮再发
     * @return 本轮处理（成功、重试、失败）的条数
     */
    public int sendSite(String siteCode, LocalDateTime now, long deadline)
    {
        MonitorScope scope = new MonitorScope(siteCode, null);
        MonitorProperties.Engine engine = properties.getEngine();
        List<MonitorPendingNotify> rows = mapper.selectDueNotify(scope, now, Math.max(1, engine.getNotifyBatchSize()));
        MonitorProperties.Lark lark = properties.getLark();
        // 本站没有可用的 Lark 机器人（一期 = 没配凭证）：不调 Lark，事件「未配置」（FY-30 R4）
        boolean noBot = StringUtils.isEmpty(lark.getAppId()) || StringUtils.isEmpty(lark.getAppSecret());
        String credentialError = null;
        int handled = 0;
        int sent = 0;
        for (MonitorPendingNotify row : rows)
        {
            if (credentialError == null && System.currentTimeMillis() > deadline)
            {
                log.info("[monitor] 站点 {} 本轮发送超过时间预算，剩余 {} 条下一轮再发", siteCode, rows.size() - handled);
                break;
            }
            handled++;
            if (noBot)
            {
                writer.notSent(scope, row, now, NO_BOT_REASON, MonitorNotifyWriter.EVENT_UNSET);
                continue;
            }
            if (credentialError != null)
            {
                // 同一个应用的调用都会失败，不再请求 Lark；暂停发送，不算发送失败（FY-30 R13②）
                writer.notSent(scope, row, now, credentialError, MonitorNotifyWriter.EVENT_PAUSED);
                continue;
            }
            if (row.getEventId() != null && "stopped".equals(row.getEventStatus()))
            {
                // 事件的通知状态也改为失败（带原因），否则会一直停在「待发送」
                writer.failed(scope, row, now, STOPPED_ERROR, true);
                log.info("[monitor] 发送记录 {} 的事件 {} 已停止（规则暂停或删除），取消发送", row.getId(), row.getEventId());
                continue;
            }
            if (StringUtils.isEmpty(row.getChatId()) || StringUtils.isEmpty(row.getContent()))
            {
                writer.failed(scope, row, now, "缺少通知群或消息內容", true);
                continue;
            }
            // 富文本（post）发送：正文里的网址、[文字](网址) 都能点开
            LarkResult<String> result = larkClient.sendPost(lark.getAppId(), lark.getAppSecret(), "chat_id",
                    row.getChatId(), "", MonitorLarkPost.paragraphs(row.getContent()));
            if (result.isSuccess())
            {
                writer.sent(scope, row, now);
                sent++;
                log.info("[monitor] 告警已发送 site={} notify={} event={} chat={} messageId={}", siteCode, row.getId(),
                        row.getEventId(), row.getChatId(), result.getData());
                continue;
            }
            // error_msg 给异常事件「通知」列的悬停看：可读原因 +（Lark 错误码 / HTTP 状态）；Lark 原文只进日志
            String error = reason(result, false);
            LarkErrorCategory category = result.getCategory();
            if (category == LarkErrorCategory.CREDENTIAL_INVALID)
            {
                credentialError = error;
                // FY-30 R14：凭证无效 → 本次及本轮之后的告警暂停发送，事件「僅記錄」（悬停「Lark 機器人憑證失效，未發送」）；
                // 下一轮仍会先试一次，凭证恢复后自动正常发送，暂停期间的不补发（R15）
                writer.notSent(scope, row, now, error, MonitorNotifyWriter.EVENT_PAUSED);
                log.error("[monitor] Lark 应用凭证无效或缺少权限，站点 {} 本轮告警暂停发送：{}（检查 MONITOR_LARK_APP_ID / "
                        + "MONITOR_LARK_APP_SECRET 和应用权限 im:message:send_as_bot）", siteCode, result);
                alertOps("監控告警暫停發送：Lark 機器人憑證失效", "站點 " + siteCode + "：" + error);
            }
            else if (category == LarkErrorCategory.TARGET_INVALID)
            {
                chatService.markDeadIfChatGone(scope, row.getChatId(), result);
                writer.failed(scope, row, now, error, true);
                log.warn("[monitor] 通知群不可用，发送失败 site={} notify={} chat={} {}", siteCode, row.getId(),
                        row.getChatId(), result);
            }
            else if (row.getRetryCount() < RETRY_DELAY_MINUTES.length)
            {
                LocalDateTime next = now.plusMinutes(RETRY_DELAY_MINUTES[row.getRetryCount()]);
                writer.retry(scope, row, row.getRetryCount() + 1, next, error);
                log.warn("[monitor] 发送失败，{} 重试（第 {} 次）site={} notify={} chat={} {}", next,
                        row.getRetryCount() + 1, siteCode, row.getId(), row.getChatId(), result);
            }
            else
            {
                writer.failed(scope, row, now, reason(result, true), true);
                log.error("[monitor] 发送失败，已重试 {} 次，放弃 site={} notify={} chat={} {}", RETRY_DELAY_MINUTES.length,
                        siteCode, row.getId(), row.getChatId(), result);
            }
        }
        if (handled > 0)
        {
            log.info("[monitor] 站点 {} 本轮发送 {} 条，成功 {} 条", siteCode, handled, sent);
        }
        return handled;
    }

    /** 本轮截止时间 */
    public long deadline()
    {
        return System.currentTimeMillis()
                + Duration.ofSeconds(Math.max(5, properties.getEngine().getNotifyBudgetSeconds())).toMillis();
    }

    private void alertOps(String title, String detail)
    {
        MonitorOpsAlerter alerter = opsAlerter.getIfAvailable();
        if (alerter == null)
        {
            return;
        }
        try
        {
            alerter.alert(title, detail);
        }
        catch (RuntimeException e)
        {
            log.warn("[monitor] 值班告警提交失败：{}", e.getMessage());
        }
    }

    /**
     * 写进 error_msg 的发送失败原因（繁体，前端悬停直接显示）：可读原因 +（Lark 错误码 / HTTP 状态）。不含 token、凭证。
     *
     * @param gaveUp 可重试的错误已重试满次数
     */
    public static String reason(LarkResult<?> r, boolean gaveUp)
    {
        String text;
        switch (r.getCategory())
        {
            case CREDENTIAL_INVALID:
                text = PAUSED_REASON;
                break;
            case TARGET_INVALID:
                text = r.isChatGone() ? "機器人已不在群裡或群已解散" : "沒有向該群發送消息的權限";
                break;
            default:
                text = gaveUp ? "網絡超時或 Lark 限流，已重試 " + RETRY_DELAY_MINUTES.length + " 次" : "網絡超時或 Lark 限流，稍後重試";
                break;
        }
        StringBuilder sb = new StringBuilder(text);
        List<String> codes = new java.util.ArrayList<>();
        if (r.getLarkCode() != null)
        {
            codes.add("code=" + r.getLarkCode());
        }
        if (r.getHttpStatus() > 0)
        {
            codes.add("http=" + r.getHttpStatus());
        }
        if (!codes.isEmpty())
        {
            sb.append("（").append(String.join(" ", codes)).append("）");
        }
        return sb.toString();
    }
}
