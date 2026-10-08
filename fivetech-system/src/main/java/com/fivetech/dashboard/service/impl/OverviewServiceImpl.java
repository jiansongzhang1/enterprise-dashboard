package com.fivetech.dashboard.service.impl;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.domain.MetricDefinition;
import com.fivetech.dashboard.domain.MetricGroupConfig;
import com.fivetech.dashboard.domain.query.OverviewQuery;
import com.fivetech.dashboard.domain.vo.MetricAlertVO;
import com.fivetech.dashboard.domain.vo.MetricCardVO;
import com.fivetech.dashboard.domain.vo.MetricGroupVO;
import com.fivetech.dashboard.domain.vo.MetricsBlockVO;
import com.fivetech.dashboard.domain.vo.OverviewCompareVO;
import com.fivetech.dashboard.domain.vo.OverviewMonitoringVO;
import com.fivetech.dashboard.domain.vo.OverviewNoticeVO;
import com.fivetech.dashboard.domain.vo.OverviewSlotVO;
import com.fivetech.dashboard.domain.vo.OverviewVO;
import com.fivetech.dashboard.enums.CompareType;
import com.fivetech.dashboard.enums.DashboardPage;
import com.fivetech.dashboard.enums.Granularity;
import com.fivetech.dashboard.enums.OverviewBlock;
import com.fivetech.dashboard.gateway.DataFreshness;
import com.fivetech.dashboard.gateway.MetricDataGateway;
import com.fivetech.dashboard.gateway.MetricSlotRequest;
import com.fivetech.dashboard.gateway.uds.UdsProperties;
import com.fivetech.dashboard.gateway.uds.UdsQueryException;
import com.fivetech.dashboard.service.IOverviewService;
import com.fivetech.dashboard.service.MetricRegistry;
import com.fivetech.dashboard.service.MetricValueNormalizer;

/**
 * 运营总览 · 主要指标实现。
 *
 * <p><b>本类目前只负责「形状」，不负责「数值」。</b>
 * 时间解析、粒度降级、指标白名单、卡片元信息都已按契约装配完成；
 * 向 Doris / UDS 取数的部分标了 {@code TODO}，数值一律保持 {@code null}。</p>
 *
 * <p>为什么留 null 而不是 0：无数据和 0 在业务上不是一回事。
 * 桩阶段填 0 会让联调时看到一屏「正常的零」，反而不知道链路根本没通。</p>
 *
 * @author fivetech
 */
@Service
public class OverviewServiceImpl implements IOverviewService
{
    private static final Logger log = LoggerFactory.getLogger(OverviewServiceImpl.class);

    private static final DateTimeFormatter SLOT_IN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM-dd");

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 区间跨度上限，超过直接拒绝——不是性能保护，是「没人真的要看 400 天的小时曲线」 */
    private static final int MAX_SPAN_DAYS = 365;

    private final DashboardProperties properties;

    private final MetricRegistry metricRegistry;

    private final UdsProperties udsProperties;

    private final MetricDataGateway gateway;

    private final MetricValueNormalizer normalizer;

    public OverviewServiceImpl(DashboardProperties properties, MetricRegistry metricRegistry,
            UdsProperties udsProperties, MetricDataGateway gateway, MetricValueNormalizer normalizer)
    {
        this.properties = properties;
        this.metricRegistry = metricRegistry;
        this.udsProperties = udsProperties;
        this.gateway = gateway;
        this.normalizer = normalizer;
    }

