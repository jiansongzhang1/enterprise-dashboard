package com.fivetech.dashboard.enums;

/**
 * 站点页面的默认展示指标集（dashboard_page_metric_site.display_type）：同一站点同一页面最多两套指标顺序与显示。
 *
 * <p>目前只是后台配置（站点管理 › 指標配置），运营总览 / 指标汇总的取数只用 {@link #DEFAULT}；
 * {@link #REPORT} 先存配置，不影响接口返回。</p>
 *
 * <p>取值必须与 {@code dashboard_page_metric_site.ck_page_metric_site_display} 约束保持一致。</p>
 *
 * @author fivetech
 */
public enum PageDisplayType
{
    /** 默认指标集（页面展示与取数用这一套） */
    DEFAULT,

    /** 报表指标集（暂只做配置） */
    REPORT;

    /** 不区分大小写；空返回 DEFAULT；不认识返回 null */
    public static PageDisplayType of(String code)
    {
        if (code == null || code.trim().isEmpty())
        {
            return DEFAULT;
        }
        for (PageDisplayType t : values())
        {
            if (t.name().equalsIgnoreCase(code.trim()))
            {
                return t;
            }
        }
        return null;
    }
}
