package com.fivetech.monitor.engine.domain;

import java.time.LocalDateTime;

/**
 * 写回 monitor_rule_state 的一行。
 *
 * @author fivetech
 */
public class MonitorRuleStateRecord
{
    private Long ruleId;

    private String siteCode;

    private LocalDateTime runStartHour;

    /** jsonb 文本 */
    private String recentLevels;

    /** jsonb 文本 */
    private String recentPeriods;

    private Long openEventId;

    private LocalDateTime lastPeriodEnd;

    private LocalDateTime lastDataHour;

    private LocalDateTime updateTime;

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

    public Long getOpenEventId()
    {
        return openEventId;
    }

    public void setOpenEventId(Long openEventId)
    {
        this.openEventId = openEventId;
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

    public LocalDateTime getUpdateTime()
    {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime)
    {
        this.updateTime = updateTime;
    }
}
