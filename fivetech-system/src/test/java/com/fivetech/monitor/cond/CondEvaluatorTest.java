package com.fivetech.monitor.cond;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 条件判定：窗口对齐、各类字段的窗口值、基准 / 昨日、变化与比较、样本、组合与连续段 */
class CondEvaluatorTest
{
    static final LocalDateTime END = LocalDateTime.of(2026, 11, 16, 12, 0);

    static CondCondition cond(int idx, String field, String how, String op, String unit, Object low, Object high, Object minSample)
    {
        CondCondition c = new CondCondition();
        c.setIndex(idx);
        c.setField(field);
        c.setHow(how);
        c.setOp(op);
        c.setUnit(unit);
        c.setLow(low == null ? null : new BigDecimal(String.valueOf(low)));
        c.setHigh(high == null ? null : new BigDecimal(String.valueOf(high)));
        c.setMinSample(minSample == null ? null : new BigDecimal(String.valueOf(minSample)));
        return c;
    }

    @Test
    void windowsAlignToNaturalPeriods()
    {
        assertTrue(CondWindow.H4.isCheckpoint(END), "12:00 是 4h 的检测点");
        assertFalse(CondWindow.H4.isCheckpoint(END.plusHours(1)));
        assertEquals(END.minusHours(4), CondWindow.H4.start(END));
        assertEquals("11-16 08:00 – 12:00", CondWindow.H4.label(END));
        assertEquals("11-16 11:00", CondWindow.H1.label(END));
        LocalDateTime midnight = LocalDateTime.of(2026, 11, 17, 0, 0);
        assertTrue(CondWindow.H24.isCheckpoint(midnight));
        assertEquals("11-16 全天", CondWindow.H24.label(midnight));
        assertEquals(LocalDateTime.of(2026, 11, 16, 0, 0), CondWindow.TODAY.start(END));
        assertEquals("11-16 00:00 – 12:00", CondWindow.TODAY.label(END));
        assertEquals(LocalDateTime.of(2026, 11, 16, 0, 0), CondWindow.TODAY.start(midnight), "0 点检测统计前一天全天");
        assertEquals(3, CondEvaluator.checkpoints(CondWindow.H4, END.minusHours(8), END).size(), "04、08、12 点");
    }

    @Test
    void windowValuesBySumRatioAverageAndSnapshot()
    {
        Map<String, CondField> f = CondFixtures.catalog().fields();
        CondSeries s = new CondSeries(END.minusHours(4));
        s.put(END.minusHours(4), CondFixtures.vals("reg_cnt", 10, "ftd_cnt", 1, "active_cnt", 100, "w_pend_cnt", 3));
        s.put(END.minusHours(3), CondFixtures.vals("reg_cnt", 30, "ftd_cnt", 5, "active_cnt", 200, "w_pend_cnt", 9));
        // 11:00 没有记录：按 0
        s.put(END.minusHours(1), CondFixtures.vals("reg_cnt", 60, "ftd_cnt", 4, "active_cnt", 300, "w_pend_cnt", 7));
        BigDecimal[] reg = CondEvaluator.window(f.get("reg"), s, END.minusHours(4), END);
        assertEquals(0, reg[0].compareTo(BigDecimal.valueOf(100)), "可加字段求和");
        BigDecimal[] ftdr = CondEvaluator.window(f.get("ftdr"), s, END.minusHours(4), END);
        assertEquals(0, ftdr[0].compareTo(BigDecimal.TEN), "比率按分子分母合计重算：10/100 × 100");
        assertEquals(0, ftdr[1].compareTo(BigDecimal.valueOf(100)), "样本 = 注册人数合计");
        BigDecimal[] active = CondEvaluator.window(f.get("active"), s, END.minusHours(4), END);
        assertEquals(0, active[0].compareTo(BigDecimal.valueOf(150)), "不可加字段取平均（缺的小时为 0）");
        BigDecimal[] pend = CondEvaluator.window(f.get("wPendN"), s, END.minusHours(4), END);
        assertEquals(0, pend[0].compareTo(BigDecimal.valueOf(7)), "快照取最后一小时");
        assertNull(CondEvaluator.window(f.get("ftdr"), new CondSeries(END), END.minusHours(1), END)[0], "分母为 0 = 无数据");
    }

