package com.fivetech.monitor.engine.judge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 回测预览结论句用的三个数：triggers（触发段）、overPeriods（n）、isolatedPeriods（m）。
 * 被样本不足 / 数据缺失打断、没连续满 N 次的越线计入 m。
 */
class MonitorJudgePreviewCountTest
{
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 1, 0, 0);

    /** 下限类：严重线 90，没有注意线，连续 2 次触发 */
    private static final JudgeRule RULE = new JudgeRule(JudgeRule.LO, null, null, BigDecimal.valueOf(90), null,
            1, 2, null, null, false);

    private static List<PeriodPoint> points(Integer... values)
    {
        List<PeriodPoint> list = new ArrayList<>();
        for (int i = 0; i < values.length; i++)
        {
            LocalDateTime from = T0.plusHours(i);
            Integer v = values[i];
            list.add(new PeriodPoint(from, from.plusHours(1), v == null ? null : BigDecimal.valueOf(v), null, null, v == null));
        }
        return list;
    }

    @Test
    void noOverPeriods()
    {
        JudgeResult r = MonitorJudge.judge(RULE, points(95, 96, 97), null, 72);
        assertEquals(0, r.getOverPeriods());
        assertEquals(0, r.getSegments().size());
    }

    @Test
    void overButNeverConsecutive()
    {
        // 越线被缺数据打断：80, 缺, 80 → 不触发，2 个零星
        JudgeResult r = MonitorJudge.judge(RULE, points(80, null, 80, 95), null, 72);
        assertEquals(2, r.getOverPeriods());
        assertEquals(0, r.getSegments().size());
        assertEquals(2, r.getIsolatedPeriods(), "没有触发时 m = n");
    }

    @Test
    void triggersAndIsolated()
    {
        // 95 | 80 | 缺 | 80 80 80 | 95 | 80
        // idx1 被缺数据打断 → 零星；idx3-5 连续 3 次 → 1 次触发；idx7 单次 → 零星
        JudgeResult r = MonitorJudge.judge(RULE, points(95, 80, null, 80, 80, 80, 95, 80), null, 72);
        assertEquals(5, r.getOverPeriods());
        assertEquals(1, r.getSegments().size());
        assertEquals(Levels.CRIT, r.getSegments().get(0).getLevel());
        assertEquals(List.of(3, 4, 5), r.getSegments().get(0).getPointIndexes());
        assertEquals(2, r.getIsolatedPeriods());
    }

    @Test
    void allOverPeriodsInsideTriggers()
    {
        JudgeResult r = MonitorJudge.judge(RULE, points(95, 80, 80, 95), null, 72);
        assertEquals(2, r.getOverPeriods());
        assertEquals(1, r.getSegments().size());
        assertEquals(0, r.getIsolatedPeriods());
    }
}
