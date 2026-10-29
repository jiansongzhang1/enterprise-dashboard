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

/**
 * 回测预览「平时水平」：直接用 UDS metric_distribution 的四值与极值时间，后端只换算单位（不统计）。
 * FY-26 R5：有效周期 &lt; 20 不估计 P5 / P95；R12：跌幅类按较基准跌幅（百分数，1 位小数），n = 0 为 empty。
 */
class MonitorPreviewUsualTest
{
    /** 比率 0～1 → 百分数（与 MetricValueNormalizer 的 PCT 一致） */
    private static final UnaryOperator<BigDecimal> PCT = v -> v == null ? null : v.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);

    /** 秒 → 分钟（MIN） */
    private static final UnaryOperator<BigDecimal> MIN = v -> v == null ? null : v.divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP);

    private static MetricDistribution dist(String p5, String p95, String min, String max, LocalDateTime minAt, LocalDateTime maxAt,
            Integer n)
    {
        MetricDistribution d = new MetricDistribution();
        d.setConfigured(true);
        d.setP5(new BigDecimal(p5));
        d.setP95(new BigDecimal(p95));
        d.setMin(new BigDecimal(min));
        d.setMax(new BigDecimal(max));
        d.setMinAt(minAt);
        d.setMaxAt(maxAt);
        d.setCount(n);
        return d;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> m(Object o)
    {
        return (Map<String, Object>) o;
    }

    private static String plain(Object v)
    {
        return v == null ? null : ((BigDecimal) v).toPlainString();
    }

    @Test
    void ratioIsScaledAndTimesComeFromUds()
    {
        // 联调文档样例：deposit_success_rate H1，门槛 30，n = 83
        MetricDistribution d = dist("0.0", "0.421052631579", "0.0", "0.507042253521",
                LocalDateTime.of(2026, 10, 3, 0, 0), LocalDateTime.of(2026, 9, 26, 8, 0), 83);
        Map<String, Object> u = MonitorPreviewService.usualOf("PCT", "lo", d, PCT, LocalDateTime.of(2026, 10, 3, 0, 0));
        assertEquals("uds", u.get("source"));
        assertEquals("ok", u.get("status"));
        assertEquals("value", u.get("kind"));
        assertEquals(83, u.get("sampleHours"));
        assertEquals(Boolean.TRUE, u.get("estimable"));
        assertEquals("0", plain(u.get("p5")));
        assertEquals("42.11", plain(u.get("p95")));
        assertEquals("50.7", plain(m(u.get("max")).get("value")));
        assertEquals("2026-10-03 00:00", m(u.get("min")).get("hour"));
        assertEquals("2026-09-26 08:00", m(u.get("max")).get("hour"));
        assertTrue((Boolean) m(u.get("min")).get("inOpenEvent"), "极值周期起点不早于当前事件起点");
        assertFalse((Boolean) m(u.get("max")).get("inOpenEvent"));
        assertFalse(u.containsKey("extreme"));
        assertNull(u.get("median"), "UDS 不提供中位数");
    }

    @Test
    void secondsToMinutes()
    {
        MetricDistribution d = dist("60", "600", "30", "1200", null, LocalDateTime.of(2026, 10, 2, 0, 0), 55);
        Map<String, Object> u = MonitorPreviewService.usualOf("MIN", "hi", d, MIN, null);
        assertEquals("1", plain(u.get("p5")));
        assertEquals("10", plain(u.get("p95")));
        assertEquals("20", plain(m(u.get("max")).get("value")));
        assertNull(m(u.get("min")).get("hour"), "UDS 没给 min_at 时为 null");
    }

    @Test
    void fewerThan20PeriodsOnlyExtremes()
    {
        // R5：n = 19 不估计 P5 / P95（UDS 仍会算，后端不返回），极值照给
        MetricDistribution d = dist("0.1", "0.4", "0.05", "0.5", LocalDateTime.of(2026, 10, 1, 0, 0), LocalDateTime.of(2026, 10, 2, 0, 0), 19);
        Map<String, Object> u = MonitorPreviewService.usualOf("PCT", "band", d, PCT, null);
        assertEquals("ok", u.get("status"));
        assertEquals(19, u.get("sampleHours"));
        assertEquals(Boolean.FALSE, u.get("estimable"));
        assertNull(u.get("p5"));
        assertNull(u.get("p95"));
        assertEquals("5", plain(m(u.get("min")).get("value")));
        assertEquals("50", plain(m(u.get("max")).get("value")));

        d.setCount(20);
        Map<String, Object> ok = MonitorPreviewService.usualOf("PCT", "band", d, PCT, null);
        assertEquals(Boolean.TRUE, ok.get("estimable"), "n = 20 开始估计");
        assertEquals("40", plain(ok.get("p95")));

        d.setCount(null);
        assertEquals(Boolean.FALSE, MonitorPreviewService.usualOf("PCT", "band", d, PCT, null).get("estimable"), "不知道 n 时不估计");
    }

    @Test
    void dropUsesP95AndMaxAsPercent()
    {
        // 跌幅类：UDS 返回（基准 − 值）÷ 基准；只用 p95、max（×100，1 位小数）
        MetricDistribution d = dist("-0.31", "0.18764", "-0.52", "0.4625", LocalDateTime.of(2026, 10, 1, 0, 0),
                LocalDateTime.of(2026, 10, 9, 12, 0), 38);
        Map<String, Object> u = MonitorPreviewService.usualOf("MONEY", "drop", d, UnaryOperator.identity(), null);
        assertEquals("drop", u.get("kind"));
        assertEquals("18.8", plain(u.get("p95")));
        assertEquals("46.3", plain(m(u.get("max")).get("value")));
        assertEquals("2026-10-09 12:00", m(u.get("max")).get("hour"));
        assertNull(u.get("p5"), "跌幅预览不用 p5");
        assertNull(u.get("min"), "跌幅预览不用 min");

        // R12 边界：p95 四舍五入到 0.1 后 ≤ 0、最大跌幅 ≤ 0（负数保留，表示高于基准）
        MetricDistribution low = dist("-0.4", "-0.0004", "-0.5", "-0.02", null, LocalDateTime.of(2026, 10, 2, 0, 0), 30);
        Map<String, Object> lu = MonitorPreviewService.usualOf("INT", "drop", low, UnaryOperator.identity(), null);
        assertEquals("0.0", plain(lu.get("p95")), "-0.04% 四舍五入为 0.0，不出现 -0.0");
        assertEquals("-2.0", plain(m(lu.get("max")).get("value")));

        // R5：跌幅类 n = 7 只给最大跌幅
        d.setCount(7);
        Map<String, Object> few = MonitorPreviewService.usualOf("MONEY", "drop", d, UnaryOperator.identity(), null);
        assertEquals(Boolean.FALSE, few.get("estimable"));
        assertNull(few.get("p95"));
        assertEquals("46.3", plain(m(few.get("max")).get("value")));
    }

    @Test
    void statusWhenNoData()
    {
        assertEquals("notConfigured", MonitorPreviewService.usualOf("PCT", "lo", null, PCT, null).get("status"));
        assertEquals("notConfigured", MonitorPreviewService.usualOf("PCT", "lo", MetricDistribution.notConfigured(), PCT, null).get("status"));
        MetricDistribution nr = new MetricDistribution();
        nr.setConfigured(true);
        nr.setNotReady(true);
        Map<String, Object> u = MonitorPreviewService.usualOf("PCT", "lo", nr, PCT, null);
        assertEquals("notReady", u.get("status"));
        assertNull(u.get("p5"));
        assertNull(u.get("min"));
        assertEquals(Boolean.FALSE, u.get("estimable"));

        // R12：n = 0（UDS 返回 valid_point_count = 0、其余 null，或不返回行）
        MetricDistribution empty = new MetricDistribution();
        empty.setConfigured(true);
        empty.setEmpty(true);
        Map<String, Object> e = MonitorPreviewService.usualOf("PCT", "lo", empty, PCT, null);
        assertEquals("empty", e.get("status"));
        assertEquals(0, e.get("sampleHours"), "没有行时 n 按 0 返回");
        assertNull(e.get("max"));
        empty.setCount(0);
        assertEquals(0, MonitorPreviewService.usualOf("PCT", "drop", empty, PCT, null).get("sampleHours"));
    }
}
