package com.fivetech.monitor.domain;

import java.time.LocalDateTime;

/**
 * 运营总览提醒条用的异常中事件（monitor_event + 规则号 + 指标名 + 规则运行状态的最近周期）。
 *
 * @author fivetech
 */
public class MonitorOverviewEvent
{
    private Long eventId;

    private Long ruleId;

    private Integer ruleNo;

    private String metricCode;

    private String metricName;

    private String valueFormat;

    private String level;

    private boolean escalated;

    private LocalDateTime startHour;

    private LocalDateTime lastPeriodEnd;

    /** 触发时的规则快照（JSON 文本）：阈值、周期、持续次数 */
    private String ruleSnapshot;

    /** 规则运行状态里的最近周期（JSON 文本）：[{from, to, value, baseline, level}] */
    private String recentPeriods;

    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public Integer getRuleNo() { return ruleNo; }
    public void setRuleNo(Integer ruleNo) { this.ruleNo = ruleNo; }
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
    public LocalDateTime getStartHour() { return startHour; }
    public void setStartHour(LocalDateTime startHour) { this.startHour = startHour; }
    public LocalDateTime getLastPeriodEnd() { return lastPeriodEnd; }
    public void setLastPeriodEnd(LocalDateTime lastPeriodEnd) { this.lastPeriodEnd = lastPeriodEnd; }
    public String getRuleSnapshot() { return ruleSnapshot; }
    public void setRuleSnapshot(String ruleSnapshot) { this.ruleSnapshot = ruleSnapshot; }
    public String getRecentPeriods() { return recentPeriods; }
    public void setRecentPeriods(String recentPeriods) { this.recentPeriods = recentPeriods; }
}
