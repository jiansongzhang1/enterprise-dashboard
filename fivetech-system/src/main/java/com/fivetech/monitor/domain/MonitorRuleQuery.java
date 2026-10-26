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
}
