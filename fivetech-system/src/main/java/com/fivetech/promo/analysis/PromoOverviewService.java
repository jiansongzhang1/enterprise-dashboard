package com.fivetech.promo.analysis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.domain.MetricGroupConfig;
import com.fivetech.dashboard.domain.query.OverviewQuery;
import com.fivetech.dashboard.domain.vo.MetricCardVO;
import com.fivetech.dashboard.domain.vo.MetricChartVO;
import com.fivetech.dashboard.domain.vo.MetricGroupVO;
import com.fivetech.dashboard.domain.vo.MetricsBlockVO;
import com.fivetech.dashboard.domain.vo.OverviewCompareVO;
import com.fivetech.dashboard.domain.vo.OverviewNoticeVO;
import com.fivetech.dashboard.domain.vo.OverviewSlotVO;
import com.fivetech.dashboard.domain.vo.OverviewVO;
import com.fivetech.dashboard.enums.CompareType;
import com.fivetech.dashboard.enums.Granularity;
import com.fivetech.dashboard.enums.OverviewBlock;
import com.fivetech.dashboard.service.impl.OverviewServiceImpl;
import com.fivetech.promo.analysis.market.MarketCube;
import com.fivetech.promo.analysis.market.MarketGateway;
import com.fivetech.promo.analysis.market.MarketValues;
import com.fivetech.promo.budget.PromoChannelDictionary;

/**
 * 推广分析 · 概览（{@code POST /dashboard/metrics/overview}，{@code scene = PROMO}）。
 *
 * <p>返回结构与运营总览相同（{@link OverviewVO}）：监控相关的 monitoring、anomalies、卡片的 alert、monitorSlots、
 * baselineSeries 一律为 null；卡片多 state、note、seriesUnavailable、spendCompare、drillable、attribution；
 * compare 多 cutAt。</p>
 *
 * <p><b>数据</b>：UDS 市场数据集 {@code dashboard_market_hour_v1}（见 {@link MarketValues}）。已接：注册、首存、首存转化率、
 * 首存金额、复存人数、淨充值（= 存款总额）、激活，以及按终端的变化归因。消耗、预算取运营导入数据（{@link PromoSpend}，
 * 可按推广商 / 推广渠道筛选）；成本类（CPA、首存成本、D0 ROI）按有效格子算（{@link PromoCost}），行为数据能按推广渠道拆之前为
 * NO_SOURCE。其余（UV、下载点击、注册转化率）没有来源或口径不同：value 为 null、state = NO_SOURCE、note 写原因，
 * notices 写 PROMO_DATA_PARTIAL。
 * 选了推广商 / 推广渠道时，渠道映射还没有，全部为 NO_SOURCE（不返回未筛选的数）。</p>
 *
 * @author fivetech
 */
@Service
public class PromoOverviewService
{
    private static final Logger log = LoggerFactory.getLogger(PromoOverviewService.class);

    public static final Set<String> DIMENSIONS = Set.of("agent", "channel", "terminal");

    static final String NOTE_CHANNEL_PENDING = "按推廣渠道篩選待數據平台提供渠道映射";

    /** 一次请求取到的市场数据：主区间 / 对比期 × 按日（合计）/ 按所选粒度（序列） */
    static final class Data
    {
        MarketCube day;
        MarketCube prevDay;
        MarketCube series;
        MarketCube prevSeries;
        LocalDateTime prevFrom;
        /** 渠道筛选无法应用 */
        boolean channelPending;
        /** 终端键（all / web / android / ios / unknown）；null = 终端认不出 */
        String terminalKey;
        /** 消耗 / 预算（含对比期）；null = 没取或关闭了读取 */
        PromoSpend.Cube spend;

        boolean has()
        {
            return day != null && !channelPending && terminalKey != null;
        }
    }

    private final DashboardProperties properties;

    private final PromoMetricCatalog catalog;

    private final PromoChannelDictionary channels;

    private final PromoTerminalDictionary terminals;

    private final MarketGateway market;

    public PromoOverviewService(DashboardProperties properties, PromoMetricCatalog catalog, PromoChannelDictionary channels,
        PromoTerminalDictionary terminals, MarketGateway market)
    {
        this.properties = properties;
        this.catalog = catalog;
        this.channels = channels;
        this.terminals = terminals;
        this.market = market;
    }

    /** 消耗 / 预算（可选，setter 注入，避免改动构造函数）；没有时这几张卡按「數據待接入」 */
    private PromoSpend spend;

    /** 按推广渠道的行为数据（成本类指标的有效格子）；数据平台提供推广渠道维度（U1）前为 NONE */
    PromoCost.ChannelBehavior behavior = PromoCost.ChannelBehavior.NONE;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSpend(PromoSpend spend)
    {
        this.spend = spend;
    }

