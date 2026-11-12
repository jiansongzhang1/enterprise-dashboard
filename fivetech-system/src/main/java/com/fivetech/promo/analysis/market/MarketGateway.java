package com.fivetech.promo.analysis.market;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.enums.Granularity;
import com.fivetech.dashboard.gateway.uds.UdsClient;
import com.fivetech.dashboard.gateway.uds.UdsQueryResult;
import com.fivetech.dashboard.gateway.uds.UdsRow;

/**
 * 推广分析的 UDS 市场数据集查询（{@code dashboard_market_hour_v1}、{@code dashboard_market_member_snapshot_v1}）。
 *
 * <p>约定（对照文档「全局约定」）：</p>
 * <ul>
 *   <li>时间窗写本地时间，{@code to} 是最后一个包含的整点；本系统左闭右开，换算在这里做；</li>
 *   <li>宽表与精确两类指标不能在一个请求里混用（4403），拆成两个请求用 /query/batch 一次发出；</li>
 *   <li>宽表不能按 terminal 过滤或分组，改取带终端前缀的指标；精确指标按 terminal 分组（Web / Android / iOS / Unknown）；</li>
 *   <li>精确人数在每个时间桶内去重，「多天按日累加」= 按日分组后相加；</li>
 *   <li>会员快照的 time 固定 snapshot_at now ~ now。</li>
 * </ul>
 *
 * @author fivetech
 */
@Component
public class MarketGateway
{
    private static final Logger log = LoggerFactory.getLogger(MarketGateway.class);

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** UDS 精确指标的终端取值 → 本系统终端键 */
    public static final Map<String, String> TERMINAL_KEYS = Map.of("web", "web", "android", "android", "ios", "ios", "unknown", "unknown");

    /** 宽表带终端前缀的指标前缀 */
    public static final List<String> TERMINAL_PREFIXES = List.of("web", "android", "ios", "unknown");

    /** 宽表：注册、首存、首存金额、复存金额（与页面口径一致的部分；激活 lp_install 口径待确认，不取） */
    public static final List<String> WIDE = List.of("reg", "ftd_players", "ftd_amount", "redeposit_amount");

    /** 宽表里有终端前缀的指标 */
    public static final List<String> WIDE_BY_TERMINAL = List.of("reg", "ftd_players", "ftd_amount", "redeposit_amount");

    /** 精确来源：按桶去重的人数与金额 */
    public static final List<String> PRECISE = List.of("deposit_users", "redeposit_users", "bet_users", "deposit_amount", "withdraw_amount",
        "bet_amount");

    private final UdsClient client;

    private final MarketProperties props;

    public MarketGateway(UdsClient client, MarketProperties props)
    {
        this.client = client;
        this.props = props;
    }

    /** 站点时区的「现在」（实时 to 用）；没注入时（单测）不做实时调整 */
    java.util.function.Supplier<LocalDateTime> clock;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDashboard(com.fivetech.dashboard.config.DashboardProperties dashboard)
    {
        this.clock = dashboard == null ? null : () -> LocalDateTime.now(dashboard.currentZone());
    }

    public boolean isEnabled()
    {
        return props.isEnabled();
    }

    public int maxChildren()
    {
        return props.getMaxChildren();
    }

    // ------------------------------------------------------------------ 小时数据集

    /**
     * 取 [from, to) 的市场数据：全部可取的指标、带终端拆分（渠道表现、获客漏斗等不确定要哪些列时用）。
     */
    public MarketCube cube(LocalDateTime from, LocalDateTime to, Granularity granularity)
    {
        return cube(from, to, granularity, MarketValues.AVAILABLE, true);
    }

