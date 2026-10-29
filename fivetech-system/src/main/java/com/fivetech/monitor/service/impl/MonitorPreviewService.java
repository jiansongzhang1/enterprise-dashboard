package com.fivetech.monitor.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.gateway.HourlyFetch;
import com.fivetech.dashboard.gateway.MetricDataGateway;
import com.fivetech.dashboard.gateway.MetricDistribution;
import com.fivetech.dashboard.service.MetricValueNormalizer;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.monitor.domain.MonitorMetricOption;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.dto.MonitorRuleBody;
import com.fivetech.monitor.domain.dto.Threshold;
import com.fivetech.monitor.engine.MonitorDataFetcher;
import com.fivetech.monitor.engine.MonitorPeriodBuilder;
import com.fivetech.monitor.engine.domain.MonitorEngineRule;
import com.fivetech.monitor.engine.judge.JudgeResult;
import com.fivetech.monitor.engine.judge.JudgeRule;
import com.fivetech.monitor.engine.judge.Levels;
import com.fivetech.monitor.engine.judge.MonitorJudge;
import com.fivetech.monitor.engine.judge.PeriodPoint;
import com.fivetech.monitor.engine.judge.Segment;
import com.fivetech.monitor.exception.MonitorFieldException;
import com.fivetech.monitor.mapper.MonitorEventMapper;
import com.fivetech.monitor.mapper.MonitorMetaMapper;
import com.fivetech.monitor.service.MonitorRuleOptions;
import tools.jackson.databind.ObjectMapper;

/**
 * POST /monitor/rules/preview · 平时水平与触发预览（不写库）。
 *
 * <ol>
 *   <li>usual（平时水平）：直接取 UDS metric_distribution 数据集按检测周期统计好的 p5 / p95 / min / max / min_at / max_at
 *       （2026-10-26 起，后端不再自己统计）。时间范围为近 30 天（今天往前 29 天的 00:00 起）内已结束的周期；
 *       Redis 缓存 {@code monitor:dist:{site}:{metric}:{period}:{first}:{last}}，10 分钟。</li>
 *   <li>preview（触发回测）：按站点 + 指标取同一窗口的逐小时序列（跌幅类另取基准），按检测周期聚合，
 *       用和线上完全相同的判定函数（{@link MonitorJudge#judge}）重放；
 *       Redis 缓存 {@code monitor:series:{site}:{metric}}，1 小时；同一站点同一指标并发只发一次 UDS 请求。</li>
 * </ol>
 * 同一用户每秒最多 2 次（Redis 计数；Redis 不可用时不限流）。
 *
 * @author fivetech
 */
@Service
public class MonitorPreviewService
{
    private static final Logger log = LoggerFactory.getLogger(MonitorPreviewService.class);

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    static final int WINDOW_DAYS = 30;

    /** FY-26 R5：有效周期少于这个数时不估计 P5 / P95，只给极值 */
    static final int MIN_ESTIMATE_PERIODS = 20;

    static final int RATE_PER_SECOND = 2;

    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private static final Duration DIST_CACHE_TTL = Duration.ofMinutes(10);

    private final MonitorMetaMapper metaMapper;

    private final MonitorEventMapper eventMapper;

    private final MonitorDataFetcher fetcher;

    private final MonitorPeriodBuilder periodBuilder;

    private final MonitorProperties properties;

    private final ObjectProvider<StringRedisTemplate> redisProvider;

    private final ObjectMapper json;

    private final MetricDataGateway gateway;

    private final MetricValueNormalizer normalizer;

    private final ZoneId zone;

    private final Map<String, Object> fetchLocks = new ConcurrentHashMap<>();

