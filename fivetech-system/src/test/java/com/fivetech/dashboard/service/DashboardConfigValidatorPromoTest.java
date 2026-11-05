package com.fivetech.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.domain.MetricCardConfig;
import com.fivetech.dashboard.domain.PageMetricConfig;

/** 推广分析页面（PROMO）：仅登记的指标可以挂；没有 PROMO 配置只告警，不挡启动 */
class DashboardConfigValidatorPromoTest
{
    private static MetricCardConfig card(String code, String status)
    {
        MetricCardConfig c = new MetricCardConfig();
        c.setMetricCode(code);
        c.setMetricName(code);
        c.setGroupCode("eg1");
        c.setShowAsCard(true);
        c.setCalcType("NONE");
        c.setValueFormat("INT");
        c.setStatus(status);
        return c;
    }

    private static PageMetricConfig page(String page, String code)
    {
        PageMetricConfig p = new PageMetricConfig();
        p.setPageCode(page);
        p.setMetricCode(code);
        p.setSortNo(10);
        p.setIsDefault(true);
        p.setEmphasis("NORMAL");
        p.setStatus("0");
        return p;
    }

    private static List<PageMetricConfig> basePages()
    {
        List<PageMetricConfig> pages = new ArrayList<>();
        pages.add(page("OVERVIEW", "reg"));
        pages.add(page("SUMMARY", "reg"));
        return pages;
    }

    @Test
    void registeredPromoMetricsAllowedOnPromoPage()
    {
        List<PageMetricConfig> pages = basePages();
        pages.add(page("PROMO", "promoReg"));
        DashboardConfigValidator.Result r = new DashboardConfigValidator().validate(
            List.of(card("reg", "0"), card("promoReg", "1")), pages, List.of("eg1"));
        assertEquals(List.of(), r.getErrors());
    }

    @Test
    void registeredMetricStillRejectedOnOverview()
    {
        List<PageMetricConfig> pages = basePages();
        pages.add(page("OVERVIEW", "promoReg"));
        pages.add(page("PROMO", "promoReg"));
        DashboardConfigValidator.Result r = new DashboardConfigValidator().validate(
            List.of(card("reg", "0"), card("promoReg", "1")), pages, List.of("eg1"));
        assertEquals(1, r.getErrors().size());
        assertTrue(r.getErrors().get(0).contains("OVERVIEW"));
    }

    @Test
    void missingPromoPageOnlyWarns()
    {
        DashboardConfigValidator.Result r = new DashboardConfigValidator().validate(List.of(card("reg", "0")), basePages(), List.of("eg1"));
        assertEquals(List.of(), r.getErrors());
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("PROMO")));
    }
}