    @Override
    public OverviewVO query(OverviewQuery query)
    {
        OverviewVO vo = new OverviewVO();
        List<OverviewNoticeVO> notices = vo.getNotices();

        // ---------- 1. 时间：解析 + 粒度降级 ----------
        LocalDateTime from = parseSlot(query.getSlotFrom(), "slotFrom");
        LocalDateTime to = parseSlot(query.getSlotTo(), "slotTo");
        if (!to.isAfter(from))
        {
            throw new ServiceException("slotFrom 必须早于 slotTo（区间左闭右开）", 4001);
        }
        if (Duration.between(from, to).toDays() > MAX_SPAN_DAYS)
        {
            throw new ServiceException("查询区间不得超过 " + MAX_SPAN_DAYS + " 天", 4001);
        }

        Granularity granularity = query.getGranularity() == null ? Granularity.HOUR : query.getGranularity();
        Granularity effective = downgrade(from, to, granularity, notices);

        OverviewSlotVO slot = buildSlot(from, to, effective);
        vo.setSlot(slot);

        // ---------- 2. 对比期 ----------
        vo.setCompare(resolveCompare(query, from, to, notices));
        

        // ---------- 4. 上下文 ----------
        vo.setCurrency(properties.getCurrency());
        vo.setTimezone(properties.getTimezone());
        OverviewMonitoringVO monitoring = new OverviewMonitoringVO();
        // 监控管理未上线：没有任何启用规则，所以卡片的 alert 一律 NOT_CONFIGURED
        monitoring.setConfigured(false);
        vo.setMonitoring(monitoring);

        // 水位：上游没有独立接口，只能用上一次查询响应里的 meta.freshness.watermark。
        // 拿不到就保持 null——前端顶栏显示「暂无数据」，不编一个当前整点冒充
        DataFreshness freshness = gateway.getFreshness(site(query));
        if (freshness != null)
        {
            vo.setAsOf(format(freshness.getAsOf()));
            vo.setUpdatedAt(format(freshness.getUpdatedAt()));
        }

        // ---------- 5. 取数 ----------
        List<String> codes = dropSingleDayOnly(resolveMetricCodes(query), from, to, vo.getCompare(), notices);
        // 派生指标要展开成「分子 ÷ 分母」给前端看，所以取数时把操作数一并带上。
        // 它们不进 items，只是多取几列，成本远低于前端为了展开构成再打一次请求
        Fetched fetched = fetch(query, from, to, effective, withOperands(codes), vo, notices);

        // ---------- 6. 分块装配 ----------
        Set<OverviewBlock> blocks = resolveBlocks(query, notices);
        if (blocks.contains(OverviewBlock.METRICS))
        {
            if (fetched == null)
            {
                vo.getBlockErrors().put(OverviewBlock.METRICS.name(),
                    com.fivetech.dashboard.domain.vo.OverviewBlockErrorVO.of(5001, "資料平台取數失敗"));
            }
            else
            {
                vo.getBlocks().put(OverviewBlock.METRICS.name(),
                    buildMetrics(query, slot, codes, fetched));
            }
        }
        vo.setEmpty(fetched == null || fetched.isEmpty());
        return vo;
    }

    // ===================== 取数 =====================

    /** 一次请求取到的四组数：当期/对比期 × 序列/合计 */
    private static final class Fetched
    {
        private Map<String, List<BigDecimal>> series = new LinkedHashMap<>();

        private Map<String, List<BigDecimal>> prevSeries = new LinkedHashMap<>();

        private Map<String, BigDecimal> totals = new LinkedHashMap<>();

        private Map<String, BigDecimal> prevTotals = new LinkedHashMap<>();

        private boolean isEmpty()
        {
            return totals.values().stream().allMatch(v -> v == null);
        }
    }