    public MonitorPreviewService(MonitorMetaMapper metaMapper, MonitorEventMapper eventMapper, MonitorDataFetcher fetcher,
            MonitorPeriodBuilder periodBuilder, MonitorProperties properties, ObjectProvider<StringRedisTemplate> redisProvider,
            ObjectMapper json, DashboardProperties dashboard, MetricDataGateway gateway, MetricValueNormalizer normalizer)
    {
        this.gateway = gateway;
        this.normalizer = normalizer;
        this.metaMapper = metaMapper;
        this.eventMapper = eventMapper;
        this.fetcher = fetcher;
        this.periodBuilder = periodBuilder;
        this.properties = properties;
        this.redisProvider = redisProvider;
        this.json = json;
        this.zone = ZoneId.of(dashboard.getTimezone());
    }

    public Map<String, Object> preview(MonitorScope scope, MonitorRuleBody body)
    {
        rateLimit(scope.getUserId());
        if (body == null || StringUtils.isEmpty(StringUtils.trim(body.getMetricCode())))
        {
            throw MonitorFieldException.of("metricCode", "請選擇指標");
        }
        MonitorMetricOption metric = metaMapper.selectMonitorableMetric(body.getMetricCode().trim());
        if (metric == null)
        {
            throw MonitorFieldException.of("metricCode", "該指標不能設置監控");
        }
        int period = body.getPeriodHours() == null ? 1 : body.getPeriodHours();
        if (!MonitorRuleOptions.isValidPeriod(period))
        {
            throw MonitorFieldException.of("periodHours", "檢測週期只能是 1、4、6、12、24 小時");
        }
        int duration = body.getDurationCount() == null ? 1 : body.getDurationCount();
        if (duration < 1 || duration > MonitorRuleOptions.maxDuration(period))
        {
            throw MonitorFieldException.of("durationCount", "持續次數為 1～" + MonitorRuleOptions.maxDuration(period) + " 次");
        }

        MonitorEngineRule rule = engineRule(scope, metric, body, period, duration);
        JudgeRule judge = new JudgeRule(metric.getJudgeType(), low(body.getWarn()), high(body.getWarn()),
            low(body.getCrit()), high(body.getCrit()), period, duration, body.getEscalateHours(),
            rule.getMinSampleValue(), "base".equalsIgnoreCase(metric.getMinSampleKey()));

        LocalDateTime currentHour = LocalDateTime.now(zone).truncatedTo(ChronoUnit.HOURS);
        LocalDateTime from = currentHour.toLocalDate().minusDays(WINDOW_DAYS - 1L).atStartOfDay();
        HourlyFetch data = load(scope.getSiteCode(), rule, from, currentHour);
        LocalDateTime to = currentHour;
        if (data != null && data.getWatermark() != null && data.getWatermark().isBefore(currentHour))
        {
            to = data.getWatermark().truncatedTo(ChronoUnit.HOURS);
        }
        if (data != null && data.isNotReady())
        {
            data = null;
        }
        String fmt = metric.getValueFormat();

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("from", from.format(MINUTE));
        window.put("to", to.format(MINUTE));
        window.put("hours", Math.max(0, ChronoUnit.HOURS.between(from, to)));
        out.put("window", window);
        out.put("periodHours", period);
        // 结论句里的 N（连续次数）；和请求里的一致，回传方便前端拼句
        out.put("durationCount", duration);
        out.put("unit", MonitorRuleServiceImpl.unit(fmt));

        // ---------- 按检测周期切分：平时水平和触发预览用同一组周期 ----------
        List<PeriodPoint> points = new ArrayList<>();
        for (LocalDateTime p = from; !p.plusHours(period).isAfter(to); p = p.plusHours(period))
        {
            points.add(periodBuilder.build(rule, data, p, p.plusHours(period)));
        }
        // 平时水平：UDS 按周期统计好的四值与极值时间，时间范围 = 窗口内已结束的周期（与回测同一组周期起点）
        LocalDateTime openStart = openEventStart(scope, metric.getMetricCode());
        // 最小样本量取表单当前值（与回测同一个值，FY-26 R1），样本量不足的周期由 UDS 排除
        MetricDistribution dist = points.isEmpty() ? null
            : distribution(scope.getSiteCode(), metric.getMetricCode(), period, from, points.get(points.size() - 1).getFrom(),
                rule.getMinSampleValue());
        String code = metric.getMetricCode();
        out.put("usual", usualOf(fmt, metric.getJudgeType(), dist, v -> normalizer.normalize(code, v), openStart));
        out.put("preview", previewOf(judge, fmt, points, properties.getEngine().getLookbackHours()));
        return out;
    }