    public OverviewVO query(OverviewQuery query)
    {
        OverviewVO vo = new OverviewVO();
        List<OverviewNoticeVO> notices = vo.getNotices();
        String site = properties.currentSite();

        // ---------- 1. 时间：主区间、截到当前整点、粒度规则 ----------
        PromoTime.Range requested = PromoTime.range(query.getSlotFrom(), query.getSlotTo());
        // 终点截到当前时间向右取整的整点（UDS 实时数据：正在进行的小时一起返回，16:50 → 17:00）
        LocalDateTime now = com.fivetech.dashboard.gateway.uds.UdsRealtime.ceilHour(LocalDateTime.now(properties.currentZone()));
        PromoTime.Range range = requested;
        if (requested.to.isAfter(now) && now.isAfter(requested.from))
        {
            range = new PromoTime.Range(requested.from, now);
            notices.add(OverviewNoticeVO.info("SLOT_CLAMPED", "已截至當前時間",
                "統計區間終點調整為 " + now.format(PromoTime.SLOT) + "（當前小時為實時數據，尚未結束）"));
        }
        Granularity asked = query.getGranularity() == null ? Granularity.HOUR : query.getGranularity();
        // 粒度按用户所选的整段区间判断（与页面的可选粒度一致），不受截到当前整点影响
        Granularity granularity = PromoTime.allowed(requested, asked);
        if (granularity != asked)
        {
            notices.add(OverviewNoticeVO.info("GRANULARITY_DOWNGRADED", "粒度已改為「" + label(granularity) + "」",
                "所選區間 " + requested.days() + " 天不支持按「" + label(asked) + "」"));
        }
        OverviewSlotVO slot = OverviewServiceImpl.buildSlot(range.from, range.to, granularity);
        vo.setSlot(slot);

        // ---------- 2. 对比期（同时段对比） ----------
        Map<String, Object> cmp = PromoTime.compare(query.getCompareType(), query.getCompareFrom(), query.getCompareTo(),
            query.isCmpLock(), range, now);
        OverviewCompareVO compare = null;
        if (cmp != null)
        {
            compare = new OverviewCompareVO();
            compare.setFrom((String) cmp.get("from"));
            compare.setTo((String) cmp.get("to"));
            compare.setType(CompareType.valueOf((String) cmp.get("type")));
            compare.setCutAt((String) cmp.get("cutAt"));
            compare.setLabel(compare.getFrom() + " – " + compare.getTo());
        }
        vo.setCompare(compare);

        // ---------- 3. 筛选 ----------
        PromoTerminalDictionary.Config terminalConfig = terminals.of(site);
        PromoScope scope = PromoScope.resolve(channels.promotersOf(site), query.getAgents(), query.getChannels(),
            terminalConfig, query.getTerminal());
        String dimension = dimension(query);

        // ---------- 4. 上下文：监控相关一律为 null ----------
        vo.setCurrency(properties.currentCurrency());
        vo.setTimezone(properties.currentTimezone());
        vo.setMonitoring(null);
        vo.setAnomalies(null);

        // ---------- 5. 指标 ----------
        List<PromoMetricCatalog.Metric> all = catalog.overviewMetrics();
        List<PromoMetricCatalog.Metric> selected = resolveMetrics(query, all, notices, viewCards(query));

        if (!resolveBlocks(query, notices).contains(OverviewBlock.METRICS))
        {
            vo.setEmpty(true);
            return vo;
        }
        // ---------- 6. 取数（UDS 市场数据集） ----------
        Data data = new Data();
        data.channelPending = !scope.isAll();
        data.terminalKey = MarketValues.terminalKey(scope.getTerminal());
        // 成本类（CPA、首存成本、D0 ROI）在推广商 / 推广渠道为「全部」时按全站注册、首存、存款估算，也要取市场数据
        boolean needCost = selected.stream().anyMatch(m -> SITE_COST.contains(m.getCode()));
        boolean needData = needCost || selected.stream().anyMatch(m -> MarketValues.AVAILABLE.contains(m.getCode()));
        if (market != null && market.isEnabled() && needData && !data.channelPending && data.terminalKey != null)
        {
            // 只取所选指标用到的字段；选了终端或按终端归因时才要带终端前缀的宽表字段
            List<String> codes = new ArrayList<>();
            selected.forEach(m -> codes.add(m.getCode()));
            if (needCost)
            {
                codes.addAll(List.of("reg", "ftd", "net"));
            }
            boolean byTerminal = scope.isTerminalSelected() || "terminal".equals(dimension);
            try
            {
                data.day = market.cube(range.from, range.to, Granularity.DAY, codes, byTerminal);
                if (compare != null)
                {
                    data.prevFrom = LocalDateTime.parse(compare.getFrom(), PromoTime.SLOT);
                    LocalDateTime prevTo = LocalDateTime.parse(compare.getTo(), PromoTime.SLOT);
                    data.prevDay = market.cube(data.prevFrom, prevTo, Granularity.DAY, codes, byTerminal);
                    if (query.isIncludeSeries() && granularity == Granularity.HOUR)
                    {
                        data.prevSeries = market.cube(data.prevFrom, prevTo, Granularity.HOUR, codes, byTerminal);
                    }
                    else
                    {
                        data.prevSeries = data.prevDay;
                    }
                }
                data.series = query.isIncludeSeries() && granularity == Granularity.HOUR
                    ? market.cube(range.from, range.to, Granularity.HOUR, codes, byTerminal) : data.day;
                if (data.day.getWatermark() != null)
                {
                    vo.setAsOf(data.day.getWatermark().format(PromoTime.SLOT));
                }
                if (data.day.isOmitted())
                {
                    notices.add(OverviewNoticeVO.warn("PROMO_SITE_NO_DATA", "本站暫無推廣數據", "數據平台未提供本站的市場數據集"));
                }
            }
            catch (RuntimeException e)
            {
                log.error("[promo] 概览取数失败：{}", e.getMessage(), e);
                vo.getBlockErrors().put(OverviewBlock.METRICS.name(),
                    com.fivetech.dashboard.domain.vo.OverviewBlockErrorVO.of(PromoErrors.DATA_FAILED, "資料平台取數失敗"));
                vo.setEmpty(true);
                return vo;
            }
        }

        // ---------- 6b. 消耗 / 预算（运营导入，按推广渠道，可按推广商 / 推广渠道筛选） ----------
        if (spend != null && selected.stream().anyMatch(m -> PromoSpend.CODES.contains(m.getCode()) || PromoCost.CODES.contains(m.getCode())))
        {
            LocalDate[] w = spendDays("budget", range, compare, now);
            LocalDate from = w[2] != null && w[2].isBefore(w[0]) ? w[2] : w[0];
            LocalDate to = w[3] != null && w[3].isAfter(w[1]) ? w[3] : w[1];
            data.spend = spend.load(from, to);
        }

        MetricsBlockVO block = new MetricsBlockVO();
        Map<String, MetricGroupConfig> groupConfigs = catalog.groups();
        List<MetricCardVO> items = new ArrayList<>();
        for (PromoMetricCatalog.Metric m : selected)
        {
            items.add(card(m, query, slot, range, compare, now, scope, dimension, groupConfigs, data, terminalConfig));
        }
        block.setItems(items);
        block.setTotal(items.size());
        block.setCoreCount((int) items.stream().filter(c -> "CORE".equals(c.getEmphasis())).count());
        block.setGroups(groups(all, groupConfigs));
        vo.getBlocks().put(OverviewBlock.METRICS.name(), block);

        // ---------- 7. 没有来源的指标 ----------
        List<String> pending = new ArrayList<>();
        items.stream().filter(c -> "NO_SOURCE".equals(c.getState())).forEach(c -> pending.add(c.getLabel()));
        if (data.channelPending)
        {
            notices.add(OverviewNoticeVO.warn("PROMO_CHANNEL_PENDING", "暫不能按推廣商 / 推廣渠道篩選",
                "數據平台還沒有推廣渠道映射，所選渠道範圍的指標暫無數值"));
        }
        else if (!pending.isEmpty())
        {
            notices.add(OverviewNoticeVO.info("PROMO_DATA_PARTIAL", "部分指標暫無數據", String.join("、", pending) + "：數據待接入或口徑待確認"));
        }
        vo.setEmpty(items.stream().allMatch(c -> c.getValue() == null));
        return vo;
    }