    /**
     * 取 [from, to) 的市场数据，按 granularity 分桶（HOUR / DAY；WEEK 请用 DAY 再自己归周）。
     * <p>只取 codes（页面指标）用到的字段（{@link MarketValues#FIELDS}）：用不到精确来源就不发精确那条查询，
     * 用不到宽表同理；两条都要时用 /query/batch 一次发出，只要一条时直接 /query。</p>
     *
     * @param byTerminal 是否要按终端拆（选了终端、按终端归因、行维度有终端）：要时宽表多取带终端前缀的字段；
     *                   精确来源始终按 terminal 分组（全部 = 各终端相加），与不拆时的合计一致
     */
    public MarketCube cube(LocalDateTime from, LocalDateTime to, Granularity granularity, java.util.Collection<String> codes,
        boolean byTerminal)
    {
        Granularity g = granularity == Granularity.HOUR ? Granularity.HOUR : Granularity.DAY;
        java.util.Set<String> fields = MarketValues.fieldsOf(codes);
        List<String> wideAll = new ArrayList<>();
        WIDE.stream().filter(fields::contains).forEach(wideAll::add);
        List<String> wideByTerminal = new ArrayList<>();
        if (byTerminal)
        {
            WIDE_BY_TERMINAL.stream().filter(fields::contains).forEach(wideByTerminal::add);
        }
        List<String> wide = new ArrayList<>(wideAll);
        for (String p : TERMINAL_PREFIXES)
        {
            wideByTerminal.forEach(m -> wide.add(p + "_" + m));
        }
        List<String> precise = new ArrayList<>();
        PRECISE.stream().filter(fields::contains).forEach(precise::add);

        List<Map<String, Object>> bodies = new ArrayList<>();
        Map<String, Object> wideBody = wide.isEmpty() ? null : hourBody(wide, List.of("uds_hh"), from, to, g, new ArrayList<>());
        Map<String, Object> preciseBody = precise.isEmpty() ? null
            : hourBody(precise, List.of("uds_hh", "terminal"), from, to, g, new ArrayList<>());
        if (wideBody != null)
        {
            bodies.add(wideBody);
        }
        if (preciseBody != null)
        {
            bodies.add(preciseBody);
        }
        MarketCube cube = new MarketCube();
        if (bodies.isEmpty())
        {
            return cube;
        }
        List<UdsQueryResult> results = bodies.size() == 1 ? List.of(client.queryWithMeta(bodies.get(0))) : client.queryBatch(bodies);
        cube.setOmitted(results.stream().anyMatch(UdsQueryResult::isDatasetOmitted));
        cube.setWatermark(results.stream().map(UdsQueryResult::getWatermark).filter(java.util.Objects::nonNull)
            .max(LocalDateTime::compareTo).orElse(null));
        int i = 0;
        if (wideBody != null)
        {
            for (UdsRow row : allRows(wideBody, results.get(i++)))
            {
                LocalDateTime b = bucket(row.str("uds_hh"), g);
                for (String m : wideAll)
                {
                    cube.add(b, MarketCube.ALL, m, row.num(m));
                }
                for (String p : TERMINAL_PREFIXES)
                {
                    for (String m : wideByTerminal)
                    {
                        cube.add(b, p, m, row.num(p + "_" + m));
                    }
                }
            }
        }
        if (preciseBody == null)
        {
            return cube;
        }
        for (UdsRow row : allRows(preciseBody, results.get(i)))
        {
            LocalDateTime b = bucket(row.str("uds_hh"), g);
            String t = terminalKey(row.str("terminal"));
            for (String m : precise)
            {
                BigDecimal v = row.num(m);
                cube.add(b, t, m, v);
                cube.add(b, MarketCube.ALL, m, v);
            }
        }
        return cube;
    }

    /**
     * 按代理（agent_login = 会员当前上级）汇总 [from, to) 的直属下级统计：新增下级 reg、首存 ftd_players、
     * 存款人数 deposit_users（按日累加）、存款总额 deposit_amount、投注总额 bet_amount。
     */
    public Map<String, Map<String, BigDecimal>> byAgent(LocalDateTime from, LocalDateTime to, List<String> agents)
    {
        Map<String, Map<String, BigDecimal>> out = new LinkedHashMap<>();
        if (agents == null || agents.isEmpty())
        {
            return out;
        }
        List<Map<String, Object>> f = new ArrayList<>();
        f.add(cond("agent_login", "IN", new ArrayList<>(agents)));
        Map<String, Object> wide = hourBody(List.of("reg", "ftd_players"), List.of("agent_login"), from, to, Granularity.DAY, f);
        Map<String, Object> precise = hourBody(List.of("deposit_users", "deposit_amount", "bet_amount"),
            List.of("uds_hh", "agent_login"), from, to, Granularity.DAY, new ArrayList<>(f));
        List<UdsQueryResult> results = client.queryBatch(List.of(wide, precise));
        for (UdsRow row : allRows(wide, results.get(0)))
        {
            Map<String, BigDecimal> m = out.computeIfAbsent(row.str("agent_login"), k -> new LinkedHashMap<>());
            merge(m, "reg", row.num("reg"));
            merge(m, "ftd_players", row.num("ftd_players"));
        }
        for (UdsRow row : allRows(precise, results.get(1)))
        {
            Map<String, BigDecimal> m = out.computeIfAbsent(row.str("agent_login"), k -> new LinkedHashMap<>());
            merge(m, "deposit_users", row.num("deposit_users"));
            merge(m, "deposit_amount", row.num("deposit_amount"));
            merge(m, "bet_amount", row.num("bet_amount"));
        }
        return out;
    }

    private static void merge(Map<String, BigDecimal> m, String k, BigDecimal v)
    {
        if (v != null)
        {
            m.merge(k, v, BigDecimal::add);
        }
    }