    /**
     * 向数据平台取数。
     *
     * <p>最多四次调用：当期序列、当期合计、对比期序列、对比期合计。
     * <b>合计不能由序列加总得出</b>——派生指标要先聚合分子分母再套公式，
     * 去重人数跨时间片不可相加（数据侧用例 03 专门验证了这一点：
     * 区间去重的 active_users 严格小于各小时相加），平均耗时要按笔数加权。</p>
     *
     * <p>取数失败返回 null，由调用方降级成 blockError——一块失败不该让整页白屏。</p>
     */
    private Fetched fetch(OverviewQuery query, LocalDateTime from, LocalDateTime to,
            Granularity granularity, List<String> codes, OverviewVO vo, List<OverviewNoticeVO> notices)
    {
        try
        {
            Fetched result = new Fetched();
            String site = site(query);

            if (query.isIncludeSeries())
            {
                result.series = gateway.querySeries(slotRequest(site, from, to, granularity, codes));
            }
            result.totals = gateway.queryTotals(slotRequest(site, from, to, granularity, codes));

            OverviewCompareVO compare = vo.getCompare();
            if (compare != null)
            {
                LocalDateTime cmpFrom = LocalDateTime.parse(compare.getFrom(), SLOT_IN);
                LocalDateTime cmpTo = LocalDateTime.parse(compare.getTo(), SLOT_IN);
                if (query.isIncludeSeries())
                {
                    result.prevSeries =
                        gateway.querySeries(slotRequest(site, cmpFrom, cmpTo, granularity, codes));
                }
                result.prevTotals =
                    gateway.queryTotals(slotRequest(site, cmpFrom, cmpTo, granularity, codes));
            }

            // 取数之后水位才有值（上游把它捎在查询响应的 meta 里），这里再补一次
            DataFreshness freshness = gateway.getFreshness(site);
            if (freshness != null && vo.getAsOf() == null)
            {
                vo.setAsOf(format(freshness.getAsOf()));
                vo.setUpdatedAt(format(freshness.getUpdatedAt()));
            }
            if (vo.getAsOf() == null)
            {
                notices.add(OverviewNoticeVO.warn("NO_COMPLETE_SLOT_YET", "暫無完整時間片",
                    "上游尚未返回水位線，頂欄時間以「—」顯示"));
            }
            return result;
        }
        catch (UdsQueryException e)
        {
            // 退避之后仍然拿不到，多半是上游滞后太多或绑定不可用。
            // 记 error 并降级，不要把异常抛到前端——整页 500 比少一块糟糕得多
            log.error("[overview] 取数失败 区间={}~{} 粒度={} : {}", from, to, granularity, e.getMessage(), e);
            notices.add(OverviewNoticeVO.warn("DATA_LAGGING", "資料平台暫時無法使用", e.getMessage()));
            return null;
        }
    }

    private MetricSlotRequest slotRequest(String site, LocalDateTime from, LocalDateTime to,
            Granularity granularity, List<String> codes)
    {
        MetricSlotRequest request = new MetricSlotRequest();
        request.setSiteCode(site);
        request.setFrom(from);
        request.setTo(to);
        request.setGranularity(granularity);
        request.setMetricCodes(new ArrayList<>(codes));
        return request;
    }

    private String site(OverviewQuery query)
    {
        return StringUtils.isEmpty(query.getSiteCode()) ? properties.getDefaultSite() : query.getSiteCode();
    }

    private static String format(LocalDateTime value)
    {
        return value == null ? null : value.format(SLOT_IN);
    }

    // ===================== 时间 =====================

    private LocalDateTime parseSlot(String text, String field)
    {
        if (StringUtils.isEmpty(text))
        {
            throw new ServiceException(field + " 必填，格式 yyyy-MM-dd HH:mm", 4001);
        }
        try
        {
            return LocalDateTime.parse(text.trim(), SLOT_IN);
        }
        catch (DateTimeParseException e)
        {
            throw new ServiceException(field + " 格式错误，应为 yyyy-MM-dd HH:mm，实际为：" + text, 4001);
        }
    }

    /**
     * 粒度降级。<b>非法组合不报 400</b>——自动降到更粗的一级并写进 notices，
     * 前端据此提示用户，而不是让整页 500。
     */
    private Granularity downgrade(LocalDateTime from, LocalDateTime to, Granularity requested,
            List<OverviewNoticeVO> notices)
    {
        int max = Math.max(1, properties.getMaxPointsPerGranularity());
        Granularity current = requested;
        while (countPoints(from, to, current) > max && current != Granularity.WEEK)
        {
            Granularity next = current == Granularity.HOUR ? Granularity.DAY : Granularity.WEEK;
            notices.add(OverviewNoticeVO.info("GRANULARITY_DOWNGRADED",
                "粒度已改為「" + label(next) + "」",
                "所選區間按「" + label(current) + "」有 " + countPoints(from, to, current)
                    + " 個時間片，超過單次返回上限 " + max));
            current = next;
        }
        return current;
    }

    private static String label(Granularity g)
    {
        switch (g)
        {
            case HOUR:
                return "小時";
            case WEEK:
                return "週";
            case DAY:
            default:
                return "日";
        }
    }

    private static int countPoints(LocalDateTime from, LocalDateTime to, Granularity granularity)
    {
        if (granularity == null)
        {
            return 0;
        }
        switch (granularity)
        {
            case HOUR:
                return (int) Math.max(0, ChronoUnit.HOURS.between(from, to));
            case WEEK:
                return (int) Math.max(0, Math.ceil(ChronoUnit.DAYS.between(from, to) / 7.0));
            case DAY:
            default:
                return (int) Math.max(0, ChronoUnit.DAYS.between(from, to));
        }
    }