    private static String dimension(OverviewQuery query)
    {
        if (query.getAttribution() == null || query.getAttribution().getDimension() == null
            || query.getAttribution().getDimension().trim().isEmpty())
        {
            return null;
        }
        String d = query.getAttribution().getDimension().trim().toLowerCase(Locale.ROOT);
        if (!DIMENSIONS.contains(d))
        {
            throw new ServiceException("不認識的歸因維度：" + query.getAttribution().getDimension() + "（agent / channel / terminal）",
                PromoErrors.UNKNOWN_CODE);
        }
        return d;
    }

    /** 个人视图（可选）：概览不传 metrics 时按「我在本站」的核心指标卡取数 */
    private com.fivetech.dashboard.view.UserViewService views;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setViews(com.fivetech.dashboard.view.UserViewService views)
    {
        this.views = views;
    }

    /** 不传 metrics 时我的核心指标卡（个人视图 promo.overview.cards）；没有个人视图能力或取不到为 null */
    private List<String> viewCards(OverviewQuery query)
    {
        if (views == null || (query.getMetrics() != null && !query.getMetrics().isEmpty()))
        {
            return null;
        }
        return views.itemsOf(PromoViewItemSource.CARDS);
    }

    /**
     * 请求的指标：不传时按个人视图（viewCards，顺序即卡片顺序），没有个人视图取页面默认；
     * 不认识的编码整个请求 4003；按 group 筛；最多 10 个
     */
    static List<PromoMetricCatalog.Metric> resolveMetrics(OverviewQuery query, List<PromoMetricCatalog.Metric> all,
        List<OverviewNoticeVO> notices, List<String> viewCards)
    {
        Map<String, PromoMetricCatalog.Metric> byCode = new LinkedHashMap<>();
        all.forEach(m -> byCode.put(m.getCode(), m));
        List<PromoMetricCatalog.Metric> out = new ArrayList<>();
        if ((query.getMetrics() == null || query.getMetrics().isEmpty()) && viewCards != null && !viewCards.isEmpty())
        {
            for (String code : viewCards)
            {
                PromoMetricCatalog.Metric m = byCode.get(code);
                if (m != null && !out.contains(m))
                {
                    out.add(m);
                }
            }
        }
        if (out.isEmpty() && (query.getMetrics() == null || query.getMetrics().isEmpty()))
        {
            all.stream().filter(PromoMetricCatalog.Metric::isDefault).forEach(out::add);
        }
        else
        {
            Set<String> seen = new LinkedHashSet<>();
            for (String raw : query.getMetrics())
            {
                String code = raw == null ? "" : raw.trim();
                PromoMetricCatalog.Metric m = byCode.get(code);
                if (m == null)
                {
                    throw new ServiceException("不認識的指標：" + raw, PromoErrors.UNKNOWN_CODE);
                }
                if (seen.add(code))
                {
                    out.add(m);
                }
            }
        }
        if (query.getGroup() != null && !query.getGroup().trim().isEmpty())
        {
            String g = query.getGroup().trim();
            out.removeIf(m -> !g.equals(m.getGroup()));
        }
        if (out.size() > PromoMetricCatalog.MAX_CARDS)
        {
            notices.add(OverviewNoticeVO.info("METRICS_TRUNCATED", "最多顯示 " + PromoMetricCatalog.MAX_CARDS + " 個指標",
                "已按顯示順序取前 " + PromoMetricCatalog.MAX_CARDS + " 個"));
            out = new ArrayList<>(out.subList(0, PromoMetricCatalog.MAX_CARDS));
        }
        return out;
    }

