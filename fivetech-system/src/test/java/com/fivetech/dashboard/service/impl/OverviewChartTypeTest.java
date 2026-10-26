package com.fivetech.dashboard.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.domain.MetricDefinition;
import com.fivetech.dashboard.domain.vo.MetricChartVO;

/** 总览卡片的 chart.type 只有 GAUGE / LINE：chart_type 配的 BAR / PIE / NUMBER 都按 LINE（2026-10-23 去掉 BAR） */
class OverviewChartTypeTest
{
    private static MetricDefinition def(String code, String format, String chartType, BigDecimal gaugeMin, BigDecimal gaugeMax)
    {
        MetricDefinition d = new MetricDefinition(code, code, "g", "ATOMIC", format, "SUM", null);
        d.setChartType(chartType);
        d.setGaugeMin(gaugeMin);
        d.setGaugeMax(gaugeMax);
        return d;
    }

    @Test
    void barAndOtherTypesBecomeLine()
    {
        for (String t : new String[] { "BAR", "bar", "PIE", "NUMBER", "LINE", "", null })
        {
            MetricChartVO c = OverviewServiceImpl.chartOf(def("net", "MONEY", t, null, null));
            assertEquals(MetricChartVO.LINE, c.getType(), "chart_type=" + t);
            assertNull(c.getScale());
        }
    }

    @Test
    void gaugeWhenScaleConfigured()
    {
        MetricChartVO c = OverviewServiceImpl.chartOf(def("dOkR", "PCT", "BAR", new BigDecimal("85"), new BigDecimal("100")));
        assertEquals(MetricChartVO.GAUGE, c.getType());
        assertEquals("85.00", c.getScale().getMin());
        assertEquals("100.00", c.getScale().getMax());
    }

    @Test
    void onlyOneScaleSideIsNotGauge()
    {
        assertEquals(MetricChartVO.LINE, OverviewServiceImpl.chartOf(def("dT", "MIN", "LINE", BigDecimal.ZERO, null)).getType());
    }
}