    private OverviewSlotVO buildSlot(LocalDateTime from, LocalDateTime to, Granularity granularity)
    {
        OverviewSlotVO slot = new OverviewSlotVO();
        slot.setFrom(from.format(SLOT_IN));
        slot.setTo(to.format(SLOT_IN));
        slot.setGranularity(granularity);

        int points = countPoints(from, to, granularity);
        slot.setPoints(points);

        List<String> labels = new ArrayList<>(points);
        boolean crossDay = ChronoUnit.HOURS.between(from, to) > 24;
        for (int i = 0; i < points; i++)
        {
            if (granularity == Granularity.HOUR)
            {
                LocalDateTime a = from.plusHours(i);
                LocalDateTime b = a.plusHours(1);
                // 小时粒度跨天时标签带上日期，否则「09:00 – 10:00」出现两次分不清是哪天
                String prefix = crossDay ? a.format(MD) + " " : "";
                labels.add(prefix + a.format(HHMM) + " – " + b.format(HHMM));
            }
            else if (granularity == Granularity.WEEK)
            {
                LocalDateTime a = from.plusWeeks(i);
                LocalDateTime b = a.plusWeeks(1).minusDays(1);
                // 最后一周多半不满 7 天，末端夹到区间结束，不印出超出区间的日期
                LocalDateTime end = b.isAfter(to.minusDays(1)) ? to.minusDays(1) : b;
                labels.add(a.format(MD) + " – " + end.format(MD));
            }
            else
            {
                labels.add(from.plusDays(i).format(YMD));
            }
        }
        slot.setLabels(labels);
        return slot;
    }

    private OverviewCompareVO resolveCompare(OverviewQuery query, LocalDateTime from, LocalDateTime to,
            List<OverviewNoticeVO> notices)
    {
        CompareType type = query.getCompareType() == null ? CompareType.PREV_PERIOD : query.getCompareType();
        if (type == CompareType.NONE)
        {
            return null;
        }

        LocalDateTime cmpFrom;
        LocalDateTime cmpTo;
        if (type == CompareType.CUSTOM)
        {
            cmpFrom = parseSlot(query.getCompareFrom(), "compareFrom");
            cmpTo = parseSlot(query.getCompareTo(), "compareTo");
            if (!cmpTo.isAfter(cmpFrom))
            {
                throw new ServiceException("compareFrom 必须早于 compareTo", 4002);
            }
            // 对比期与主区间重叠时，环比失去意义——同一批数据自己和自己比
            if (!cmpTo.isBefore(from) && !cmpTo.isEqual(from))
            {
                throw new ServiceException("对比期不得与主区间重叠", 4002);
            }
            String launch = properties.getLaunchDate();
            if (StringUtils.isNotEmpty(launch) && cmpFrom.toLocalDate().toString().compareTo(launch) < 0)
            {
                throw new ServiceException("对比期早于站点上线日 " + launch, 4002);
            }
            long mainDays = ChronoUnit.DAYS.between(from, to);
            long cmpDays = ChronoUnit.DAYS.between(cmpFrom, cmpTo);
            if (mainDays != cmpDays)
            {
                if (query.isCmpLock())
                {
                    throw new ServiceException("对比期长度需与主区间一致（cmpLock=true）", 4002);
                }
                notices.add(OverviewNoticeVO.warn("CMP_LENGTH_WARN", "對比期與主區間長度不同",
                    "主區間 " + mainDays + " 天，對比期 " + cmpDays + " 天，環比數值不可直接解讀"));
            }
        }
        else if (type == CompareType.LAST_YEAR)
        {
            cmpFrom = from.minusYears(1);
            cmpTo = to.minusYears(1);
        }
        else
        {
            // PREV_PERIOD：与主区间等长且紧邻
            Duration span = Duration.between(from, to);
            cmpTo = from;
            cmpFrom = from.minus(span);
        }

        if (StringUtils.isNotEmpty(query.getCompareFrom()) && type != CompareType.CUSTOM)
        {
            notices.add(OverviewNoticeVO.info("PARAM_IGNORED",
                "compareFrom / compareTo 已忽略", "僅 compareType=CUSTOM 時生效"));
        }

        OverviewCompareVO compare = new OverviewCompareVO();
        compare.setFrom(cmpFrom.format(SLOT_IN));
        compare.setTo(cmpTo.format(SLOT_IN));
        compare.setType(type);
        // 标签要能单独读懂。早先是 from(完整) + to(只有 HH:mm)，跨天时会拼出
        // 「2026-09-28 00:00 – 00:00」，前端直接显示没人看得出右端是哪天
        boolean sameDay = cmpFrom.toLocalDate().equals(cmpTo.minusMinutes(1).toLocalDate());
        compare.setLabel(sameDay
            ? cmpFrom.format(SLOT_IN) + " – " + cmpTo.format(HHMM)
            : cmpFrom.format(SLOT_IN) + " – " + cmpTo.format(SLOT_IN));
        return compare;
    }