    // ------------------------------------------------------------------ 会员快照

    /** 会员计数（record_count），每组条件一个数，一次 batch 发出 */
    public List<Long> memberCounts(List<List<Map<String, Object>>> conditions)
    {
        List<Map<String, Object>> bodies = new ArrayList<>();
        for (List<Map<String, Object>> c : conditions)
        {
            bodies.add(memberBody(List.of(), new ArrayList<>(c), List.of(), 1, 0));
        }
        List<Long> out = new ArrayList<>();
        if (bodies.isEmpty())
        {
            return out;
        }
        for (UdsQueryResult r : client.queryBatch(bodies))
        {
            BigDecimal n = r.getRows().isEmpty() ? null : r.getRows().get(0).num("record_count");
            out.add(n == null ? 0L : n.longValue());
        }
        return out;
    }

    /** 按代理计数直属下级（截至当前，不随日期变化） */
    public Map<String, Long> directCounts(List<String> agents)
    {
        Map<String, Long> out = new LinkedHashMap<>();
        if (agents == null || agents.isEmpty())
        {
            return out;
        }
        List<Map<String, Object>> f = new ArrayList<>();
        f.add(cond("agent_login", "IN", new ArrayList<>(agents)));
        Map<String, Object> body = memberBody(List.of("agent_login"), f, List.of(), props.getPageSize(), 0);
        for (UdsRow row : allRows(body, client.queryWithMeta(body)))
        {
            BigDecimal n = row.num("record_count");
            out.put(row.str("agent_login"), n == null ? 0L : n.longValue());
        }
        return out;
    }

    /** 会员列表（分页须含 uds_rowkey 排序收尾） */
    public List<UdsRow> members(List<Map<String, Object>> filters, List<String> dims, List<Map<String, Object>> orderBy,
        int limit, int offset)
    {
        List<Map<String, Object>> order = new ArrayList<>(orderBy);
        order.add(order("uds_rowkey", "ASC"));
        List<String> d = new ArrayList<>(dims);
        if (!d.contains("uds_rowkey"))
        {
            d.add(0, "uds_rowkey");
        }
        return client.queryWithMeta(memberBody(d, new ArrayList<>(filters), order, limit, offset)).getRows();
    }

    // ------------------------------------------------------------------ 注册分群

    /** 留存与回收要的分群指标：注册人数 reg，加上每个天数 N 的到期人数、留存人数、N 日累计存款额 */
    public static List<String> cohortMetrics(List<Integer> offsets)
    {
        List<String> m = new ArrayList<>();
        m.add("reg");
        for (int n : offsets)
        {
            m.add("matured_d" + n);
            m.add("retained_d" + n);
            m.add("deposit_amount_d" + n);
        }
        return m;
    }

    /**
     * 注册分群 {@code dashboard_market_cohort_v1}：注册日 [from, to]（两端都含，按日），按 dims 分组（空 = 整段一行）。
     * <p>第 N 日还没结束的会员不计入：整行没到期时 {@code matured_dN = 0}、其余为 null（不是 0）。
     * 只能按 reg_dt / referral_code / agent_login / terminal 分组，没有推广渠道维度。</p>
     *
     * @return 行；整数据集缺口（如 IVI 站点，200 + meta.omitted）返回 null
     */
    public List<UdsRow> cohort(LocalDate from, LocalDate to, List<String> dims, List<String> metrics)
    {
        Map<String, Object> time = new LinkedHashMap<>();
        time.put("dimension", "reg_dt");
        time.put("granularity", "DAY");
        time.put("from", from.format(DATE));
        // 分群数据集只有 DAY，to 写日期、闭区间
        time.put("to", to.format(DATE));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataset", props.getCohortDataset());
        body.put("dimensions", new ArrayList<>(dims));
        body.put("metrics", new ArrayList<>(metrics));
        body.put("time", time);
        putFilters(body, new ArrayList<>());
        List<Map<String, Object>> order = new ArrayList<>();
        dims.forEach(d -> order.add(order(d, "ASC")));
        body.put("orderBy", order);
        body.put("limit", Math.max(1, Math.min(1000, props.getPageSize())));
        body.put("offset", 0);
        body.put("options", options());
        UdsQueryResult first = client.queryWithMeta(body);
        if (first.isDatasetOmitted())
        {
            return null;
        }
        return allRows(body, first);
    }

    // ------------------------------------------------------------------ 请求体

