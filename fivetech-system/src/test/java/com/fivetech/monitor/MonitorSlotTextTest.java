package com.fivetech.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import com.fivetech.monitor.engine.judge.JudgeRule;
import com.fivetech.monitor.service.MonitorSlotServiceTextProbe;

/** 抽屉监控格文字：只写越过的那条线，区间只写越过的一侧 */
class MonitorSlotTextTest
{
    private static BigDecimal d(String v)
    {
        return new BigDecimal(v);
    }

    @Test
    void loAndHi()
    {
        JudgeRule lo = new JudgeRule(JudgeRule.LO, d("95"), null, d("90"), null, 1, 2, null, null, false);
        assertEquals("< 90.00%", MonitorSlotServiceTextProbe.line(lo, "CRIT", d("85"), "PCT"));
        assertEquals("< 95.00%", MonitorSlotServiceTextProbe.line(lo, "WARN", d("93"), "PCT"));
        JudgeRule hi = new JudgeRule(JudgeRule.HI, null, d("5"), null, d("15"), 1, 2, null, null, false);
        assertEquals("> 15.0 min", MonitorSlotServiceTextProbe.line(hi, "CRIT", d("16"), "MIN"));
    }

    @Test
    void bandWritesOnlyTheCrossedSide()
    {
        JudgeRule band = new JudgeRule(JudgeRule.BAND, d("3"), d("6"), d("2"), d("8"), 1, 2, null, null, false);
        assertEquals("> 8.00%", MonitorSlotServiceTextProbe.line(band, "CRIT", d("9"), "PCT"));
        assertEquals("< 3.00%", MonitorSlotServiceTextProbe.line(band, "WARN", d("2.5"), "PCT"));
    }
}
