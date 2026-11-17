package com.fivetech.monitor.domain;

/**
 * 规则列表筛选与分页。ownerId 为负责人筛选（owner=me 时由 Controller 换成当前用户），不是数据范围。
 *
 * @author fivetech
 */
public class MonitorRuleQuery
{
    private Boolean enabled;

    private String chatId;

    private String metricCode;

    private Long ownerId;

    private Integer pageNum;

    private Integer pageSize;

    public Boolean getEnabled()
    {
        return enabled;
    }

    public void setEnabled(Boolean enabled)
    {
        this.enabled = enabled;
    }

    public String getChatId()
    {
        return chatId;
    }

    public void setChatId(String chatId)
    {
        this.chatId = chatId;
    }

    public String getMetricCode()
    {
        return metricCode;
    }

    public void setMetricCode(String metricCode)
    {
        this.metricCode = metricCode;
    }

    public Long getOwnerId()
    {
        return ownerId;
    }

    public void setOwnerId(Long ownerId)
    {
        this.ownerId = ownerId;
    }

    public Integer getPageNum()
    {
        return pageNum;
    }

    public void setPageNum(Integer pageNum)
    {
        this.pageNum = pageNum;
    }

    public Integer getPageSize()
    {
        return pageSize;
    }

    public void setPageSize(Integer pageSize)
    {
        this.pageSize = pageSize;
    }

    /** 多选筛选（release_next ㉓，三类规则同一张表）：类型 METRIC / COND / ORDER、负责人、启停、通知群；空 = 不筛 */
    private java.util.List<String> ruleTypes;

    private java.util.List<Long> ownerIds;

    private java.util.List<Boolean> enableds;

    private java.util.List<String> chatIds;

    public java.util.List<String> getRuleTypes() { return ruleTypes; }
    public void setRuleTypes(java.util.List<String> ruleTypes) { this.ruleTypes = ruleTypes; }
    public java.util.List<Long> getOwnerIds() { return ownerIds; }
    public void setOwnerIds(java.util.List<Long> ownerIds) { this.ownerIds = ownerIds; }
    public java.util.List<Boolean> getEnableds() { return enableds; }
    public void setEnableds(java.util.List<Boolean> enableds) { this.enableds = enableds; }
    public java.util.List<String> getChatIds() { return chatIds; }
    public void setChatIds(java.util.List<String> chatIds) { this.chatIds = chatIds; }
}
