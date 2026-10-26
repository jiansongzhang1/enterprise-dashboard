package com.fivetech.monitor.domain.vo;

import java.math.BigDecimal;
import java.util.List;

/**
 * GET /monitor/events 的一行，字段见设计文档「异常事件 · 事件列表」。
 *
 * @author fivetech
 */
public class MonitorEventVO
{
    private Long eventId;
    private Long ruleId;
    private Integer ruleNo;
    private String ruleCode;
    private boolean ruleDeleted;
    private String metricCode;
    private String metricName;
    private String unit;
    private String level;
    private boolean escalated;
    private Integer escalateHours;
    private String status;
    private String judgeType;
    private int periodHours;
    private BigDecimal currentValue;
    private BigDecimal baselineValue;
    private ThresholdVO threshold;
    private List<String> judgeBasis;
    private String startHour;
    private String triggerHour;
    private String endHour;
    private String stoppedAt;
    private long durationHours;
    private String durationTip;
    private OwnerVO owner;
    private NotifyVO notify;
    private HandleVO handle;

    /** 对照的判定线（取自事件的规则快照）；跌幅类 low 是跌幅阈值（%） */
    public static class ThresholdVO
    {
        private final String against;
        private final BigDecimal low;
        private final BigDecimal high;
        public ThresholdVO(String against, BigDecimal low, BigDecimal high) { this.against = against; this.low = low; this.high = high; }
        public String getAgainst() { return against; }
        public BigDecimal getLow() { return low; }
        public BigDecimal getHigh() { return high; }
    }

    public static class OwnerVO
    {
        private final Long userId;
        private final String email;
        public OwnerVO(Long userId, String email) { this.userId = userId; this.email = email; }
        public Long getUserId() { return userId; }
        public String getEmail() { return email; }
    }

    public static class NotifyVO
    {
        /** none / pending / sent / failed / unset */
        private String status;
        private String sentAt;
        private String chatId;
        private String chatName;
        /** ok / dead */
        private String chatStatus;
        /** status = failed 时的失败原因（繁体，可直接显示），如「機器人已不在群裡或群已解散（code=230002 http=400）」；其余为 null */
        private String error;
        /** status = paused 时：暂停发送的原因（前端「僅記錄」的悬停），如「Lark 機器人憑證失效，未發送」；其余为 null */
        private String pausedReason;
        public String getPausedReason() { return pausedReason; }
        public void setPausedReason(String pausedReason) { this.pausedReason = pausedReason; }
        /** status = muted 时：静默期内未发送的原因；其余为 null */
        private MutedVO muted;
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
        public MutedVO getMuted() { return muted; }
        public void setMuted(MutedVO muted) { this.muted = muted; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getSentAt() { return sentAt; }
        public void setSentAt(String sentAt) { this.sentAt = sentAt; }
        public String getChatId() { return chatId; }
        public void setChatId(String chatId) { this.chatId = chatId; }
        public String getChatName() { return chatName; }
        public void setChatName(String chatName) { this.chatName = chatName; }
        public String getChatStatus() { return chatStatus; }
        public void setChatStatus(String chatStatus) { this.chatStatus = chatStatus; }
    }

    /** 静默期内未发送：「靜默期內：上次發送 {lastSentAt}，靜默 {muteHours} 小時，本次未發送」 */
    public static class MutedVO
    {
        private final String lastSentAt;
        private final Integer muteHours;
        public MutedVO(String lastSentAt, Integer muteHours) { this.lastSentAt = lastSentAt; this.muteHours = muteHours; }
        public String getLastSentAt() { return lastSentAt; }
        public Integer getMuteHours() { return muteHours; }
    }

    public static class HandleVO
    {
        /** pending / done */
        private String status;
        private String note;
        private String by;
        private String at;
        /** 曾标记处理、之后由注意升为严重而自动退回未处理 */
        private boolean reset;
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
        public String getBy() { return by; }
        public void setBy(String by) { this.by = by; }
        public String getAt() { return at; }
        public void setAt(String at) { this.at = at; }
        public boolean isReset() { return reset; }
        public void setReset(boolean reset) { this.reset = reset; }
    }

    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public Integer getRuleNo() { return ruleNo; }
    public void setRuleNo(Integer ruleNo) { this.ruleNo = ruleNo; }
    public String getRuleCode() { return ruleCode; }
    public void setRuleCode(String ruleCode) { this.ruleCode = ruleCode; }
    public boolean isRuleDeleted() { return ruleDeleted; }
    public void setRuleDeleted(boolean ruleDeleted) { this.ruleDeleted = ruleDeleted; }
    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public boolean isEscalated() { return escalated; }
    public void setEscalated(boolean escalated) { this.escalated = escalated; }
    public Integer getEscalateHours() { return escalateHours; }
    public void setEscalateHours(Integer escalateHours) { this.escalateHours = escalateHours; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getJudgeType() { return judgeType; }
    public void setJudgeType(String judgeType) { this.judgeType = judgeType; }
    public int getPeriodHours() { return periodHours; }
    public void setPeriodHours(int periodHours) { this.periodHours = periodHours; }
    public BigDecimal getCurrentValue() { return currentValue; }
    public void setCurrentValue(BigDecimal currentValue) { this.currentValue = currentValue; }
    public BigDecimal getBaselineValue() { return baselineValue; }
    public void setBaselineValue(BigDecimal baselineValue) { this.baselineValue = baselineValue; }
    public ThresholdVO getThreshold() { return threshold; }
    public void setThreshold(ThresholdVO threshold) { this.threshold = threshold; }
    public List<String> getJudgeBasis() { return judgeBasis; }
    public void setJudgeBasis(List<String> judgeBasis) { this.judgeBasis = judgeBasis; }
    public String getStartHour() { return startHour; }
    public void setStartHour(String startHour) { this.startHour = startHour; }
    public String getTriggerHour() { return triggerHour; }
    public void setTriggerHour(String triggerHour) { this.triggerHour = triggerHour; }
    public String getEndHour() { return endHour; }
    public void setEndHour(String endHour) { this.endHour = endHour; }
    public String getStoppedAt() { return stoppedAt; }
    public void setStoppedAt(String stoppedAt) { this.stoppedAt = stoppedAt; }
    public long getDurationHours() { return durationHours; }
    public void setDurationHours(long durationHours) { this.durationHours = durationHours; }
    public String getDurationTip() { return durationTip; }
    public void setDurationTip(String durationTip) { this.durationTip = durationTip; }
    public OwnerVO getOwner() { return owner; }
    public void setOwner(OwnerVO owner) { this.owner = owner; }
    public NotifyVO getNotify() { return notify; }
    public void setNotify(NotifyVO notify) { this.notify = notify; }
    public HandleVO getHandle() { return handle; }
    public void setHandle(HandleVO handle) { this.handle = handle; }
}
