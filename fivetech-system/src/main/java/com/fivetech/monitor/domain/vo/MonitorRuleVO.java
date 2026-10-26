package com.fivetech.monitor.domain.vo;

/**
 * 规则列表的一行。
 *
 * <ul>
 * <li>warn / crit 统一为 {@code {low, high}}，数值与指标展示单位一致；</li>
 * <li>owner.valid = false：负责人已停用或删除；</li>
 * <li>notifyChat 为 null：未配置通知群；status = dead 时前端显示「失效」；</li>
 * <li>lastJudgedAt / judgeError：「检测 · 持续」第二行的「最近判定」或「判定失败」；</li>
 * <li>updateBy / updateTime：「最近修改」，updateBy 为邮箱。</li>
 * </ul>
 *
 * @author fivetech
 */
public class MonitorRuleVO
{
    private Long ruleId;

    private Integer ruleNo;

    /** R-001 */
    private String ruleCode;

    private String metricCode;

    private String metricName;

    /** 阈值单位：% / x / min / ₹ / 空串（人数、笔数） */
    private String unit;

    private String judgeType;

    private ThresholdVO warn;

    private ThresholdVO crit;

    private Integer periodHours;

    private Integer durationCount;

    private Integer escalateHours;

    private Integer muteHours;

    private OwnerVO owner;

    private NotifyChatVO notifyChat;

    /** IST，yyyy-MM-dd HH:mm */
    private String lastTriggerHour;

    private String lastJudgedAt;

    private String judgeError;

    private String updateBy;

    private String updateTime;

    private Boolean enabled;

    public Long getRuleId()
    {
        return ruleId;
    }

    public void setRuleId(Long ruleId)
    {
        this.ruleId = ruleId;
    }

    public Integer getRuleNo()
    {
        return ruleNo;
    }

    public void setRuleNo(Integer ruleNo)
    {
        this.ruleNo = ruleNo;
    }

    public String getRuleCode()
    {
        return ruleCode;
    }

    public void setRuleCode(String ruleCode)
    {
        this.ruleCode = ruleCode;
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

    public String getUnit()
    {
        return unit;
    }

    public void setUnit(String unit)
    {
        this.unit = unit;
    }

    public String getJudgeType()
    {
        return judgeType;
    }

    public void setJudgeType(String judgeType)
    {
        this.judgeType = judgeType;
    }

    public ThresholdVO getWarn()
    {
        return warn;
    }

    public void setWarn(ThresholdVO warn)
    {
        this.warn = warn;
    }

    public ThresholdVO getCrit()
    {
        return crit;
    }

    public void setCrit(ThresholdVO crit)
    {
        this.crit = crit;
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

    public OwnerVO getOwner()
    {
        return owner;
    }

    public void setOwner(OwnerVO owner)
    {
        this.owner = owner;
    }

    public NotifyChatVO getNotifyChat()
    {
        return notifyChat;
    }

    public void setNotifyChat(NotifyChatVO notifyChat)
    {
        this.notifyChat = notifyChat;
    }

    public String getLastTriggerHour()
    {
        return lastTriggerHour;
    }

    public void setLastTriggerHour(String lastTriggerHour)
    {
        this.lastTriggerHour = lastTriggerHour;
    }

    public String getLastJudgedAt()
    {
        return lastJudgedAt;
    }

    public void setLastJudgedAt(String lastJudgedAt)
    {
        this.lastJudgedAt = lastJudgedAt;
    }

    public String getJudgeError()
    {
        return judgeError;
    }

    public void setJudgeError(String judgeError)
    {
        this.judgeError = judgeError;
    }

    public String getUpdateBy()
    {
        return updateBy;
    }

    public void setUpdateBy(String updateBy)
    {
        this.updateBy = updateBy;
    }

    public String getUpdateTime()
    {
        return updateTime;
    }

    public void setUpdateTime(String updateTime)
    {
        this.updateTime = updateTime;
    }

    public Boolean getEnabled()
    {
        return enabled;
    }

    public void setEnabled(Boolean enabled)
    {
        this.enabled = enabled;
    }
}
