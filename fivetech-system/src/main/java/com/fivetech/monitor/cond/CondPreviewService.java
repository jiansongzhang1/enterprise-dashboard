package com.fivetech.monitor.cond;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 触发预览（POST /monitor/cond-rules/preview，设计文档 7.3.2）：用当前表单回测近 {@code preview-days} 天，每个检测点算一次，
 * 与每小时检测同一套算法（{@link CondEvaluator}、{@link CondRuns}）。按「站点 + 表单签名」缓存 {@code preview-cache-seconds} 秒。
 *
 * @author fivetech
 */
@Service
public class CondPreviewService
{
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final DateTimeFormatter MD_HM = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** 有效时段少于这个数时不给 p5 / p95 */
    static final int MIN_FOR_PERCENTILE = 20;

    private final CondSupport support;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(long at, Map<String, Object> value)
    {
    }

    public CondPreviewService(CondSupport support)
    {
        this.support = support;
    }

    /** spec 已校验；labels 为站点的维度字典 */
    public Map<String, Object> preview(String site, CondSpec spec, CondRules.DimLabels labels)
    {
        CondProperties p = support.props();
        String key = site + "|" + spec.signature();
        long now = System.currentTimeMillis();
        Cached c = cache.get(key);
        if (c != null && now - c.at() < Math.max(0, p.getPreviewCacheSeconds()) * 1000L)
        {
            return c.value();
        }
        LocalDateTime end = support.siteNow(site).truncatedTo(ChronoUnit.HOURS);
        List<LocalDateTime> cps = CondEvaluator.checkpoints(spec.getWindow(), end.minusDays(Math.max(1, p.getPreviewDays())).plusHours(1), end);
        LocalDateTime from = CondEvaluator.dataFrom(spec, cps.get(0), p.getBaseWeeks());
        Map<Integer, CondSeries> series = support.fetch(spec, from, end);
        Map<String, Object> out = backtest(spec, support.catalog().fields(), series, cps, p.getBaseWeeks(), labels, support.symbol(site));
        if (cache.size() > 500)
        {
            cache.clear();
        }
        cache.put(key, new Cached(now, out));
        return out;
    }

    /**
     * 条件的参考信息（POST /monitor/cond-rules/condition-info，FY-93 R4 / R13）：同一范围近 30 天每个时段样本量的中位数，
     * 有数据的天数，以及「较基准」「较昨日」从哪天起能判定。按「站点 + 字段 + 范围 + 窗口」缓存 10 分钟。
     */
    public Map<String, Object> conditionInfo(String site, CondCondition c, CondWindow w)
    {
        String key = "info|" + site + "|" + c.getField() + "|" + CondSupport.scopeKey(c.getScope()) + "|" + w.getCode();
        long now = System.currentTimeMillis();
        Cached hit = cache.get(key);
        if (hit != null && now - hit.at() < INFO_CACHE_MS)
        {
            return hit.value();
        }
        CondField f = support.catalog().field(c.getField());
        LocalDateTime end = support.siteNow(site).truncatedTo(ChronoUnit.HOURS);
        LocalDateTime from = end.minusDays(INFO_DAYS);
        CondSpec spec = new CondSpec();
        spec.setWindow(w);
        spec.setConditions(List.of(c));
        CondSeries s = support.fetch(spec, from, end).get(c.getIndex());
        if (s == null)
        {
            s = new CondSeries(from);
        }
        Map<String, Object> out = info(f, s, w, end, support.props().getBaseWeeks());
        if (cache.size() > 500)
        {
            cache.clear();
        }
        cache.put(key, new Cached(now, out));
        return out;
    }

    static final int INFO_DAYS = 30;

    static final long INFO_CACHE_MS = 10 * 60 * 1000L;

