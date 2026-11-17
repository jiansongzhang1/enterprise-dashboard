package com.fivetech.monitor.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 新增 / 修改规则的请求体（两者共用）。
 *
 * <p>不传 judgeType 和 minSampleKey：由后端按指标配置填入。修改时不能改 metricCode。</p>
 *
 * @author fivetech
 */
public class MonitorRuleBody
{
    private String metricCode;

    /** 注意线，选填 */
    private Threshold warn;

    /** 严重线，必填 */
    private Threshold crit;

    private Integer periodHours;

    private Integer durationCount;

    /** 注意升级为严重的小时数；不填 = 不升级 */
    private Integer escalateHours;

    private Integer muteHours;

    /** 最小样本量；不填按指标默认值，0 = 不限 */
    private BigDecimal minSampleValue;

    /** 通知的 Lark 群 chat_id（从 GET /monitor/lark/chats 选）；旧字段，单个群。传了 notifyChatIds 时忽略 */
    private String notifyChatId;

    /** 通知群，1–5 个（release_next ㉑，原型 2026-11-13 起多选），同一条告警分别发到每个群 */
    private List<String> notifyChatIds;

    private Long ownerUserId;

    /** 新增时不填默认启用；修改时不填表示不改 */
    private Boolean enabled;

    public String getMetricCode()
    {
        return metricCode;
    }

    public void setMetricCode(String metricCode)
    {
        this.metricCode = metricCode;
    }

    public Threshold getWarn()
    {
        return warn;
    }

    public void setWarn(Threshold warn)
    {
        this.warn = warn;
    }

    public Threshold getCrit()
    {
        return crit;
    }

    public void setCrit(Threshold crit)
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

    public BigDecimal getMinSampleValue()
    {
        return minSampleValue;
    }

    public void setMinSampleValue(BigDecimal minSampleValue)
    {
        this.minSampleValue = minSampleValue;
    }

    public List<String> getNotifyChatIds()
    {
        return notifyChatIds;
    }

    public void setNotifyChatIds(List<String> notifyChatIds)
    {
        this.notifyChatIds = notifyChatIds;
    }

    public String getNotifyChatId()
    {
        return notifyChatId;
    }

    public void setNotifyChatId(String notifyChatId)
    {
        this.notifyChatId = notifyChatId;
    }

    public Long getOwnerUserId()
    {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId)
    {
        this.ownerUserId = ownerUserId;
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
