package com.fivetech.monitor.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 事件列表的一行（monitor_event + 规则、指标、负责人、通知群、处理人）。
 *
 * @author fivetech
 */
public class MonitorEventRow
{
    private Long eventId;
    private Long ruleId;
    private Integer ruleNo;
    private boolean ruleDeleted;
    private String metricCode;
    private String metricName;
    private String valueFormat;
    private String level;
    private boolean escalated;
    private String status;
    private LocalDateTime startHour;
    private LocalDateTime triggerHour;
    private LocalDateTime endHour;
    private LocalDateTime lastPeriodEnd;
    private LocalDateTime stoppedAt;
    private BigDecimal currentValue;
    private BigDecimal baselineValue;
    private String ruleSnapshot;
    private Long ownerUserId;
    private String ownerEmail;
    private String notifyStatus;
    private LocalDateTime notifiedAt;
    private String chatId;
    private String chatName;
    private String chatStatus;
    private String notifyError;
    private LocalDateTime muteSentAt;
    private Integer muteHours;
    private String handleStatus;
    private String handleNote;
    private String handledBy;
    private LocalDateTime handledAt;
    private boolean handleReset;

    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public Integer getRuleNo() { return ruleNo; }
    public void setRuleNo(Integer ruleNo) { this.ruleNo = ruleNo; }
    public boolean isRuleDeleted() { return ruleDeleted; }
    public void setRuleDeleted(boolean ruleDeleted) { this.ruleDeleted = ruleDeleted; }
    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }
    public String getValueFormat() { return valueFormat; }
    public void setValueFormat(String valueFormat) { this.valueFormat = valueFormat; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public boolean isEscalated() { return escalated; }
    public void setEscalated(boolean escalated) { this.escalated = escalated; }
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
    public BigDecimal getCurrentValue() { return currentValue; }
    public void setCurrentValue(BigDecimal currentValue) { this.currentValue = currentValue; }
    public BigDecimal getBaselineValue() { return baselineValue; }
    public void setBaselineValue(BigDecimal baselineValue) { this.baselineValue = baselineValue; }
    public String getRuleSnapshot() { return ruleSnapshot; }
    public void setRuleSnapshot(String ruleSnapshot) { this.ruleSnapshot = ruleSnapshot; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getOwnerEmail() { return ownerEmail; }
    public void setOwnerEmail(String ownerEmail) { this.ownerEmail = ownerEmail; }
    public String getNotifyStatus() { return notifyStatus; }
    public void setNotifyStatus(String notifyStatus) { this.notifyStatus = notifyStatus; }
    public LocalDateTime getNotifiedAt() { return notifiedAt; }
    public void setNotifiedAt(LocalDateTime notifiedAt) { this.notifiedAt = notifiedAt; }
    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }
    public String getChatName() { return chatName; }
    public void setChatName(String chatName) { this.chatName = chatName; }
    public String getChatStatus() { return chatStatus; }
    public void setChatStatus(String chatStatus) { this.chatStatus = chatStatus; }
    public String getNotifyError() { return notifyError; }
    public void setNotifyError(String notifyError) { this.notifyError = notifyError; }
    public LocalDateTime getMuteSentAt() { return muteSentAt; }
    public void setMuteSentAt(LocalDateTime muteSentAt) { this.muteSentAt = muteSentAt; }
    public Integer getMuteHours() { return muteHours; }
    public void setMuteHours(Integer muteHours) { this.muteHours = muteHours; }
    public String getHandleStatus() { return handleStatus; }
    public void setHandleStatus(String handleStatus) { this.handleStatus = handleStatus; }
    public String getHandleNote() { return handleNote; }
    public void setHandleNote(String handleNote) { this.handleNote = handleNote; }
    public String getHandledBy() { return handledBy; }
    public void setHandledBy(String handledBy) { this.handledBy = handledBy; }
    public LocalDateTime getHandledAt() { return handledAt; }
    public void setHandledAt(LocalDateTime handledAt) { this.handledAt = handledAt; }
    public boolean isHandleReset() { return handleReset; }
    public void setHandleReset(boolean handleReset) { this.handleReset = handleReset; }
}
