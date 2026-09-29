package com.fivetech.dashboard.enums;

/**
 * 看板页面。
 *
 * <p><b>这是枚举而不是配置表</b>：每个页面都有自己的 Controller、自己的权限标识、
 * 自己的返回体结构，新增一个页面必然要写代码。做成表只会多出一种故障——
 * 表里加了一行，代码里没有对应实现，运行时静默 404，还没人知道为什么。</p>
 *
 * <p>与之相对，「哪些指标出现在这个页面、排第几、默认选不选」是配置
 * （{@code dashboard_page_metric}），因为那是运营会调的，不该发版。
 * 分界线是：<b>代码要 switch 的用枚举，只是数据搬运的用配置。</b></p>
 *
 * <p>本枚举的取值必须与 {@code dashboard_page_metric.ck_page_code} 约束保持一致。</p>
 *
 * @author fivetech
 */
public enum DashboardPage
{
    /** 运营总览 · 指标墙。20 个指标，登录人数不上墙 */
    OVERVIEW("dashboard:overview:view"),

    /** 指标汇总表。21 个指标全部可选，默认选中 8 个核心指标 */
    SUMMARY("dashboard:summary:view");

    private final String permission;

    DashboardPage(String permission)
    {
        this.permission = permission;
    }

    /** 页面维度权限标识，与 @PreAuthorize 里写的字符串是同一个值 */
    public String getPermission()
    {
        return permission;
    }

    public static DashboardPage of(String code)
    {
        if (code == null)
        {
            return null;
        }
        for (DashboardPage page : values())
        {
            if (page.name().equalsIgnoreCase(code.trim()))
            {
                return page;
            }
        }
        return null;
    }
}
