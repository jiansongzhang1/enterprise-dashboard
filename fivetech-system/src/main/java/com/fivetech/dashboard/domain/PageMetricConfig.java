package com.fivetech.dashboard.domain;

import java.io.Serializable;

/**
 * {@code dashboard_page_metric} 的一行：页面 ↔ 指标的归属、顺序、默认选中与视觉权重。
 *
 * @author fivetech
 */
public class PageMetricConfig implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 与 {@code DashboardPage} 枚举一一对应 */
    private String pageCode;

    private String metricCode;

    private Integer sortNo;

    /** 指标汇总的初始列；指标墙恒 true */
    private boolean isDefault;

    /** CORE 大号卡 / NORMAL；仅指标墙使用 */
    private String emphasis;

    private String status;

    public boolean isEnabled()
    {
        return status == null || "0".equals(status);
    }

    public boolean isCore()
    {
        return "CORE".equalsIgnoreCase(emphasis);
    }

    public String getPageCode()
    {
        return pageCode;
    }

    public void setPageCode(String pageCode)
    {
        this.pageCode = pageCode;
    }

    public String getMetricCode()
    {
        return metricCode;
    }

    public void setMetricCode(String metricCode)
    {
        this.metricCode = metricCode;
    }

    public Integer getSortNo()
    {
        return sortNo;
    }

    public void setSortNo(Integer sortNo)
    {
        this.sortNo = sortNo;
    }

    public boolean getIsDefault()
    {
        return isDefault;
    }

    public void setIsDefault(boolean isDefault)
    {
        this.isDefault = isDefault;
    }

    public String getEmphasis()
    {
        return emphasis;
    }

    public void setEmphasis(String emphasis)
    {
        this.emphasis = emphasis;
    }

    public String getStatus()
    {
        return status;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }
}
