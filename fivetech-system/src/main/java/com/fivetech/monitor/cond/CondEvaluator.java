package com.fivetech.monitor.cond;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 条件判定（纯函数）：窗口值、基准、变化、比较，以及多条件组合。每小时检测与触发预览用同一套算法。
 *
 * <ul>
 *   <li>窗口值：可加字段求和；比率字段按分子分母合计重算（分母为 0 = 无数据）；其他取各小时平均；快照字段取检测前最后一小时；</li>
 *   <li>较基准（BASE）：近 {@code baseWeeks} 周同星期、同时段的窗口值平均；历史不够 = 基准不足；</li>
 *   <li>较昨日同时段（YD）：前一天同时段；</li>
 *   <li>变化：PCT 为 (当前 − 基准) ÷ 基准 × 100（基准为 0 时算基准不足），ABS 为 当前 − 基准（比率字段为百分点）；</li>
 *   <li>UP：变化 &gt; 门槛；DOWN：变化 &lt; −门槛；CHG：|变化| &gt; 门槛；当前值 GT / GE / LT / LE / OUT（&lt; 下限或 &gt; 上限）；</li>
 *   <li>样本（minSample）：窗口内样本合计低于门槛 = 样本不足，这次视为不满足。</li>
 * </ul>
 *
 * @author fivetech
 */
public final class CondEvaluator
{
    /** 满足 / 不满足 / 基准不足 / 样本不足 / 无数据（比率的分母为 0） */
    public enum Status
    {
        MET, NOT, NO_BASE, LOW_SAMPLE, NO_DATA
    }

    /** 一个条件在一个检测点的结果 */
    public record Outcome(int index, Status status, BigDecimal value, BigDecimal base, BigDecimal change, BigDecimal sample)
    {
        public boolean met()
        {
            return status == Status.MET;
        }
    }

    /** 一个检测点的结果 */
    public record Point(LocalDateTime end, List<Outcome> outcomes, boolean hit)
    {
        public int metCount()
        {
            return (int) outcomes.stream().filter(Outcome::met).count();
        }