    private static Set<OverviewBlock> resolveBlocks(OverviewQuery query, List<OverviewNoticeVO> notices)
    {
        Set<OverviewBlock> result = new LinkedHashSet<>();
        for (String raw : query.getBlocks())
        {
            OverviewBlock b = OverviewBlock.of(raw);
            if (b == null)
            {
                notices.add(OverviewNoticeVO.info("PARAM_IGNORED", "未知的 blocks 取值：" + raw, "可選值為 METRICS"));
                continue;
            }
            result.add(b);
        }
        if (result.isEmpty())
        {
            result.add(OverviewBlock.METRICS);
        }
        return result;
    }

    private MetricCardVO card(PromoMetricCatalog.Metric m, OverviewQuery query, OverviewSlotVO slot, PromoTime.Range range,
        OverviewCompareVO compare, LocalDateTime now, PromoScope scope, String dimension, Map<String, MetricGroupConfig> groups,
        Data data, PromoTerminalDictionary.Config terminalConfig)
    {
        PromoMetricCatalog.Spec spec = m.getSpec();
        MetricCardVO c = new MetricCardVO();
        c.setCode(m.getCode());
        c.setLabel(m.getName());
        c.setGroup(m.getGroup());
        MetricGroupConfig g = m.getGroup() == null ? null : groups.get(m.getGroup());
        c.setGroupLabel(g == null ? m.getGroup() : g.getGroupName());
        c.setSortNo(m.getSortNo());
        c.setEmphasis(m.getEmphasis());
        c.setKind(m.isRatio() ? "DERIVED" : "ATOM");
        c.setExpression(spec.getDefinition());
        c.setValueFormat(m.getFormat());
        c.setDecimals(m.getDecimals());
        c.setDirection(m.getDirection());
        c.setUpdateFrequency(m.getUpdateFrequency());
        c.setAlert(null);
        c.setChart(new MetricChartVO(MetricChartVO.LINE, null));
        c.setUdsMetric(null);
        c.setComposition(null);

        // 终端适用性
        boolean na = false;
        if (scope.isTerminalSelected())
        {
            if (!spec.isByTerminal())
            {
                na = true;
                c.setNote(spec.getTerminalNote());
            }
            else if (spec.isAndroidOnly() && !"android".equals(scope.getTerminal()))
            {
                na = true;
                c.setNote(spec.getTerminalNote());
            }
        }
        String code = m.getCode();
        boolean available = MarketValues.AVAILABLE.contains(code);
        boolean spendCard = PromoSpend.CODES.contains(code);
        boolean costCard = PromoCost.CODES.contains(code);
        LocalDate[] w = spendCard || costCard ? spendDays(code, range, compare, now) : null;
        PromoCost.Result cost = null;
        PromoCost.Result prevCost = null;
        String state = na ? "NA" : "OK";
        if (!na && spendCard)
        {
            // 消耗 / 预算带推广渠道，选了推广商 / 推广渠道也能算
            if (data.spend == null || data.spend.isFailed())
            {
                state = "NO_SOURCE";
                c.setNote(data.spend == null ? MarketValues.noSourceNote(code) : PromoSpend.FAILED_NOTE);
            }
        }
        else if (!na && costCard)
        {
            if (data.spend == null || data.spend.isFailed())
            {
                state = "NO_SOURCE";
                c.setNote(data.spend == null ? PromoCost.note(code) : PromoSpend.FAILED_NOTE);
            }
            else
            {
                cost = costOf(code, data, scope, w[0], w[1], false);
                prevCost = w[2] == null ? null : costOf(code, data, scope, w[2], w[3], true);
                state = cost.state();
                c.setNote(cost.note());
            }
        }
        else if (!na && (!available || data.channelPending))
        {
            state = "NO_SOURCE";
            c.setNote(data.channelPending ? NOTE_CHANNEL_PENDING : MarketValues.noSourceNote(code));
        }
        c.setState(state);
        boolean fill = "OK".equals(state) && data.has();
        BigDecimal value;
        BigDecimal prev;
        java.util.function.Predicate<com.fivetech.promo.budget.BudgetDataReader.Row> inScope = PromoSpend.inScope(scope);
        if (spendCard)
        {
            boolean ok = "OK".equals(state);
            value = ok ? data.spend.sum(PromoSpend.kind(code), w[0], w[1], inScope) : null;
            prev = ok && w[2] != null ? data.spend.sum(PromoSpend.kind(code), w[2], w[3], inScope) : null;
        }
        else if (costCard)
        {
            value = cost == null ? null : cost.value();
            prev = prevCost == null || !"OK".equals(prevCost.state()) ? null : prevCost.value();
        }
        else
        {
            value = fill ? MarketValues.value(code, data.day, data.terminalKey, null) : null;
            prev = fill && data.prevDay != null ? MarketValues.value(code, data.prevDay, data.terminalKey, null) : null;
        }
        c.setValue(value);
        c.setPrevValue(prev);
        if (value != null && prev != null)
        {
            c.setDelta(value.subtract(prev));
            BigDecimal[] d = MarketValues.delta(code, value, prev);
            c.setDeltaPct(d[0]);
            c.setDeltaPt(d[1]);
        }
        if (query.isIncludeSeries())
        {
            int points = Math.max(0, slot.getPoints());
            boolean spendSeries = spendCard && "OK".equals(state);
            // 成本类：每个时间片按同一套算法（有效格子 / 全站估算）各算一次
            boolean costSeries = costCard && "OK".equals(state);
            c.setSeries(spendSeries ? spendSeries(data.spend, PromoSpend.kind(code), inScope, range.from, slot.getGranularity(), points)
                : costSeries ? costSeries(range.from.toLocalDate(), w[1], slot.getGranularity(), points,
                    (s, e) -> costOf(code, data, scope, s, e, false))
                : fill && !costCard ? series(code, data.series, data.terminalKey, range.from, slot.getGranularity(), points)
                : new ArrayList<>(Collections.nCopies(points, null)));
            if (compare == null)
            {
                c.setPrevSeries(new ArrayList<>());
            }
            else
            {
                LocalDateTime prevStart = LocalDateTime.parse(compare.getFrom(), PromoTime.SLOT);
                c.setPrevSeries(spendSeries
                    ? spendSeries(data.spend, PromoSpend.kind(code), inScope, prevStart, slot.getGranularity(), points)
                    : costSeries && w[2] != null
                    ? costSeries(prevStart.toLocalDate(), w[3], slot.getGranularity(), points, (s, e) -> costOf(code, data, scope, s, e, true))
                    : fill && !costCard && data.prevSeries != null
                    ? series(code, data.prevSeries, data.terminalKey, data.prevFrom, slot.getGranularity(), points)
                    : new ArrayList<>(Collections.nCopies(points, null)));
            }
        }
        if (!spec.isHourly() && slot.getGranularity() == Granularity.HOUR)
        {
            c.setSeriesUnavailable("DAILY_ONLY");
        }
        c.setDrillable(spec.isDrillable() && slot.getGranularity() == Granularity.DAY);
        if ("spend".equals(m.getCode()) && compare != null)
        {
            c.setSpendCompare(spendCompare(range, compare, now));
        }
        if (dimension != null)
        {
            // 带了 attribution 时每张卡都返回：不可归因的 supported = false，reason 写给用户看的原因
            Map<String, Object> a = attribution(m, dimension, scope);
            fillAttribution(a, m, c, data, terminalConfig);
            c.setAttribution(a);
        }
        return c;
    }

