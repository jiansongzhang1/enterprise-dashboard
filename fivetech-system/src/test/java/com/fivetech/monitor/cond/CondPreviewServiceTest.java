package com.fivetech.monitor.cond;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 触发预览的回测：触发段、孤立命中、平时水平、多条件的统计 */
class CondPreviewServiceTest
{
    static final LocalDateTime END = LocalDateTime.of(2026, 11, 16, 0, 0);

    @Test
    @SuppressWarnings("unchecked")
    void backtestRunsAndUsual()
    {
        Map<String, CondField> fields = CondFixtures.catalog().fields();
        CondSpec spec = new CondSpec();
        spec.setWindow(CondWindow.H1);
        spec.setDurationCount(2);
        spec.setLevel("CRIT");
        CondCondition c = CondEvaluatorTest.cond(1, "reg", "CUR", "GT", null, 100, null, null);
        spec.setConditions(List.of(c));
        CondSeries s = CondFixtures.flat(END.minusHours(48), 48, "reg_cnt", 50);
        // 两段：一段连续 3 小时（触发），一段只有 1 小时（孤立）
        for (int h : new int[] { 10, 11, 12 })
        {
            s.put(END.minusHours(48 - h), CondFixtures.vals("reg_cnt", 150 + h));
        }
        s.put(END.minusHours(48 - 30), CondFixtures.vals("reg_cnt", 120));
        List<LocalDateTime> cps = CondEvaluator.checkpoints(CondWindow.H1, END.minusHours(47), END);
        Map<String, Object> out = CondPreviewService.backtest(spec, fields, Map.of(1, s), cps, 4, null, "₹");
        Map<String, Object> summary = (Map<String, Object>) out.get("summary");
        assertEquals(1L, summary.get("triggers"));
        assertEquals(1L, summary.get("crit"));
        assertEquals(1, summary.get("isolated"));
        assertTrue(String.valueOf(summary.get("text")).contains("會觸發 1 次（嚴重 1）"), String.valueOf(summary.get("text")));
        List<Map<String, Object>> runs = (List<Map<String, Object>>) out.get("runs");
        assertEquals(1, runs.size());
        assertEquals(3, runs.get(0).get("hours"));
        assertEquals("11-14 10:00 – 13:00", runs.get(0).get("label"));
        Map<String, Object> worst = (Map<String, Object>) runs.get(0).get("worst");
        assertEquals(0, new BigDecimal("162").compareTo((BigDecimal) worst.get("value")), "最差 = 最大值");
        Map<String, Object> usual = ((List<Map<String, Object>>) out.get("usual")).get(0);
        assertEquals(48, usual.get("validPeriods"));
        assertNotNull(usual.get("p95"));
        assertNull(out.get("conditionHours"), "单条件不返回");
    }
}
