package com.fivetech.monitor.domain;

import java.math.BigDecimal;

/**
 * monitor_rule_condition 的一行（release_next ㉓）：条件规则的一个条件。scope 为 jsonb 文本。
 *
 * @author fivetech
 */
public class MonitorCondRow
{
    private Long ruleId;

    private Integer seq;

    private String fieldCode;

    private String scope;

    private String how;

    private String op;

    private String unit;

    private BigDecimal thresholdLow;

    private BigDecimal thresholdHigh;

    private BigDecimal minSample;

    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public String getFieldCode() { return fieldCode; }
    public void setFieldCode(String fieldCode) { this.fieldCode = fieldCode; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getHow() { return how; }
    public void setHow(String how) { this.how = how; }
    public String getOp() { return op; }
    public void setOp(String op) { this.op = op; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public BigDecimal getThresholdLow() { return thresholdLow; }
    public void setThresholdLow(BigDecimal thresholdLow) { this.thresholdLow = thresholdLow; }
    public BigDecimal getThresholdHigh() { return thresholdHigh; }
    public void setThresholdHigh(BigDecimal thresholdHigh) { this.thresholdHigh = thresholdHigh; }
    public BigDecimal getMinSample() { return minSample; }
    public void setMinSample(BigDecimal minSample) { this.minSample = minSample; }
}