    /**
     * 消耗 / 预算与成本类指标的日期：{ 本期起, 本期止, 对比期起, 对比期止 }（都含当天；没有对比期时后两个为 null）。
     * 消耗本期含今天且不只今天时，两期都去掉今天、按同样天数比（与卡片 spendCompare 一致）；预算含今天。
     */
    static LocalDate[] spendDays(String code, PromoTime.Range range, OverviewCompareVO compare, LocalDateTime now)
    {
        LocalDate from = range.firstDay();
        LocalDate to = range.lastDay();
        LocalDate prevFrom = null;
        LocalDate prevTo = null;
        if (compare != null)
        {
            prevFrom = LocalDateTime.parse(compare.getFrom(), PromoTime.SLOT).toLocalDate();
            prevTo = LocalDateTime.parse(compare.getTo(), PromoTime.SLOT).minusMinutes(1).toLocalDate();
        }
        if (!"budget".equals(code) && compare != null && spendCompare(range, compare, now) != null)
        {
            LocalDate today = now.toLocalDate();
            long days = ChronoUnit.DAYS.between(from, today);
            to = today.minusDays(1);
            prevTo = prevFrom.plusDays(days - 1);
        }
        return new LocalDate[] { from, to, prevFrom, prevTo };
    }

    /** 推广商 / 推广渠道为「全部」时可按全站估算的成本类指标（UV 成本的 UV-落地頁口径待确认，不估算） */
    static final Set<String> SITE_COST = Set.of("cpa", "ftdCost", "roi");

