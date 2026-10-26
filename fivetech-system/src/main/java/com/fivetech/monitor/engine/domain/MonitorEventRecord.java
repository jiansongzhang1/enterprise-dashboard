package com.fivetech.monitor.engine.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 写入 / 更新 monitor_event 的一行（告警事件）。
 *
 * @author fivetech
 */
public class MonitorEventRecord
{
    private Long eventId;

    private String siteCode;

    private Long ruleId;

    private String metricCode;

    /** 固定 alert（2026-10-22 起数据缺失不再写事件记录） */
    private String eventType;

    private String level;

    private String peakLevel;

    private boolean escalated;

    /** open / resolved / stopped */
    private String status;

    private LocalDateTime startHour;

    private LocalDateTime triggerHour;

    private LocalDateTime endHour;

    private LocalDateTime lastPeriodEnd;

    private BigDecimal currentValue;

    private BigDecimal baselineValue;

    /** jsonb 文本 */
    private String ruleSnapshot;

    private String notifyStatus;

    private String handleStatus;

    private boolean handleReset;

    private LocalDateTime now;

    /** 静默期内未发送：压住本次的那次发送时间（规则的 last_notified_at） */
    private LocalDateTime muteSentAt;

    /** 静默期内未发送：当时规则的静默时长（小时） */
    private Integer muteHours;

    public LocalDateTime getMuteSentAt()
    {
        return muteSentAt;
    }

    public void setMuteSentAt(LocalDateTime muteSentAt)
    {
        this.muteSentAt = muteSentAt;
    }

    public Integer getMuteHours()
    {
        return muteHours;
    }

    public void setMuteHours(Integer muteHours)
    {
        this.muteHours = muteHours;
    }

    public Long getEventId()
    {
        return eventId;
    }

    public void setEventId(Long eventId)
    {
        this.eventId = eventId;
    }

    public String getSiteCode()
    {
        return siteCode;
    }

    public void setSiteCode(String siteCode)
    {
        this.siteCode = siteCode;
    }

    public Long getRuleId()
    {
        return ruleId;
    }

    public void setRuleId(Long ruleId)
    {
        this.ruleId = ruleId;
    }

    public String getMetricCode()
    {
        return metricCode;
    }

    public void setMetricCode(String metricCode)
    {
        this.metricCode = metricCode;
    }

    public String getEventType()
    {
        return eventType;
    }

    public void setEventType(String eventType)
    {
        this.eventType = eventType;
    }

    public String getLevel()
    {
        return level;
    }

    public void setLevel(String level)
    {
        this.level = level;
    }

    public String getPeakLevel()
    {
        return peakLevel;
    }

    public void setPeakLevel(String peakLevel)
    {
        this.peakLevel = peakLevel;
    }

    public boolean isEscalated()
    {
        return escalated;
    }

    public void setEscalated(boolean escalated)
    {
        this.escalated = escalated;
    }

    public String getStatus()
    {
        return status;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public LocalDateTime getStartHour()
    {
        return startHour;
    }

    public void setStartHour(LocalDateTime startHour)
    {
        this.startHour = startHour;
    }

    public LocalDateTime getTriggerHour()
    {
        return triggerHour;
    }

    public void setTriggerHour(LocalDateTime triggerHour)
    {
        this.triggerHour = triggerHour;
    }

    public LocalDateTime getEndHour()
    {
        return endHour;
    }

    public void setEndHour(LocalDateTime endHour)
    {
        this.endHour = endHour;
    }

    public LocalDateTime getLastPeriodEnd()
    {
        return lastPeriodEnd;
    }

    public void setLastPeriodEnd(LocalDateTime lastPeriodEnd)
    {
        this.lastPeriodEnd = lastPeriodEnd;
    }

    public BigDecimal getCurrentValue()
    {
        return currentValue;
    }

    public void setCurrentValue(BigDecimal currentValue)
    {
        this.currentValue = currentValue;
    }

    public BigDecimal getBaselineValue()
    {
        return baselineValue;
    }

    public void setBaselineValue(BigDecimal baselineValue)
    {
        this.baselineValue = baselineValue;
    }

    public String getRuleSnapshot()
    {
        return ruleSnapshot;
    }

    public void setRuleSnapshot(String ruleSnapshot)
    {
        this.ruleSnapshot = ruleSnapshot;
    }

    public String getNotifyStatus()
    {
        return notifyStatus;
    }

    public void setNotifyStatus(String notifyStatus)
    {
        this.notifyStatus = notifyStatus;
    }

    public String getHandleStatus()
    {
        return handleStatus;
    }

    public void setHandleStatus(String handleStatus)
    {
        this.handleStatus = handleStatus;
    }

    public boolean isHandleReset()
    {
        return handleReset;
    }

    public void setHandleReset(boolean handleReset)
    {
        this.handleReset = handleReset;
    }

    public LocalDateTime getNow()
    {
        return now;
    }

    public void setNow(LocalDateTime now)
    {
        this.now = now;
    }
}
