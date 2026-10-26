package com.fivetech.monitor.domain.vo;

/**
 * 占用该指标的规则。
 *
 * @author fivetech
 */
public class OccupiedByVO
{
    private String ruleCode;

    private String ownerEmail;

    public String getRuleCode()
    {
        return ruleCode;
    }

    public void setRuleCode(String ruleCode)
    {
        this.ruleCode = ruleCode;
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
