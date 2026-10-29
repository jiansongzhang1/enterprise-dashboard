package com.fivetech.dashboard.gateway.uds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.gateway.MetricDistribution;

/** metric_distribution 请求体与取值（UDS 20 指标四值联调文档） */
class UdsDistributionQueryTest
{
    private static UdsProperties.Distribution cfg()
    {
        UdsProperties.Distribution c = new UdsProperties.Distribution();
        c.setName("metric_distribution_v1");
        c.getMetrics().put("dOkR", "deposit_success_rate");
        return c;
    }

    @Test
    @SuppressWarnings("unchecked")
    void bodyFixesMetricAndPeriodWithAnd()
    {
        Map<String, Object> body = UdsMetricDataGateway.distributionBody(cfg(), "deposit_success_rate", "H12",
                LocalDateTime.of(2026, 9, 27, 0, 0), LocalDateTime.of(2026, 10, 26, 0, 0), null);
        assertEquals("metric_distribution_v1", body.get("dataset"));
        assertEquals(List.of(), body.get("dimensions"), "单指标查询不带 dimensions");
        assertEquals(List.of("p5", "p95", "min", "max", "min_at", "max_at", "valid_point_count"), body.get("metrics"));
        Map<String, Object> time = (Map<String, Object>) body.get("time");
        assertEquals("period_start", time.get("dimension"));
        assertEquals("HOUR", time.get("granularity"));
        assertEquals("2026-09-27 00:00:00", time.get("from"));
        assertEquals("2026-10-26 00:00:00", time.get("to"), "两端都包含：to 是最后一个周期的起点");
        List<Map<String, Object>> and = (List<Map<String, Object>>) ((Map<String, Object>) body.get("filters")).get("and");
        assertEquals("metric_code", and.get(0).get("field"));
        assertEquals("EQ", and.get(0).get("op"));
        assertEquals("deposit_success_rate", and.get(0).get("value"));
        assertEquals("period_code", and.get(1).get("field"));
        assertEquals("H12", and.get(1).get("value"));
        assertEquals(2, and.size(), "没设最小样本量：不带 sample_basis");
        assertEquals(1, body.get("limit"));
        assertEquals("ONLINE", ((Map<String, Object>) body.get("options")).get("channel"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sampleThresholdOnlyWhenPositive()
    {
        LocalDateTime f = LocalDateTime.of(2026, 9, 26, 0, 0);
        LocalDateTime t = LocalDateTime.of(2026, 10, 25, 23, 0);
        // 联调文档样例：存款成功率 H1，门槛 30
        Map<String, Object> body = UdsMetricDataGateway.distributionBody(cfg(), "deposit_success_rate", "H1", f, t, new BigDecimal("30.00"));
        List<Map<String, Object>> and = (List<Map<String, Object>>) ((Map<String, Object>) body.get("filters")).get("and");
        assertEquals(3, and.size());
        assertEquals("sample_basis", and.get(2).get("field"));
        assertEquals("GTE", and.get(2).get("op"));
        assertEquals(30L, and.get(2).get("value"), "整数门槛传整数");

        Map<String, Object> money = UdsMetricDataGateway.distributionBody(cfg(), "deposit_amount", "DAY", f, t, new BigDecimal("50000"));
        Object v = ((List<Map<String, Object>>) ((Map<String, Object>) money.get("filters")).get("and")).get(2).get("value");
        assertEquals(50000L, v, "不能变成 5E+4");
        assertEquals("2.5", UdsMetricDataGateway.plainNumber(new BigDecimal("2.50")).toString());

        // 0 或负数：省略条件（不能传 GTE 0）
        for (BigDecimal none : new BigDecimal[] { BigDecimal.ZERO, new BigDecimal("0.00"), new BigDecimal("-1") })
        {
            Map<String, Object> b = UdsMetricDataGateway.distributionBody(cfg(), "deposit_success_rate", "H1", f, t, none);
            assertEquals(2, ((List<?>) ((Map<String, Object>) b.get("filters")).get("and")).size(), String.valueOf(none));
        }
    }

    @Test
    void defaultsAndOverrides()
    {
        UdsProperties.Distribution c = cfg();
        assertEquals("DAY", c.getPeriodCodes().get("24"));
        assertEquals("H1", c.getPeriodCodes().get("1"));
        Map<String, String> extra = new LinkedHashMap<>();
        assertEquals("valid_point_count", c.getColumns().get("count"), "有效周期数默认列");
        assertEquals("sample_basis", c.getSampleField(), "样本量列默认 sample_basis");
        extra.put("count", "n");
        c.setColumns(extra);
        assertEquals("p5", c.getColumns().get("p5"), "yml 只写要覆盖的列，其余默认列保留");
        assertEquals("n", c.getColumns().get("count"));
        assertFalse(new UdsProperties.Distribution().isConfigured(), "没配数据集名和指标映射 = 未接入");
        assertTrue(c.isConfigured());
    }

    @Test
    void fillByColumnName()
    {
        Map<String, Object> cells = new LinkedHashMap<>();
        cells.put("max", 0.610389610390);
        cells.put("min_at", "2026-10-01 12:00:00");
        cells.put("p95", 0.443478260870);
        cells.put("p5", 0.058441558442);
        cells.put("min", 0.041237113402);
        cells.put("max_at", "2026-10-20T00:00");
        cells.put("valid_point_count", 58);
        MetricDistribution d = new MetricDistribution();
        UdsMetricDataGateway.fillDistribution(cfg(), new UdsRow(cells), d);
        assertFalse(d.isEmpty());
        assertEquals("0.058441558442", d.getP5().toPlainString());
        assertEquals(LocalDateTime.of(2026, 10, 1, 12, 0), d.getMinAt());
        assertEquals(LocalDateTime.of(2026, 10, 20, 0, 0), d.getMaxAt());
        assertEquals(58, d.getCount(), "sampleHours 来自 valid_point_count");

        MetricDistribution none = new MetricDistribution();
        UdsMetricDataGateway.fillDistribution(cfg(), null, none);
        assertTrue(none.isEmpty(), "没有行 = 没有有效周期，不补 0");

        // n = 0：UDS 返回 valid_point_count = 0、其余六个字段为 null
        Map<String, Object> zero = new LinkedHashMap<>();
        zero.put("p5", null);
        zero.put("p95", null);
        zero.put("min", null);
        zero.put("max", null);
        zero.put("min_at", null);
        zero.put("max_at", null);
        zero.put("valid_point_count", 0);
        MetricDistribution z = new MetricDistribution();
        UdsMetricDataGateway.fillDistribution(cfg(), new UdsRow(zero), z);
        assertTrue(z.isEmpty());
        assertEquals(0, z.getCount());
    }

    @Test
    void parseTimeFormats()
    {
        assertEquals(LocalDateTime.of(2026, 10, 20, 0, 0), UdsMetricDataGateway.parseTime("2026-10-20"));
        assertEquals(LocalDateTime.of(2026, 10, 20, 12, 0), UdsMetricDataGateway.parseTime("2026-10-20 12:00"));
        assertEquals(LocalDateTime.of(2026, 10, 20, 12, 0), UdsMetricDataGateway.parseTime("2026-10-20 12:00:00.000"));
        assertNull(UdsMetricDataGateway.parseTime("x"));
        assertNull(UdsMetricDataGateway.parseTime(null));
    }
}
