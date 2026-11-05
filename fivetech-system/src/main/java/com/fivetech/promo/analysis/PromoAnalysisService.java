package com.fivetech.promo.analysis;

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

    public PromoAnalysisService(DashboardProperties properties, PromoChannelDictionary channels, PromoTerminalDictionary terminals,
        PromoMetricCatalog catalog)
    {
        this.properties = properties;
        this.channels = channels;
        this.terminals = terminals;
        this.catalog = catalog;
    }

    // ------------------------------------------------------------------ 公共

    String site()
    {
        return properties.currentSite();
    }

    LocalDateTime nowHour()
    {
        return LocalDateTime.now(properties.currentZone()).truncatedTo(ChronoUnit.HOURS);
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
        return m;
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
        String launch = properties.getLaunchDate();
        out.put("siteLaunchDate", launch);
        out.put("dataStart", launch);
        out.put("asOf", null);
        out.put("timezone", properties.currentTimezone());
        out.put("currency", currency());
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

    /** 渠道表现按天：主区间取整天（起点当天 00:00 到终点所在日的次日 00:00） */
    static PromoTime.Range wholeDays(PromoTime.Range r)
    {
        return new PromoTime.Range(r.firstDay().atStartOfDay(), r.lastDay().plusDays(1).atStartOfDay());
    }

    public Map<String, Object> channelTable(ChannelTableQuery q)
    {
        PromoTime.Range range = wholeDays(PromoTime.range(q.getSlotFrom(), q.getSlotTo()));
        List<PromoMetricCatalog.Column> cols = columns(q.getColumns());
        ChannelTree tree = tree(q, range);
        tree.checkParent(q.getParent());
        sortOf(q.getSort(), "reg");

        int level = q.getParent().size();
        List<PathItem> parent = new ArrayList<>();
        for (int i = 0; i < level; i++)
        {
            parent.add(new PathItem(tree.dims().get(i), q.getParent().get(i).getKey().trim()));
        }
        Map<String, Object> out = header(PromoTime.slot(range, null), null);
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
        scope(q.getAgents(), q.getChannels(), null);
        Map<String, Object> compare = PromoTime.compare(q.getCompareType(), q.getCompareFrom(), q.getCompareTo(), true, range, nowHour());
        Map<String, Object> out = header(PromoTime.slot(range, null), compare);
        List<Map<String, Object>> overall = new ArrayList<>();
        for (String code : List.of("lpUV", "hUV", "reg", "ftd"))
        {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("code", code);
            x.put("value", null);
            if (compare != null)
            {
                x.put("prev", null);
                x.put("deltaPct", null);
            }
            overall.add(x);
        }
        out.put("overall", overall);
        out.put("regShare", new ArrayList<>());
        List<Map<String, Object>> cards = new ArrayList<>();
        for (PromoTerminalDictionary.Terminal t : terminalConfig().getTerminals())
        {
            if (t.getSteps().isEmpty())
            {
                continue;
            }
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("terminal", t.getCode());
            card.put("reg", null);
            card.put("viaLandingPage", null);
            card.put("lost", null);
            List<Map<String, Object>> steps = new ArrayList<>();
            for (PromoTerminalDictionary.Step s : t.getSteps())
            {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("code", s.getCode());
                x.put("value", null);
                x.put("role", s.getRole());
                x.put("strict", s.isStrict());
                x.put("rate", null);
                steps.add(x);
            }
            card.put("steps", steps);
            cards.add(card);
        }
        out.put("terminals", cards);
        return out;
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
            card.put("state", m == null ? "PENDING" : "OK");
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
            marks.add(matured(r[0], r[1], n) == null ? "PENDING" : null);
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
                states.add(d.plusDays(n).isAfter(yesterday) ? "PENDING" : "OK");
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

    public Map<String, Object> fissionAgents(FissionQuery q)
    {
        PromoTime.Range range = PromoTime.range(q.getSlotFrom(), q.getSlotTo());
        scope(q.getAgents(), q.getChannels(), null);
        fissionSort(q.getSort());
        boolean top = q.getParentId() == null || q.getParentId().isBlank();
        Map<String, Object> out = header(PromoTime.slot(range, null), null);
        if (top)
        {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("fissionReg", null);
            summary.put("fissionFtd", null);
            summary.put("activeAgents", null);
            summary.put("directShare", null);
            out.put("summary", summary);
            out.put("current", null);
            out.put("breadcrumb", new ArrayList<>());
        }
        else
        {
            out.put("summary", null);
            Map<String, Object> current = new LinkedHashMap<>();
            current.put("userId", q.getParentId().trim());
            current.put("account", null);
            current.put("level", null);
            current.put("agent", null);
            current.put("channel", null);
            current.put("directCount", null);
            current.put("newSub", null);
            current.put("ftd", null);
            current.put("betAmt", null);
            out.put("current", current);
            out.put("breadcrumb", new ArrayList<>());
        }
        Map<String, Object> total = fissionStats();
        total.put("directCount", null);
        out.put("total", total);
        out.put("rows", new ArrayList<>());
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("num", q.getPageNum() == null ? 1 : Math.max(1, q.getPageNum()));
        page.put("size", q.getPageSize() == null ? 20 : Math.max(1, Math.min(200, q.getPageSize())));
        page.put("total", 0);
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
