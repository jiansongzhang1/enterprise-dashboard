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


    // ===================== 监控管理一期新增（只增不改） =====================

    /** 原因·值：「近 2 小時 83.60% – 84.20%」；没有规则或没有判定结果为 null */
    private String reasonValue;

    /** 原因·对照线：「低於嚴重線 90.00%」；只在 WARN / CRIT 时有值 */
    private String reasonLine;

    /** 由注意升级为严重 */
    private Boolean escalated;

    /** 规则（含已暂停的）；没有规则为 null */
    private RuleVO rule;

    /** 判定窗口内各周期，时间正序 */
    private java.util.List<RecentVO> recent;

    /** 最近一个已判定周期离线多远 */
    private DeviationVO deviation;

    /** 异常中事件的持续时长（整小时） */
    private Long durationHours;

    /** 异常中事件 id */
    private Long eventId;

    public static class Threshold implements Serializable
    {
        private static final long serialVersionUID = 1L;
        private final String low;
        private final String high;
        public Threshold(String low, String high) { this.low = low; this.high = high; }
        public String getLow() { return low; }
        public String getHigh() { return high; }
    }

    public static class RuleVO implements Serializable
    {
        private static final long serialVersionUID = 1L;
        private String ruleCode;
        private String judgeType;
        private boolean enabled;
        private Threshold warn;
        private Threshold crit;
        /** 检测周期（小时）：每 periodHours 小时判定一次，取值 1 / 4 / 6 / 12 / 24 */
        private int periodHours;
        /** 持续次数：连续 durationCount 个周期越线才触发 */
        private int durationCount;
        /** 注意升级：触发后注意状态持续 escalateHours 小时仍未恢复即升为严重；未设升级为 null */
        private Integer escalateHours;
        public String getRuleCode() { return ruleCode; }
        public void setRuleCode(String ruleCode) { this.ruleCode = ruleCode; }
        public String getJudgeType() { return judgeType; }
        public void setJudgeType(String judgeType) { this.judgeType = judgeType; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Threshold getWarn() { return warn; }
        public void setWarn(Threshold warn) { this.warn = warn; }
        public Threshold getCrit() { return crit; }
        public void setCrit(Threshold crit) { this.crit = crit; }
        public int getPeriodHours() { return periodHours; }
        public void setPeriodHours(int periodHours) { this.periodHours = periodHours; }
        public int getDurationCount() { return durationCount; }
        public void setDurationCount(int durationCount) { this.durationCount = durationCount; }
        public Integer getEscalateHours() { return escalateHours; }
        public void setEscalateHours(Integer escalateHours) { this.escalateHours = escalateHours; }
    }

    public static class RecentVO implements Serializable
    {
        private static final long serialVersionUID = 1L;
        private String from;
        private String to;
        private java.math.BigDecimal value;
        private java.math.BigDecimal baseline;
        /** OK / WARN / CRIT / SKIP（未判定） */
        private String level;
        /** 这个周期判定后，事件是否处于「注意升级为严重」（已升级）；不在事件里的周期为 false */
        private boolean escalated;
        private java.math.BigDecimal deviation;
        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }
        public String getTo() { return to; }
        public void setTo(String to) { this.to = to; }
        public java.math.BigDecimal getValue() { return value; }
        public void setValue(java.math.BigDecimal value) { this.value = value; }
        public java.math.BigDecimal getBaseline() { return baseline; }
        public void setBaseline(java.math.BigDecimal baseline) { this.baseline = baseline; }
        public String getLevel() { return level; }
        public void setLevel(String level) { this.level = level; }
        public boolean isEscalated() { return escalated; }
        public void setEscalated(boolean escalated) { this.escalated = escalated; }
        public java.math.BigDecimal getDeviation() { return deviation; }
        public void setDeviation(java.math.BigDecimal deviation) { this.deviation = deviation; }
    }

    public static class DeviationVO implements Serializable
    {
        private static final long serialVersionUID = 1L;
        private final java.math.BigDecimal value;
        private final String unit;
        private final String against;
        private final String text;
        public DeviationVO(java.math.BigDecimal value, String unit, String against, String text)
        {
            this.value = value;
            this.unit = unit;
            this.against = against;
            this.text = text;
        }
        public java.math.BigDecimal getValue() { return value; }
        public String getUnit() { return unit; }
        public String getAgainst() { return against; }
        public String getText() { return text; }
    }

    public String getReasonValue() { return reasonValue; }
    public void setReasonValue(String reasonValue) { this.reasonValue = reasonValue; }
    public String getReasonLine() { return reasonLine; }
    public void setReasonLine(String reasonLine) { this.reasonLine = reasonLine; }
    public Boolean getEscalated() { return escalated; }
    public void setEscalated(Boolean escalated) { this.escalated = escalated; }
    public RuleVO getRule() { return rule; }
    public void setRule(RuleVO rule) { this.rule = rule; }
    public java.util.List<RecentVO> getRecent() { return recent; }
    public void setRecent(java.util.List<RecentVO> recent) { this.recent = recent; }
    public DeviationVO getDeviation() { return deviation; }
    public void setDeviation(DeviationVO deviation) { this.deviation = deviation; }
    public Long getDurationHours() { return durationHours; }
    public void setDurationHours(Long durationHours) { this.durationHours = durationHours; }
    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
}
