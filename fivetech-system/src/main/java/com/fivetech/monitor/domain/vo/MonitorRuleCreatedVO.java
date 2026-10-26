package com.fivetech.monitor.domain.vo;

/**
 * 新增规则的返回：{@code { ruleId, ruleNo, ruleCode }}。
 *
 * @author fivetech
 */
public class MonitorRuleCreatedVO
{
    private Long ruleId;

    private Integer ruleNo;

    private String ruleCode;

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
}
