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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.gateway.HourlyFetch;
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
 *   <li>按站点 + 指标取近 30 天（今天往前 29 天的 00:00 起，到最近一个已落库的整点）逐小时序列、样本量序列，跌幅类另取基准；
 *       Redis 缓存 {@code monitor:series:{site}:{metric}}，1 小时；同一站点同一指标并发只发一次 UDS 请求。</li>
 *   <li>按检测周期聚合成周期序列，用和线上完全相同的判定函数（{@link MonitorJudge#judge}）跑一遍，得到越线段。</li>
 *   <li>usual 按检测周期统计，只用参与判定的周期（排除样本量不足、数据缺失、跌幅缺基准）；p5 / p95 用最近排名法，不插值。</li>
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

    static final int MIN_HOURS = 48;

    static final int MAX_EVENTS = 50;

    static final int RATE_PER_SECOND = 2;

    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final MonitorMetaMapper metaMapper;

    private final MonitorEventMapper eventMapper;

    private final MonitorDataFetcher fetcher;

    private final MonitorPeriodBuilder periodBuilder;

    private final MonitorProperties properties;

    private final ObjectProvider<StringRedisTemplate> redisProvider;

    private final ObjectMapper json;

    private final ZoneId zone;

    private final Map<String, Object> fetchLocks = new ConcurrentHashMap<>();

    public MonitorPreviewService(MonitorMetaMapper metaMapper, MonitorEventMapper eventMapper, MonitorDataFetcher fetcher,
            MonitorPeriodBuilder periodBuilder, MonitorProperties properties, ObjectProvider<StringRedisTemplate> redisProvider,
            ObjectMapper json, DashboardProperties dashboard)
    {
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
        // 参与判定的周期：排除样本量不足、数据缺失、跌幅缺基准（与判定同一函数）
        List<PeriodPoint> valid = new ArrayList<>();
        for (PeriodPoint p : points)
        {
            if (!Levels.SKIP.equals(MonitorJudge.periodLevel(judge, p)))
            {
                valid.add(p);
            }
        }
        boolean tooFew = (long) valid.size() * period < MIN_HOURS;
        LocalDateTime openStart = openEventStart(scope, metric.getMetricCode());
        out.put("usual", usual(metric.getJudgeType(), fmt, valid, points.size() - valid.size(), openStart));
        out.put("preview", previewOf(judge, fmt, points));
        out.put("tooFewData", tooFew);
        return out;
    }

    // ------------------------------------------------------------------ usual

    /**
     * 平时水平（原型 y12usual、设计文档 usual）：近 30 天每个检测周期的值，只用参与判定的周期。
     * <ul>
     *   <li>p5 / p95：第 5 / 第 95 百分位，最近排名法（n 个值升序，取第 floor(p×(n−1)) 个，从 0 计，不插值）。</li>
     *   <li>min / max：最小值 / 最大值，hour 为该周期的开始小时，同值取最早一次；属于当前未恢复事件时 inOpenEvent = true。</li>
     *   <li>跌幅类：p5 / p95 / min / max / median 都是较基准的跌幅百分比（1 位小数）。</li>
     *   <li>sampleHours / skippedHours：参与 / 不参与判定的周期数（字段名沿用，周期为 1 小时时即小时数）。</li>
     *   <li>extreme：保留兼容，最差的一个周期（下限取 min、上限取 max、跌幅取跌得最多的、区间取离中位数更远的一端）。</li>
     * </ul>
     * 周期少于 20 个时前端不估计「约 5%」的位置、只给极值（原型 Y12USN），后端照常返回。
     */
    private Map<String, Object> usual(String judgeType, String fmt, List<PeriodPoint> periods, int skipped,
            LocalDateTime openStart)
    {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("sampleHours", periods.size());
        u.put("skippedHours", skipped);
        boolean drop = JudgeRule.DROP.equals(judgeType);
        List<BigDecimal> values = new ArrayList<>();
        for (PeriodPoint p : periods)
        {
            BigDecimal v = drop ? MonitorJudge.dropPct(p.getValue(), p.getBaseline()) : p.getValue();
            values.add(v == null ? BigDecimal.ZERO : v);
        }
        if (values.isEmpty())
        {
            u.put("median", null);
            u.put("p5", null);
            u.put("p95", null);
            u.put("min", null);
            u.put("max", null);
            u.put("extreme", null);
            return u;
        }
        u.put("median", out(drop, fmt, nearestRank(values, 0.50)));
        u.put("p5", out(drop, fmt, nearestRank(values, 0.05)));
        u.put("p95", out(drop, fmt, nearestRank(values, 0.95)));
        int iMin = 0;
        int iMax = 0;
        for (int i = 1; i < values.size(); i++)
        {
            // 严格小于 / 大于：同值保留最早一次
            if (values.get(i).compareTo(values.get(iMin)) < 0)
            {
                iMin = i;
            }
            if (values.get(i).compareTo(values.get(iMax)) > 0)
            {
                iMax = i;
            }
        }
        Map<String, Object> min = point(drop, fmt, values.get(iMin), periods.get(iMin), openStart);
        Map<String, Object> max = point(drop, fmt, values.get(iMax), periods.get(iMax), openStart);
        u.put("min", min);
        u.put("max", max);
        Map<String, Object> extreme;
        switch (judgeType)
        {
            case JudgeRule.LO:
                extreme = min;
                break;
            case JudgeRule.HI:
            case JudgeRule.DROP:
                extreme = max;
                break;
            default:
                BigDecimal median = nearestRank(values, 0.50);
                extreme = values.get(iMin).subtract(median).abs().compareTo(values.get(iMax).subtract(median).abs()) > 0 ? min : max;
                break;
        }
        u.put("extreme", extreme);
        return u;
    }

    private static Map<String, Object> point(boolean drop, String fmt, BigDecimal v, PeriodPoint p, LocalDateTime openStart)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", out(drop, fmt, v));
        m.put("hour", p.getFrom().format(MINUTE));
        m.put("inOpenEvent", openStart != null && !p.getFrom().isBefore(openStart));
        return m;
    }

    /** 跌幅百分比 1 位小数；其余按指标展示精度 */
    private static BigDecimal out(boolean drop, String fmt, BigDecimal v)
    {
        if (v == null)
        {
            return null;
        }
        return drop ? v.setScale(1, RoundingMode.HALF_UP) : round(fmt, v);
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

    private Map<String, Object> previewOf(JudgeRule judge, String fmt, List<PeriodPoint> points)
    {
        JudgeResult r = MonitorJudge.judge(judge, points, null, properties.getEngine().getLookbackHours());
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
            if (events.size() >= MAX_EVENTS)
            {
                continue;
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

    /** 最近排名法百分位：升序后取第 floor(p × (n − 1)) 个（从 0 计），不插值；空列表为 null */
    static BigDecimal nearestRank(List<BigDecimal> values, double p)
    {
        if (values == null || values.isEmpty())
        {
            return null;
        }
        List<BigDecimal> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int i = (int) Math.floor(p * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
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