        public boolean anyNoBase()
        {
            return outcomes.stream().anyMatch(o -> o.status() == Status.NO_BASE);
        }
    }

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);

    private CondEvaluator()
    {
    }

    /** 窗口值与样本：[0] 值（null = 无数据），[1] 样本 */
    static BigDecimal[] window(CondField f, CondSeries s, LocalDateTime start, LocalDateTime end)
    {
        if (f.isSnapshot())
        {
            LocalDateTime last = end.minusHours(1);
            BigDecimal v = s.get(last, f.getUdsField());
            BigDecimal sample = f.getSampleField() == null ? null : s.get(last, f.getSampleField());
            return new BigDecimal[] { s.has(last) ? v : null, sample };
        }
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal num = BigDecimal.ZERO;
        BigDecimal den = BigDecimal.ZERO;
        BigDecimal sample = f.getSampleField() == null ? null : BigDecimal.ZERO;
        int n = 0;
        for (LocalDateTime h = start; h.isBefore(end); h = h.plusHours(1))
        {
            n++;
            if (f.isRatio())
            {
                num = num.add(s.get(h, f.getNumerator()));
                den = den.add(s.get(h, f.getDenominator()));
            }
            else
            {
                sum = sum.add(s.get(h, f.getUdsField()));
            }
            if (sample != null)
            {
                sample = sample.add(s.get(h, f.getSampleField()));
            }
        }
        BigDecimal value;
        if (f.isRatio())
        {
            value = den.signum() == 0 ? null : num.multiply(f.getRatioScale()).divide(den, MC);
        }
        else if (f.isAdditive() || n == 0)
        {
            value = sum;
        }
        else
        {
            value = sum.divide(BigDecimal.valueOf(n), MC);
        }
        return new BigDecimal[] { value, sample };
    }

    /** 一个条件在检测点 end 的结果 */
    public static Outcome judge(CondCondition c, CondField f, CondSeries s, CondWindow w, LocalDateTime end, int baseWeeks)
    {
        LocalDateTime start = w.start(end);
        BigDecimal[] cur = window(f, s, start, end);
        BigDecimal value = cur[0];
        BigDecimal sample = cur[1];
        if (value == null)
        {
            return new Outcome(c.getIndex(), Status.NO_DATA, null, null, null, sample);
        }
        boolean lowSample = c.getMinSample() != null && (sample == null || sample.compareTo(c.getMinSample()) < 0);
        if (!c.isChange())
        {
            if (lowSample)
            {
                return new Outcome(c.getIndex(), Status.LOW_SAMPLE, value, null, null, sample);
            }
            return new Outcome(c.getIndex(), compare(c, value) ? Status.MET : Status.NOT, value, null, null, sample);
        }
        BigDecimal base = base(c, f, s, w, start, end, baseWeeks);
        if (base == null || ("PCT".equals(c.getUnit()) && base.signum() == 0))
        {
            return new Outcome(c.getIndex(), Status.NO_BASE, value, base, null, sample);
        }
        BigDecimal change = "PCT".equals(c.getUnit())
            ? value.subtract(base).multiply(BigDecimal.valueOf(100)).divide(base.abs(), MC)
            : value.subtract(base);
        if (lowSample)
        {
            return new Outcome(c.getIndex(), Status.LOW_SAMPLE, value, base, change, sample);
        }
        return new Outcome(c.getIndex(), compareChange(c, change) ? Status.MET : Status.NOT, value, base, change, sample);
    }

    /** 基准：BASE 为近 N 周同时段平均，YD 为前一天同时段；历史不够返回 null */
    static BigDecimal base(CondCondition c, CondField f, CondSeries s, CondWindow w, LocalDateTime start, LocalDateTime end,
            int baseWeeks)
    {
        if ("YD".equals(c.getHow()))
        {
            if (!s.covers(start.minusDays(1)))
            {
                return null;
            }
            return window(f, s, start.minusDays(1), end.minusDays(1))[0];
        }
        int weeks = Math.max(1, baseWeeks);
        if (!s.covers(start.minusWeeks(weeks)))
        {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        int n = 0;
        for (int k = 1; k <= weeks; k++)
        {
            BigDecimal v = window(f, s, start.minusWeeks(k), end.minusWeeks(k))[0];
            if (v != null)
            {
                sum = sum.add(v);
                n++;
            }
        }
        return n == 0 ? null : sum.divide(BigDecimal.valueOf(n), MC);
    }

    static boolean compare(CondCondition c, BigDecimal v)
    {
        switch (c.getOp())
        {
            case "GT":
                return v.compareTo(c.getLow()) > 0;
            case "GE":
                return v.compareTo(c.getLow()) >= 0;
            case "LT":
                return v.compareTo(c.getLow()) < 0;
            case "LE":
                return v.compareTo(c.getLow()) <= 0;
            case "OUT":
                return v.compareTo(c.getLow()) < 0 || v.compareTo(c.getHigh()) > 0;
            default:
                return false;
        }
    }

    static boolean compareChange(CondCondition c, BigDecimal change)
    {
        switch (c.getOp())
        {
            case "UP":
                return change.compareTo(c.getLow()) > 0;
            case "DOWN":
                return change.compareTo(c.getLow().negate()) < 0;
            case "CHG":
                return change.abs().compareTo(c.getLow()) > 0;
            default:
                return false;
        }
    }

    /** 一个检测点：全部条件的结果与是否命中（ALL：全部满足；ANY：任一满足） */
    public static Point point(CondSpec spec, Map<String, CondField> fields, Map<Integer, CondSeries> series, LocalDateTime end,
            int baseWeeks)
    {
        List<Outcome> outs = new ArrayList<>();
        for (CondCondition c : spec.getConditions())
        {
            CondField f = fields.get(c.getField());
            CondSeries s = series.get(c.getIndex());
            if (f == null || s == null)
            {
                outs.add(new Outcome(c.getIndex(), Status.NO_DATA, null, null, null, null));
                continue;
            }
            outs.add(judge(c, f, s, spec.getWindow(), end, baseWeeks));
        }
        boolean hit = spec.isAll() ? !outs.isEmpty() && outs.stream().allMatch(Outcome::met) : outs.stream().anyMatch(Outcome::met);
        return new Point(end, outs, hit);
    }

    /** from（含）到 to（含）之间这个窗口的全部检测点，从早到晚 */
    public static List<LocalDateTime> checkpoints(CondWindow w, LocalDateTime from, LocalDateTime to)
    {
        List<LocalDateTime> out = new ArrayList<>();
        for (LocalDateTime t = from; !t.isAfter(to); t = t.plusHours(1))
        {
            if (w.isCheckpoint(t))
            {
                out.add(t);
            }
        }
        return out;
    }

    /** 一条规则判定需要从哪个小时起的数据（含基准） */
    public static LocalDateTime dataFrom(CondSpec spec, LocalDateTime firstCheckpoint, int baseWeeks)
    {
        LocalDateTime start = spec.getWindow().start(firstCheckpoint);
        boolean base = spec.getConditions().stream().anyMatch(c -> "BASE".equals(c.getHow()));
        boolean yd = spec.getConditions().stream().anyMatch(c -> "YD".equals(c.getHow()));
        if (base)
        {
            return start.minusWeeks(Math.max(1, baseWeeks));
        }
        return yd ? start.minusDays(1) : start;
    }
}
