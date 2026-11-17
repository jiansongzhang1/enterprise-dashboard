package com.fivetech.monitor.domain;

import java.time.LocalDateTime;

/**
 * 条件规则的异常事件（monitor_event 里 rule_type = 'COND' 的行）+ 规则当前的版本与删除状态，给事件详情用。时间为统计时区。
 *
 * @author fivetech
 */
public class MonitorCondEvent
{
    private Long eventId;

    private Long ruleId;

    private String ruleType;

    private String level;

    private String status;

    private LocalDateTime startHour;

    private LocalDateTime triggerHour;

    private LocalDateTime endHour;

    private LocalDateTime lastPeriodEnd;

    private LocalDateTime stoppedAt;

    private String stopReason;

    private String ruleSnapshot;

    private String detail;

    private String notifyStatus;

    private String handleStatus;

    private String handleNote;

    private String handledBy;

    private LocalDateTime handledAt;

    private Long ruleVersion;

    private Boolean ruleDeleted;

    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public String getRuleType() { return ruleType; }
    public void setRuleType(String ruleType) { this.ruleType = ruleType; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getStartHour() { return startHour; }
    public void setStartHour(LocalDateTime startHour) { this.startHour = startHour; }
    public LocalDateTime getTriggerHour() { return triggerHour; }
    public void setTriggerHour(LocalDateTime triggerHour) { this.triggerHour = triggerHour; }
    public LocalDateTime getEndHour() { return endHour; }
    public void setEndHour(LocalDateTime endHour) { this.endHour = endHour; }
    public LocalDateTime getLastPeriodEnd() { return lastPeriodEnd; }
    public void setLastPeriodEnd(LocalDateTime lastPeriodEnd) { this.lastPeriodEnd = lastPeriodEnd; }
    public LocalDateTime getStoppedAt() { return stoppedAt; }
    public void setStoppedAt(LocalDateTime stoppedAt) { this.stoppedAt = stoppedAt; }
    public String getStopReason() { return stopReason; }
    public void setStopReason(String stopReason) { this.stopReason = stopReason; }
    public String getRuleSnapshot() { return ruleSnapshot; }
    public void setRuleSnapshot(String ruleSnapshot) { this.ruleSnapshot = ruleSnapshot; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getNotifyStatus() { return notifyStatus; }
    public void setNotifyStatus(String notifyStatus) { this.notifyStatus = notifyStatus; }
    public String getHandleStatus() { return handleStatus; }
    public void setHandleStatus(String handleStatus) { this.handleStatus = handleStatus; }
    public String getHandleNote() { return handleNote; }
    public void setHandleNote(String handleNote) { this.handleNote = handleNote; }
    public String getHandledBy() { return handledBy; }
    public void setHandledBy(String handledBy) { this.handledBy = handledBy; }
    public LocalDateTime getHandledAt() { return handledAt; }
    public void setHandledAt(LocalDateTime handledAt) { this.handledAt = handledAt; }
    public Long getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(Long ruleVersion) { this.ruleVersion = ruleVersion; }
    public Boolean getRuleDeleted() { return ruleDeleted; }
    public void setRuleDeleted(Boolean ruleDeleted) { this.ruleDeleted = ruleDeleted; }
}
