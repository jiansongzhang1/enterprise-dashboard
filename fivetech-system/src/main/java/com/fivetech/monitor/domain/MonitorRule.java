package com.fivetech.monitor.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 告警规则（monitor_rule）。
 *
 * judgeType、minSampleKey 是建规则时从 dashboard_metric_card 复制的快照，之后不随指标表变化。
 * 列表查询时顺带带出负责人、通知群、最近触发时间和指标名称（只读，不写库）。
 *
 * @author fivetech
 */
public class MonitorRule
{
    private Long ruleId;

    private String siteCode;

    /** 显示编号，格式化为 R-001 */
    private Integer ruleNo;

    private String metricCode;

    private Long ownerUserId;

    /** lo / hi / band / drop */
    private String judgeType;

    private BigDecimal warnLow;

    private BigDecimal warnHigh;

    private BigDecimal critLow;

    private BigDecimal critHigh;

    private Integer periodHours;

    private Integer durationCount;

    /** NULL = 不升级 */
    private Integer escalateHours;

    private Integer muteHours;

    private String minSampleKey;

    private BigDecimal minSampleValue;

    /** 通知的 Lark 群 chat_id */
    private String notifyChatId;

    /** 保存规则时的群名 */
    private String notifyChatName;

    private Boolean enabled;

    private LocalDateTime pausedAt;

    private LocalDateTime lastNotifiedAt;

    /** 最近一次判定时间 */
    private LocalDateTime lastJudgedAt;

    /** 最近一次判定失败的原因，成功后清空 */
    private String judgeError;

    private String delFlag;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;

    /** —— 以下为查询时关联出的只读字段 —— */
    private String metricName;

    private String valueFormat;

    private Integer valueDecimals;

    private String minSampleLabel;

    private String ownerEmail;

    /** 负责人已停用、删除时为 false */
    private Boolean ownerValid;

    /** 群列表缓存里的最新群名；没有时用 notifyChatName */
    private String chatName;

    /** ok / dead；缓存里没有该群时为 null */
    private String chatStatus;

    /** 创建人邮箱（按 create_by 关联 sys_user） */
    private String createByEmail;

    /** 最近修改人邮箱 */
    private String updateByEmail;

    /** 最近一条事件的 trigger_hour */
    private LocalDateTime lastTriggerHour;

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

    public Long getOwnerUserId()
    {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId)
    {
        this.ownerUserId = ownerUserId;
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

    public Boolean getEnabled()
    {
        return enabled;
    }

    public void setEnabled(Boolean enabled)
    {
        this.enabled = enabled;
    }

    public LocalDateTime getPausedAt()
    {
        return pausedAt;
    }

    public void setPausedAt(LocalDateTime pausedAt)
    {
        this.pausedAt = pausedAt;
    }

    public LocalDateTime getLastNotifiedAt()
    {
        return lastNotifiedAt;
    }

    public void setLastNotifiedAt(LocalDateTime lastNotifiedAt)
    {
        this.lastNotifiedAt = lastNotifiedAt;
    }

    public LocalDateTime getLastJudgedAt()
    {
        return lastJudgedAt;
    }

    public void setLastJudgedAt(LocalDateTime lastJudgedAt)
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

    public String getDelFlag()
    {
        return delFlag;
    }

    public void setDelFlag(String delFlag)
    {
        this.delFlag = delFlag;
    }

    public String getCreateBy()
    {
        return createBy;
    }

    public void setCreateBy(String createBy)
    {
        this.createBy = createBy;
    }

    public LocalDateTime getCreateTime()
    {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime)
    {
        this.createTime = createTime;
    }

    public String getUpdateBy()
    {
        return updateBy;
    }

    public void setUpdateBy(String updateBy)
    {
        this.updateBy = updateBy;
    }

    public LocalDateTime getUpdateTime()
    {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime)
    {
        this.updateTime = updateTime;
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

    public Integer getValueDecimals()
    {
        return valueDecimals;
    }

    public void setValueDecimals(Integer valueDecimals)
    {
        this.valueDecimals = valueDecimals;
    }

    public String getMinSampleLabel()
    {
        return minSampleLabel;
    }

    public void setMinSampleLabel(String minSampleLabel)
    {
        this.minSampleLabel = minSampleLabel;
    }

    public String getOwnerEmail()
    {
        return ownerEmail;
    }

    public void setOwnerEmail(String ownerEmail)
    {
        this.ownerEmail = ownerEmail;
    }

    public Boolean getOwnerValid()
    {
        return ownerValid;
    }

    public void setOwnerValid(Boolean ownerValid)
    {
        this.ownerValid = ownerValid;
    }

    public String getChatName()
    {
        return chatName;
    }

    public void setChatName(String chatName)
    {
        this.chatName = chatName;
    }

    public String getChatStatus()
    {
        return chatStatus;
    }

    public void setChatStatus(String chatStatus)
    {
        this.chatStatus = chatStatus;
    }

    public String getCreateByEmail()
    {
        return createByEmail;
    }

    public void setCreateByEmail(String createByEmail)
    {
        this.createByEmail = createByEmail;
    }

    public String getUpdateByEmail()
    {
        return updateByEmail;
    }

    public void setUpdateByEmail(String updateByEmail)
    {
        this.updateByEmail = updateByEmail;
    }

    public LocalDateTime getLastTriggerHour()
    {
        return lastTriggerHour;
    }

    public void setLastTriggerHour(LocalDateTime lastTriggerHour)
    {
        this.lastTriggerHour = lastTriggerHour;
    }
}
