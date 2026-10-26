package com.fivetech.monitor.domain;

/**
 * 已被规则占用的指标（本站未删除的规则）。只用于「可新建的指标」标出占用，不含阈值。
 *
 * @author fivetech
 */
public class MonitorOccupiedMetric
{
    private String metricCode;

    private Integer ruleNo;

    private String ownerEmail;

    public String getMetricCode()
    {
        return metricCode;
    }

    public void setMetricCode(String metricCode)
    {
        this.metricCode = metricCode;
    }

    public Integer getRuleNo()
    {
        return ruleNo;
    }

    public void setRuleNo(Integer ruleNo)
    {
        this.ruleNo = ruleNo;
    }

    public String getOwnerEmail()
    {
        return ownerEmail;
    }

    public void setOwnerEmail(String ownerEmail)
    {
        this.ownerEmail = ownerEmail;
    }
}
