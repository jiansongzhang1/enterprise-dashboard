package com.fivetech.dashboard.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 站点币种、币种符号（站点表 monitor_site_setting.currency / currency_symbol；getInfo 的 sites[]） */
class DashboardPropertiesSiteCurrencyTest
{
    private static DashboardProperties props()
    {
        DashboardProperties p = new DashboardProperties();
        p.setCurrency("INR");
        DashboardProperties.Site jito = new DashboardProperties.Site();
        jito.setCode("JITO");
        jito.setCurrency("cny");
        DashboardProperties.Site ph = new DashboardProperties.Site();
        ph.setCode("SITE-PH");
        ph.setCurrency("PHP");
        ph.setCurrencySymbol("PHP ₱");
        DashboardProperties.Site xx = new DashboardProperties.Site();
        xx.setCode("SITE-XX");
        xx.setCurrency("XAF");
        DashboardProperties.Site none = new DashboardProperties.Site();
        none.setCode("SITE-NONE");
        p.applySites("SITE-IN", "uWin", "SITE-IN", null, null, List.of(jito, ph, xx, none), null);
        return p;
    }

    @Test
    void currencyFallsBackToConfig()
    {
        DashboardProperties p = props();
        assertEquals("INR", p.siteCurrency("SITE-IN"), "默认站点没设置：dashboard.currency");
        assertEquals("₹", p.siteCurrencySymbol("SITE-IN"));
        assertEquals("CNY", p.siteCurrency("jito"), "大写、编码不区分大小写");
        assertEquals("¥", p.siteCurrencySymbol("JITO"), "没设置符号：按币种推导");
        assertEquals("PHP ₱", p.siteCurrencySymbol("SITE-PH"), "设置了符号用设置的");
        assertEquals("XAF", p.siteCurrencySymbol("SITE-XX"), "认不出的币种：符号用币种代码");
        assertEquals("INR", p.siteCurrency("SITE-NONE"), "没设置币种：dashboard.currency");
        assertEquals("INR", p.siteCurrency(null), "没有站点上下文按默认站点");
    }

    @Test
    void defaultSiteFromTable()
    {
        DashboardProperties p = new DashboardProperties();
        p.applySites("SITE-IN", "uWin", null, "USD", null, List.of(), null);
        assertEquals("USD", p.siteCurrency("SITE-IN"));
        assertEquals("US$", p.siteCurrencySymbol("site-in"));
    }
}
