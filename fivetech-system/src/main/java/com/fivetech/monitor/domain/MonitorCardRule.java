package com.fivetech.monitor.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 运营总览卡片 alert 用的规则（含已暂停的）+ 运行状态 + 异常中事件。一个站点一个指标最多一条规则。
 *
 * @author fivetech
 */
public class MonitorCardRule
{
    private Long ruleId;
    private Integer ruleNo;
    private String metricCode;
    private String valueFormat;
    private boolean enabled;
    private String judgeType;
    private BigDecimal warnLow;
    private BigDecimal warnHigh;
    private BigDecimal critLow;
    private BigDecimal critHigh;
    private Integer periodHours;
    private Integer durationCount;
    private Integer escalateHours;
    private String minSampleKey;
    private java.math.BigDecimal minSampleValue;
    /** monitor_rule_state.recent_periods（JSON 文本） */
    private String recentPeriods;
    private Long eventId;
    private String eventLevel;
    private Boolean eventEscalated;
    private LocalDateTime eventStartHour;
    /** 事件触发（开事件）的周期起点；注意升级时长从这里算 */
    private LocalDateTime eventTriggerHour;
    private LocalDateTime eventLastPeriodEnd;

    /** 异常中事件触发时的规则快照（JSON）；原因句按它写，与提醒条一致（MON-19） */
    private String eventRuleSnapshot;

    public String getEventRuleSnapshot()
    {
        return eventRuleSnapshot;
    }

    public void setEventRuleSnapshot(String eventRuleSnapshot)
    {
        this.eventRuleSnapshot = eventRuleSnapshot;
    }

    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public Integer getRuleNo() { return ruleNo; }
    public void setRuleNo(Integer ruleNo) { this.ruleNo = ruleNo; }
    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
    public String getValueFormat() { return valueFormat; }
    public void setValueFormat(String valueFormat) { this.valueFormat = valueFormat; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getJudgeType() { return judgeType; }
    public void setJudgeType(String judgeType) { this.judgeType = judgeType; }
    public BigDecimal getWarnLow() { return warnLow; }
    public void setWarnLow(BigDecimal warnLow) { this.warnLow = warnLow; }
    public BigDecimal getWarnHigh() { return warnHigh; }
    public void setWarnHigh(BigDecimal warnHigh) { this.warnHigh = warnHigh; }
    public BigDecimal getCritLow() { return critLow; }
    public void setCritLow(BigDecimal critLow) { this.critLow = critLow; }
    public BigDecimal getCritHigh() { return critHigh; }
    public void setCritHigh(BigDecimal critHigh) { this.critHigh = critHigh; }
    public Integer getPeriodHours() { return periodHours; }
    public void setPeriodHours(Integer periodHours) { this.periodHours = periodHours; }
    public Integer getDurationCount() { return durationCount; }
    public void setDurationCount(Integer durationCount) { this.durationCount = durationCount; }
    public Integer getEscalateHours() { return escalateHours; }
    public String getMinSampleKey() { return minSampleKey; }
    public void setMinSampleKey(String minSampleKey) { this.minSampleKey = minSampleKey; }
    public java.math.BigDecimal getMinSampleValue() { return minSampleValue; }
    public void setMinSampleValue(java.math.BigDecimal minSampleValue) { this.minSampleValue = minSampleValue; }
    public void setEscalateHours(Integer escalateHours) { this.escalateHours = escalateHours; }
    public String getRecentPeriods() { return recentPeriods; }
    public void setRecentPeriods(String recentPeriods) { this.recentPeriods = recentPeriods; }
    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
    public String getEventLevel() { return eventLevel; }
    public void setEventLevel(String eventLevel) { this.eventLevel = eventLevel; }
    public Boolean getEventEscalated() { return eventEscalated; }
    public void setEventEscalated(Boolean eventEscalated) { this.eventEscalated = eventEscalated; }
    public LocalDateTime getEventStartHour() { return eventStartHour; }
    public void setEventStartHour(LocalDateTime eventStartHour) { this.eventStartHour = eventStartHour; }
    public LocalDateTime getEventTriggerHour() { return eventTriggerHour; }
    public void setEventTriggerHour(LocalDateTime eventTriggerHour) { this.eventTriggerHour = eventTriggerHour; }
    public LocalDateTime getEventLastPeriodEnd() { return eventLastPeriodEnd; }
    public void setEventLastPeriodEnd(LocalDateTime eventLastPeriodEnd) { this.eventLastPeriodEnd = eventLastPeriodEnd; }
}
