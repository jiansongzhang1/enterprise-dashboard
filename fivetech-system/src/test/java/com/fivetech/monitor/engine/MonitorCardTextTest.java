package com.fivetech.monitor.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.monitor.engine.judge.JudgeRule;
import com.fivetech.monitor.engine.judge.Levels;

/** 原型 Yata-V0.2 优化版：指标卡标签（ytagParts / y02cmp）、提醒条与 Lark 原因句的写法（MON-22） */
class MonitorCardTextTest
{
    private final MonitorMessageBuilder messages = new MonitorMessageBuilder(new DashboardProperties(), new MonitorProperties());

    private static BigDecimal d(String s)
    {
        return new BigDecimal(s);
    }

    private static List<BigDecimal[]> one(String value, String baseline)
    {
        return Collections.singletonList(new BigDecimal[] { value == null ? null : d(value), baseline == null ? null : d(baseline) });
    }

    private static List<BigDecimal[]> two(String a, String b)
    {
        return Arrays.asList(new BigDecimal[] { d(a), null }, new BigDecimal[] { d(b), null });
    }

    @Test
    void dropHourly()
    {
        JudgeRule drop = new JudgeRule(JudgeRule.DROP, d("30"), null, d("60"), null, 1, 1, null, null, false);
        assertEquals("超過嚴重線 60%", messages.cardLineText("INT", drop, Levels.CRIT, false));
        assertEquals("較基準跌 100.0%", messages.cardValueText("INT", drop, Levels.CRIT, false, one("0", "381"), "近 1 小時"));
        // 提醒条展开表、Lark 原因句
        assertEquals("超過嚴重線 60%", messages.lineText("INT", drop, Levels.CRIT, false));
        assertEquals("較基準下跌 100.0%", messages.valueText("INT", drop, Levels.CRIT, false, one("0", "381")));
    }

    @Test
    void dropMultiHourKeepsPeriodLabel()
    {
        JudgeRule drop = new JudgeRule(JudgeRule.DROP, null, null, d("20"), null, 4, 1, null, null, false);
        assertEquals("00:00 – 04:00 較基準跌 50.0%",
            messages.cardValueText("MONEY", drop, Levels.CRIT, false, one("50", "100"), "00:00 – 04:00"));
    }

    @Test
    void lowerKeepsWindowAndCompactsRange()
    {
        JudgeRule lo = new JudgeRule(JudgeRule.LO, d("95"), null, d("90"), null, 1, 2, null, null, false);
        assertEquals("近 2 小時 83.60–84.20%", messages.cardValueText("PCT", lo, Levels.CRIT, false, two("83.6", "84.2"), "近 2 小時"));
        assertEquals("83.60% – 84.20%", messages.valueText("PCT", lo, Levels.CRIT, false, two("83.6", "84.2")));
        assertEquals("低於嚴重線 90.00%", messages.cardLineText("PCT", lo, Levels.CRIT, false));
    }

    @Test
    void bandRange()
    {
        JudgeRule band = new JudgeRule(JudgeRule.BAND, d("8"), d("20"), d("5"), d("30"), 1, 1, null, null, false);
        assertEquals("超出嚴重區間 5.00–30.00×", messages.cardLineText("MULTIPLE", band, Levels.CRIT, false));
        assertEquals("超出嚴重區間 5.00× – 30.00×", messages.lineText("MULTIPLE", band, Levels.CRIT, false));
        assertEquals("超出注意區間 8.00–20.00%", messages.cardLineText("PCT", band, Levels.WARN, false));
    }

    @Test
    void minutesUseMin()
    {
        JudgeRule hi = new JudgeRule(JudgeRule.HI, null, d("5"), null, d("15"), 1, 2, null, null, false);
        assertEquals("高於注意線 5.0 min", messages.cardLineText("MIN", hi, Levels.WARN, false));
        assertEquals("高於注意線 5.0 min", messages.lineText("MIN", hi, Levels.WARN, false));
        assertEquals("近 2 小時 20.5–21.1 min", messages.cardValueText("MIN", hi, Levels.WARN, false, two("20.5", "21.1"), "近 2 小時"));
        assertEquals("高於注意線 0.3 min", messages.deviation("MIN", hi, Levels.WARN, false, d("5.3"), null).getText());
    }

    @Test
    void compactLikePrototype()
    {
        assertEquals("₹1,000–₹2,000", MonitorMessageBuilder.compact("₹1,000 – ₹2,000"));
        assertEquals("−₹1,000 – −₹500", MonitorMessageBuilder.compact("−₹1,000 – −₹500"));
        assertEquals("1.80%、8.60%", MonitorMessageBuilder.compact("1.80%、8.60%"));
    }
}
