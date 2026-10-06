package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;

/**
 * 指标卡的告警状态。
 *
 * <p><b>{@code NOT_CONFIGURED} 绝不能填成 {@code OK}。</b>
 * 「没有配规则」和「查过了一切正常」是两件事，前者染成绿色是在骗人——
 * 凌晨 3 点存款成功率 50%（2 笔挂 1 笔）却显示一片正常，
 * 事后追责时这个字段就是证据。</p>
 *
 * @author fivetech
 */
public class MetricAlertVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** NOT_CONFIGURED / OK / WARN / CRIT / INSUFFICIENT_SAMPLE */
    private String state;

    /** WARN / CRIT；非告警态为 null */
    private String level;

    private String ruleId;

    /** 触发原因，直接展示在卡片底行 */
    private String reason;

    /** 首次触发时间 */
    private String since;

    /** 是否根因。因果去重后只有根因需要人去看 */
    private Boolean isRoot;

    /** 被谁带坏的。非空时卡片降一级权重——「在动，但不是它的问题」 */
    private String drivenBy;

    /** 规则未配置：监控管理尚未上线，没有任何启用规则 */
    public static final String STATE_NOT_CONFIGURED = "NOT_CONFIGURED";

    /** 有规则，已判定，未触发 */
    public static final String STATE_OK = "OK";

    /** 有规则，但样本量不足，本时间片不判定 */
    public static final String STATE_INSUFFICIENT_SAMPLE = "INSUFFICIENT_SAMPLE";

    public static MetricAlertVO notConfigured()
    {
        MetricAlertVO vo = new MetricAlertVO();
        vo.state = STATE_NOT_CONFIGURED;
        return vo;
    }

    public String getState()
    {
        return state;
    }

    public void setState(String state)
    {
        this.state = state;
    }

    public String getLevel()
    {
        return level;
    }

    public void setLevel(String level)
    {
        this.level = level;
    }

    public String getRuleId()
    {
        return ruleId;
    }

    public void setRuleId(String ruleId)
    {
        this.ruleId = ruleId;
    }

    public String getReason()
    {
        return reason;
    }

    public void setReason(String reason)
    {
        this.reason = reason;
    }

    public String getSince()
    {
        return since;
    }

    public void setSince(String since)
    {
        this.since = since;
    }

    public Boolean getIsRoot()
    {
        return isRoot;
    }

    public void setIsRoot(Boolean isRoot)
    {
        this.isRoot = isRoot;
    }

    public String getDrivenBy()
    {
        return drivenBy;
    }

    public void setDrivenBy(String drivenBy)
    {
        this.drivenBy = drivenBy;
    }

}