    // ===================== 分块 =====================

    private Set<OverviewBlock> resolveBlocks(OverviewQuery query, List<OverviewNoticeVO> notices)
    {
        Set<OverviewBlock> result = new LinkedHashSet<>();
        for (String raw : query.getBlocks())
        {
            OverviewBlock block = OverviewBlock.of(raw);
            if (block == null)
            {
                notices.add(OverviewNoticeVO.info("PARAM_IGNORED", "未知的 blocks 取值：" + raw,
                    "可選值為 METRICS"));
                continue;
            }
            result.add(block);
        }
        if (result.isEmpty())
        {
            // 不传就是首屏：返回指标墙
            result.add(OverviewBlock.METRICS);
        }
        return result;
    }

    private MetricsBlockVO buildMetrics(OverviewQuery query, OverviewSlotVO slot,
            List<String> codes, Fetched fetched)
    {
        MetricsBlockVO block = new MetricsBlockVO();
        List<MetricCardVO> items = new ArrayList<>(codes.size());
        List<String> core = metricRegistry.coreOf(DashboardPage.OVERVIEW);

        for (String code : codes)
        {
            MetricDefinition def = metricRegistry.get(code);
            if (def == null)
            {
                continue;
            }
            MetricCardVO card = new MetricCardVO();
            card.setCode(code);
            // 名称、分组、口径按原型返回繁体（库里是简体，供其他页面共用）
            card.setLabel(com.fivetech.dashboard.service.OverviewMetricText.label(code, def.getLabel()));
            card.setGroup(def.getGroup());
            card.setGroupLabel(com.fivetech.dashboard.service.OverviewMetricText.group(groupLabel(def.getGroup())));
            card.setEmphasis(core.contains(code) ? "CORE" : "NORMAL");
            card.setKind(def.getKind());
            card.setExpression(com.fivetech.dashboard.service.OverviewMetricText.caliber(code, def, metricRegistry));
            card.setValueFormat(def.getFormat());
            card.setDecimals(decimalsOf(def));
            // 序号取页面关系 dashboard_page_metric.sort_no，与列表顺序同源；
            // 不是返回列表里的位次——前端自己重排过，靠这个还原成页面约定的次序。
            // 不能用 def.getSortNo()：那是 dashboard_metric_card 的全局顺序，两列配得不一样时会和列表顺序打架
            card.setSortNo(metricRegistry.pageSortNo(DashboardPage.OVERVIEW, code));
            card.setUpdateFrequency(def.getUpdateFrequency());
            card.setDirection(def.getDirection());
            // 监控管理未上线：一律 NOT_CONFIGURED，绝不填 OK。
            // 「没配规则」和「查过了一切正常」是两件事，后者染绿是骗人
            card.setAlert(MetricAlertVO.notConfigured());
            card.setUdsMetric(udsMetricOf(code));

            // 换算量纲：UDS 的比率是 0~1、时长是秒，这里转成展示用的百分数与分钟
            BigDecimal value = normalizer.normalize(code, fetched.totals.get(code));
            BigDecimal prev = normalizer.normalize(code, fetched.prevTotals.get(code));
            card.setValue(value);
            card.setPrevValue(prev);
            if (value != null && prev != null)
            {
                card.setDelta(value.subtract(prev));
                BigDecimal[] d = normalizer.delta(code, value, prev);
                card.setDeltaPct(d[0]);
                card.setDeltaPt(d[1]);
            }

            if (query.isIncludeSeries())
            {
                // series 长度必须恒等于 slot.points：前端按下标对齐 slot.labels，
                // 短一截会让整条趋势线错位，而且不报错
                card.setSeries(fitSeries(normalizer.normalizeSeries(code, fetched.series.get(code)),
                    slot.getPoints()));
                card.setPrevSeries(fitSeries(
                    normalizer.normalizeSeries(code, fetched.prevSeries.get(code)), slot.getPoints()));
            }
            card.setComposition(buildComposition(def, fetched));
            items.add(card);
        }
        block.setGroups(buildGroups());
        block.setItems(items);
        block.setTotal(items.size());
        block.setCoreCount((int) items.stream().filter(x -> "CORE".equals(x.getEmphasis())).count());
        return block;
    }

