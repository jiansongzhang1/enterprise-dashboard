package com.fivetech.monitor.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.engine.domain.MonitorPendingNotify;

/**
 * 发送任务的读写（monitor_notify_log 投递结果、事件通知状态、规则最近发送时间）。
 * 除 {@link #selectNotifySites(LocalDateTime)} 外都按站点（MonitorScope）限定。
 *
 * @author fivetech
 */
public interface MonitorNotifyMapper
{
    /** 有到期待发送记录的站点（跨站点，发送任务入口用） */
    List<String> selectNotifySites(@Param("now") LocalDateTime now);

    /** 本站到期的待发送记录，先到期的先发 */
    List<MonitorPendingNotify> selectDueNotify(@Param("scope") MonitorScope scope, @Param("now") LocalDateTime now,
            @Param("limit") int limit);

    /** pending → sent（乐观锁：只改仍是 pending 的行） */
    int markSent(@Param("scope") MonitorScope scope, @Param("id") Long id, @Param("now") LocalDateTime now);

    /** 仍是 pending，记下失败原因和下次重试时间 */
    int markRetry(@Param("scope") MonitorScope scope, @Param("id") Long id, @Param("retryCount") int retryCount,
            @Param("nextRetryAt") LocalDateTime nextRetryAt, @Param("error") String error);

    /** pending → failed */
    int markFailed(@Param("scope") MonitorScope scope, @Param("id") Long id, @Param("now") LocalDateTime now,
            @Param("error") String error);

    /** 事件：发送成功一次（notify_status = sent，notified_at，notify_count + 1） */
    int updateEventSent(@Param("scope") MonitorScope scope, @Param("eventId") Long eventId,
            @Param("now") LocalDateTime now);

    /** 事件的通知状态：failed 发送失败 / paused 机器人凭证失效暂停发送 / unset 没有可用机器人 */
    int updateEventNotifyStatus(@Param("scope") MonitorScope scope, @Param("eventId") Long eventId,
            @Param("status") String status, @Param("now") LocalDateTime now);

    /** 规则最近一次发送成功的时间（静默期从这里算） */
    int updateRuleNotified(@Param("scope") MonitorScope scope, @Param("ruleId") Long ruleId,
            @Param("now") LocalDateTime now);
}
