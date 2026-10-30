package com.fivetech.monitor.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.domain.MetricDefinition;
import com.fivetech.dashboard.gateway.HourlyFetch;
import com.fivetech.dashboard.service.MetricRegistry;
import com.fivetech.dashboard.service.MetricValueNormalizer;
import com.fivetech.monitor.engine.domain.MonitorEngineRule;
import com.fivetech.monitor.engine.judge.PeriodPoint;

/**
 * 多小时周期的平均到账时间：时段内耗时合计 ÷ 单数合计（后端相除组装，2026-10-29），
 * 不是各小时均值按成功笔数加权。
 */
class MonitorPeriodRatioTest
{
    private static BigDecimal[] arr(String... v)
    {
        return Arrays.stream(v).map(x -> x == null ? null : new BigDecimal(x)).toArray(BigDecimal[]::new);
    }

    @Test
    void multiHourPeriodUsesSumOverSum()
    {
        MetricDefinition def = new MetricDefinition("dT", "平均到帳時間", "pay", "ATOMIC", "MIN", "AVG_WEIGHTED", null);
        MetricRegistry registry = new MetricRegistry(null, null)
        {
            @Override
            public MetricDefinition get(String code)
            {
                return "dT".equals(code) ? def : null;
            }
        };
        MetricValueNormalizer normalizer = new MetricValueNormalizer(registry);
        MonitorPeriodBuilder builder = new MonitorPeriodBuilder(registry, normalizer);

        LocalDateTime from = LocalDateTime.of(2026, 10, 29, 0, 0);
        HourlyFetch data = new HourlyFetch(from, 2);
        // 第 1 小时：6000 秒 / 10 单 = 600 秒；第 2 小时：120 秒 / 1 单 = 120 秒
        data.put("dT", Arrays.asList(arr("600", "120")));
        data.put("dep_time_sum", Arrays.asList(arr("6000", "120")));
        data.put("dep_time_count", Arrays.asList(arr("10", "1")));
        // 成功笔数与计入耗时的单数不同：按它加权会得到 (600×5 + 120×5) ÷ 10 = 360 秒（错）
        data.put("dOk", Arrays.asList(arr("5", "5")));
        data.putRatio("dT", "dep_time_sum", "dep_time_count");

        MonitorEngineRule rule = new MonitorEngineRule();
        rule.setMetricCode("dT");
        rule.setJudgeType("hi");
        rule.setPeriodHours(2);
        rule.setMinSampleKey("dOk");

        PeriodPoint p = builder.build(rule, data, from, from.plusHours(2));
        // (6000 + 120) ÷ (10 + 1) = 556.36 秒 = 9.3 分钟
        assertEquals(normalizer.normalize("dT", new BigDecimal("6120").divide(new BigDecimal("11"), 10, java.math.RoundingMode.HALF_UP)),
            p.getValue());
        assertEquals(new BigDecimal("9.3"), p.getValue());
        assertEquals(new BigDecimal("10"), p.getSample(), "样本量仍是成功笔数之和");
    }

    @Test
    void oneHourPeriodTakesHourValue()
    {
        MetricDefinition def = new MetricDefinition("dT", "平均到帳時間", "pay", "ATOMIC", "MIN", "AVG_WEIGHTED", null);
        MetricRegistry registry = new MetricRegistry(null, null)
        {
            @Override
            public MetricDefinition get(String code)
            {
                return def;
            }
        };
        MonitorPeriodBuilder builder = new MonitorPeriodBuilder(registry, new MetricValueNormalizer(registry));
        LocalDateTime from = LocalDateTime.of(2026, 10, 29, 0, 0);
        HourlyFetch data = new HourlyFetch(from, 1);
        data.put("dT", Arrays.asList(arr("600")));
        data.putRatio("dT", "dep_time_sum", "dep_time_count");
        MonitorEngineRule rule = new MonitorEngineRule();
        rule.setMetricCode("dT");
        rule.setJudgeType("hi");
        rule.setPeriodHours(1);
        assertEquals(new BigDecimal("10.0"), builder.build(rule, data, from, from.plusHours(1)).getValue());
    }
}