    /**
     * 把派生指标的操作数补进取数清单。
     *
     * <p><b>只补上游查得到的</b>：像「首存金额」这种只作为 ARPPU 分子存在于配置里、
     * 在 UDS 没有对应 metric 的中间量，补进去会让整个查询因为找不到指标名而失败，
     * 代价是整页白屏，远大于少展开一个构成。查不到的那一个在构成里标 available=false。</p>
     *
     * <p>不做递归展开：操作数本身又是派生指标的情况目前没有，真出现了也只展开一层——
     * 卡片上放得下的就是一行算式，再深一层没有展示的地方。</p>
     */
    private List<String> withOperands(List<String> codes)
    {
        LinkedHashSet<String> all = new LinkedHashSet<>(codes);
        for (String code : codes)
        {
            MetricDefinition def = metricRegistry.get(code);
            if (def == null || !"DERIVED".equalsIgnoreCase(def.getKind()))
            {
                continue;
            }
            for (String operand : new String[] { def.getLeftCode(), def.getRightCode() })
            {
                if (StringUtils.isNotEmpty(operand) && udsMetricOf(operand) != null)
                {
                    all.add(operand);
                }
            }
        }
        return new ArrayList<>(all);
    }

    /**
     * 区间跨天时去掉「仅支持单日」的指标（dashboard_metric_card.single_day_only = true）。
     *
     * <p>在取数之前过滤：这些指标既不向数据平台查询，也不出现在 items 里，
     * total / coreCount 随之减少。派生指标的操作数由 withOperands 按过滤后的列表展开，
     * 被隐藏的指标的操作数也不会再去查。</p>
     *
     * <p>「跨天」按站点时区的自然日判断：00:00 ~ 次日 00:00 仍算单日；
     * 对比期跨天也算——否则当期有值、对比期是个跨天的错数，变化率同样没有意义。</p>
     */
    private List<String> dropSingleDayOnly(List<String> codes, LocalDateTime from, LocalDateTime to,
            OverviewCompareVO compare, List<OverviewNoticeVO> notices)
    {
        boolean multiDay = spansMultipleDays(from, to);
        if (!multiDay && compare != null)
        {
            try
            {
                multiDay = spansMultipleDays(LocalDateTime.parse(compare.getFrom(), SLOT_IN),
                    LocalDateTime.parse(compare.getTo(), SLOT_IN));
            }
            catch (DateTimeParseException ignore)
            {
                // 对比期格式异常不影响主区间判断
            }
        }
        if (!multiDay)
        {
            return codes;
        }
        List<String> kept = new ArrayList<>();
        List<String> hidden = new ArrayList<>();
        for (String code : codes)
        {
            MetricDefinition def = metricRegistry.get(code);
            if (def != null && def.isSingleDayOnly())
            {
                hidden.add(com.fivetech.dashboard.service.OverviewMetricText.label(def.getCode(), def.getLabel()));
            }
            else
            {
                kept.add(code);
            }
        }
        if (!hidden.isEmpty())
        {
            notices.add(OverviewNoticeVO.info("SINGLE_DAY_ONLY_HIDDEN",
                String.join("、", hidden) + "僅支援單日查詢，已隱藏",
                "所選區間跨越多個自然日，這些指標只有按日口徑的資料"));
        }
        return kept;
    }