    /** 纯函数（测试直接调）：s 为 [end − 30 天, end) 的小时数据 */
    static Map<String, Object> info(CondField f, CondSeries s, CondWindow w, LocalDateTime end, int baseWeeks)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        if (f != null && f.getSampleField() != null)
        {
            List<BigDecimal> samples = new ArrayList<>();
            LocalDateTime firstHour = s.getFirstHour();
            for (LocalDateTime cp : CondEvaluator.checkpoints(w, s.getFetchedFrom().plusDays(1), end))
            {
                // 最早有数据之前的时段不算（那是没有历史，不是 0）
                if (firstHour == null || w.start(cp).isBefore(firstHour))
                {
                    continue;
                }
                BigDecimal v = CondEvaluator.window(f, s, w.start(cp), cp)[1];
                if (v != null)
                {
                    samples.add(v);
                }
            }
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("median", samples.isEmpty() ? null : CondSupport.round(median(samples)));
            sample.put("periods", samples.size());
            sample.put("key", f.getSampleKey());
            sample.put("name", f.getSampleName());
            sample.put("unit", f.getSampleUnit());
            out.put("sample", sample);
        }
        else
        {
            out.put("sample", null);
        }
        LocalDateTime first = s.getFirstHour();
        long days = first == null ? 0 : java.time.temporal.ChronoUnit.DAYS.between(first.toLocalDate(), end.toLocalDate());
        out.put("historyDays", first != null && !first.isAfter(s.getFetchedFrom()) ? INFO_DAYS : days);
        out.put("historyAtLeast", first != null && !first.isAfter(s.getFetchedFrom()));
        LocalDateTime start = w.start(end);
        int weeks = Math.max(1, baseWeeks);
        boolean baseReady = s.covers(start.minusWeeks(weeks));
        boolean ydReady = s.covers(start.minusDays(1));
        java.time.LocalDate base0 = first == null ? end.toLocalDate() : first.toLocalDate();
        out.put("baseReady", baseReady);
        out.put("baseFrom", baseReady ? null : base0.plusWeeks(weeks).toString());
        out.put("ydReady", ydReady);
        out.put("ydFrom", ydReady ? null : base0.plusDays(1).toString());
        return out;
    }

    static BigDecimal median(List<BigDecimal> values)
    {
        List<BigDecimal> v = new ArrayList<>(values);
        v.sort(null);
        int n = v.size();
        return n % 2 == 1 ? v.get(n / 2) : v.get(n / 2 - 1).add(v.get(n / 2)).divide(BigDecimal.valueOf(2), java.math.MathContext.DECIMAL64);
    }

    /** 回测（纯函数，测试直接调） */
    static Map<String, Object> backtest(CondSpec spec, Map<String, CondField> fields, Map<Integer, CondSeries> series,
            List<LocalDateTime> cps, int baseWeeks, CondRules.DimLabels labels, String symbol)
    {
        CondWindow w = spec.getWindow();
        List<CondEvaluator.Point> points = new ArrayList<>();
        for (LocalDateTime t : cps)
        {
            points.add(CondEvaluator.point(spec, fields, series, t, baseWeeks));
        }
        List<CondRuns.Run> runs = CondRuns.runs(points, spec.getDurationCount());
        String unitWord = w.getHours() > 1 ? "個時段" : "個小時";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window", w.getCode());
        out.put("windowText", w.getText());
        out.put("periodUnit", w.getHours() > 1 ? "PERIOD" : "HOUR");
        out.put("periods", points.size());

        List<Map<String, Object>> usual = new ArrayList<>();
        for (CondCondition c : spec.getConditions())
        {
            usual.add(usual(c, fields.get(c.getField()), points, w, symbol));
        }
        out.put("usual", usual);

        long triggers = runs.stream().filter(CondRuns.Run::triggered).count();
        int isolated = CondRuns.isolated(runs);
        Map<String, Object> summary = new LinkedHashMap<>();
        StringBuilder text = new StringBuilder("按當前設置，近 " + Math.round(points.size() * Math.max(1, w.getHours()) / 24.0) + " 天");
        if (triggers == 0)
        {
            text.append("不會觸發。");
        }
        else
        {
            text.append("會觸發 ").append(triggers).append(" 次（").append(CondRules.LEVEL_TEXT.get(spec.getLevel())).append(" ")
                .append(triggers).append("）。");
        }
        if (isolated > 0)
        {
            text.append(triggers > 0 ? "這 " + triggers + " 次以外，還有 " : "有 ").append(isolated).append(" ").append(unitWord)
                .append("滿足條件，但沒有連續達到 ").append(spec.getDurationCount()).append(" 次。");
        }
        summary.put("text", text.toString());
        summary.put("triggers", triggers);
        summary.put("crit", "CRIT".equals(spec.getLevel()) ? triggers : 0);
        summary.put("warn", "WARN".equals(spec.getLevel()) ? triggers : 0);
        summary.put("isolated", isolated);
        out.put("summary", summary);

        if (spec.getConditions().size() > 1)
        {
            List<Map<String, Object>> ch = new ArrayList<>();
            for (CondCondition c : spec.getConditions())
            {
                long n = points.stream().filter(pt -> pt.outcomes().stream().anyMatch(o -> o.index() == c.getIndex() && o.met())).count();
                ch.add(Map.of("index", c.getIndex(), "hours", n));
            }
            out.put("conditionHours", ch);
            out.put("matchedHours", points.stream().filter(CondEvaluator.Point::hit).count());
        }

        List<Map<String, Object>> rs = new ArrayList<>();
        for (int i = runs.size() - 1; i >= 0; i--)
        {
            CondRuns.Run r = runs.get(i);
            if (!r.triggered())
            {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("start", w.start(r.first()).format(MINUTE));
            m.put("label", runLabel(w, r));
            m.put("level", spec.getLevel());
            m.put("hours", r.count() * Math.max(1, w.getHours()));
            m.put("open", r.open());
            CondEvaluator.Point trig = points.get(r.fromIndex() + spec.getDurationCount() - 1);
            if (spec.getConditions().size() == 1)
            {
                m.put("worst", worst(spec.getConditions().get(0), fields.get(spec.getConditions().get(0).getField()),
                    points.subList(r.fromIndex(), r.toIndex() + 1), symbol));
            }
            else
            {
                m.put("met", trig.metCount());
                m.put("total", spec.getConditions().size());
            }
            m.put("values", values(spec, fields, trig, symbol));
            rs.add(m);
        }
        out.put("runs", rs);
        return out;
    }

    static String runLabel(CondWindow w, CondRuns.Run r)
    {
        if (r.count() == 1)
        {
            return w.label(r.first());
        }
        LocalDateTime start = w.start(r.first());
        LocalDateTime end = r.last();
        return start.format(MD_HM) + " – " + (end.toLocalDate().equals(start.toLocalDate()) ? end.format(DateTimeFormatter.ofPattern("HH:mm"))
            : end.format(MD_HM));
    }

    /** 触发时各条件的值 */
    static List<Map<String, Object>> values(CondSpec spec, Map<String, CondField> fields, CondEvaluator.Point pt, String symbol)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CondEvaluator.Outcome o : pt.outcomes())
        {
            CondCondition c = spec.getConditions().stream().filter(x -> x.getIndex() == o.index()).findFirst().orElse(null);
            if (c == null)
            {
                continue;
            }
            out.add(outcomeView(c, fields.get(c.getField()), o, symbol));
        }
        return out;
    }

    /** 一个结果的展示：{index, value, base, change, text, met, noBase?, lowSample?, noData?} */
    public static Map<String, Object> outcomeView(CondCondition c, CondField f, CondEvaluator.Outcome o, String symbol)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", o.index());
        m.put("value", CondSupport.round(o.value()));
        m.put("base", CondSupport.round(o.base()));
        m.put("change", CondSupport.round(o.change()));
        m.put("text", o.value() == null ? "—" : CondRules.valueText(c, f, o.value(), o.change(), symbol));
        m.put("met", o.met());
        if (o.status() == CondEvaluator.Status.NO_BASE)
        {
            m.put("noBase", true);
        }
        if (o.status() == CondEvaluator.Status.LOW_SAMPLE)
        {
            m.put("lowSample", Map.of("value", CondSupport.round(o.sample() == null ? BigDecimal.ZERO : o.sample()),
                "min", CondSupport.round(c.getMinSample())));
        }
        if (o.status() == CondEvaluator.Status.NO_DATA)
        {
            m.put("noData", true);
        }
        return m;
    }

    /** 一段里的最差值：上升 / 大于取最大，下降 / 小于取最小，双向与区间取离得最远的 */
    static Map<String, Object> worst(CondCondition c, CondField f, List<CondEvaluator.Point> pts, String symbol)
    {
        CondEvaluator.Outcome best = null;
        BigDecimal bestScore = null;
        for (CondEvaluator.Point p : pts)
        {
            for (CondEvaluator.Outcome o : p.outcomes())
            {
                if (o.index() != c.getIndex() || o.value() == null)
                {
                    continue;
                }
                BigDecimal score = score(c, o);
                if (score != null && (bestScore == null || score.compareTo(bestScore) > 0))
                {
                    bestScore = score;
                    best = o;
                }
            }
        }
        if (best == null)
        {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", CondSupport.round(best.value()));
        m.put("change", CondSupport.round(best.change()));
        m.put("text", CondRules.valueText(c, f, best.value(), best.change(), symbol));
        return m;
    }

    static BigDecimal score(CondCondition c, CondEvaluator.Outcome o)
    {
        BigDecimal v = c.isChange() ? o.change() : o.value();
        if (v == null)
        {
            return null;
        }
        switch (c.getOp())
        {
            case "UP":
            case "GT":
            case "GE":
                return v;
            case "DOWN":
            case "LT":
            case "LE":
                return v.negate();
            case "OUT":
                return v.compareTo(c.getLow()) < 0 ? c.getLow().subtract(v) : v.subtract(c.getHigh());
            default:
                return v.abs();
        }
    }

    /** 平时水平：变化类为变化幅度，否则为值；只算能判定的时段 */
    static Map<String, Object> usual(CondCondition c, CondField f, List<CondEvaluator.Point> points, CondWindow w, String symbol)
    {
        record V(BigDecimal v, LocalDateTime at)
        {
        }
        List<V> vals = new ArrayList<>();
        for (CondEvaluator.Point p : points)
        {
            for (CondEvaluator.Outcome o : p.outcomes())
            {
                if (o.index() == c.getIndex() && (o.status() == CondEvaluator.Status.MET || o.status() == CondEvaluator.Status.NOT))
                {
                    BigDecimal v = c.isChange() ? o.change() : o.value();
                    if (v != null)
                    {
                        vals.add(new V(v, p.end()));
                    }
                }
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", c.getIndex());
        m.put("validPeriods", vals.size());
        if (vals.isEmpty())
        {
            m.put("text", "近期沒有可判定的時段");
            m.put("p5", null);
            m.put("p95", null);
            m.put("min", null);
            m.put("max", null);
            return m;
        }
        List<V> sorted = new ArrayList<>(vals);
        sorted.sort(Comparator.comparing(V::v));
        V min = sorted.get(0);
        V max = sorted.get(sorted.size() - 1);
        BigDecimal p5 = sorted.size() >= MIN_FOR_PERCENTILE ? pct(sorted, 5).v() : null;
        BigDecimal p95 = sorted.size() >= MIN_FOR_PERCENTILE ? pct(sorted, 95).v() : null;
        m.put("p5", CondSupport.round(p5));
        m.put("p95", CondSupport.round(p95));
        m.put("min", Map.of("value", CondSupport.round(min.v()), "at", w.label(min.at())));
        m.put("max", Map.of("value", CondSupport.round(max.v()), "at", w.label(max.at())));
        String name = f == null ? c.getField() : f.getName();
        String what = c.isChange() ? name + CondRules.HOW_TEXT.get(c.getHow()) + "的變化" : name;
        StringBuilder t = new StringBuilder();
        if (p5 != null)
        {
            t.append(what).append("平時在 ").append(show(c, f, p5, symbol)).append(" ~ ").append(show(c, f, p95, symbol))
                .append("（90% 的時段）；");
        }
        t.append("最高 ").append(show(c, f, max.v(), symbol)).append("（").append(w.label(max.at())).append("）");
        t.append("，最低 ").append(show(c, f, min.v(), symbol)).append("（").append(w.label(min.at())).append("）");
        m.put("text", t.toString());
        return m;
    }

    private static String show(CondCondition c, CondField f, BigDecimal v, String symbol)
    {
        return c.isChange() ? CondRules.changeText(c, f, v, symbol) : CondRules.value(f, v, symbol);
    }

    private static <T> T pct(List<T> sorted, int p)
    {
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }
}