    public static Map<String, Object> cond(String field, String op, Object value)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("op", op);
        m.put("value", value);
        return m;
    }

    public static Map<String, Object> order(String field, String dir)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("dir", dir);
        return m;
    }

    /** 本地时间写法（不带时区） */
    public static String datetime(LocalDateTime t)
    {
        return t.format(DATETIME);
    }

    Map<String, Object> hourBody(List<String> metrics, List<String> dims, LocalDateTime from, LocalDateTime to, Granularity g,
        List<Map<String, Object>> and)
    {
        Map<String, Object> time = new LinkedHashMap<>();
        time.put("dimension", "uds_hh");
        time.put("granularity", g.name());
        time.put("from", from.format(DATETIME));
        // to 闭区间：平时为最后一个包含的整点；查询今日或包含今日时按当前时间向右取整（16:50 → 17:00，UDS 实时数据）
        LocalDateTime last = com.fivetech.dashboard.gateway.uds.UdsRealtime.hourTo(to, to.minusHours(1), clock == null ? null : clock.get());
        time.put("to", (last.isBefore(from) ? from : last).format(DATETIME));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataset", props.getHourDataset());
        body.put("dimensions", new ArrayList<>(dims));
        body.put("metrics", new ArrayList<>(metrics));
        body.put("time", time);
        putFilters(body, and);
        List<Map<String, Object>> order = new ArrayList<>();
        dims.forEach(d -> order.add(order(d, "ASC")));
        body.put("orderBy", order);
        body.put("limit", Math.max(1, Math.min(1000, props.getPageSize())));
        body.put("offset", 0);
        body.put("options", options());
        return body;
    }

    Map<String, Object> memberBody(List<String> dims, List<Map<String, Object>> and, List<Map<String, Object>> orderBy, int limit,
        int offset)
    {
        Map<String, Object> time = new LinkedHashMap<>();
        time.put("dimension", "snapshot_at");
        time.put("granularity", "HOUR");
        time.put("from", "now");
        time.put("to", "now");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataset", props.getMemberDataset());
        body.put("dimensions", new ArrayList<>(dims));
        body.put("metrics", List.of("record_count"));
        body.put("time", time);
        putFilters(body, and);
        body.put("orderBy", new ArrayList<>(orderBy));
        body.put("limit", Math.max(1, Math.min(1000, limit)));
        body.put("offset", Math.max(0, offset));
        body.put("options", options());
        return body;
    }

    private void putFilters(Map<String, Object> body, List<Map<String, Object>> and)
    {
        List<Map<String, Object>> all = new ArrayList<>();
        if (StringUtils.isNotEmpty(props.getMerchantCode()))
        {
            all.add(cond("merchant_code", "EQ", props.getMerchantCode().trim()));
        }
        all.addAll(and);
        if (!all.isEmpty())
        {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("and", all);
            body.put("filters", f);
        }
    }

    private static Map<String, Object> options()
    {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("channel", "ONLINE");
        return o;
    }

    /** 第一页之后满页就继续翻页 */
    private List<UdsRow> allRows(Map<String, Object> body, UdsQueryResult first)
    {
        List<UdsRow> rows = new ArrayList<>(first.getRows());
        int limit = (Integer) body.get("limit");
        int offset = 0;
        List<UdsRow> page = first.getRows();
        while (page.size() >= limit && offset < 100_000)
        {
            offset += limit;
            Map<String, Object> next = new LinkedHashMap<>(body);
            next.put("offset", offset);
            page = client.queryWithMeta(next).getRows();
            rows.addAll(page);
        }
        return rows;
    }

    /** uds_hh 的值 → 桶起点（DAY 取当天 00:00） */
    static LocalDateTime bucket(String value, Granularity g)
    {
        if (value == null || value.isBlank())
        {
            return null;
        }
        String t = value.trim().replace('T', ' ');
        try
        {
            if (t.length() >= 19)
            {
                LocalDateTime x = LocalDateTime.parse(t.substring(0, 19), DATETIME);
                return g == Granularity.HOUR ? x : x.toLocalDate().atStartOfDay();
            }
            if (t.length() >= 16)
            {
                LocalDateTime x = LocalDateTime.parse(t.substring(0, 16) + ":00", DATETIME);
                return g == Granularity.HOUR ? x : x.toLocalDate().atStartOfDay();
            }
            return LocalDate.parse(t.substring(0, 10), DATE).atStartOfDay();
        }
        catch (RuntimeException e)
        {
            log.debug("[promo-market] 无法解析时间值 {}", value);
            return null;
        }
    }

    /** UDS 终端值（Web / Android / iOS / Unknown，空为 Unknown）→ 终端键 */
    static String terminalKey(String value)
    {
        if (value == null || value.isBlank())
        {
            return "unknown";
        }
        return TERMINAL_KEYS.getOrDefault(value.trim().toLowerCase(Locale.ROOT), "unknown");
    }
}
