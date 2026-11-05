package com.fivetech.dashboard.domain;

/**
 * 某站点某页面上一个指标的位置与显示（运营总览 / 指标汇总按站点调整，见 dashboard_page_metric_site）。
 *
 * @author fivetech
 */
public class PageMetricLayout
{
    private final String metricCode;

    /** 全站默认顺序（dashboard_page_metric.sort_no） */
    private final Integer defaultSortNo;

    /** 站点内名次（含隐藏的指标，从 1 起） */
    private final int position;

    private final boolean visible;

    /** 是否来自站点配置；false = 站点没配置这个指标，按全站默认排在后面 */
    private final boolean customized;

    public PageMetricLayout(String metricCode, Integer defaultSortNo, int position, boolean visible, boolean customized)
    {
        this.metricCode = metricCode;
        this.defaultSortNo = defaultSortNo;
        this.position = position;
        this.visible = visible;
        this.customized = customized;
    }

    public String getMetricCode() { return metricCode; }

    public Integer getDefaultSortNo() { return defaultSortNo; }

    public int getPosition() { return position; }

    public boolean isVisible() { return visible; }

    public boolean isCustomized() { return customized; }
}
