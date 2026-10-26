package com.fivetech.monitor.engine.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 判定引擎读取的一条启用规则：规则本身 + 运行状态（monitor_rule_state）+ 异常中的事件（monitor_event）。
 *
 * @author fivetech
 */
public class MonitorEngineRule
{
    private Long ruleId;

    private String siteCode;

    private Integer ruleNo;

    private String metricCode;

    /** 指标名（dashboard_metric_card） */
    private String metricName;

    /** PCT / MIN / MULTIPLE / MONEY / INT */
    private String valueFormat;

    /** 负责人邮箱（消息里写邮箱，不 @ 人） */
    private String ownerEmail;

    /** lo / hi / band / drop */
    private String judgeType;

    private BigDecimal warnLow;

    private BigDecimal warnHigh;

    private BigDecimal critLow;

    private BigDecimal critHigh;

    private Integer periodHours;

    private Integer durationCount;

    private Integer escalateHours;

    private Integer muteHours;

    /** base = 跌幅基准；其余为分母指标编码 */
    private String minSampleKey;

    private BigDecimal minSampleValue;

    private String notifyChatId;

    private String notifyChatName;

    private LocalDateTime lastNotifiedAt;

    /** 运行状态行存在时等于 ruleId */
    private Long stateRuleId;

    private LocalDateTime runStartHour;

    /** jsonb 文本，如 ["WARN","CRIT"] */
    private String recentLevels;

    /** jsonb 文本 */
    private String recentPeriods;

    private LocalDateTime lastPeriodEnd;

    /** 已处理到的数据小时 */
    private LocalDateTime lastDataHour;

    /** 异常中的事件；没有为 null */
    private Long eventId;

    private String eventLevel;

    private String eventPeakLevel;

    private Boolean eventEscalated;

    private LocalDateTime eventStartHour;

    private LocalDateTime eventTriggerHour;

    private String eventHandleStatus;

    private String eventHandledLevel;

    private Boolean eventHandleReset;

    private String eventNotifyStatus;

    public Long getRuleId()
    {
        return ruleId;
    }

    public void setRuleId(Long ruleId)
    {
        this.ruleId = ruleId;
    }

    public String getSiteCode()
    {
        return siteCode;
    }

    public void setSiteCode(String siteCode)
    {
        this.siteCode = siteCode;
    }

    public Integer getRuleNo()
    {
        return ruleNo;
    }

    public void setRuleNo(Integer ruleNo)
    {
        this.ruleNo = ruleNo;
    }

    public String getMetricCode()
    {
        return metricCode;
    }

    public void setMetricCode(String metricCode)
    {
        this.metricCode = metricCode;
    }

    public String getMetricName()
    {
        return metricName;
    }

    public void setMetricName(String metricName)
    {
        this.metricName = metricName;
    }

    public String getValueFormat()
    {
        return valueFormat;
    }

    public void setValueFormat(String valueFormat)
    {
        this.valueFormat = valueFormat;
    }

    public String getOwnerEmail()
    {
        return ownerEmail;
    }

    public void setOwnerEmail(String ownerEmail)
    {
        this.ownerEmail = ownerEmail;
    }

    public String getJudgeType()
    {
        return judgeType;
    }

    public void setJudgeType(String judgeType)
    {
        this.judgeType = judgeType;
    }

    public BigDecimal getWarnLow()
    {
        return warnLow;
    }

    public void setWarnLow(BigDecimal warnLow)
    {
        this.warnLow = warnLow;
    }

    public BigDecimal getWarnHigh()
    {
        return warnHigh;
    }

    public void setWarnHigh(BigDecimal warnHigh)
    {
        this.warnHigh = warnHigh;
    }

    public BigDecimal getCritLow()
    {
        return critLow;
    }

    public void setCritLow(BigDecimal critLow)
    {
        this.critLow = critLow;
    }

    public BigDecimal getCritHigh()
    {
        return critHigh;
    }

    public void setCritHigh(BigDecimal critHigh)
    {
        this.critHigh = critHigh;
    }

    public Integer getPeriodHours()
    {
        return periodHours;
    }

    public void setPeriodHours(Integer periodHours)
    {
        this.periodHours = periodHours;
    }

    public Integer getDurationCount()
    {
        return durationCount;
    }

    public void setDurationCount(Integer durationCount)
    {
        this.durationCount = durationCount;
    }

    public Integer getEscalateHours()
    {
        return escalateHours;
    }

    public void setEscalateHours(Integer escalateHours)
    {
        this.escalateHours = escalateHours;
    }