    /** 全站估算的说明（卡片 note） */
    static final String SITE_COST_NOTE = "推廣商、推廣渠道為「全部」時按全站估算：有消耗的日期內，全站註冊 / 首存 / 存款總額（含官方等自然量）對全部消耗；"
        + "數據平台提供推廣渠道維度後改按有效格子（日 × 推廣渠道）計算";

    /** 全站估算时格子的渠道键 */
    private static final String SITE = "*";

    /**
     * 成本类指标。先按有效格子（日 × 推广渠道）算；行为数据还不能按推广渠道拆（NO_SOURCE），
     * 且推广商 / 推广渠道、终端都为「全部」时，按全站估算：有效格子退化为「有消耗的日」，
     * CPA ＝ 这些日的消耗 ÷ 全站注册人数，首存成本 ＝ 消耗 ÷ 全站首存人数，D0 ROI ＝ 全站存款总额（淨充值）÷ 消耗 − 1。
     *
     * @param prev 是否对比期（用对比期的市场数据）
     */
    PromoCost.Result costOf(String code, Data data, PromoScope scope, LocalDate from, LocalDate to, boolean prev)
    {
        PromoCost.Result r = costOf(code, data.spend, scope, from, to);
        MarketCube cube = prev ? data.prevDay : data.day;
        if (!"NO_SOURCE".equals(r.state()) || !SITE_COST.contains(code) || !scope.isAll() || scope.isTerminalSelected()
            || cube == null || cube.isOmitted())
        {
            return r;
        }
        return siteCost(code, data.spend.cells(com.fivetech.promo.budget.BudgetKind.SPEND, from, to, PromoSpend.inScope(scope)),
            cube, from, to);
    }

