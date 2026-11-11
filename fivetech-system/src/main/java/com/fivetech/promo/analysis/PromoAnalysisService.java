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
import com.fivetech.configcenter.ConfigCodes;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.promo.analysis.query.ChannelTableQuery;
import com.fivetech.promo.analysis.query.ChannelTableQuery.PathItem;
import com.fivetech.promo.analysis.query.CohortQuery;
import com.fivetech.promo.analysis.query.FissionQuery;
import com.fivetech.promo.analysis.query.PromoFilter;
import com.fivetech.promo.analysis.query.Sort;
import com.fivetech.promo.analysis.market.MarketCube;
import com.fivetech.promo.analysis.market.MarketGateway;
import com.fivetech.promo.analysis.market.MarketValues;
import com.fivetech.promo.budget.PromoChannelDictionary;

/**
 * 推广分析 · {@code /dashboard/promo/*} 的取数接口（概览走运营总览接口，见 {@link PromoOverviewService}）。
 *
 * <p><b>数据未接入</b>：UDS 的推广数据集（promo_channel_hour / day、promo_cohort_day、promo_fission_day、promo_spend_day）还没定，
 * 现在各接口只做请求校验并返回完整结构：数值为 null、列表为空，顶层 {@code dataPending = true}。
 * 能从字典与时间算出来的部分照常返回（推广商 / 推广渠道 / 日期 / 终端成员、批次指标的到期天数 matured 与 PENDING）。</p>
 *
 * @author fivetech
 */
@Service
public class PromoAnalysisService
{
    /** 批次指标的天数（注册当天为第 0 日） */
    public static final List<Integer> OFFSETS = List.of(1, 3, 7, 15, 30);

    /** 留存与回收：注册日最早 120 天前、单次最多 60 天 */
    static final int COHORT_EARLIEST_DAYS = 120;

    static final int COHORT_MAX_DAYS = 60;

    /** 裂变层级可排序的列 */
    static final List<String> FISSION_COLUMNS = List.of("directCount", "newSub", "ftd", "depU", "wdU", "depAmt", "betAmt", "winLoss");

    static final Set<String> COHORT_METRICS = Set.of("regRet", "ltv", "roas");

    private final DashboardProperties properties;

    private final PromoChannelDictionary channels;

    private final PromoTerminalDictionary terminals;

    private final PromoMetricCatalog catalog;

    private final MarketGateway market;

    public PromoAnalysisService(DashboardProperties properties, PromoChannelDictionary channels, PromoTerminalDictionary terminals,
        PromoMetricCatalog catalog, MarketGateway market)
    {
        this.properties = properties;
        this.channels = channels;
        this.terminals = terminals;
        this.catalog = catalog;
        this.market = market;
    }

    boolean marketOn()
    {
        return market != null && market.isEnabled();
    }

    /**
     * 投放日报：按日取全站（不分推广渠道）的市场数据，只取 codes 用到的字段。没开市场数据返回 null；取数失败照常抛出，由调用方决定。
     */
    MarketCube dailyCube(LocalDate from, LocalDate to, java.util.Collection<String> codes)
    {
        if (!marketOn())
        {
            return null;
        }
        return market.cube(from.atStartOfDay(), to.plusDays(1).atStartOfDay(), com.fivetech.dashboard.enums.Granularity.DAY,
            codes, false);
    }

    /** UDS 取数失败统一成 5001 */
    static ServiceException dataFailed(RuntimeException e)
    {
        return new ServiceException("資料平台取數失敗：" + e.getMessage(), PromoErrors.DATA_FAILED);
    }

    static Map<String, Object> notice(String code, String message)
    {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("code", code);
        n.put("message", message);
        return n;
    }

    // ------------------------------------------------------------------ 公共

    String site()
    {
        return properties.currentSite();
    }

    /** 当前时间向右取整的整点（UDS 实时数据：16:50 → 17:00），用作「今天截至」 */
    LocalDateTime nowHour()
    {
        return com.fivetech.dashboard.gateway.uds.UdsRealtime.ceilHour(LocalDateTime.now(properties.currentZone()));
    }

    LocalDate today()
    {
        return LocalDate.now(properties.currentZone());
    }

    PromoScope scope(List<String> agents, List<String> chs, String terminal)
    {
        String site = site();
        return PromoScope.resolve(channels.promotersOf(site), agents, chs, terminals.of(site), terminal);
    }

    PromoTerminalDictionary.Config terminalConfig()
    {
        return terminals.of(site());
    }

