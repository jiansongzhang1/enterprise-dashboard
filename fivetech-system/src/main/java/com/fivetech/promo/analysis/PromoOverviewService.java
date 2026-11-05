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
import com.fivetech.promo.budget.PromoChannelDictionary;

/**
 * 推广分析 · 概览（{@code POST /dashboard/metrics/overview}，{@code scene = PROMO}）。
 *
 * <p>返回结构与运营总览相同（{@link OverviewVO}）：监控相关的 monitoring、anomalies、卡片的 alert、monitorSlots、
 * baselineSeries 一律为 null；卡片多 state、note、seriesUnavailable、spendCompare、drillable、attribution；
 * compare 多 cutAt。</p>
 *
 * <p><b>数据未接入</b>：UDS 的推广数据集还没定（设计文档第 8 节），现在所有数值、序列、归因成员都是空
 * （value / prevValue 为 null、series 全为 null、rows 为空），并在 notices 里写 PROMO_DATA_PENDING。
 * 请求校验、指标与粒度规则、对比期、终端适用性等结构已按设计实现，接入数据时只补取数。</p>
 *
 * @author fivetech
 */
@Service
public class PromoOverviewService
{
    public static final Set<String> DIMENSIONS = Set.of("agent", "channel", "terminal");

    private final DashboardProperties properties;

    private final PromoMetricCatalog catalog;

    private final PromoChannelDictionary channels;

    private final PromoTerminalDictionary terminals;

    public PromoOverviewService(DashboardProperties properties, PromoMetricCatalog catalog, PromoChannelDictionary channels,
        PromoTerminalDictionary terminals)
    {
        this.properties = properties;
        this.catalog = catalog;
        this.channels = channels;
        this.terminals = terminals;
    }

    public OverviewVO query(OverviewQuery query)
    {
        OverviewVO vo = new OverviewVO();
        List<OverviewNoticeVO> notices = vo.getNotices();
        String site = properties.currentSite();

        // ---------- 1. 时间：主区间、截到当前整点、粒度规则 ----------
        PromoTime.Range requested = PromoTime.range(query.getSlotFrom(), query.getSlotTo());
        LocalDateTime now = LocalDateTime.now(properties.currentZone()).truncatedTo(ChronoUnit.HOURS);
        PromoTime.Range range = requested;
        if (requested.to.isAfter(now) && now.isAfter(requested.from))
        {
            range = new PromoTime.Range(requested.from, now);
            notices.add(OverviewNoticeVO.info("SLOT_CLAMPED", "已截至最近完整小時",
                "統計區間終點調整為 " + now.format(PromoTime.SLOT) + "（當前小時尚未結束）"));
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
        PromoScope scope = PromoScope.resolve(channels.promotersOf(site), query.getAgents(), query.getChannels(),
            terminals.of(site), query.getTerminal());
        String dimension = dimension(query);

        // ---------- 4. 上下文：监控相关一律为 null ----------
        vo.setCurrency(properties.currentCurrency());
        vo.setTimezone(properties.currentTimezone());
        vo.setMonitoring(null);
        vo.setAnomalies(null);

        // ---------- 5. 指标 ----------
        List<PromoMetricCatalog.Metric> all = catalog.overviewMetrics();
        List<PromoMetricCatalog.Metric> selected = resolveMetrics(query, all, notices);

        if (!resolveBlocks(query, notices).contains(OverviewBlock.METRICS))
        {
            vo.setEmpty(true);
            return vo;
        }
        MetricsBlockVO block = new MetricsBlockVO();
        Map<String, MetricGroupConfig> groupConfigs = catalog.groups();
        List<MetricCardVO> items = new ArrayList<>();
        for (PromoMetricCatalog.Metric m : selected)
        {
            items.add(card(m, query, slot, range, compare, now, scope, dimension, groupConfigs));
        }
        block.setItems(items);
        block.setTotal(items.size());
        block.setCoreCount((int) items.stream().filter(c -> "CORE".equals(c.getEmphasis())).count());
        block.setGroups(groups(all, groupConfigs));
        vo.getBlocks().put(OverviewBlock.METRICS.name(), block);

        // ---------- 6. 数据未接入 ----------
        notices.add(OverviewNoticeVO.warn("PROMO_DATA_PENDING", "推廣數據尚未接入",
            "數據平台的推廣數據集還在確定中，指標暫無數值"));
        vo.setEmpty(true);
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

    /** 请求的指标：为空取默认；不认识的编码整个请求 4003；按 group 筛；最多 10 个 */
    private static List<PromoMetricCatalog.Metric> resolveMetrics(OverviewQuery query, List<PromoMetricCatalog.Metric> all,
        List<OverviewNoticeVO> notices)
    {
        Map<String, PromoMetricCatalog.Metric> byCode = new LinkedHashMap<>();
        all.forEach(m -> byCode.put(m.getCode(), m));
        List<PromoMetricCatalog.Metric> out = new ArrayList<>();
        if (query.getMetrics() == null || query.getMetrics().isEmpty())
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
        OverviewCompareVO compare, LocalDateTime now, PromoScope scope, String dimension, Map<String, MetricGroupConfig> groups)
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
        c.setState(na ? "NA" : "OK");
        // 数据未接入：值一律为 null
        c.setValue(null);
        c.setPrevValue(null);
        if (query.isIncludeSeries())
        {
            List<BigDecimal> empty = new ArrayList<>(Collections.nCopies(Math.max(0, slot.getPoints()), null));
            c.setSeries(empty);
            c.setPrevSeries(compare == null ? new ArrayList<>() : new ArrayList<>(empty));
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
        if (dimension != null && m.isAttributable())
        {
            c.setAttribution(attribution(m, dimension, scope));
        }
        return c;
    }

    /**
     * 消耗卡：本期含今天且不只今天时，两期都去掉今天、按同样天数比，返回给前端写「?」里的两段日期（MM-dd）。
     */
    static Map<String, Object> spendCompare(PromoTime.Range range, OverviewCompareVO compare, LocalDateTime now)
    {
        LocalDate today = now.toLocalDate();
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

    /** 变化归因：成员值待数据接入（rows 为空，total 为 null） */
    static Map<String, Object> attribution(PromoMetricCatalog.Metric m, String dimension, PromoScope scope)
    {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("dimension", dimension);
        String reason = null;
        if ("terminal".equals(dimension))
        {
            if (scope.isTerminalSelected())
            {
                reason = "TERMINAL_SELECTED";
            }
            else if (!m.getSpec().isByTerminal() || m.getSpec().isAndroidOnly())
            {
                reason = "NOT_BY_TERMINAL";
            }
        }
        a.put("supported", reason == null);
        a.put("reason", reason);
        if (reason == null)
        {
            Map<String, Object> total = new LinkedHashMap<>();
            total.put("value", null);
            total.put("prevValue", null);
            a.put("total", total);
            a.put("rows", new ArrayList<>());
        }
        return a;
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
