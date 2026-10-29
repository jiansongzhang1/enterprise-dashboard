package com.fivetech.dashboard.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.fivetech.monitor.engine.MonitorMessageBuilder;

/** Lark 消息的时区按站点：标注（IST / UTC+8）与统计时区 → 站点时区的换算 */
class DashboardPropertiesSiteZoneTest
{
    private static DashboardProperties props()
    {
        DashboardProperties p = new DashboardProperties();
        p.setTimezone("Asia/Kolkata");
        DashboardProperties.Site jito = new DashboardProperties.Site();
        jito.setCode("JITO");
        jito.setTimezone("Asia/Shanghai");
        DashboardProperties.Site np = new DashboardProperties.Site();
        np.setCode("SITE-NP");
        np.setTimezone("Asia/Kathmandu");
        p.applySites("SITE-IN", "uWin", null, List.of(jito, np));
        return p;
    }

    @Test
    void zoneLabel()
    {
        DashboardProperties p = props();
        assertEquals("IST", p.siteZoneLabel("SITE-IN"));
        assertEquals("UTC+8", p.siteZoneLabel("jito"));
        assertEquals("UTC+5:45", p.siteZoneLabel("SITE-NP"));
    }

    @Test
    void statTimeToSiteTime()
    {
        DashboardProperties p = props();
        LocalDateTime ist = LocalDateTime.of(2026, 10, 28, 10, 0);
        assertEquals(LocalDateTime.of(2026, 10, 28, 12, 30), p.toSiteTime("JITO", ist), "印度 10:00 = 北京 12:30");
        assertEquals(ist, p.toSiteTime("SITE-IN", ist));
    }

    @Test
    void triggerTimeLabel()
    {
        LocalDateTime t = LocalDateTime.of(2026, 10, 22, 14, 0);
        // Lark 消息的时间不写时区（2026-10-29）
        assertEquals("2026-10-22 14:00", MonitorMessageBuilder.triggerTime(t, 1));
        assertEquals("2026-10-22 12:00 – 24:00", MonitorMessageBuilder.triggerTime(t.withHour(12), 12));
    }
}
