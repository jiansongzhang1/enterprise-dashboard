package com.fivetech.monitor.cond;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 条件参考信息：最小样本量中位数、历史天数、较基准 / 较昨日从哪天起可判定 */
class CondConditionInfoTest
{
    static final LocalDateTime END = LocalDateTime.of(2026, 11, 17, 10, 0);

    @Test
    @SuppressWarnings("unchecked")
    void shortHistoryGivesMedianAndBaseFrom()
    {
        Map<String, CondField> f = CondFixtures.catalog().fields();
        CondSeries s = new CondSeries(END.minusDays(30));
        // 只有近 12 天的数据：每小时注册 10、20 交替
        LocalDateTime first = END.minusDays(12);
        for (LocalDateTime h = first; h.isBefore(END); h = h.plusHours(1))
        {
            s.put(h, CondFixtures.vals("reg_cnt", h.getHour() % 2 == 0 ? 10 : 20, "ftd_cnt", 1));
        }
        Map<String, Object> out = CondPreviewService.info(f.get("ftdr"), s, CondWindow.H1, END, 4);
        Map<String, Object> sample = (Map<String, Object>) out.get("sample");
        assertEquals(0, new BigDecimal("15").compareTo((BigDecimal) sample.get("median")), "只算有数据以后的时段：10 与 20 各一半");
        assertEquals("註冊人數", sample.get("name"));
        assertEquals(12L, out.get("historyDays"));
        assertEquals(false, out.get("baseReady"));
        assertEquals("2026-12-03", out.get("baseFrom"), "最早数据 11-05 + 4 周");
        assertEquals(true, out.get("ydReady"));
        assertNull(out.get("ydFrom"));

        assertNull(CondPreviewService.info(f.get("dep"), s, CondWindow.H4, END, 4).get("sample"), "没有样本口径的字段");
    }

    @Test
    void fullHistoryAndMedian()
    {
        Map<String, CondField> f = CondFixtures.catalog().fields();
        CondSeries s = CondFixtures.flat(END.minusDays(30), 24 * 30, "reg_cnt", 7, "ftd_cnt", 1);
        Map<String, Object> out = CondPreviewService.info(f.get("ftdr"), s, CondWindow.H1, END, 4);
        assertEquals(30l, out.get("historyDays"));
        assertEquals(true, out.get("historyAtLeast"));
        assertEquals(true, out.get("baseReady"));
        assertEquals(0, new BigDecimal("2.5").compareTo(CondPreviewService.median(List.of(BigDecimal.ONE, new BigDecimal(2), new BigDecimal(3), new BigDecimal(4)))));
    }
}