    /** 全站估算：消耗按日合并成一个格子，行为数据取全站当日值，再走同一套有效格子计算 */
    static PromoCost.Result siteCost(String code, Map<PromoCost.Cell, BigDecimal> spendCells, MarketCube cube, LocalDate from, LocalDate to)
    {
        Map<PromoCost.Cell, BigDecimal> spendByDay = new LinkedHashMap<>();
        spendCells.forEach((k, v) -> {
            if (v != null)
            {
                spendByDay.merge(new PromoCost.Cell(k.day(), SITE), v, BigDecimal::add);
            }
        });
        String metric = PromoCost.behaviorMetric(code);
        Map<PromoCost.Cell, BigDecimal> behavior = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1))
        {
            LocalDate day = d;
            BigDecimal v = MarketValues.value(metric, cube, MarketCube.ALL, b -> b.toLocalDate().equals(day));
            if (v != null)
            {
                behavior.put(new PromoCost.Cell(d, SITE), v);
            }
        }
        PromoCost.Result s = PromoCost.evaluate(code, spendByDay, behavior);
        return "OK".equals(s.state()) ? new PromoCost.Result("OK", s.value(), SITE_COST_NOTE) : s;
    }

    /** 成本类指标：范围内（所选推广商 / 推广渠道）按有效格子算 */
    PromoCost.Result costOf(String code, PromoSpend.Cube cube, PromoScope scope, LocalDate from, LocalDate to)
    {
        java.util.Set<String> ids = new java.util.HashSet<>();
        scope.getChannels().forEach(ch -> ids.add(com.fivetech.configcenter.ConfigCodes.idKey(ch.getId())));
        java.util.function.Predicate<String> channel = scope.isAll() ? k -> true : ids::contains;
        Map<PromoCost.Cell, BigDecimal> cells = cube.cells(com.fivetech.promo.budget.BudgetKind.SPEND, from, to, PromoSpend.inScope(scope));
        Map<PromoCost.Cell, BigDecimal> b = PromoCost.filter(behavior.byChannel(PromoCost.behaviorMetric(code), from, to), from, to, channel);
        return PromoCost.evaluate(code, cells, b);
    }

    /** 消耗 / 预算的序列：按日录入，日 / 周粒度按导入日期归点；小时粒度没有（DAILY_ONLY） */
    static List<BigDecimal> spendSeries(PromoSpend.Cube cube, com.fivetech.promo.budget.BudgetKind kind,
        java.util.function.Predicate<com.fivetech.promo.budget.BudgetDataReader.Row> filter, LocalDateTime from, Granularity g, int points)
    {
        List<BigDecimal> out = new ArrayList<>(Collections.nCopies(points, null));
        if (cube == null || g == Granularity.HOUR)
        {
            return out;
        }
        for (com.fivetech.promo.budget.BudgetDataReader.Row r : cube.rows(kind))
        {
            if (r.getDate() == null || r.getAmount() == null || !filter.test(r))
            {
                continue;
            }
            int i = index(r.getDate().atStartOfDay(), from, g);
            if (i >= 0 && i < points)
            {
                out.set(i, out.get(i) == null ? r.getAmount() : out.get(i).add(r.getAmount()));
            }
        }
        return out;
    }

    /**
     * 成本类（CPA、首存成本、D0 ROI、UV 成本）的序列：第 i 个时间片取 [片首日, min(片尾日, lastDay)]，用 calc 算一次。
     * 日粒度一天一片，周粒度 7 天一片（与 {@link #index} 一致）；小时粒度没有（消耗按日录入，卡片标 DAILY_ONLY）。
     * 片首日超过 lastDay（消耗只算到昨天）或该片不是 OK（没有消耗 NO_SPEND 等）为 null。
     */
    static List<BigDecimal> costSeries(LocalDate from, LocalDate lastDay, Granularity g, int points,
        java.util.function.BiFunction<LocalDate, LocalDate, PromoCost.Result> calc)
    {
        List<BigDecimal> out = new ArrayList<>(Collections.nCopies(points, null));
        if (g == Granularity.HOUR || from == null || lastDay == null)
        {
            return out;
        }
        int step = g == Granularity.WEEK ? 7 : 1;
        for (int i = 0; i < points; i++)
        {
            LocalDate s = from.plusDays((long) i * step);
            if (s.isAfter(lastDay))
            {
                break;
            }
            LocalDate e = s.plusDays(step - 1L);
            PromoCost.Result r = calc.apply(s, e.isAfter(lastDay) ? lastDay : e);
            if (r != null && "OK".equals(r.state()))
            {
                out.set(i, r.value());
            }
        }
        return out;
    }

    /** 序列：每个时间片的值（与 slot.points 等长，缺的为 null） */
    static List<BigDecimal> series(String code, MarketCube cube, String terminal, LocalDateTime from, Granularity g, int points)
    {
        List<BigDecimal> out = new ArrayList<>(Collections.nCopies(points, null));
        if (cube == null)
        {
            return out;
        }
        for (int i = 0; i < points; i++)
        {
            int idx = i;
            out.set(i, MarketValues.value(code, cube, terminal, b -> index(b, from, g) == idx));
        }
        return out;
    }

    static int index(LocalDateTime bucket, LocalDateTime from, Granularity g)
    {
        if (g == Granularity.HOUR)
        {
            return (int) ChronoUnit.HOURS.between(from, bucket);
        }
        long days = ChronoUnit.DAYS.between(from.toLocalDate(), bucket.toLocalDate());
        return (int) (g == Granularity.WEEK ? Math.floorDiv(days, 7) : days);
    }

    /**
     * 归因成员：按终端拆时取市场数据（字典里的终端 + 未識別），两期都为空或 0 的成员不返回；
     * 按推广商 / 推广渠道拆、或该指标没有来源时 supported = false、reasonCode = PENDING_SOURCE。
     */
    static void fillAttribution(Map<String, Object> a, PromoMetricCatalog.Metric m, MetricCardVO card, Data data,
        PromoTerminalDictionary.Config terminalConfig)
    {
        if (!Boolean.TRUE.equals(a.get("supported")))
        {
            return;
        }
        String dimension = (String) a.get("dimension");
        if (!"terminal".equals(dimension) || !MarketValues.AVAILABLE.contains(m.getCode()) || data.channelPending)
        {
            String text = !"terminal".equals(dimension)
                ? "數據平台還沒有按" + DIMENSION_NAMES.get(dimension) + "拆分的數據，暫不能歸因。"
                : data.channelPending
                    ? "已篩選推廣商 / 推廣渠道，數據平台還沒有推廣渠道映射，暫不能歸因。"
                    : m.getName() + "的數據待接入，暫不能按終端拆分。";
            unsupported(a, "PENDING_SOURCE", text);
            return;
        }
        Map<String, Object> total = new LinkedHashMap<>();
        total.put("value", card.getValue());
        total.put("prevValue", card.getPrevValue());
        a.put("total", total);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (data.has())
        {
            List<String[]> members = new ArrayList<>();
            terminalConfig.getTerminals().forEach(t -> members.add(new String[] { t.getCode(), t.getName() }));
            members.add(new String[] { PromoTerminalDictionary.UNIDENTIFIED, PromoTerminalDictionary.UNIDENTIFIED_NAME });
            for (String[] t : members)
            {
                String key = MarketValues.terminalKey(t[0]);
                if (key == null || MarketCube.ALL.equals(key))
                {
                    continue;
                }
                BigDecimal v = MarketValues.value(m.getCode(), data.day, key, null);
                BigDecimal p = data.prevDay == null ? null : MarketValues.value(m.getCode(), data.prevDay, key, null);
                if ((v == null || v.signum() == 0) && (p == null || p.signum() == 0))
                {
                    continue;
                }
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("key", t[0]);
                r.put("name", t[1]);
                r.put("agent", null);
                r.put("value", v);
                r.put("prevValue", p);
                rows.add(r);
            }
        }
        a.put("rows", rows);
    }

    /**
     * 消耗卡：本期含今天且不只今天时，两期都去掉今天、按同样天数比，返回给前端写「?」里的两段日期（MM-dd）。
     */
    static Map<String, Object> spendCompare(PromoTime.Range range, OverviewCompareVO compare, LocalDateTime now)
    {
        // now 是向右取整的整点（不含），23:xx 时为次日 00:00：往回 1 纳秒取日期
        LocalDate today = now.minusNanos(1).toLocalDate();
        if (!range.lastDay().equals(today) || !range.firstDay().isBefore(today))
        {
            return null;
        }
        LocalDate prevFrom = LocalDateTime.parse(compare.getFrom(), PromoTime.SLOT).toLocalDate();
        long days = ChronoUnit.DAYS.between(range.firstDay(), today);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", range.firstDay().format(PromoTime.MMDD));
        m.put("to", today.minusDays(1).format(PromoTime.MMDD));
        m.put("prevFrom", prevFrom.format(PromoTime.MMDD));
        m.put("prevTo", prevFrom.plusDays(days - 1).format(PromoTime.MMDD));
        m.put("days", days);
        return m;
    }

    /** 归因维度的显示名（拼原因文字用） */
    static final Map<String, String> DIMENSION_NAMES = Map.of("agent", "推廣商", "channel", "推廣渠道", "terminal", "終端");

    /**
     * 变化归因：请求带了 attribution 时每张卡都有。
     * <p>supported = false 时 reasonCode 给前端判断、reason 是直接展示的文字，没有 total / rows：</p>
     * <ul>
     *   <li>NOT_CONFIGURED：指标管理里没打开「可歸因」；</li>
     *   <li>TERMINAL_SELECTED：已筛选单个终端，又按终端拆；</li>
     *   <li>NOT_BY_TERMINAL：指标不区分终端，或只统计 Android；</li>
     *   <li>PENDING_SOURCE：拆分数据待接入（{@link #fillAttribution} 判断）。</li>
     * </ul>
     * <p>supported = true 时 reasonCode、reason 为 null，成员值由 {@link #fillAttribution} 填（这里 rows 为空，total 为 null）。</p>
     */
    static Map<String, Object> attribution(PromoMetricCatalog.Metric m, String dimension, PromoScope scope)
    {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("dimension", dimension);
        a.put("supported", true);
        a.put("reasonCode", null);
        a.put("reason", null);
        if (!m.isAttributable())
        {
            // 比率类（百分比、按消耗算的）说明不能拆的原因；可加总的指标只是没打开开关，不能套用比率的说法
            unsupported(a, "NOT_CONFIGURED", m.isRatio()
                ? m.getName() + "是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，"
                    + "不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。"
                : m.getName() + "未開啟變化歸因（系統管理 › 指標管理 › 可歸因）。");
            return a;
        }
        if ("terminal".equals(dimension))
        {
            if (scope.isTerminalSelected())
            {
                unsupported(a, "TERMINAL_SELECTED", "已篩選單個終端，不能再按終端拆分；終端選「全部」後可查看。");
                return a;
            }
            if (!m.getSpec().isByTerminal())
            {
                unsupported(a, "NOT_BY_TERMINAL", m.getName() + "不區分終端統計，不能按終端拆分。");
                return a;
            }
            if (m.getSpec().isAndroidOnly())
            {
                unsupported(a, "NOT_BY_TERMINAL", m.getName() + "只統計 Android，不能按終端拆分。");
                return a;
            }
        }
        Map<String, Object> total = new LinkedHashMap<>();
        total.put("value", null);
        total.put("prevValue", null);
        a.put("total", total);
        a.put("rows", new ArrayList<>());
        return a;
    }

    /** 不支持归因：supported = false，写原因，去掉 total / rows */
    private static void unsupported(Map<String, Object> a, String code, String text)
    {
        a.put("supported", false);
        a.put("reasonCode", code);
        a.put("reason", text);
        a.remove("total");
        a.remove("rows");
    }

    /** 分组按钮：推广分析页面上至少有一张卡的分组，count 按页面配置 */
    private static List<MetricGroupVO> groups(List<PromoMetricCatalog.Metric> all, Map<String, MetricGroupConfig> configs)
    {
        Map<String, Integer> counts = new LinkedHashMap<>();
        all.forEach(m -> {
            if (m.getGroup() != null)
            {
                counts.merge(m.getGroup(), 1, Integer::sum);
            }
        });
        List<MetricGroupVO> out = new ArrayList<>();
        for (MetricGroupConfig g : configs.values())
        {
            Integer n = counts.get(g.getGroupCode());
            if (n != null)
            {
                out.add(new MetricGroupVO(g.getGroupCode(), g.getGroupName(), g.getGroupNameEn(), n));
            }
        }
        return out;
    }

    private static String label(Granularity g)
    {
        return g == Granularity.HOUR ? "小時" : g == Granularity.WEEK ? "週" : "日";
    }
}