    // ------------------------------------------------------------------ usual

    /**
     * 平时水平（原型 y12usual、设计文档 usual、FY-26 R1–R5 / R12）：取 UDS metric_distribution 的结果，后端只做单位换算和格式化，不统计。
     * <ul>
     *   <li>kind = value（下限 / 上限 / 区间）：p5 / p95 / min / max 是周期原值；比率 ×100、时长秒 → 分钟（MetricValueNormalizer）。</li>
     *   <li>kind = drop（跌幅类）：UDS 返回较基准的跌幅（基准 − 值）÷ 基准（小数，可为负），×100 后保留 1 位小数；
     *       只用 p95（较基准跌超）和 max（最大跌幅），p5 / min 为 null。p95 ≤ 0、max ≤ 0 对应 R12 的两种写法，前端按值判断。</li>
     *   <li>min / max 的 hour：极值所在周期的起点（UDS min_at / max_at）；属于当前未恢复事件时 inOpenEvent = true。</li>
     *   <li>sampleHours：有效周期数 n（UDS valid_point_count，已按最小样本量过滤）；empty 时为 0。</li>
     *   <li>estimable：n ≥ 20。不足 20 时不估计（R5），p5 / p95 为 null，只给极值。</li>
     *   <li>source = uds；status：ok / empty（n = 0，R12）/ notReady（上游未就绪）/ notConfigured（未接入）。</li>
     * </ul>
     */
    static Map<String, Object> usualOf(String fmt, String judgeType, MetricDistribution d, UnaryOperator<BigDecimal> norm,
            LocalDateTime openStart)
    {
        boolean drop = JudgeRule.DROP.equals(judgeType);
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("source", "uds");
        String status = d == null || !d.isConfigured() ? "notConfigured" : d.isNotReady() ? "notReady" : d.isEmpty() ? "empty" : "ok";
        u.put("status", status);
        u.put("kind", drop ? "drop" : "value");
        Integer n = d == null ? null : d.getCount();
        if ("empty".equals(status) && n == null)
        {
            n = 0;
        }
        u.put("sampleHours", n);
        boolean estimable = "ok".equals(status) && n != null && n >= MIN_ESTIMATE_PERIODS;
        u.put("estimable", estimable);
        u.put("skippedHours", null);
        u.put("median", null);
        if (!"ok".equals(status))
        {
            u.put("p5", null);
            u.put("p95", null);
            u.put("min", null);
            u.put("max", null);
            return u;
        }
        if (drop)
        {
            u.put("p5", null);
            u.put("p95", estimable ? dropPercent(d.getP95()) : null);
            u.put("min", null);
            u.put("max", point(dropPercent(d.getMax()), d.getMaxAt(), openStart));
        }
        else
        {
            u.put("p5", estimable ? round(fmt, norm.apply(d.getP5())) : null);
            u.put("p95", estimable ? round(fmt, norm.apply(d.getP95())) : null);
            u.put("min", point(round(fmt, norm.apply(d.getMin())), d.getMinAt(), openStart));
            u.put("max", point(round(fmt, norm.apply(d.getMax())), d.getMaxAt(), openStart));
        }
        u.put("stale", d.isStale());
        return u;
    }