    /** [from, to) 是否跨越多个自然日：to 是开区间，末端回退一分钟再取日期 */
    private static boolean spansMultipleDays(LocalDateTime from, LocalDateTime to)
    {
        return !from.toLocalDate().equals(to.minusMinutes(1).toLocalDate());
    }

    /**
     * 指标白名单。<b>传了不认识的 code 直接报 4003</b>，不静默丢弃——
     * 少一列而不报错，排查时没人想得到是入参拼错了。
     */
    private List<String> resolveMetricCodes(OverviewQuery query)
    {
        List<String> configured = metricRegistry.columnsOf(DashboardPage.OVERVIEW);
        List<String> requested = query.getMetrics();
        if (requested == null || requested.isEmpty())
        {
            return filterByGroup(configured, query.getGroup());
        }
        List<String> unknown = new ArrayList<>();
        for (String code : requested)
        {
            if (!configured.contains(code))
            {
                unknown.add(code);
            }
        }
        if (!unknown.isEmpty())
        {
            throw new ServiceException("未知的指标编码：" + String.join(", ", unknown), 4003);
        }
        // 按配置顺序返回，不按传入顺序——页面的卡片次序由配置决定
        List<String> ordered = new ArrayList<>();
        for (String code : configured)
        {
            if (requested.contains(code))
            {
                ordered.add(code);
            }
        }
        return filterByGroup(ordered, query.getGroup());
    }

    private List<String> filterByGroup(List<String> codes, String group)
    {
        if (StringUtils.isEmpty(group))
        {
            return codes;
        }
        List<String> result = new ArrayList<>();
        for (String code : codes)
        {
            MetricDefinition def = metricRegistry.get(code);
            if (def != null && group.equalsIgnoreCase(def.getGroup()))
            {
                result.add(code);
            }
        }
        return result;
    }

    /**
     * 指标卡展示小数位（卡片数值头用）：按指标配置，金额 0 位（前端 M 表达）、ARPPU 类 1 位。
     * 返回的数值本身保留两位，供抽屉逐时段明细按两位显示
     */
    private static Integer decimalsOf(MetricDefinition def)
    {
        return def.getDecimals();
    }

    private String groupLabel(String group)
    {
        if (group == null)
        {
            return null;
        }
        // 分组名读 dashboard_metric_group，运营改名不用发版；
        // 字典里没有时退回编码——启动校验已对这种情况告警
        MetricGroupConfig config = metricRegistry.groupOf(group);
        return config == null || StringUtils.isEmpty(config.getGroupName()) ? group : config.getGroupName();
    }

