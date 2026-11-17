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

    /** 通知群（最多 5 个），JSON；notify_chat_id 为第一个 */
    private String notifyChats;

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

    /** 规则类型：METRIC 指标规则 / COND 条件规则 / ORDER 订单规则；旧数据为 METRIC */
    private String ruleType;

    /** 规则名称（条件规则、订单规则） */
    private String ruleName;

    /** 规则说明 */
    private String description;

    /** 等级（条件规则、订单规则）：CRIT / WARN */
    private String level;

    /** 是否发送通知（条件规则、订单规则）；指标规则为 null */
    private Boolean notifyEnabled;

    /** 保存时的站点币种（订单规则） */
    private String currency;

    /** 订单规则的条件（jsonb 文本，见 OrderRuleConfig） */
    private String config;

    /** 版本号（monitor_rule_version_seq），每次保存、启停取新值 */
    private Long version;

    /** 负责人昵称（查询时关联，只读） */
    private String ownerName;

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

    /** 通知群（release_next ㉑）：[{chatId, name, status?}] 的 JSON，读写都经 {@link com.fivetech.monitor.service.MonitorChats} */
    public String getNotifyChats()
    {
        return notifyChats;
    }

    public void setNotifyChats(String notifyChats)
    {
        this.notifyChats = notifyChats;
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

    public String getRuleType()
    {
        return ruleType;
    }

    public void setRuleType(String ruleType)
    {
        this.ruleType = ruleType;
    }

    public String getRuleName()
    {
        return ruleName;
    }

    public void setRuleName(String ruleName)
    {
        this.ruleName = ruleName;
    }

    public String getDescription()
    {
        return description;
    }

    public void setDescription(String description)
    {
        this.description = description;
    }

    public String getLevel()
    {
        return level;
    }

    public void setLevel(String level)
    {
        this.level = level;
    }

    public Boolean getNotifyEnabled()
    {
        return notifyEnabled;
    }

    public void setNotifyEnabled(Boolean notifyEnabled)
    {
        this.notifyEnabled = notifyEnabled;
    }

    public String getCurrency()
    {
        return currency;
    }

    public void setCurrency(String currency)
    {
        this.currency = currency;
    }

    public String getConfig()
    {
        return config;
    }

    public void setConfig(String config)
    {
        this.config = config;
    }

    public Long getVersion()
    {
        return version;
    }

    public void setVersion(Long version)
    {
        this.version = version;
    }

    public String getOwnerName()
    {
        return ownerName;
    }

    public void setOwnerName(String ownerName)
    {
        this.ownerName = ownerName;
    }

    /** 最近一次修改配置的时间（release_next ㉓；启停不改它，告警规则列表按它排序）；没有时为 update_time / create_time */
    private java.time.LocalDateTime editTime;

    public java.time.LocalDateTime getEditTime() { return editTime; }
    public void setEditTime(java.time.LocalDateTime editTime) { this.editTime = editTime; }

    /** 最近一次修改配置的人（登录名，release_next ㉓；启停不改它）；没有时为 update_by */
    private String editBy;

    private String editByEmail;

    public String getEditBy() { return editBy; }
    public void setEditBy(String editBy) { this.editBy = editBy; }
    public String getEditByEmail() { return editByEmail; }
    public void setEditByEmail(String editByEmail) { this.editByEmail = editByEmail; }
}
