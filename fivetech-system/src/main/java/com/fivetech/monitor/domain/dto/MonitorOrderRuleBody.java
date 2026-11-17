package com.fivetech.monitor.domain.dto;

import java.math.BigDecimal;

/**
 * 新建 / 编辑订单规则的请求体（POST /monitor/order-rules、/monitor/order-rules/{ruleId}/update）。
 *
 * <p>编辑时整条提交（同新建），enabled 不在编辑里改，启停走 POST /monitor/rules/{ruleId}/enabled。
 * 字段说明见设计文档 5.1。</p>
 *
 * @author fivetech
 */
public class MonitorOrderRuleBody
{
    /** 规则名称，必填，最多 30 字 */
    private String name;

    /** 说明，选填，最多 200 字 */
    private String description;

    /** 负责人，必填 */
    private Long ownerUserId;

    /** 订单类型，不传 = WITHDRAW（本期只有提款） */
    private String orderType;

    /** SUBMIT 提交时 / PAID 出款成功时 */
    private String triggerAt;

    /** SINGLE 单笔 / DAILY_TOTAL 账号当日累计 */
    private String amountMode;

    /** 金额 ≥，大于 0，最多 100,000,000，最多 2 位小数 */
    private BigDecimal amountThreshold;

    /** 渠道范围：不传或空 = 全站；g:推广商ID 或 c:推广商ID|推广渠道ID */
    private String channelScope;

    /** CRIT 严重 / WARN 注意 */
    private String level;

    /** 是否发送通知，不传 = 开启；开启时 notifyChatIds（或旧字段 notifyChatId）必填 */
    private Boolean notifyEnabled;

    /** 通知群 chat_id（从 GET /monitor/lark/chats 选） */
    private String notifyChatId;

    /** 通知群，1–5 个（release_next ㉑）；传了它时忽略旧字段 notifyChatId */
    private java.util.List<String> notifyChatIds;

    public java.util.List<String> getNotifyChatIds()
    {
        return notifyChatIds;
    }

    public void setNotifyChatIds(java.util.List<String> notifyChatIds)
    {
        this.notifyChatIds = notifyChatIds;
    }

    /** 每天最多发送条数：10 / 20 / 50（默认）/ 100 / 0（不限） */
    private Integer dailyCap;

    /** 只在新建时用：不传 = 启用 */
    private Boolean enabled;

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public String getDescription()
    {
        return description;
    }

    public void setDescription(String description)
    {
        this.description = description;
    }

    public Long getOwnerUserId()
    {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId)
    {
        this.ownerUserId = ownerUserId;
    }

    public String getOrderType()
    {
        return orderType;
    }

    public void setOrderType(String orderType)
    {
        this.orderType = orderType;
    }

    public String getTriggerAt()
    {
        return triggerAt;
    }

    public void setTriggerAt(String triggerAt)
    {
        this.triggerAt = triggerAt;
    }

    public String getAmountMode()
    {
        return amountMode;
    }

    public void setAmountMode(String amountMode)
    {
        this.amountMode = amountMode;
    }

    public BigDecimal getAmountThreshold()
    {
        return amountThreshold;
    }

    public void setAmountThreshold(BigDecimal amountThreshold)
    {
        this.amountThreshold = amountThreshold;
    }

    public String getChannelScope()
    {
        return channelScope;
    }

    public void setChannelScope(String channelScope)
    {
        this.channelScope = channelScope;
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

    public String getNotifyChatId()
    {
        return notifyChatId;
    }

    public void setNotifyChatId(String notifyChatId)
    {
        this.notifyChatId = notifyChatId;
    }

    public Integer getDailyCap()
    {
        return dailyCap;
    }

    public void setDailyCap(Integer dailyCap)
    {
        this.dailyCap = dailyCap;
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
