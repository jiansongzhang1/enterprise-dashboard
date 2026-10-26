package com.fivetech.dashboard.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** getInfo 的 sites[].timezone：站点单独配置的时区优先，没配或配错用全局 dashboard.timezone */
class DashboardPropertiesSiteTimezoneTest
{
    private static DashboardProperties props(String jitoTimezone)
    {
        DashboardProperties p = new DashboardProperties();
        p.setDefaultSite("SITE-IN");
        p.setTimezone("Asia/Kolkata");
        DashboardProperties.Site jito = new DashboardProperties.Site();
        jito.setCode("JITO");
        jito.setName("jito");
        jito.setTimezone(jitoTimezone);
        p.setSites(List.of(jito));
        return p;
    }

    @Test
    void defaultSiteUsesGlobalTimezone()
    {
        assertEquals("Asia/Kolkata", props("Asia/Dubai").siteTimezone("SITE-IN"));
    }

    @Test
    void configuredSiteTimezoneWins()
    {
        assertEquals("Asia/Dubai", props("Asia/Dubai").siteTimezone("JITO"));
        assertEquals("Asia/Dubai", props(" Asia/Dubai ").siteTimezone("jito"), "站点编码不区分大小写，时区去掉空白");
    }

    @Test
    void blankOrInvalidFallsBackToGlobal()
    {
        assertEquals("Asia/Kolkata", props(null).siteTimezone("JITO"));
        assertEquals("Asia/Kolkata", props("").siteTimezone("JITO"));
        assertEquals("Asia/Kolkata", props("Mars/Olympus").siteTimezone("JITO"), "配错不让 getInfo 失败");
    }

    @Test
    void unknownSiteUsesGlobal()
    {
        assertEquals("Asia/Kolkata", props("Asia/Dubai").siteTimezone("NOPE"));
    }
}
