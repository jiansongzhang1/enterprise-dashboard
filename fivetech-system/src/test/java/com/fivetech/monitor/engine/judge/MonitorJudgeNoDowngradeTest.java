package com.fivetech.monitor.engine.judge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** MON-01：事件等级只升不降（FY-23 R23）；样本量取不到按样本不足（MON-34） */
class MonitorJudgeNoDowngradeTest
{
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 27, 8, 0);

    /** 存款成功率：注意 < 80，严重 < 50，每小时、连续 1 次 */
    private static final JudgeRule RULE = new JudgeRule(JudgeRule.LO, BigDecimal.valueOf(80), null, BigDecimal.valueOf(50), null,
            1, 1, null, null, false);

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
    void critThenWarnStaysCrit()
    {
        // 08:00 0%（严重）→ 09:00 50%（注意）：事件仍为严重
        JudgeResult r = MonitorJudge.judge(RULE, points(0, 50), null, 72);
        assertEquals(1, r.getSegments().size());
        Segment s = r.getSegments().get(0);
        assertEquals(Levels.CRIT, s.getLevel());
        assertFalse(s.isEscalated());
        assertTrue(s.isOngoing());
        assertEquals(List.of(Levels.CRIT, Levels.WARN), r.getLevels(), "周期等级照实记录，只有事件等级不降");
    }

    @Test
    void warnThenCritThenWarnIsCrit()
    {
        JudgeResult r = MonitorJudge.judge(RULE, points(60, 40, 70, 90), null, 72);
        assertEquals(1, r.getSegments().size());
        assertEquals(Levels.CRIT, r.getSegments().get(0).getLevel());
        assertFalse(r.getSegments().get(0).isOngoing(), "回到正常才结束");
    }

    @Test
    void higher()
    {
        assertEquals(Levels.CRIT, Levels.higher(Levels.CRIT, Levels.WARN));
        assertEquals(Levels.CRIT, Levels.higher(Levels.WARN, Levels.CRIT));
        assertEquals(Levels.WARN, Levels.higher(Levels.WARN, Levels.WARN));
        assertEquals(Levels.WARN, Levels.higher(null, Levels.WARN));
    }

    @Test
    void missingSampleMeansNotJudged()
    {
        // 最小样本量 50，样本量取不到（null）→ 不判定（以前按样本足够判定）
        JudgeRule withSample = new JudgeRule(JudgeRule.LO, BigDecimal.valueOf(80), null, BigDecimal.valueOf(50), null,
                1, 1, null, BigDecimal.valueOf(50), false);
        PeriodPoint p = new PeriodPoint(T0, T0.plusHours(1), BigDecimal.ZERO, null, null, false);
        assertEquals(Levels.SKIP, MonitorJudge.periodLevel(withSample, p));
        PeriodPoint few = new PeriodPoint(T0, T0.plusHours(1), BigDecimal.ZERO, null, BigDecimal.valueOf(3), false);
        assertEquals(Levels.SKIP, MonitorJudge.periodLevel(withSample, few));
        PeriodPoint enough = new PeriodPoint(T0, T0.plusHours(1), BigDecimal.ZERO, null, BigDecimal.valueOf(60), false);
        assertEquals(Levels.CRIT, MonitorJudge.periodLevel(withSample, enough));
    }
}