    /** 跌幅小数 → 百分数，保留 1 位（R12 按四舍五入到 0.1 后判断 ≤ 0） */
    static BigDecimal dropPercent(BigDecimal ratio)
    {
        if (ratio == null)
        {
            return null;
        }
        BigDecimal r = ratio.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP);
        return r.signum() == 0 ? BigDecimal.ZERO.setScale(1) : r;
    }

    private static Map<String, Object> point(BigDecimal v, LocalDateTime at, LocalDateTime openStart)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", v);
        m.put("hour", at == null ? null : at.format(MINUTE));
        m.put("inOpenEvent", openStart != null && at != null && !at.isBefore(openStart));
        return m;
    }

    /** UDS 周期值分布（Redis 缓存 10 分钟；取数失败不影响回测，平时水平返回 notReady） */
    private MetricDistribution distribution(String site, String metricCode, int period, LocalDateTime firstStart,
            LocalDateTime lastStart, BigDecimal minSample)
    {
        BigDecimal threshold = minSample == null || minSample.signum() <= 0 ? null : minSample;
        String key = "monitor:dist:" + site + ":" + metricCode + ":" + period + ":" + firstStart + ":" + lastStart + ":"
            + (threshold == null ? "-" : threshold.stripTrailingZeros().toPlainString());
        MetricDistribution cached = readDistCache(key);
        if (cached != null)
        {
            return cached;
        }
        MetricDistribution d;
        try
        {
            d = gateway.queryDistribution(metricCode, period, firstStart, lastStart, threshold);
        }
        catch (RuntimeException e)
        {
            log.warn("[monitor] 回测预览取平时水平失败 site={} metric={} period={}：{}", site, metricCode, period, e.getMessage());
            d = new MetricDistribution();
            d.setConfigured(true);
            d.setNotReady(true);
            return d;
        }
        if (d != null && d.isConfigured() && !d.isNotReady())
        {
            writeDistCache(key, d);
        }
        return d;
    }

    private MetricDistribution readDistCache(String key)
    {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null)
        {
            return null;
        }
        try
        {
            String text = redis.opsForValue().get(key);
            if (StringUtils.isEmpty(text))
            {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = json.readValue(text, Map.class);
            MetricDistribution d = new MetricDistribution();
            d.setConfigured(true);
            d.setEmpty(Boolean.TRUE.equals(m.get("empty")));
            d.setStale(Boolean.TRUE.equals(m.get("stale")));
            d.setP5(dec(m.get("p5")));
            d.setP95(dec(m.get("p95")));
            d.setMin(dec(m.get("min")));
            d.setMax(dec(m.get("max")));
            d.setMinAt(m.get("minAt") == null ? null : LocalDateTime.parse(String.valueOf(m.get("minAt"))));
            d.setMaxAt(m.get("maxAt") == null ? null : LocalDateTime.parse(String.valueOf(m.get("maxAt"))));
            d.setCount(m.get("count") == null ? null : Integer.valueOf(String.valueOf(m.get("count"))));
            return d;
        }
        catch (RuntimeException e)
        {
            log.warn("[monitor] 平时水平缓存读取失败 {}：{}", key, e.getMessage());
            return null;
        }
    }

    private void writeDistCache(String key, MetricDistribution d)
    {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null)
        {
            return;
        }
        try
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("empty", d.isEmpty());
            m.put("stale", d.isStale());
            m.put("p5", d.getP5() == null ? null : d.getP5().toPlainString());
            m.put("p95", d.getP95() == null ? null : d.getP95().toPlainString());
            m.put("min", d.getMin() == null ? null : d.getMin().toPlainString());
            m.put("max", d.getMax() == null ? null : d.getMax().toPlainString());
            m.put("minAt", d.getMinAt() == null ? null : d.getMinAt().toString());
            m.put("maxAt", d.getMaxAt() == null ? null : d.getMaxAt().toString());
            m.put("count", d.getCount());
            redis.opsForValue().set(key, json.writeValueAsString(m), DIST_CACHE_TTL);
        }
        catch (RuntimeException e)
        {
            log.warn("[monitor] 平时水平缓存写入失败 {}：{}", key, e.getMessage());
        }
    }

    private static BigDecimal dec(Object o)
    {
        return o == null ? null : new BigDecimal(String.valueOf(o));
    }

    private LocalDateTime openEventStart(MonitorScope scope, String metricCode)
    {
        try
        {
            return eventMapper.selectOpenEventStart(scope, metricCode);
        }
        catch (RuntimeException e)
        {
            log.warn("[monitor] 回测预览查询异常中事件失败：{}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ preview

    /** 触发回测：events 返回全部触发（按开始时间倒序，不截断），triggers 与 events 条数一致；包内可见供测试 */
    static Map<String, Object> previewOf(JudgeRule judge, String fmt, List<PeriodPoint> points, int lookbackHours)
    {
        JudgeResult r = MonitorJudge.judge(judge, points, null, lookbackHours);
        int crit = 0;
        int warn = 0;
        List<Map<String, Object>> events = new ArrayList<>();
        List<Segment> segments = new ArrayList<>(r.getSegments());
        segments.sort(Comparator.comparing(Segment::getStartHour).reversed());
        for (Segment s : segments)
        {
            if (Levels.CRIT.equals(s.getLevel()))
            {
                crit++;
            }
            else
            {
                warn++;
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("startHour", s.getStartHour().format(MINUTE));
            e.put("endHour", s.isOngoing() || s.getEndHour() == null ? null : s.getEndHour().format(MINUTE));
            e.put("ongoing", s.isOngoing());
            e.put("durationHours", s.getDurationHours());
            e.put("level", s.getLevel());
            e.put("escalated", s.isEscalated());
            Map<String, Object> w = null;
            if (s.getWorstIndex() >= 0)
            {
                PeriodPoint p = points.get(s.getWorstIndex());
                w = new LinkedHashMap<>();
                w.put("value", round(fmt, p.getValue()));
                w.put("hour", p.getFrom().format(MINUTE));
                boolean drop = JudgeRule.DROP.equals(judge.getJudgeType());
                w.put("baseline", drop ? round(fmt, p.getBaseline()) : null);
                BigDecimal pct = drop ? MonitorJudge.dropPct(p.getValue(), p.getBaseline()) : null;
                w.put("dropPct", pct == null ? null : pct.setScale(1, RoundingMode.HALF_UP));
            }
            e.put("worst", w);
            events.add(e);
        }
        Map<String, Object> triggers = new LinkedHashMap<>();
        triggers.put("total", crit + warn);
        triggers.put("crit", crit);
        triggers.put("warn", warn);
        Map<String, Object> pv = new LinkedHashMap<>();
        pv.put("triggers", triggers);
        pv.put("overPeriods", r.getOverPeriods());
        pv.put("isolatedPeriods", r.getIsolatedPeriods());
        pv.put("events", events);
        return pv;
    }

    // ------------------------------------------------------------------ 取数与缓存

    private MonitorEngineRule engineRule(MonitorScope scope, MonitorMetricOption metric, MonitorRuleBody body, int period,
            int duration)
    {
        MonitorEngineRule rule = new MonitorEngineRule();
        rule.setSiteCode(scope.getSiteCode());
        rule.setMetricCode(metric.getMetricCode());
        rule.setJudgeType(metric.getJudgeType());
        rule.setMinSampleKey(metric.getMinSampleKey());
        BigDecimal min = body.getMinSampleValue() != null ? body.getMinSampleValue() : metric.getMinSampleDefault();
        rule.setMinSampleValue(min == null || min.signum() < 0 ? BigDecimal.ZERO : min);
        rule.setPeriodHours(period);
        rule.setDurationCount(duration);
        rule.setEscalateHours(body.getEscalateHours());
        return rule;
    }

    /** Redis 缓存 → 没有或窗口不对时取 UDS（同一站点同一指标只有一个线程去取，其余等它写缓存） */
    private HourlyFetch load(String site, MonitorEngineRule rule, LocalDateTime from, LocalDateTime to)
    {
        String key = "monitor:series:" + site + ":" + rule.getMetricCode();
        HourlyFetch cached = readCache(key, from, to);
        if (cached != null)
        {
            return cached;
        }
        synchronized (fetchLocks.computeIfAbsent(key, k -> new Object()))
        {
            cached = readCache(key, from, to);
            if (cached != null)
            {
                return cached;
            }
            HourlyFetch data;
            try
            {
                data = fetcher.fetch(List.of(rule), from, to);
            }
            catch (RuntimeException e)
            {
                log.warn("[monitor] 回测预览取数失败 site={} metric={}：{}", site, rule.getMetricCode(), e.getMessage());
                throw new ServiceException("數據暫時取不到，請稍後再試");
            }
            if (data != null && !data.isNotReady())
            {
                writeCache(key, data);
            }
            return data;
        }
    }

    private HourlyFetch readCache(String key, LocalDateTime from, LocalDateTime to)
    {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null)
        {
            return null;
        }
        try
        {
            String text = redis.opsForValue().get(key);
            if (StringUtils.isEmpty(text))
            {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = json.readValue(text, Map.class);
            LocalDateTime cFrom = LocalDateTime.parse(String.valueOf(m.get("from")));
            int points = Integer.parseInt(String.valueOf(m.get("points")));
            if (!cFrom.equals(from) || !cFrom.plusHours(points).equals(to))
            {
                return null;   // 窗口已经移动（过了整点），重新取
            }
            HourlyFetch data = new HourlyFetch(cFrom, points);
            Object wm = m.get("watermark");
            data.setWatermark(wm == null ? null : LocalDateTime.parse(String.valueOf(wm)));
            @SuppressWarnings("unchecked")
            Map<String, Object> series = (Map<String, Object>) m.get("series");
            for (Map.Entry<String, Object> s : series.entrySet())
            {
                List<BigDecimal> values = new ArrayList<>();
                for (Object o : (List<?>) s.getValue())
                {
                    values.add(o == null ? null : new BigDecimal(o.toString()));
                }
                data.put(s.getKey(), values);
            }
            return data;
        }
        catch (RuntimeException e)
        {
            log.warn("[monitor] 回测预览缓存读取失败 {}：{}", key, e.getMessage());
            return null;
        }
    }

    private void writeCache(String key, HourlyFetch data)
    {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null)
        {
            return;
        }
        try
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from", data.getFrom().toString());
            m.put("points", data.getPoints());
            m.put("watermark", data.getWatermark() == null ? null : data.getWatermark().toString());
            Map<String, Object> series = new LinkedHashMap<>();
            for (String k : keys(data))
            {
                List<String> values = new ArrayList<>();
                for (BigDecimal v : data.get(k))
                {
                    values.add(v == null ? null : v.toPlainString());
                }
                series.put(k, values);
            }
            m.put("series", series);
            redis.opsForValue().set(key, json.writeValueAsString(m), CACHE_TTL);
        }
        catch (RuntimeException e)
        {
            log.warn("[monitor] 回测预览缓存写入失败 {}：{}", key, e.getMessage());
        }
    }

    private static List<String> keys(HourlyFetch data)
    {
        return new ArrayList<>(data.keys());
    }

    /** 同一用户每秒最多 2 次 */
    private void rateLimit(Long userId)
    {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null || userId == null)
        {
            return;
        }
        String key = "monitor:preview:rate:" + userId + ":" + (System.currentTimeMillis() / 1000);
        try
        {
            Long n = redis.opsForValue().increment(key);
            if (n != null && n == 1L)
            {
                redis.expire(key, Duration.ofSeconds(2));
            }
            if (n != null && n > RATE_PER_SECOND)
            {
                throw new ServiceException("請求過於頻繁，請稍後再試");
            }
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (RuntimeException e)
        {
            log.debug("[monitor] 回测预览限流计数失败：{}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 数值

    private static BigDecimal low(Threshold t)
    {
        return t == null ? null : t.getLow();
    }

    private static BigDecimal high(Threshold t)
    {
        return t == null ? null : t.getHigh();
    }

    /** 按指标展示精度取整：分钟 1 位，其余 2 位；去掉尾零 */
    static BigDecimal round(String valueFormat, BigDecimal v)
    {
        if (v == null)
        {
            return null;
        }
        String f = valueFormat == null ? "" : valueFormat.toUpperCase(Locale.ROOT);
        BigDecimal r = v.setScale("MIN".equals(f) ? 1 : 2, RoundingMode.HALF_UP).stripTrailingZeros();
        return r.scale() < 0 ? r.setScale(0) : r;
    }
}