    /** 公共返回字段：slot、compare、asOf、timezone、currency、dataPending */
    Map<String, Object> header(Map<String, Object> slot, Map<String, Object> compare)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        if (slot != null)
        {
            m.put("slot", slot);
        }
        m.put("compare", compare);
        m.put("asOf", null);
        m.put("timezone", properties.currentTimezone());
        m.put("currency", currency());
        m.put("dataPending", true);
        m.put("notices", new ArrayList<Map<String, Object>>());
        return m;
    }

    @SuppressWarnings("unchecked")
    static void addNotice(Map<String, Object> out, String code, String message)
    {
        ((List<Map<String, Object>>) out.get("notices")).add(notice(code, message));
    }

    Map<String, Object> currency()
    {
        Map<String, Object> c = new LinkedHashMap<>();
        String site = site();
        c.put("code", properties.currentCurrency());
        c.put("symbol", properties.siteCurrencySymbol(site));
        return c;
    }

    // ------------------------------------------------------------------ 7.1 options

    public Map<String, Object> options()
    {
        String site = site();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agents", agents(channels.promotersOf(site), true));
        List<Map<String, Object>> ts = new ArrayList<>();
        for (PromoTerminalDictionary.Terminal t : terminals.of(site).getTerminals())
        {
            ts.add(codeName(t.getCode(), t.getName()));
        }
        out.put("terminals", ts);
        // 站点数据起始日：站点管理里设置的，没设置用 dashboard.launch-date
        String launch = properties.siteDataStartDate(site);
        out.put("siteLaunchDate", launch);
        out.put("dataStart", launch);
        out.put("asOf", null);
        out.put("timezone", properties.currentTimezone());
        out.put("currency", currency());
        // 当前站点是否实时站点（站点管理里设置），与指标汇总 summary 的 realtime 相同
        out.put("realtime", properties.isRealtime(site));
        List<Map<String, Object>> metrics = new ArrayList<>();
        for (PromoMetricCatalog.Metric m : catalog.overviewMetrics())
        {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("code", m.getCode());
            x.put("name", m.getName());
            x.put("group", m.getGroup());
            x.put("format", m.getFormat());
            x.put("decimals", m.getDecimals());
            x.put("direction", m.getDirection());
            x.put("definition", m.getSpec().getDefinition());
            x.put("hourly", m.getSpec().isHourly());
            x.put("byTerminal", m.getSpec().isByTerminal());
            x.put("attributable", m.isAttributable());
            x.put("isDefault", m.isDefault());
            metrics.add(x);
        }
        out.put("metrics", metrics);
        List<Map<String, Object>> groups = new ArrayList<>();
        PromoMetricCatalog.COLUMN_GROUPS.forEach((k, v) -> groups.add(codeName(k, v)));
        out.put("columnGroups", groups);
        List<Map<String, Object>> cols = new ArrayList<>();
        for (PromoMetricCatalog.Column c : PromoMetricCatalog.COLUMNS)
        {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("code", c.getCode());
            x.put("name", c.getName());
            x.put("group", c.getGroup());
            x.put("format", c.getFormat());
            x.put("drillable", c.isDrillable());
            cols.add(x);
        }
        out.put("columns", cols);
        out.put("dataPending", true);
        return out;
    }

    /** 渠道字典 → agents[]（withDetail：带 media / entryType） */
    static List<Map<String, Object>> agents(List<PromoChannelDictionary.Promoter> dict, boolean withDetail)
    {
        List<Map<String, Object>> agents = new ArrayList<>();
        for (PromoChannelDictionary.Promoter p : dict)
        {
            Map<String, Object> a = codeName(p.getId(), p.getName());
            List<Map<String, Object>> chs = new ArrayList<>();
            for (PromoChannelDictionary.Channel c : p.getChannels())
            {
                Map<String, Object> x = codeName(c.getId(), c.getName());
                if (withDetail)
                {
                    x.put("media", c.getMedia() == null ? PromoChannelDictionary.MEDIA_UNKNOWN : c.getMedia());
                }
                x.put("status", c.getStatus());
                if (withDetail)
                {
                    x.put("entryType", c.getEntryType());
                }
                chs.add(x);
            }
            a.put("channels", chs);
            agents.add(a);
        }
        return agents;
    }

    static Map<String, Object> codeName(String code, String name)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", name);
        return m;
    }

    // ------------------------------------------------------------------ 5.1 渠道表现

    /** 列编码校验：不能为空、不认识 4003 */
    static List<PromoMetricCatalog.Column> columns(List<String> codes)
    {
        if (codes == null || codes.isEmpty())
        {
            throw new ServiceException("columns 不能為空", PromoErrors.UNKNOWN_CODE);
        }
        List<PromoMetricCatalog.Column> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String code : codes)
        {
            PromoMetricCatalog.Column c = PromoMetricCatalog.column(code);
            if (c == null)
            {
                throw new ServiceException("不認識的列：" + code, PromoErrors.UNKNOWN_CODE);
            }
            if (seen.add(c.getCode()))
            {
                out.add(c);
            }
        }
        return out;
    }

    ChannelTree tree(ChannelTableQuery q, PromoTime.Range range)
    {
        PromoScope scope = scope(q.getAgents(), q.getChannels(), null);
        return ChannelTree.of(q.getRows(), scope, terminalConfig(), range, today().minusDays(1));
    }

    /**
     * 给行维度树装上按日的市场数据（列里有能取到的指标、且没有渠道筛选时才取）。
     *
     * @return 取到的数据；没取返回 null
     */
    MarketCube attachData(ChannelTree tree, ChannelTableQuery q, PromoTime.Range range, List<PromoMetricCatalog.Column> cols)
    {
        boolean channelPending = !q.getAgents().isEmpty() || !q.getChannels().isEmpty();
        boolean need = cols.stream().anyMatch(c -> MarketValues.AVAILABLE.contains(c.getCode()));
        MarketCube cube = null;
        if (!channelPending && need && marketOn())
        {
            try
            {
                // 只取所选列用到的字段；行维度有终端时才要带终端前缀的宽表字段
                List<String> codes = new ArrayList<>();
                cols.forEach(c -> codes.add(c.getCode()));
                cube = market.cube(range.from, range.to, com.fivetech.dashboard.enums.Granularity.DAY, codes,
                    tree.dims().contains(ChannelTree.TERMINAL));
            }
            catch (RuntimeException e)
            {
                throw dataFailed(e);
            }
        }
        tree.setData(cube, channelPending);
        return cube;
    }

    /** 消耗 / 预算（可选，setter 注入，避免改动构造函数） */
    private PromoSpend spend;

    /** 按推广渠道的行为数据（成本类指标的有效格子）；数据平台提供推广渠道维度（U1）前为 NONE */
    PromoCost.ChannelBehavior behavior = PromoCost.ChannelBehavior.NONE;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSpend(PromoSpend spend)
    {
        this.spend = spend;
    }

    PromoSpend spend()
    {
        return spend;
    }

    /** 列里有消耗、预算或成本类时，给行维度树装上整个区间的消耗与预算 */
    void attachSpend(ChannelTree tree, PromoTime.Range range, List<PromoMetricCatalog.Column> cols)
    {
        boolean need = cols.stream().anyMatch(c -> PromoSpend.CODES.contains(c.getCode()) || PromoCost.CODES.contains(c.getCode()));
        tree.setSpend(need && spend != null ? spend.load(range.firstDay(), range.lastDay()) : null, behavior);
    }

    /** 渠道表现按天：主区间取整天（起点当天 00:00 到终点所在日的次日 00:00） */
    static PromoTime.Range wholeDays(PromoTime.Range r)
    {
        return new PromoTime.Range(r.firstDay().atStartOfDay(), r.lastDay().plusDays(1).atStartOfDay());
    }

    /** 个人视图（可选）：渠道表现不传 columns / rows 时按「我在本站」的表格列、行维度 */
    private com.fivetech.dashboard.view.UserViewService views;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setViews(com.fivetech.dashboard.view.UserViewService views)
    {
        this.views = views;
    }

    /** 渠道表现 / 导出：columns、rows 为空时用个人视图补上（取不到就保持为空，照常 4003） */
    void applyView(ChannelTableQuery q)
    {
        if (views == null)
        {
            return;
        }
        if (q.getColumns().isEmpty())
        {
            List<String> cols = views.itemsOf(PromoViewItemSource.COLUMNS);
            if (cols != null)
            {
                q.setColumns(new ArrayList<>(cols));
            }
        }
        if (q.getRows().isEmpty())
        {
            List<String> rows = views.itemsOf(PromoViewItemSource.ROWS);
            if (rows != null)
            {
                q.setRows(new ArrayList<>(rows));
            }
        }
    }

    public Map<String, Object> channelTable(ChannelTableQuery q)
    {
        applyView(q);
        PromoTime.Range range = wholeDays(PromoTime.range(q.getSlotFrom(), q.getSlotTo()));
        List<PromoMetricCatalog.Column> cols = columns(q.getColumns());
        ChannelTree tree = tree(q, range);
        tree.checkParent(q.getParent());
        sortOf(q.getSort(), "reg");
        MarketCube cube = attachData(tree, q, range, cols);
        attachSpend(tree, range, cols);

        int level = q.getParent().size();
        List<PathItem> parent = new ArrayList<>();
        for (int i = 0; i < level; i++)
        {
            parent.add(new PathItem(tree.dims().get(i), q.getParent().get(i).getKey().trim()));
        }
        Map<String, Object> out = header(PromoTime.slot(range, null), null);
        if (cube != null)
        {
            out.put("dataPending", false);
            if (cube.getWatermark() != null)
            {
                out.put("asOf", cube.getWatermark().format(PromoTime.SLOT));
            }
        }
        if (!q.getAgents().isEmpty() || !q.getChannels().isEmpty())
        {
            addNotice(out, "PROMO_CHANNEL_PENDING", "數據平台還沒有推廣渠道映射，暫不能按推廣商 / 推廣渠道篩選");
        }
        // 本次请求的列（按请求顺序）：drillable = 这个指标支持下钻；单元格里的 drillable 再看这一格有没有值
        List<Map<String, Object>> colMeta = new ArrayList<>();
        for (PromoMetricCatalog.Column c : cols)
        {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("code", c.getCode());
            x.put("name", c.getName());
            x.put("format", c.getFormat());
            x.put("drillable", c.isDrillable());
            colMeta.add(x);
        }
        out.put("columns", colMeta);
        out.put("level", level + 1);
        String dim = tree.dims().get(level);
        out.put("dim", dim);
        if (level == 0)
        {
            Map<String, Object> total = new LinkedHashMap<>();
            total.put("cells", tree.cells(cols, List.of()));
            out.put("total", total);
        }
        else
        {
            out.put("total", null);
        }
        List<ChannelTree.Member> members = tree.members(level, parent);
        int size = q.getPageSize() == null ? 1000 : Math.max(1, Math.min(1000, q.getPageSize()));
        int num = q.getPageNum() == null ? 1 : Math.max(1, q.getPageNum());
        int fromIdx = Math.min(members.size(), (num - 1) * size);
        int toIdx = Math.min(members.size(), fromIdx + size);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ChannelTree.Member m : members.subList(fromIdx, toIdx))
        {
            List<PathItem> path = new ArrayList<>(parent);
            path.add(new PathItem(dim, m.key));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", m.key);
            row.put("name", m.name);
            if (m.agent != null)
            {
                row.put("agent", m.agent);
            }
            row.put("path", path);
            row.put("expandable", level + 1 < tree.dims().size());
            row.put("disabled", m.disabled);
            row.put("cells", tree.cells(cols, path));
            rows.add(row);
        }
        out.put("rows", rows);
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("num", num);
        page.put("size", size);
        page.put("total", members.size());
        out.put("page", page);
        return out;
    }

    /** 排序：列编码必须认识（否则 4003），dir 只能 asc / desc（其他按 desc） */
    static String[] sortOf(Sort sort, String defColumn)
    {
        String col = sort == null || sort.getColumn() == null || sort.getColumn().isBlank() ? defColumn : sort.getColumn().trim();
        if (PromoMetricCatalog.column(col) == null)
        {
            throw new ServiceException("不認識的排序列：" + col, PromoErrors.UNKNOWN_CODE);
        }
        String dir = sort != null && "asc".equalsIgnoreCase(sort.getDir()) ? "asc" : "desc";
        return new String[] { col, dir };
    }

    // ------------------------------------------------------------------ 6.1 获客漏斗

    public Map<String, Object> funnel(PromoFilter q)
    {
        PromoTime.Range range = PromoTime.range(q.getSlotFrom(), q.getSlotTo());
        PromoScope scope = scope(q.getAgents(), q.getChannels(), null);
        Map<String, Object> compare = PromoTime.compare(q.getCompareType(), q.getCompareFrom(), q.getCompareTo(), true, range, nowHour());
        Map<String, Object> out = header(PromoTime.slot(range, null), compare);

        // 取数：注册、首存、激活来自小时宽表；流失人数来自会员快照。UV 两项没有来源 / 口径不同
        MarketCube cube = null;
        MarketCube prev = null;
        Map<String, Long> lost = new LinkedHashMap<>();
        PromoTerminalDictionary.Config cfg = terminalConfig();
        if (!scope.isAll())
        {
            addNotice(out, "PROMO_CHANNEL_PENDING", "數據平台還沒有推廣渠道映射，暫不能按推廣商 / 推廣渠道篩選");
        }
        else if (marketOn())
        {
            try
            {
                // 漏斗只用到注册、首存、激活与各终端漏斗步骤；各终端的注册、占比要按终端拆
                java.util.Set<String> codes = new java.util.LinkedHashSet<>(List.of("reg", "ftd", "act"));
                cfg.getTerminals().forEach(t -> t.getSteps().forEach(s -> codes.add(s.getCode())));
                cube = market.cube(range.from, range.to, com.fivetech.dashboard.enums.Granularity.DAY, codes, true);
                if (compare != null)
                {
                    prev = market.cube(LocalDateTime.parse((String) compare.get("from"), PromoTime.SLOT),
                        LocalDateTime.parse((String) compare.get("to"), PromoTime.SLOT), com.fivetech.dashboard.enums.Granularity.DAY,
                        codes, true);
                }
                List<String> keys = new ArrayList<>();
                List<List<Map<String, Object>>> conds = new ArrayList<>();
                for (PromoTerminalDictionary.Terminal t : cfg.getTerminals())
                {
                    String key = MarketValues.terminalKey(t.getCode());
                    if (key == null || MarketCube.ALL.equals(key) || t.getSteps().isEmpty())
                    {
                        continue;
                    }
                    keys.add(t.getCode());
                    List<Map<String, Object>> c = new ArrayList<>();
                    c.add(MarketGateway.cond("register_time", "GTE", MarketGateway.datetime(range.from)));
                    c.add(MarketGateway.cond("register_time", "LT", MarketGateway.datetime(range.to)));
                    c.add(MarketGateway.cond("is_ftd", "EQ", 0));
                    c.add(MarketGateway.cond("terminal", "EQ", MarketValues.udsTerminal(key)));
                    conds.add(c);
                }
                List<Long> counts = market.memberCounts(conds);
                for (int i = 0; i < keys.size(); i++)
                {
                    lost.put(keys.get(i), counts.get(i));
                }
                out.put("dataPending", false);
                if (cube.getWatermark() != null)
                {
                    out.put("asOf", cube.getWatermark().format(PromoTime.SLOT));
                }
            }
            catch (RuntimeException e)
            {
                throw dataFailed(e);
            }
        }
        Map<String, String[]> meta = funnelMeta();
        List<Map<String, Object>> overall = new ArrayList<>();
        for (String code : List.of("lpUV", "hUV", "reg", "ftd"))
        {
            boolean ok = MarketValues.AVAILABLE.contains(code);
            BigDecimal v = ok ? MarketValues.value(code, cube, MarketCube.ALL, null) : null;
            BigDecimal p = ok ? MarketValues.value(code, prev, MarketCube.ALL, null) : null;
            Map<String, Object> x = funnelItem(code, meta, v);
            if (compare != null)
            {
                x.put("prev", p);
                x.put("deltaPct", MarketValues.delta(code, v, p)[0]);
            }
            x.put("state", ok ? "OK" : "NO_SOURCE");
            x.put("note", ok ? null : MarketValues.noSourceNote(code));
            overall.add(x);
        }
        out.put("overall", overall);

        List<Map<String, Object>> share = new ArrayList<>();
        BigDecimal regAll = MarketValues.value("reg", cube, MarketCube.ALL, null);
        List<String[]> members = new ArrayList<>();
        cfg.getTerminals().forEach(t -> members.add(new String[] { t.getCode(), MarketValues.terminalKey(t.getCode()) }));
        members.add(new String[] { PromoTerminalDictionary.UNIDENTIFIED, "unknown" });
        if (cube != null)
        {
            for (String[] t : members)
            {
                if (t[1] == null || MarketCube.ALL.equals(t[1]))
                {
                    continue;
                }
                BigDecimal r = MarketValues.value("reg", cube, t[1], null);
                if (r == null || (PromoTerminalDictionary.UNIDENTIFIED.equals(t[0]) && r.signum() == 0))
                {
                    continue;
                }
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("terminal", t[0]);
                x.put("pct", MarketValues.pct(r, regAll));
                share.add(x);
            }
        }
        out.put("regShare", share);

        List<Map<String, Object>> cards = new ArrayList<>();
        for (PromoTerminalDictionary.Terminal t : cfg.getTerminals())
        {
            if (t.getSteps().isEmpty())
            {
                continue;
            }
            String key = MarketValues.terminalKey(t.getCode());
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("terminal", t.getCode());
            card.put("terminalName", t.getName());
            card.put("reg", funnelItem("reg", meta, MarketValues.value("reg", cube, key, null)));
            card.put("viaLandingPage", null);
            Long l = lost.get(t.getCode());
            card.put("lost", funnelItem(LOST, meta, l == null ? null : BigDecimal.valueOf(l)));
            card.put("steps", steps(t.getSteps(), cube, key, meta));
            cards.add(card);
        }
        out.put("terminals", cards);
        return out;
    }

    /** 「流失」的编码（不是指标管理里的指标，名称与说明写在代码里） */
    static final String LOST = "lost";

    /** 获客漏斗里支持下钻名单的：注册、首存、流失 */
    static final java.util.Set<String> FUNNEL_DRILLABLE = java.util.Set.of("reg", "ftd", LOST);

    /**
     * 漏斗里各项的名称与说明：名称取指标管理里推广分析指标的名称（页面配置），说明取口径说明；
     * 读不到配置时按代码里的名称。
     */
    Map<String, String[]> funnelMeta()
    {
        Map<String, String[]> m = new LinkedHashMap<>();
        for (String code : List.of("lpUV", "hUV", "dl", "act", "reg", "ftd"))
        {
            PromoMetricCatalog.Column col = PromoMetricCatalog.column(code);
            PromoMetricCatalog.Spec spec = PromoMetricCatalog.spec(code);
            m.put(code, new String[] { col == null ? code : col.getName(), spec == null ? null : spec.getDefinition() });
        }
        for (PromoMetricCatalog.Metric x : catalog.overviewMetrics())
        {
            String[] v = m.get(x.getCode());
            if (v != null && x.getName() != null && !x.getName().isBlank())
            {
                v[0] = x.getName();
            }
        }
        m.put(LOST, new String[] { "流失", "所選時間內註冊、截至目前還沒有首存的人數（按這批註冊用戶直接統計，不是註冊減首存）" });
        return m;
    }

    /** 漏斗里的一项：code、name、description、value、drillable */
    static Map<String, Object> funnelItem(String code, Map<String, String[]> meta, BigDecimal value)
    {
        String[] nm = meta.getOrDefault(code, new String[] { code, null });
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("code", code);
        x.put("name", nm[0]);
        x.put("description", nm[1]);
        x.put("value", value);
        x.put("drillable", FUNNEL_DRILLABLE.contains(code));
        return x;
    }

    /**
     * 漏斗步骤。rate = 本步 ÷ 前面最近一个有数值的步骤（rateFrom 为该步骤的编码）；入口步、非严格步、
     * 前面没有可比的步骤时为 null。没有来源的步骤 state = NO_SOURCE，不参与比较。
     */
    static List<Map<String, Object>> steps(List<PromoTerminalDictionary.Step> defs, MarketCube cube, String terminalKey,
        Map<String, String[]> meta)
    {
        List<Map<String, Object>> steps = new ArrayList<>();
        BigDecimal base = null;
        String baseCode = null;
        for (PromoTerminalDictionary.Step s : defs)
        {
            boolean ok = MarketValues.AVAILABLE.contains(s.getCode());
            BigDecimal v = ok ? MarketValues.value(s.getCode(), cube, terminalKey, null) : null;
            Map<String, Object> x = funnelItem(s.getCode(), meta, v);
            x.put("role", s.getRole());
            x.put("strict", s.isStrict());
            boolean rated = s.isStrict() && !"ENTRY".equals(s.getRole()) && base != null;
            x.put("rate", rated ? MarketValues.pct(v, base) : null);
            x.put("rateFrom", rated ? baseCode : null);
            x.put("state", ok ? "OK" : "NO_SOURCE");
            x.put("note", ok ? null : MarketValues.noSourceNote(s.getCode()));
            steps.add(x);
            if (v != null)
            {
                base = v;
                baseCode = s.getCode();
            }
        }
        return steps;
    }

    // ------------------------------------------------------------------ 6.3–6.5 留存与回收

    /** 注册日范围 [regFrom, regTo)：最早 120 天前、最晚到昨天、最多 60 天；不合法 4001 */
    LocalDate[] cohortRange(CohortQuery q)
    {
        LocalDate from = PromoTime.parseDay(q.getRegFrom(), "regFrom", PromoErrors.BAD_TIME);
        LocalDate to = PromoTime.parseDay(q.getRegTo(), "regTo", PromoErrors.BAD_TIME);
        LocalDate today = today();
        if (!to.isAfter(from))
        {
            throw new ServiceException("regFrom 必須早於 regTo（regTo 不含）", PromoErrors.BAD_TIME);
        }
        if (from.isBefore(today.minusDays(COHORT_EARLIEST_DAYS)))
        {
            throw new ServiceException("註冊日最早 " + COHORT_EARLIEST_DAYS + " 天前", PromoErrors.BAD_TIME);
        }
        if (to.isAfter(today))
        {
            throw new ServiceException("註冊日最晚到昨天（regTo 不含，最大為今天）", PromoErrors.BAD_TIME);
        }
        if (ChronoUnit.DAYS.between(from, to) > COHORT_MAX_DAYS)
        {
            throw new ServiceException("單次最多 " + COHORT_MAX_DAYS + " 天", PromoErrors.BAD_TIME);
        }
        scope(q.getAgents(), q.getChannels(), null);
        return new LocalDate[] { from, to.minusDays(1) };
    }

    /** 第 n 日已结束的注册日：[from, min(to, 昨天 − n)]；没有返回 null */
    LocalDate[] matured(LocalDate from, LocalDate to, int n)
    {
        LocalDate last = today().minusDays(1).minusDays(n);
        LocalDate end = to.isBefore(last) ? to : last;
        return end.isBefore(from) ? null : new LocalDate[] { from, end };
    }

    public Map<String, Object> cohortSummary(CohortQuery q)
    {
        LocalDate[] r = cohortRange(q);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", today().minusDays(1).toString());
        out.put("timezone", properties.currentTimezone());
        out.put("currency", currency());
        out.put("dataPending", true);
        List<Map<String, Object>> cards = new ArrayList<>();
        String[][] defs = { { "regRet1", "1" }, { "regRet7", "7" }, { "ltv7", "7" }, { "roas7", "7" } };
        for (String[] d : defs)
        {
            LocalDate[] m = matured(r[0], r[1], Integer.parseInt(d[1]));
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("code", d[0]);
            card.put("value", null);
            // 注册分群数据集未接入（UDS dashboard_market_cohort_v1 安全禁用）：到期的也没有值
            card.put("state", m == null ? "PENDING" : "NO_SOURCE");
            Map<String, Object> days = new LinkedHashMap<>();
            days.put("from", m == null ? null : m[0].toString());
            days.put("to", m == null ? null : m[1].toString());
            days.put("m", m == null ? 0 : ChronoUnit.DAYS.between(m[0], m[1]) + 1);
            card.put("regDays", days);
            cards.add(card);
        }
        out.put("cards", cards);
        return out;
    }

    private static String cohortMetric(CohortQuery q)
    {
        String m = q.getMetric() == null ? "" : q.getMetric().trim();
        if (!COHORT_METRICS.contains(m))
        {
            throw new ServiceException("metric 只能是 regRet / ltv / roas", PromoErrors.UNKNOWN_CODE);
        }
        return m;
    }

    public Map<String, Object> cohortCurves(CohortQuery q)
    {
        LocalDate[] r = cohortRange(q);
        cohortMetric(q);
        if (q.getCompareItems().size() > 4)
        {
            throw new ServiceException("對比項最多 4 項", PromoErrors.UNKNOWN_CODE);
        }
        String site = site();
        List<PromoChannelDictionary.Promoter> dict = channels.promotersOf(site);
        List<Map<String, Object>> series = new ArrayList<>();
        series.add(curve("ALL", "全部", r));
        for (CohortQuery.CompareItem item : q.getCompareItems())
        {
            String type = item == null || item.getType() == null ? "" : item.getType().trim().toLowerCase(Locale.ROOT);
            String code = item == null || item.getCode() == null ? "" : item.getCode().trim();
            String name = null;
            if ("agent".equals(type))
            {
                name = dict.stream().filter(p -> ConfigCodes.idKey(p.getId()).equals(ConfigCodes.idKey(code)))
                    .map(PromoChannelDictionary.Promoter::getName).findFirst().orElse(null);
            }
            else if ("channel".equals(type))
            {
                name = dict.stream().flatMap(p -> p.getChannels().stream())
                    .filter(c -> ConfigCodes.idKey(c.getId()).equals(ConfigCodes.idKey(code)))
                    .map(PromoChannelDictionary.Channel::getName).findFirst().orElse(null);
            }
            else
            {
                throw new ServiceException("對比項 type 只能是 agent / channel", PromoErrors.UNKNOWN_CODE);
            }
            if (name == null)
            {
                throw new ServiceException("不認識的" + ("agent".equals(type) ? "推廣商" : "推廣渠道") + "：" + code, PromoErrors.UNKNOWN_FILTER);
            }
            series.add(curve(type + ":" + code, name, r));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", today().minusDays(1).toString());
        out.put("currency", currency());
        out.put("dataPending", true);
        out.put("offsets", OFFSETS);
        out.put("series", series);
        return out;
    }

    private Map<String, Object> curve(String key, String name, LocalDate[] r)
    {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("key", key);
        s.put("name", name);
        s.put("values", new ArrayList<>(Collections.nCopies(OFFSETS.size(), null)));
        List<String> marks = new ArrayList<>();
        for (int n : OFFSETS)
        {
            marks.add(matured(r[0], r[1], n) == null ? "PENDING" : "NO_SOURCE");
        }
        s.put("marks", marks);
        return s;
    }

    public Map<String, Object> cohortBatches(CohortQuery q)
    {
        LocalDate[] r = cohortRange(q);
        String metric = cohortMetric(q);
        LocalDate yesterday = today().minusDays(1);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", yesterday.toString());
        out.put("currency", currency());
        out.put("dataPending", true);
        out.put("offsets", OFFSETS);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("base", null);
        summary.put("values", new ArrayList<>(Collections.nCopies(OFFSETS.size(), null)));
        out.put("summary", summary);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LocalDate d = r[1]; !d.isBefore(r[0]); d = d.minusDays(1))
        {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("regDate", d.toString());
            row.put("base", null);
            row.put("values", new ArrayList<>(Collections.nCopies(OFFSETS.size(), null)));
            if ("regRet".equals(metric))
            {
                row.put("counts", new ArrayList<>(Collections.nCopies(OFFSETS.size(), null)));
            }
            List<String> states = new ArrayList<>();
            for (int n : OFFSETS)
            {
                states.add(d.plusDays(n).isAfter(yesterday) ? "PENDING" : "NO_SOURCE");
            }
            row.put("states", states);
            rows.add(row);
        }
        out.put("rows", rows);
        return out;
    }

    // ------------------------------------------------------------------ 7.2 裂变层级

    static String[] fissionSort(Sort sort)
    {
        String col = sort == null || sort.getColumn() == null || sort.getColumn().isBlank() ? "directCount" : sort.getColumn().trim();
        if (!FISSION_COLUMNS.contains(col))
        {
            throw new ServiceException("不認識的排序列：" + col, PromoErrors.UNKNOWN_CODE);
        }
        return new String[] { col, sort != null && "asc".equalsIgnoreCase(sort.getDir()) ? "asc" : "desc" };
    }

    static Map<String, Object> fissionStats()
    {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String c : List.of("newSub", "ftd", "depU", "wdU", "depAmt", "betAmt", "winLoss"))
        {
            m.put(c, null);
        }
        return m;
    }

    /** 进入某用户后的数据：该用户、他的直属下级（全部，已排序）、合计 */
    static final class Fission
    {
        Map<String, Object> current;
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> total;
        List<Map<String, Object>> notices = new ArrayList<>();
        boolean loaded;
    }

    /**
     * 裂变层级：进入某用户（parentId）时，按 UDS 会员快照的 agent_login（会员当前上级）列出直属下级，
     * 每人的新增下级、首存、存款人数（按日累加）、存款总额、投注总额取小时数据集按 agent_login 汇总，直属下级人数取快照计数。
     * 顶层一级代理清单、层级、提款人数、会员输赢、推广商 / 推广渠道待数据平台补源。
     */
    Fission fission(FissionQuery q, PromoTime.Range range)
    {
        Fission f = new Fission();
        f.total = fissionStats();
        f.total.put("directCount", null);
        boolean top = q.getParentId() == null || q.getParentId().isBlank();
        if (top)
        {
            f.notices.add(notice("FISSION_TOP_PENDING", "一級代理清單、層級與裂變註冊待數據平台提供關係閉包與渠道映射"));
            return f;
        }
        String id = q.getParentId().trim();
        if (!marketOn())
        {
            f.current = currentStub(id, null);
            return f;
        }
        try
        {
            List<String> dims = List.of("player_id", "member_account", "agent_login");
            List<com.fivetech.dashboard.gateway.uds.UdsRow> me = market.members(
                List.of(MarketGateway.cond("player_id", "EQ", id.matches("\\d{1,18}") ? (Object) Long.valueOf(id) : id)), dims, List.of(), 1, 0);
            if (me.isEmpty())
            {
                me = market.members(List.of(MarketGateway.cond("member_account", "EQ", id)), dims, List.of(), 1, 0);
            }
            if (me.isEmpty())
            {
                f.current = null;
                f.notices.add(notice("NOT_FOUND", "找不到這個用戶：" + id));
                f.loaded = true;
                return f;
            }
            String account = me.get(0).str("member_account");
            String userId = me.get(0).str("player_id");
            int max = Math.max(1, Math.min(1000, market.maxChildren()));
            List<com.fivetech.dashboard.gateway.uds.UdsRow> children = market.members(
                List.of(MarketGateway.cond("agent_login", "EQ", account)), List.of("player_id", "member_account", "register_time"),
                List.of(MarketGateway.order("register_time", "ASC")), max, 0);
            if (children.size() >= max)
            {
                f.notices.add(notice("CHILDREN_TRUNCATED", "直屬下級超過 " + max + " 人，只列出最早註冊的 " + max + " 人"));
            }
            List<String> accounts = new ArrayList<>();
            accounts.add(account);
            children.forEach(c -> accounts.add(c.str("member_account")));
            Map<String, Map<String, BigDecimal>> stats = market.byAgent(range.from, range.to, accounts);
            Map<String, Long> direct = market.directCounts(accounts);

            Map<String, BigDecimal> mine = stats.getOrDefault(account, Map.of());
            f.current = currentStub(userId, account);
            f.current.put("directCount", direct.getOrDefault(account, 0L));
            f.current.put("newSub", mine.getOrDefault("reg", BigDecimal.ZERO));
            f.current.put("ftd", mine.getOrDefault("ftd_players", BigDecimal.ZERO));
            f.current.put("betAmt", mine.getOrDefault("bet_amount", BigDecimal.ZERO));

            for (com.fivetech.dashboard.gateway.uds.UdsRow c : children)
            {
                String acc = c.str("member_account");
                Map<String, BigDecimal> st = stats.getOrDefault(acc, Map.of());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("userId", c.str("player_id"));
                row.put("account", acc);
                row.put("level", null);
                row.put("agent", null);
                row.put("channel", null);
                row.put("directCount", direct.getOrDefault(acc, 0L));
                row.put("newSub", st.getOrDefault("reg", BigDecimal.ZERO));
                row.put("ftd", st.getOrDefault("ftd_players", BigDecimal.ZERO));
                row.put("depU", st.getOrDefault("deposit_users", BigDecimal.ZERO));
                row.put("wdU", null);
                row.put("depAmt", st.getOrDefault("deposit_amount", BigDecimal.ZERO));
                row.put("betAmt", st.getOrDefault("bet_amount", BigDecimal.ZERO));
                row.put("winLoss", null);
                f.rows.add(row);
            }
            String[] sort = fissionSort(q.getSort());
            java.util.Comparator<Map<String, Object>> cmp = java.util.Comparator.comparing(
                (Map<String, Object> r) -> num(r.get(sort[0])), java.util.Comparator.nullsLast(
                    "asc".equals(sort[1]) ? java.util.Comparator.<BigDecimal>naturalOrder() : java.util.Comparator.<BigDecimal>reverseOrder()))
                .thenComparing(r -> num(r.get("newSub")), java.util.Comparator.nullsLast(java.util.Comparator.<BigDecimal>reverseOrder()))
                .thenComparing(r -> String.valueOf(r.get("account")));
            f.rows.sort(cmp);
            long directSum = 0;
            for (Map<String, Object> r : f.rows)
            {
                directSum += ((Number) r.get("directCount")).longValue();
                for (String k : List.of("newSub", "ftd", "depU", "depAmt", "betAmt"))
                {
                    BigDecimal v = num(r.get(k));
                    BigDecimal t = num(f.total.get(k));
                    f.total.put(k, v == null ? t : t == null ? v : t.add(v));
                }
            }
            f.total.put("directCount", directSum);
            f.notices.add(notice("FISSION_PARTIAL", "層級、推廣商 / 推廣渠道、提款人數、會員輸贏待數據平台補源；上級關係按會員當前上級"));
            f.loaded = true;
            return f;
        }
        catch (RuntimeException e)
        {
            throw dataFailed(e);
        }
    }

    private static BigDecimal num(Object v)
    {
        if (v == null)
        {
            return null;
        }
        return v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
    }

    private static Map<String, Object> currentStub(String userId, String account)
    {
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("userId", userId);
        current.put("account", account);
        current.put("level", null);
        current.put("agent", null);
        current.put("channel", null);
        current.put("directCount", null);
        current.put("newSub", null);
        current.put("ftd", null);
        current.put("betAmt", null);
        return current;
    }

    public Map<String, Object> fissionAgents(FissionQuery q)
    {
        PromoTime.Range range = PromoTime.range(q.getSlotFrom(), q.getSlotTo());
        scope(q.getAgents(), q.getChannels(), null);
        fissionSort(q.getSort());
        boolean top = q.getParentId() == null || q.getParentId().isBlank();
        Fission f = fission(q, range);
        Map<String, Object> out = header(PromoTime.slot(range, null), null);
        out.put("dataPending", !f.loaded);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> notices = (List<Map<String, Object>>) out.get("notices");
        notices.addAll(f.notices);
        if (top)
        {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("fissionReg", null);
            summary.put("fissionFtd", null);
            summary.put("activeAgents", null);
            summary.put("directShare", null);
            out.put("summary", summary);
        }
        else
        {
            out.put("summary", null);
        }
        out.put("current", f.current);
        List<Map<String, Object>> crumb = new ArrayList<>();
        if (f.current != null)
        {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("userId", f.current.get("userId"));
            c.put("account", f.current.get("account"));
            crumb.add(c);
        }
        out.put("breadcrumb", crumb);
        out.put("total", f.total);
        int num = q.getPageNum() == null ? 1 : Math.max(1, q.getPageNum());
        int size = q.getPageSize() == null ? 20 : Math.max(1, Math.min(200, q.getPageSize()));
        int from = Math.min(f.rows.size(), (num - 1) * size);
        out.put("rows", new ArrayList<>(f.rows.subList(from, Math.min(f.rows.size(), from + size))));
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("num", num);
        page.put("size", size);
        page.put("total", f.rows.size());
        out.put("page", page);
        return out;
    }

    public Map<String, Object> fissionLocate(String q)
    {
        if (q == null || q.isBlank())
        {
            throw new ServiceException("請輸入完整帳號或用戶 ID", PromoErrors.UNKNOWN_CODE);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", false);
        out.put("reason", null);
        out.put("path", new ArrayList<>());
        out.put("row", null);
        out.put("flag", null);
        out.put("dataPending", true);
        return out;
    }
}