    public Integer getMuteHours()
    {
        return muteHours;
    }

    public void setMuteHours(Integer muteHours)
    {
        this.muteHours = muteHours;
    }

    public String getMinSampleKey()
    {
        return minSampleKey;
    }

    public void setMinSampleKey(String minSampleKey)
    {
        this.minSampleKey = minSampleKey;
    }

    public BigDecimal getMinSampleValue()
    {
        return minSampleValue;
    }

    public void setMinSampleValue(BigDecimal minSampleValue)
    {
        this.minSampleValue = minSampleValue;
    }

    public String getNotifyChatId()
    {
        return notifyChatId;
    }

    public void setNotifyChatId(String notifyChatId)
    {
        this.notifyChatId = notifyChatId;
    }

    public String getNotifyChatName()
    {
        return notifyChatName;
    }

    public void setNotifyChatName(String notifyChatName)
    {
        this.notifyChatName = notifyChatName;
    }

    public LocalDateTime getLastNotifiedAt()
    {
        return lastNotifiedAt;
    }

    public void setLastNotifiedAt(LocalDateTime lastNotifiedAt)
    {
        this.lastNotifiedAt = lastNotifiedAt;
    }

    public Long getStateRuleId()
    {
        return stateRuleId;
    }

    public void setStateRuleId(Long stateRuleId)
    {
        this.stateRuleId = stateRuleId;
    }

    public LocalDateTime getRunStartHour()
    {
        return runStartHour;
    }

    public void setRunStartHour(LocalDateTime runStartHour)
    {
        this.runStartHour = runStartHour;
    }

    public String getRecentLevels()
    {
        return recentLevels;
    }

    public void setRecentLevels(String recentLevels)
    {
        this.recentLevels = recentLevels;
    }

    public String getRecentPeriods()
    {
        return recentPeriods;
    }

    public void setRecentPeriods(String recentPeriods)
    {
        this.recentPeriods = recentPeriods;
    }

    public LocalDateTime getLastPeriodEnd()
    {
        return lastPeriodEnd;
    }

    public void setLastPeriodEnd(LocalDateTime lastPeriodEnd)
    {
        this.lastPeriodEnd = lastPeriodEnd;
    }

    public LocalDateTime getLastDataHour()
    {
        return lastDataHour;
    }

    public void setLastDataHour(LocalDateTime lastDataHour)
    {
        this.lastDataHour = lastDataHour;
    }

    public Long getEventId()
    {
        return eventId;
    }

    public void setEventId(Long eventId)
    {
        this.eventId = eventId;
    }

    public String getEventLevel()
    {
        return eventLevel;
    }

    public void setEventLevel(String eventLevel)
    {
        this.eventLevel = eventLevel;
    }

    public String getEventPeakLevel()
    {
        return eventPeakLevel;
    }

    public void setEventPeakLevel(String eventPeakLevel)
    {
        this.eventPeakLevel = eventPeakLevel;
    }

    public Boolean getEventEscalated()
    {
        return eventEscalated;
    }

    public void setEventEscalated(Boolean eventEscalated)
    {
        this.eventEscalated = eventEscalated;
    }

    public LocalDateTime getEventStartHour()
    {
        return eventStartHour;
    }

    public void setEventStartHour(LocalDateTime eventStartHour)
    {
        this.eventStartHour = eventStartHour;
    }

    public LocalDateTime getEventTriggerHour()
    {
        return eventTriggerHour;
    }

    public void setEventTriggerHour(LocalDateTime eventTriggerHour)
    {
        this.eventTriggerHour = eventTriggerHour;
    }

    public String getEventHandleStatus()
    {
        return eventHandleStatus;
    }

    public void setEventHandleStatus(String eventHandleStatus)
    {
        this.eventHandleStatus = eventHandleStatus;
    }

    public String getEventHandledLevel()
    {
        return eventHandledLevel;
    }

    public void setEventHandledLevel(String eventHandledLevel)
    {
        this.eventHandledLevel = eventHandledLevel;
    }

    public Boolean getEventHandleReset()
    {
        return eventHandleReset;
    }

    public void setEventHandleReset(Boolean eventHandleReset)
    {
        this.eventHandleReset = eventHandleReset;
    }

    public String getEventNotifyStatus()
    {
        return eventNotifyStatus;
    }

    public void setEventNotifyStatus(String eventNotifyStatus)
    {
        this.eventNotifyStatus = eventNotifyStatus;
    }
}