    @Test
    void baseNeedsFourWeeksAndChangeOps()
    {
        Map<String, CondField> f = CondFixtures.catalog().fields();
        CondSeries s = CondFixtures.flat(END.minusWeeks(4).minusHours(1), 24 * 28 + 1, "reg_cnt", 100);
        s.put(END.minusHours(1), CondFixtures.vals("reg_cnt", 160));
        CondCondition up = cond(1, "reg", "BASE", "UP", "PCT", 50, null, null);
        CondEvaluator.Outcome o = CondEvaluator.judge(up, f.get("reg"), s, CondWindow.H1, END, 4);
        assertEquals(CondEvaluator.Status.MET, o.status());
        assertEquals(0, o.change().compareTo(BigDecimal.valueOf(60)));
        assertEquals(0, o.base().compareTo(BigDecimal.valueOf(100)));
        assertEquals(CondEvaluator.Status.NOT, CondEvaluator.judge(cond(1, "reg", "BASE", "DOWN", "PCT", 10, null, null), f.get("reg"), s,
            CondWindow.H1, END, 4).status());
        assertEquals(CondEvaluator.Status.MET, CondEvaluator.judge(cond(1, "reg", "BASE", "CHG", "ABS", 50, null, null), f.get("reg"), s,
            CondWindow.H1, END, 4).status(), "绝对值变化 60 > 50");

        CondSeries shortHistory = CondFixtures.flat(END.minusWeeks(2), 24 * 14, "reg_cnt", 100);
        assertEquals(CondEvaluator.Status.NO_BASE, CondEvaluator.judge(up, f.get("reg"), shortHistory, CondWindow.H1, END, 4).status(),
            "历史不到 4 周");
        CondCondition yd = cond(1, "reg", "YD", "DOWN", "PCT", 30, null, null);
        CondSeries ys = CondFixtures.flat(END.minusDays(1).minusHours(1), 25, "reg_cnt", 100);
        ys.put(END.minusHours(1), CondFixtures.vals("reg_cnt", 50));
        assertEquals(CondEvaluator.Status.MET, CondEvaluator.judge(yd, f.get("reg"), ys, CondWindow.H1, END, 4).status(), "较昨日 −50%");
    }

    @Test
    void currentValueOpsAndMinSample()
    {
        Map<String, CondField> f = CondFixtures.catalog().fields();
        CondSeries s = new CondSeries(END.minusHours(1));
        s.put(END.minusHours(1), CondFixtures.vals("reg_cnt", 50, "ftd_cnt", 2));
        CondCondition lt = cond(1, "ftdr", "CUR", "LT", null, 5, null, 100);
        CondEvaluator.Outcome o = CondEvaluator.judge(lt, f.get("ftdr"), s, CondWindow.H1, END, 4);
        assertEquals(CondEvaluator.Status.LOW_SAMPLE, o.status(), "注册 50 < 最小样本 100");
        lt.setMinSample(null);
        assertEquals(CondEvaluator.Status.MET, CondEvaluator.judge(lt, f.get("ftdr"), s, CondWindow.H1, END, 4).status(), "4% < 5%");
        CondCondition out = cond(1, "ftdr", "CUR", "OUT", null, 5, 10, null);
        assertEquals(CondEvaluator.Status.MET, CondEvaluator.judge(out, f.get("ftdr"), s, CondWindow.H1, END, 4).status());
        out.setLow(BigDecimal.ONE);
        assertEquals(CondEvaluator.Status.NOT, CondEvaluator.judge(out, f.get("ftdr"), s, CondWindow.H1, END, 4).status());
    }

    @Test
    void combineAndRuns()
    {
        Map<String, CondField> f = CondFixtures.catalog().fields();
        CondSpec spec = new CondSpec();
        spec.setWindow(CondWindow.H1);
        spec.setDurationCount(2);
        spec.setConditions(List.of(cond(1, "reg", "CUR", "GT", null, 100, null, null), cond(2, "dep", "CUR", "GT", null, 1000, null, null)));
        CondSeries reg = new CondSeries(END.minusHours(6));
        CondSeries dep = new CondSeries(END.minusHours(6));
        int[] regs = { 200, 200, 50, 200, 200, 200 };
        int[] deps = { 5000, 5000, 5000, 5000, 5000, 10 };
        for (int i = 0; i < 6; i++)
        {
            reg.put(END.minusHours(6 - i), CondFixtures.vals("reg_cnt", regs[i]));
            dep.put(END.minusHours(6 - i), CondFixtures.vals("dep_amount", deps[i]));
        }
        Map<Integer, CondSeries> series = Map.of(1, reg, 2, dep);
        List<CondEvaluator.Point> points = new ArrayList<>();
        for (LocalDateTime t : CondEvaluator.checkpoints(CondWindow.H1, END.minusHours(5), END))
        {
            points.add(CondEvaluator.point(spec, f, series, t, 4));
        }
        assertEquals(List.of(true, true, false, true, true, false), points.stream().map(CondEvaluator.Point::hit).toList(), "ALL");
        List<CondRuns.Run> runs = CondRuns.runs(points, 2);
        assertEquals(2, runs.size());
        assertTrue(runs.get(0).triggered());
        assertEquals(END.minusHours(4), runs.get(0).trigger(), "第 2 次命中时触发");
        assertFalse(runs.get(1).open());
        assertEquals(0, CondRuns.isolated(runs));

        spec.setMatchMode("ANY");
        CondEvaluator.Point last = CondEvaluator.point(spec, f, series, END, 4);
        assertTrue(last.hit(), "ANY：注册满足即可");
        assertEquals(1, last.metCount());
        assertEquals(1, CondRuns.isolated(CondRuns.runs(List.of(points.get(0), points.get(2)), 2)));
    }
}
