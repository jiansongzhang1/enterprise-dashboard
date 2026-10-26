package com.fivetech.monitor.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.gateway.MetricDistribution;

/** 回测预览「平时水平」：直接用 UDS metric_distribution 的四值与极值时间，后端只换算单位（不统计） */
class MonitorPreviewUsualTest
{
    /** 比率 0～1 → 百分数（与 MetricValueNormalizer 的 PCT 一致） */
    private static final UnaryOperator<BigDecimal> PCT = v -> v == null ? null : v.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);

    /** 秒 → 分钟（MIN） */
    private static final UnaryOperator<BigDecimal> MIN = v -> v == null ? null : v.divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP);

    private static MetricDistribution dist(String p5, String p95, String min, String max, LocalDateTime minAt, LocalDateTime maxAt)
    {
        MetricDistribution d = new MetricDistribution();
        d.setConfigured(true);
        d.setP5(new BigDecimal(p5));
        d.setP95(new BigDecimal(p95));
        d.setMin(new BigDecimal(min));
        d.setMax(new BigDecimal(max));
        d.setMinAt(minAt);
        d.setMaxAt(maxAt);
        return d;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> m(Object o)
    {
        return (Map<String, Object>) o;
    }

    @Test
    void ratioIsScaledAndTimesComeFromUds()
    {
        // 联调文档样例：deposit_success_rate H12
        MetricDistribution d = dist("0.058441558442", "0.443478260870", "0.041237113402", "0.610389610390",
                LocalDateTime.of(2026, 10, 1, 12, 0), LocalDateTime.of(2026, 10, 20, 0, 0));
        Map<String, Object> u = MonitorPreviewService.usualOf("PCT", d, PCT, LocalDateTime.of(2026, 10, 20, 0, 0));
        assertEquals("uds", u.get("source"));
        assertEquals("ok", u.get("status"));
        assertEquals("5.84", ((BigDecimal) u.get("p5")).toPlainString());
        assertEquals("44.35", ((BigDecimal) u.get("p95")).toPlainString());
        assertEquals("4.12", ((BigDecimal) m(u.get("min")).get("value")).toPlainString());
        assertEquals("2026-10-01 12:00", m(u.get("min")).get("hour"));
        assertEquals("2026-10-20 00:00", m(u.get("max")).get("hour"));
        assertFalse((Boolean) m(u.get("min")).get("inOpenEvent"));
        assertTrue((Boolean) m(u.get("max")).get("inOpenEvent"), "极值周期起点不早于当前事件起点");
        assertFalse(u.containsKey("extreme"), "extreme 已去掉");
        assertNull(u.get("median"), "UDS 不提供中位数");
        assertNull(u.get("sampleHours"), "没取到 valid_point_count 时为 null");
    }

    @Test
    void secondsToMinutesAndDropRawValues()
    {
        MetricDistribution d = dist("60", "600", "30", "1200", null, LocalDateTime.of(2026, 10, 2, 0, 0));
        d.setCount(55);
        Map<String, Object> u = MonitorPreviewService.usualOf("MIN", d, MIN, null);
        assertEquals("1", ((BigDecimal) u.get("p5")).toPlainString());
        assertEquals("20", ((BigDecimal) m(u.get("max")).get("value")).toPlainString());
        assertNull(m(u.get("min")).get("hour"), "UDS 没给 min_at 时为 null");
        assertEquals(55, u.get("sampleHours"));

        // 跌幅类：同样是周期原始值的四值，不做跌幅换算
        Map<String, Object> drop = MonitorPreviewService.usualOf("INT",
                dist("120", "260", "80", "300", null, null), UnaryOperator.identity(), null);
        assertEquals("120", ((BigDecimal) drop.get("p5")).toPlainString());
        assertEquals("80", ((BigDecimal) m(drop.get("min")).get("value")).toPlainString());
    }

    @Test
    void statusWhenNoData()
    {
        assertEquals("notConfigured", MonitorPreviewService.usualOf("PCT", null, PCT, null).get("status"));
        assertEquals("notConfigured", MonitorPreviewService.usualOf("PCT", MetricDistribution.notConfigured(), PCT, null).get("status"));
        MetricDistribution nr = new MetricDistribution();
        nr.setConfigured(true);
        nr.setNotReady(true);
        Map<String, Object> u = MonitorPreviewService.usualOf("PCT", nr, PCT, null);
        assertEquals("notReady", u.get("status"));
        assertNull(u.get("p5"));
        assertNull(u.get("min"));
        MetricDistribution empty = new MetricDistribution();
        empty.setConfigured(true);
        empty.setEmpty(true);
        assertEquals("empty", MonitorPreviewService.usualOf("PCT", empty, PCT, null).get("status"));
    }
}
