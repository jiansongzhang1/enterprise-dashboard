package com.fivetech.dashboard.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** X-Uds-Site：每次请求 UDS 都带，默认站点也带 */
class DashboardPropertiesUdsSiteTest
{
    private static DashboardProperties props(String defaultUdsSite)
    {
        DashboardProperties p = new DashboardProperties();
        p.setDefaultSite("SITE-IN");
        p.setDefaultUdsSite(defaultUdsSite);
        DashboardProperties.Site jito = new DashboardProperties.Site();
        jito.setCode("JITO");
        jito.setUdsSite("SITE-B");
        DashboardProperties.Site bdw = new DashboardProperties.Site();
        bdw.setCode("BDW");
        bdw.setUdsSite(" ");
        p.setSites(Arrays.asList(jito, bdw));
        return p;
    }

    @Test
    void defaultSiteAlsoHasUdsSite()
    {
        assertEquals("SITE-IN", props(null).udsSite("SITE-IN"), "没配 default-uds-site 用默认站点编码");
        assertEquals("SITE-A", props(" SITE-A ").udsSite("site-in"), "配了用配置值，站点编码不区分大小写");
        assertEquals("SITE-IN", props(null).udsSite(null), "没有站点上下文按默认站点");
        assertEquals("SITE-IN", props("").udsSite(" "));
    }

    @Test
    void otherSites()
    {
        assertEquals("SITE-B", props(null).udsSite("JITO"));
        assertEquals("SITE-B", props(null).udsSite("jito"));
        assertEquals("BDW", props(null).udsSite("BDW"), "uds-site 没配用站点编码");
        assertNull(props(null).udsSite("NOPE"), "未配置的站点");
    }
}
