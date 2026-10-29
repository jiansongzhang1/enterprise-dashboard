package com.fivetech.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.domain.ResolvedRange;
import com.fivetech.dashboard.domain.vo.QueryContext;

/** 指标汇总 / 明细的 context.timezone、context.currency 按站点（站点表），不再是全局 Asia/Kolkata / INR */
class TimeRangeResolverSiteZoneTest
{
    @Test
    void contextUsesSiteTimezoneAndCurrency()
    {
        DashboardProperties p = new DashboardProperties();
        p.setTimezone("Asia/Kolkata");
        p.setCurrency("INR");
        DashboardProperties.Site jito = new DashboardProperties.Site();
        jito.setCode("JITO");
        jito.setTimezone("Asia/Shanghai");
        jito.setCurrency("CNY");
        p.applySites("SITE-IN", "uWin", null, null, null, null, List.of(jito), null);
        TimeRangeResolver resolver = new TimeRangeResolver(p, null);
        ResolvedRange range = ResolvedRange.of(LocalDateTime.of(2026, 10, 29, 0, 0), LocalDateTime.of(2026, 10, 29, 6, 0), null);

        QueryContext j = resolver.buildContext("JITO", range, null, new ArrayList<>());
        assertEquals("Asia/Shanghai", j.getTimezone());
        assertEquals("CNY", j.getCurrency());

        QueryContext in = resolver.buildContext("SITE-IN", range, null, new ArrayList<>());
        assertEquals("Asia/Kolkata", in.getTimezone(), "默认站点表里没设置：dashboard.timezone 兜底");
        assertEquals("INR", in.getCurrency());
    }
}
