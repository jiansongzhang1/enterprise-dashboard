package com.fivetech.monitor.domain;

import java.time.LocalDateTime;

/**
 * 条件规则的检测状态（monitor_rule_state 一行）+ 进行中的事件。时间为统计时区（库里的时区）。
 *
 * <ul>
 *   <li>lastDataHour：最近一次处理的数据小时（= 检测点 − 1 小时），新建 / 启用时置为当时的上一小时，之前的不判定；</li>
 *   <li>recentPeriods：当前连续命中的检测点（站点时区，jsonb 数组），个数即连续次数；</li>
 *   <li>detail：最近一次检测的说明，如 { "noBase": [1, 3], "from": "2026-11-20" }。</li>
 * </ul>
 *
 * @author fivetech
 */
public class MonitorCondState
{
    private Long ruleId;

    private LocalDateTime runStartHour;

    private String recentPeriods;

    private LocalDateTime lastPeriodEnd;

    private LocalDateTime lastDataHour;

    private String detail;

    private Long eventId;

    private LocalDateTime eventStartHour;

    private LocalDateTime eventTriggerHour;

    private String eventDetail;

    private String eventHandleStatus;

    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public LocalDateTime getRunStartHour() { return runStartHour; }
    public void setRunStartHour(LocalDateTime runStartHour) { this.runStartHour = runStartHour; }
    public String getRecentPeriods() { return recentPeriods; }
    public void setRecentPeriods(String recentPeriods) { this.recentPeriods = recentPeriods; }
    public LocalDateTime getLastPeriodEnd() { return lastPeriodEnd; }
    public void setLastPeriodEnd(LocalDateTime lastPeriodEnd) { this.lastPeriodEnd = lastPeriodEnd; }
    public LocalDateTime getLastDataHour() { return lastDataHour; }
    public void setLastDataHour(LocalDateTime lastDataHour) { this.lastDataHour = lastDataHour; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
    public LocalDateTime getEventStartHour() { return eventStartHour; }
    public void setEventStartHour(LocalDateTime eventStartHour) { this.eventStartHour = eventStartHour; }
    public LocalDateTime getEventTriggerHour() { return eventTriggerHour; }
    public void setEventTriggerHour(LocalDateTime eventTriggerHour) { this.eventTriggerHour = eventTriggerHour; }
    public String getEventDetail() { return eventDetail; }
    public void setEventDetail(String eventDetail) { this.eventDetail = eventDetail; }
    public String getEventHandleStatus() { return eventHandleStatus; }
    public void setEventHandleStatus(String eventHandleStatus) { this.eventHandleStatus = eventHandleStatus; }
}
