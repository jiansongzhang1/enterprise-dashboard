package com.fivetech.dashboard.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.fivetech.common.utils.SiteContext;

/**
 * dashboard 各接口的时区、币种按站点取（站点表 monitor_site_setting → DashboardProperties 内存缓存）：
 * 默认站点也从站点表取，没设置 / 不合法时用 dashboard.timezone、dashboard.currency 兜底；当前站点跟随 X-Site-Code（SiteContext）。
 */
class DashboardPropertiesSiteTableZoneTest
{
    private static DashboardProperties props(String defaultTz, String defaultCurrency)
    {
        DashboardProperties p = new DashboardProperties();
        p.setTimezone("Asia/Kolkata");
        p.setCurrency("INR");
        DashboardProperties.Site jito = new DashboardProperties.Site();
        jito.setCode("JITO");
        jito.setTimezone("Asia/Shanghai");
        jito.setCurrency("CNY");
        DashboardProperties.Site bad = new DashboardProperties.Site();
        bad.setCode("SITE-BAD");
        bad.setTimezone("Mars/Base");
        p.applySites("SITE-IN", "uWin", null, defaultTz, defaultCurrency, null, List.of(jito, bad), null);
        return p;
    }

    @Test
    void defaultSiteFromTableWithConfigFallback()
    {
        assertEquals("Asia/Tokyo", props("Asia/Tokyo", "JPY").siteTimezone("SITE-IN"));
        assertEquals("JPY", props("Asia/Tokyo", "JPY").siteCurrency("site-in"));
        DashboardProperties p = props(null, null);
        assertEquals("Asia/Kolkata", p.siteTimezone("SITE-IN"), "站点表没设置：dashboard.timezone");
        assertEquals("INR", p.siteCurrency("SITE-IN"), "站点表没设置：dashboard.currency");
        assertEquals("Asia/Kolkata", p.siteTimezone("SITE-BAD"), "不合法的时区：dashboard.timezone");
        assertEquals("Asia/Shanghai", p.siteTimezone("JITO"));
        assertEquals(ZoneId.of("Asia/Shanghai"), p.siteZone("jito"));
    }

    @Test
    void currentSiteFollowsSiteContext()
    {
        DashboardProperties p = props(null, null);
        assertEquals("Asia/Kolkata", p.currentTimezone(), "没有请求上下文 = 默认站点");
        assertEquals("INR", p.currentCurrency());
        SiteContext.set("JITO", "JITO");
        try
        {
            assertEquals("Asia/Shanghai", p.currentTimezone());
            assertEquals(ZoneId.of("Asia/Shanghai"), p.currentZone());
            assertEquals("CNY", p.currentCurrency());
        }
        finally
        {
            SiteContext.clear();
        }
    }

    @Test
    void zoneTextForExports()
    {
        DashboardProperties p = props(null, null);
        assertEquals("IST（UTC+5:30）", p.siteZoneText("SITE-IN"), "与原来写死的写法一致");
        assertEquals("Asia/Shanghai（UTC+8）", p.siteZoneText("JITO"));
        assertEquals("UTC+8", p.siteZoneLabel("JITO"));
    }
}
