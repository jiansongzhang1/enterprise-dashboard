package com.fivetech.monitor.domain;

/**
 * 「訂單告警」列表的一行：事件本身 + 规则当前的名称、说明、条件（规则不存快照，取当前值）+ 负责人、处理人。
 *
 * @author fivetech
 */
public class MonitorOrderEventRow extends MonitorOrderEvent
{
    private Integer ruleNo;

    private String ruleName;

    private String ruleDescription;

    /** 规则当前的 config（jsonb 文本），用来生成摘要 */
    private String ruleConfig;

    private Boolean ruleEnabled;

    private Long ownerUserId;

    private String ownerName;

    /** 处理人显示名（邮箱，没有时用登录名） */
    private String handleByName;

    public Integer getRuleNo() { return ruleNo; }
    public void setRuleNo(Integer ruleNo) { this.ruleNo = ruleNo; }
    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }
    public String getRuleDescription() { return ruleDescription; }
    public void setRuleDescription(String ruleDescription) { this.ruleDescription = ruleDescription; }
    public String getRuleConfig() { return ruleConfig; }
    public void setRuleConfig(String ruleConfig) { this.ruleConfig = ruleConfig; }
    public Boolean getRuleEnabled() { return ruleEnabled; }
    public void setRuleEnabled(Boolean ruleEnabled) { this.ruleEnabled = ruleEnabled; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public String getHandleByName() { return handleByName; }
    public void setHandleByName(String handleByName) { this.handleByName = handleByName; }

    /** 规则已删除（订单规则可删，2026-11-17）：名称、负责人照常返回，前端标「已刪除」 */
    private Boolean ruleDeleted;

    public Boolean getRuleDeleted() { return ruleDeleted; }
    public void setRuleDeleted(Boolean ruleDeleted) { this.ruleDeleted = ruleDeleted; }
}
