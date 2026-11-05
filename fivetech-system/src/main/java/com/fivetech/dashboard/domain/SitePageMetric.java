package com.fivetech.dashboard.domain;

import java.time.LocalDateTime;

/**
 * 站点自定义的页面指标顺序与显示（dashboard_page_metric_site）：某站点在运营总览 / 指标汇总上，指标排第几、显不显示。
 * <p>站点没有配置的指标按全站默认（dashboard_page_metric）的顺序排在已配置的后面、默认显示。</p>
 *
 * @author fivetech
 */
public class SitePageMetric
{
    private String siteCode;

    /** OVERVIEW / SUMMARY */
    private String pageCode;

    private String metricCode;

    /** 指标集：DEFAULT 默认 / REPORT 报表（{@link com.fivetech.dashboard.enums.PageDisplayType}）；为空按 DEFAULT */
    private String displayType = "DEFAULT";

    /** 站点内的顺序，从小到大 */
    private Integer sortNo;

    /** 是否显示；false = 这个站点的该页面不返回这个指标 */
    private boolean visible = true;

    private String updateBy;

    private LocalDateTime updateTime;

    public String getSiteCode() { return siteCode; }

    public void setSiteCode(String siteCode) { this.siteCode = siteCode; }

    public String getPageCode() { return pageCode; }

    public void setPageCode(String pageCode) { this.pageCode = pageCode; }

    public String getMetricCode() { return metricCode; }

    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }

    public String getDisplayType() { return displayType; }

    public void setDisplayType(String displayType) { this.displayType = displayType; }

    public Integer getSortNo() { return sortNo; }

    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }

    public boolean isVisible() { return visible; }

    public void setVisible(boolean visible) { this.visible = visible; }

    public String getUpdateBy() { return updateBy; }

    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }

    public LocalDateTime getUpdateTime() { return updateTime; }

    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
