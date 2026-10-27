package com.fivetech.dashboard.gateway.uds;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.config.DashboardProperties;

/** 每次请求 UDS 都带 X-Uds-Site */
class UdsSiteHeaderTest
{
    private static DashboardProperties props()
    {
        DashboardProperties p = new DashboardProperties();
        p.setDefaultSite("SITE-IN");
        DashboardProperties.Site jito = new DashboardProperties.Site();
        jito.setCode("JITO");
        jito.setUdsSite("SITE-B");
        p.setSites(List.of(jito));
        return p;
    }

    @Test
    void contextValueWins()
    {
        assertEquals("SITE-B", UdsClient.siteHeader("JITO", "SITE-B", props()));
        assertEquals("SITE-IN", UdsClient.siteHeader("SITE-IN", " SITE-IN ", props()));
    }

    @Test
    void fallsBackWhenNoContext()
    {
        assertEquals("SITE-IN", UdsClient.siteHeader(null, null, props()), "定时任务等没有站点上下文：默认站点");
        assertEquals("SITE-B", UdsClient.siteHeader("JITO", null, props()), "只有站点编码时按配置解析");
        assertEquals("SITE-IN", UdsClient.siteHeader("SITE-IN", "", props()));
    }
}
