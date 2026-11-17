package com.fivetech.monitor.domain.vo;

/**
 * 规则列表的一行。
 *
 * <ul>
 * <li>warn / crit 统一为 {@code {low, high}}，数值与指标展示单位一致；</li>
 * <li>owner.valid = false：负责人已停用或删除；</li>
 * <li>notifyChats：通知群（1–5 个，按选择顺序），每个 {chatId, name, status}，status = dead 时前端显示「失效」；
 *     notifyChat 为第一个群（旧字段，为 null 表示未配置通知群）；</li>
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

    /** 阈值单位：% / x / min / 空串（人数、笔数）；金额在规则列表 GET /monitor/rules 为 "money"（前端按站点币种显示符号），详情为 ₹ */
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

    private java.util.List<NotifyChatVO> notifyChats;

    public java.util.List<NotifyChatVO> getNotifyChats()
    {
        return notifyChats;
    }

    public void setNotifyChats(java.util.List<NotifyChatVO> notifyChats)
    {
        this.notifyChats = notifyChats;
    }

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

    // ------------------------------------------------------------------ release_next ㉓：三类规则同一张表

    /** METRIC 指標規則 / COND 條件規則 / ORDER 訂單規則 */
    private String ruleType;

    /** 规则名：指标规则为指标名 */
    private String name;

    /** 触发条件，按类型不同（设计文档 7.3.1） */
    private java.util.Map<String, Object> trigger;

    /** 检测：{text, durationText, snapshot, lastJudgedAt, paused, noBase} */
    private java.util.Map<String, Object> detect;

    private Boolean notifyEnabled;

    /** 同 notifyChats */
    private java.util.List<NotifyChatVO> chats;

    /** 最近触发：指标 / 条件规则为判定时间，订单规则为最新一条订单告警的触发时间 */
    private String lastTriggerAt;

    private boolean canCopy;

    private boolean canTest;

    public String getRuleType() { return ruleType; }
    public void setRuleType(String ruleType) { this.ruleType = ruleType; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public java.util.Map<String, Object> getTrigger() { return trigger; }
    public void setTrigger(java.util.Map<String, Object> trigger) { this.trigger = trigger; }
    public java.util.Map<String, Object> getDetect() { return detect; }
    public void setDetect(java.util.Map<String, Object> detect) { this.detect = detect; }
    public Boolean getNotifyEnabled() { return notifyEnabled; }
    public void setNotifyEnabled(Boolean notifyEnabled) { this.notifyEnabled = notifyEnabled; }
    public java.util.List<NotifyChatVO> getChats() { return chats; }
    public void setChats(java.util.List<NotifyChatVO> chats) { this.chats = chats; }
    public String getLastTriggerAt() { return lastTriggerAt; }
    public void setLastTriggerAt(String lastTriggerAt) { this.lastTriggerAt = lastTriggerAt; }
    public boolean isCanCopy() { return canCopy; }
    public void setCanCopy(boolean canCopy) { this.canCopy = canCopy; }
    public boolean isCanTest() { return canTest; }
    public void setCanTest(boolean canTest) { this.canTest = canTest; }
}
