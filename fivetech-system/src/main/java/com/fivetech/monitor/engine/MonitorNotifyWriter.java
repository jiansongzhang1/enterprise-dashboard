package com.fivetech.monitor.engine;

import java.time.LocalDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.engine.domain.MonitorPendingNotify;
import com.fivetech.monitor.mapper.MonitorNotifyMapper;

/**
 * 发送任务的写库：一条记录的投递结果和它带来的事件、规则变化在一个事务里提交。
 * 发送记录的更新都带「仍是 pending」条件，另一个实例已处理过的记录不会被重复回写。
 *
 * @author fivetech
 */
@Component
public class MonitorNotifyWriter
{
    /** monitor_notify_log.error_msg 的长度 */
    static final int ERROR_MAX = 500;

    private final MonitorNotifyMapper mapper;

    public MonitorNotifyWriter(MonitorNotifyMapper mapper)
    {
        this.mapper = mapper;
    }

    /** 发送成功：记录 sent；事件 sent、notified_at、notify_count + 1；规则 last_notified_at（静默期从这里算） */
    @Transactional(rollbackFor = Exception.class)
    public void sent(MonitorScope scope, MonitorPendingNotify row, LocalDateTime now)
    {
        if (mapper.markSent(scope, row.getId(), now) == 0)
        {
            return;
        }
        if (row.getEventId() != null)
        {
            mapper.updateEventSent(scope, row.getEventId(), now);
        }
        if (row.getRuleId() != null)
        {
            mapper.updateRuleNotified(scope, row.getRuleId(), now);
        }
    }

    /** 可重试的失败：记录保持 pending，到 nextRetryAt 再发；事件仍显示「待发送」 */
    @Transactional(rollbackFor = Exception.class)
    public void retry(MonitorScope scope, MonitorPendingNotify row, int retryCount, LocalDateTime nextRetryAt,
            String error)
    {
        mapper.markRetry(scope, row.getId(), retryCount, nextRetryAt, cut(error));
    }

    /**
     * 最终失败：记录 failed，事件「通知送達」显示「發送失敗」。
     *
     * @param eventFailed 是否把事件也改为 failed（false = 不动事件）
     */
    @Transactional(rollbackFor = Exception.class)
    public void failed(MonitorScope scope, MonitorPendingNotify row, LocalDateTime now, String error, boolean eventFailed)
    {
        closed(scope, row, now, error, eventFailed ? EVENT_FAILED : null);
    }

    /**
     * 没有发出、也不算发送失败（FY-30 R13②、R4）：记录 failed 并写明原因，事件改为给定的通知状态——
     * {@code paused}（机器人凭证失效，暂停发送，显示「僅記錄」）或 {@code unset}（本站没有可用的 Lark 机器人，显示「未配置」）。
     * 恢复后不补发（R15）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void notSent(MonitorScope scope, MonitorPendingNotify row, LocalDateTime now, String reason, String eventStatus)
    {
        closed(scope, row, now, reason, eventStatus);
    }

    private void closed(MonitorScope scope, MonitorPendingNotify row, LocalDateTime now, String error, String eventStatus)
    {
        if (mapper.markFailed(scope, row.getId(), now, cut(error)) == 0)
        {
            return;
        }
        if (eventStatus != null && row.getEventId() != null)
        {
            mapper.updateEventNotifyStatus(scope, row.getEventId(), eventStatus, now);
        }
    }

    static final String EVENT_FAILED = "failed";

    /** 机器人凭证失效：暂停发送，「僅記錄」 */
    public static final String EVENT_PAUSED = "paused";

    /** 本站没有可用的 Lark 机器人：「未配置」 */
    public static final String EVENT_UNSET = "unset";

    static String cut(String error)
    {
        if (error == null || error.length() <= ERROR_MAX)
        {
            return error;
        }
        return error.substring(0, ERROR_MAX);
    }
}
