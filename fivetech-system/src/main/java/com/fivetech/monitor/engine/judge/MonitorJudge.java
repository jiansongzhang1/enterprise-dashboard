package com.fivetech.monitor.engine.judge;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 判定纯函数：不碰数据库、UDS 和时钟。每小时判定（{@link #step}）和回测预览（{@link #judge}）共用，口径一致。
 *
 * <p>规则见设计文档「判定引擎」与「后台任务逻辑细化 · 规则运行状态与事件状态机」：</p>
 * <ul>
 *   <li>周期等级：样本量不足 / 周期内缺数据 / 跌幅缺基准 → SKIP；越过严重线 → CRIT；越过注意线 → WARN；否则 OK。
 *       等于阈值：下限、上限、区间不算越线，跌幅算越线。</li>
 *   <li>没有异常中的事件：连续 N 个周期越线（任一等级）开事件；OK / SKIP / 断档清空连续计数。</li>
 *   <li>有异常中的事件：OK → 恢复；SKIP → 不变；WARN / CRIT → 按「当前等级」重算（可升可降）。</li>
 *   <li>当前等级：最近 N 个周期全是 CRIT → 严重；否则设了注意升级且从触发时间起已持续满升级时长 → 严重（已升级）；否则注意。</li>
 * </ul>
 *
 * @author fivetech
 */
public final class MonitorJudge
{
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private MonitorJudge()
    {
    }

    // ------------------------------------------------------------------ 周期等级

    /** 一个周期的等级 */
    public static String periodLevel(JudgeRule rule, PeriodPoint p)
    {
        if (p == null || p.isMissing() || p.getValue() == null)
        {
            return Levels.SKIP;
        }
        boolean drop = JudgeRule.DROP.equals(rule.getJudgeType());
        if (drop && (p.getBaseline() == null || p.getBaseline().signum() <= 0))
        {
            return Levels.SKIP;
        }
        if (!sampleEnough(rule, p))
        {
            return Levels.SKIP;
        }
        if (crosses(rule, p, rule.getCritLow(), rule.getCritHigh()))
        {
            return Levels.CRIT;
        }
        if (rule.hasWarn() && crosses(rule, p, rule.getWarnLow(), rule.getWarnHigh()))
        {
            return Levels.WARN;
        }
        return Levels.OK;
    }

    /** 样本量：低于最小值不判定；取不到样本量（字段未接入）时不拦 */
    private static boolean sampleEnough(JudgeRule rule, PeriodPoint p)
    {
        BigDecimal min = rule.getMinSample();
        if (min == null || min.signum() <= 0)
        {
            return true;
        }
        BigDecimal sample = rule.isSampleIsBaseline() ? p.getBaseline() : p.getSample();
        return sample == null || sample.compareTo(min) >= 0;
    }

    private static boolean crosses(JudgeRule rule, PeriodPoint p, BigDecimal low, BigDecimal high)
    {
        BigDecimal v = p.getValue();
        switch (rule.getJudgeType())
        {
            case JudgeRule.LO:
                return low != null && v.compareTo(low) < 0;
            case JudgeRule.HI:
                return high != null && v.compareTo(high) > 0;
            case JudgeRule.BAND:
                return (low != null && v.compareTo(low) < 0) || (high != null && v.compareTo(high) > 0);
            case JudgeRule.DROP:
                BigDecimal pct = dropPct(v, p.getBaseline());
                return low != null && pct != null && pct.compareTo(low) >= 0;
            default:
                return false;
        }
    }

    /** 较基准下跌的百分比：(基准 − 值) / 基准 × 100；基准为空或 ≤ 0 时为 null */
    public static BigDecimal dropPct(BigDecimal value, BigDecimal baseline)
    {
        if (value == null || baseline == null || baseline.signum() <= 0)
        {
            return null;
        }
        return baseline.subtract(value).multiply(HUNDRED).divide(baseline, 4, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ 状态机

    /**
     * 判定一个周期并推进运行状态。
     *
     * @param prev          上一次的状态（null = 空状态）；eventOpen / eventTriggerHour 由调用方按 monitor_event 填好
     * @param lookbackHours 事件起点最多回看的小时数
     */
    public static StepResult step(JudgeRule rule, RunState prev, PeriodPoint p, int lookbackHours)
    {
        RunState s = prev == null ? RunState.empty() : prev.copy();
        String level = periodLevel(rule, p);
        int n = rule.getDurationCount();

        // 断档（服务停过、规则刚重置）：没有事件时按中断处理
        if (!s.isEventOpen() && s.getLastPeriodEnd() != null && p.getFrom().isAfter(s.getLastPeriodEnd()))
        {
            s.resetRun();
        }

        StepResult out = new StepResult();
        out.setLevel(level);
        if (!s.isEventOpen())
        {
            if (Levels.isOver(level))
            {
                if (s.getRecentLevels().isEmpty())
                {
                    s.setRunStartHour(p.getFrom());
                }
                s.push(level, n);
                if (s.getRecentLevels().size() >= n)
                {
                    LocalDateTime earliest = p.getFrom().minusHours(Math.max(0, lookbackHours));
                    LocalDateTime start = s.getRunStartHour() == null || s.getRunStartHour().isBefore(earliest)
                            ? earliest : s.getRunStartHour();
                    s.setEventOpen(true);
                    s.setEventTriggerHour(p.getFrom());
                    out.setAction(StepResult.Action.OPEN);
                    out.setStartHour(start);
                    out.setTriggerHour(p.getFrom());
                    currentLevel(rule, s, p, out);
                }
            }
            else
            {
                s.resetRun();
            }
        }
        else if (Levels.OK.equals(level))
        {
            out.setAction(StepResult.Action.RESOLVE);
            out.setEndHour(p.getFrom());
            s.resetRun();
            s.setEventOpen(false);
            s.setEventTriggerHour(null);
        }
        else if (Levels.isOver(level))
        {
            if (s.getRecentLevels().isEmpty() && s.getRunStartHour() == null)
            {
                s.setRunStartHour(p.getFrom());
            }
            s.push(level, n);
            out.setAction(StepResult.Action.UPDATE);
            currentLevel(rule, s, p, out);
        }
        // 有事件且本周期 SKIP：事件不变
        s.setLastPeriodEnd(p.getTo());
        out.setNext(s);
        return out;
    }

    private static void currentLevel(JudgeRule rule, RunState s, PeriodPoint p, StepResult out)
    {
        List<String> recent = s.getRecentLevels();
        boolean allCrit = recent.size() >= rule.getDurationCount();
        for (String l : recent)
        {
            allCrit &= Levels.CRIT.equals(l);
        }
        if (allCrit)
        {
            out.setEventLevel(Levels.CRIT);
            out.setEscalated(false);
            return;
        }
        int escPeriods = rule.escalatePeriods();
        LocalDateTime trigger = s.getEventTriggerHour();
        if (escPeriods > 0 && trigger != null
                && ChronoUnit.HOURS.between(trigger, p.getFrom()) >= (long) escPeriods * rule.getPeriodHours())
        {
            out.setEventLevel(Levels.CRIT);
            out.setEscalated(true);
            return;
        }
        out.setEventLevel(Levels.WARN);
        out.setEscalated(false);
    }

    // ------------------------------------------------------------------ 回测

    /**
     * 判定一串周期（按时间正序）。回测预览传入 30 天的全部周期、prev 为 null。
     */
    public static JudgeResult judge(JudgeRule rule, List<PeriodPoint> points, RunState prev, int lookbackHours)
    {
        JudgeResult result = new JudgeResult();
        RunState state = prev;
        Segment open = null;
        List<Integer> run = new ArrayList<>();
        for (int i = 0; i < points.size(); i++)
        {
            PeriodPoint p = points.get(i);
            StepResult r = step(rule, state, p, lookbackHours);
            result.getLevels().add(r.getLevel());
            boolean over = Levels.isOver(r.getLevel());
            if (over)
            {
                result.setOverPeriods(result.getOverPeriods() + 1);
            }
            switch (r.getAction())
            {
                case OPEN:
                    run.add(i);
                    open = new Segment();
                    open.setStartHour(r.getStartHour());
                    open.setTriggerHour(r.getTriggerHour());
                    open.getPointIndexes().addAll(run);
                    open.setLevel(r.getEventLevel());
                    open.setEscalated(r.isEscalated());
                    result.getSegments().add(open);
                    run.clear();
                    break;
                case UPDATE:
                    open.getPointIndexes().add(i);
                    open.setLevel(r.getEventLevel());
                    open.setEscalated(r.isEscalated());
                    break;
                case RESOLVE:
                    close(rule, open, points, r.getEndHour(), false);
                    open = null;
                    run.clear();
                    break;
                default:
                    if (open == null)
                    {
                        // 没有事件：越线就攒进当前段，否则（OK / SKIP / 断档）段中断
                        boolean contiguous = state == null || state.getLastPeriodEnd() == null
                                || !p.getFrom().isAfter(state.getLastPeriodEnd());
                        if (!over || !contiguous)
                        {
                            run.clear();
                        }
                        if (over)
                        {
                            run.add(i);
                        }
                    }
                    break;
            }
            state = r.getNext();
        }
        if (open != null)
        {
            LocalDateTime end = points.get(points.size() - 1).getTo();
            close(rule, open, points, end, true);
        }
        Set<Integer> inSegments = new HashSet<>();
        for (Segment s : result.getSegments())
        {
            inSegments.addAll(s.getPointIndexes());
        }
        result.setIsolatedPeriods(result.getOverPeriods() - inSegments.size());
        result.setNext(state);
        return result;
    }

    private static void close(JudgeRule rule, Segment seg, List<PeriodPoint> points, LocalDateTime end, boolean ongoing)
    {
        seg.setOngoing(ongoing);
        seg.setEndHour(ongoing ? null : end);
        seg.setDurationHours(Math.max(0, ChronoUnit.HOURS.between(seg.getStartHour(), end)));
        seg.setWorstIndex(worstIndex(rule, points, seg.getPointIndexes()));
    }

    /**
     * 段内最差的周期：下限取最小值，上限取最大值，区间取超出区间最远的（设了注意区间按注意区间，否则按严重区间），
     * 跌幅取较基准跌得最多的。
     */
    public static int worstIndex(JudgeRule rule, List<PeriodPoint> points, List<Integer> indexes)
    {
        int best = -1;
        BigDecimal bestScore = null;
        for (int i : indexes)
        {
            PeriodPoint p = points.get(i);
            BigDecimal score = badness(rule, p);
            if (score != null && (bestScore == null || score.compareTo(bestScore) > 0))
            {
                best = i;
                bestScore = score;
            }
        }
        return best;
    }

    /** 越大越差 */
    private static BigDecimal badness(JudgeRule rule, PeriodPoint p)
    {
        BigDecimal v = p.getValue();
        if (v == null)
        {
            return null;
        }
        switch (rule.getJudgeType())
        {
            case JudgeRule.LO:
                return v.negate();
            case JudgeRule.HI:
                return v;
            case JudgeRule.BAND:
                BigDecimal low = rule.hasWarn() ? rule.getWarnLow() : rule.getCritLow();
                BigDecimal high = rule.hasWarn() ? rule.getWarnHigh() : rule.getCritHigh();
                BigDecimal below = low == null ? null : low.subtract(v);
                BigDecimal above = high == null ? null : v.subtract(high);
                if (below == null)
                {
                    return above;
                }
                return above == null || below.compareTo(above) >= 0 ? below : above;
            case JudgeRule.DROP:
                return dropPct(v, p.getBaseline());
            default:
                return null;
        }
    }
}