    /**
     * 分组按钮：按 dashboard_metric_group.sort_no 排序，只列指标墙上至少有一张卡的分组。
     *
     * <p>count 按<b>页面配置</b>统计，不按本次返回的 items——请求带了 group 筛选时，
     * 其余分组的按钮仍要显示原本的数量，不能跟着变 0。</p>
     */
    private List<MetricGroupVO> buildGroups()
    {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String code : metricRegistry.columnsOf(DashboardPage.OVERVIEW))
        {
            MetricDefinition def = metricRegistry.get(code);
            if (def != null && def.getGroup() != null)
            {
                counts.merge(def.getGroup(), 1, Integer::sum);
            }
        }
        List<MetricGroupVO> result = new ArrayList<>();
        for (MetricGroupConfig g : metricRegistry.groups())
        {
            Integer count = counts.get(g.getGroupCode());
            if (count != null && count > 0)
            {
                result.add(new MetricGroupVO(g.getGroupCode(),
                    com.fivetech.dashboard.service.OverviewMetricText.group(
                        StringUtils.isEmpty(g.getGroupName()) ? g.getGroupCode() : g.getGroupName()),
                    g.getGroupNameEn(), count));
            }
        }
        return result;
    }

    private String udsMetricOf(String code)
    {
        // 后端相减组装的指标（net / ngr）：返回公式，提示前端这不是 UDS 的同名字段
        List<String> ops = udsProperties.getOverview().getSubtract().get(code);
        if (ops != null && ops.size() == 2)
        {
            return ops.get(0) + "-" + ops.get(1);
        }
        for (UdsProperties.Dataset dataset : udsProperties.getDatasets().values())
        {
            String name = dataset.getMetrics().get(code);
            if (name != null)
            {
                return name;
            }
        }
        return null;
    }

    /**
     * 把序列夹到 points 长度：短了补 null，长了截断。
     * <p>宁可补 null 也不让数组变短——长度是前端对齐时间片标签的契约，
     * 短一截不会报错，只会让整条线悄悄错位。</p>
     */
    private static List<BigDecimal> fitSeries(List<BigDecimal> raw, int points)
    {
        int size = Math.max(0, points);
        List<BigDecimal> result = new ArrayList<>(Collections.nCopies(size, null));
        if (raw != null)
        {
            for (int i = 0; i < Math.min(size, raw.size()); i++)
            {
                result.set(i, raw.get(i));
            }
        }
        return result;
    }

    /**
     * 派生指标的计算构成，供前端展开成
     * 「首存人数 47 ↓11.3% ÷ 注册人数 297 ↑2.8% = 首存转化率 15.82% ↓2.51pt」。
     *
     * <p>操作数的值直接取本次查询的区间合计，<b>不是把序列加起来</b>，也不是从卡片值反推——
     * 反推会在派生指标身上出错：15.82% 这个转化率是「区间去重的首存人数 ÷ 区间去重的注册人数」，
     * 拿它乘回去得不到任何一个真实的数。</p>
     */
    private com.fivetech.dashboard.domain.vo.MetricCompositionVO buildComposition(
            MetricDefinition def, Fetched fetched)
    {
        if (def == null || !"DERIVED".equalsIgnoreCase(def.getKind()))
        {
            return null;
        }
        String calcType = def.getCalcType();
        if (StringUtils.isEmpty(calcType) || "NONE".equalsIgnoreCase(calcType))
        {
            return null;
        }

        com.fivetech.dashboard.domain.vo.MetricCompositionVO vo =
            new com.fivetech.dashboard.domain.vo.MetricCompositionVO();
        vo.setOperator(calcType.toUpperCase());
        vo.setOperatorLabel("DIVIDE".equalsIgnoreCase(calcType) ? "÷" : "−");
        vo.setExpression(com.fivetech.dashboard.service.OverviewMetricText.caliber(def.getCode(), def, metricRegistry));

        com.fivetech.dashboard.domain.vo.MetricOperandVO left = operand(def.getLeftCode(), fetched);
        com.fivetech.dashboard.domain.vo.MetricOperandVO right = operand(def.getRightCode(), fetched);
        if (left == null || right == null)
        {
            // 配置里缺操作数，校验器本该拦住；真漏了就不给半截构成
            return null;
        }
        vo.getOperands().add(left);
        vo.getOperands().add(right);
        vo.setComplete(left.isAvailable() && right.isAvailable());
        return vo;
    }

    private com.fivetech.dashboard.domain.vo.MetricOperandVO operand(String code, Fetched fetched)
    {
        if (StringUtils.isEmpty(code))
        {
            return null;
        }
        com.fivetech.dashboard.domain.vo.MetricOperandVO vo =
            new com.fivetech.dashboard.domain.vo.MetricOperandVO();
        vo.setCode(code);

        MetricDefinition def = metricRegistry.get(code);
        // 注册表里没有这个操作数时，label 退化成编码本身，总比显示空白强
        vo.setLabel(com.fivetech.dashboard.service.OverviewMetricText.label(code, def == null ? code : def.getLabel()));
        vo.setValueFormat(def == null ? null : def.getFormat());
        vo.setDecimals(def == null ? null : decimalsOf(def));

        // 上游没注册成可查指标（例如「首存金额」只在配置里作为分子存在）：
        // 名称照给，值留 null 并标明不可得，让缺口摆在明面上
        boolean available = udsMetricOf(code) != null;
        vo.setAvailable(available);
        if (!available)
        {
            return vo;
        }

        BigDecimal value = normalizer.normalize(code, fetched.totals.get(code));
        BigDecimal prev = normalizer.normalize(code, fetched.prevTotals.get(code));
        vo.setValue(value);
        vo.setPrevValue(prev);
        if (value != null && prev != null)
        {
            vo.setDelta(value.subtract(prev));
            BigDecimal[] d = normalizer.delta(code, value, prev);
            vo.setDeltaPct(d[0]);
            vo.setDeltaPt(d[1]);
        }
        return vo;
    }
}
