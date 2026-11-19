package com.fivetech.risk.user;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.risk.RiskException;
import com.fivetech.risk.RiskProperties;
import com.fivetech.risk.catalog.RiskCatalog;
import com.fivetech.risk.catalog.RiskColumn;
import com.fivetech.risk.config.RiskConfigService;
import com.fivetech.risk.data.RiskDataSource;
import com.fivetech.risk.data.RiskFilter;
import com.fivetech.risk.rank.RiskAggregator;
import com.fivetech.risk.rank.RiskRankData;
import com.fivetech.risk.rank.RiskRankService;
import com.fivetech.risk.rank.RiskUser;

/**
 * 风控分析 · 用户详情抽屉（FY-75）与全部用户搜索（FY-67 R18）。
 *
 * <ul>
 *   <li>时间范围只作用于抽屉：按注册日期（當日 / 次日 / 7 / 30 / 90 日）或自定义；注册早于资料起始日时默认用名单的时间范围。</li>
 *   <li>概況、歷史累計、趋势、遊戲用哪些列在 yml（risk.user-*-cols），口径同排行榜的列（指标管理 RISK_RANK）。</li>
 *   <li>資金流水的存款、优惠明细与用户资料来自 UDS 的数据集（risk.deposit-order-dataset 等），TODO：数据集与字段名待 UDS 确认，
 *       没接入时对应部分返回空、带 pending 标记。</li>
 * </ul>
 *
 * @author fivetech
 */
@Service
public class RiskUserService
{
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM-dd");

    static final DateTimeFormatter SLOT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    static final int PAGE_SIZE = 20;

    static final List<String> WINDOWS = List.of("REG_D0", "REG_D1", "REG_7", "REG_30", "REG_90", "CUSTOM");

    private final RiskProperties props;

    private final RiskCatalog catalog;

    private final RiskDataSource source;

    private final RiskRankService rank;

    private final RiskConfigService config;

    private final DashboardProperties dashboard;

    public RiskUserService(RiskProperties props, RiskCatalog catalog, RiskDataSource source, RiskRankService rank, RiskConfigService config,
        DashboardProperties dashboard)
    {
        this.props = props;
        this.catalog = catalog;
        this.source = source;
        this.rank = rank;
        this.config = config;
        this.dashboard = dashboard;
    }

    String site()
    {
        return dashboard == null ? null : dashboard.currentSite();
    }

    ZoneId zone()
    {
        return dashboard == null ? ZoneId.systemDefault() : dashboard.currentZone();
    }

    LocalDate today()
    {
        return LocalDate.now(zone());
    }

    // ================================================================== 全部用户搜索

    /** 全站用户（含所选时间内没有活动的）：账号包含、ID 开头，2 个字符起，最多 8 条 */
    public Map<String, Object> search(String keyword)
    {
        String kw = StringUtils.trimToEmpty(keyword);
        if (kw.length() < 2)
        {
            throw RiskException.field("keyword", "至少輸入 2 個字");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keyword", kw);
        out.put("dataPending", !source.available(RiskDataSource.Kind.USER));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : source.searchUsers(kw, 8))
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", str(r.get("userId")));
            m.put("account", RiskRankService.mask(str(r.get("account"))));
            String reg = time(r.get("regTime"));
            m.put("regDate", reg == null ? null : reg.substring(0, Math.min(10, reg.length())));
            m.put("agentId", str(r.get("agentId")));
            m.put("channelId", str(r.get("channelId")));
            items.add(m);
        }
        out.put("items", items);
        return out;
    }

    // ================================================================== 时间范围

    /** 抽屉的时间范围（R4） */
    static final class Range
    {
        String mode;

        LocalDate from;

        LocalDate to;

        LocalDate regDate;

        LocalDate dataStart;

        boolean regBeforeDataStart;

        String label;

        String notice;

        String progress;

        List<Map<String, Object>> options = new ArrayList<>();

        LocalDateTime start()
        {
            return from.atStartOfDay();
        }

        LocalDateTime end()
        {
            return to.plusDays(1).atStartOfDay();
        }

        Map<String, Object> toMap(LocalDate today)
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mode", mode);
            m.put("from", from.format(DAY));
            m.put("to", to.format(DAY));
            m.put("label", label);
            m.put("notice", notice);
            m.put("progress", progress);
            m.put("regDate", regDate == null ? null : regDate.format(DAY));
            m.put("dataStart", dataStart == null ? null : dataStart.format(DAY));
            m.put("asOfDate", today.format(DAY));
            m.put("regBeforeDataStart", regBeforeDataStart);
            m.put("options", options);
            return m;
        }
    }

    Range range(RiskUserQuery q, LocalDate regDate)
    {
        LocalDate today = today();
        Range r = new Range();
        r.regDate = regDate;
        r.dataStart = date(props.getDataStartDate(), "dataStartDate");
        r.regBeforeDataStart = regDate != null && r.dataStart != null && regDate.isBefore(r.dataStart);
        boolean byReg = regDate != null && !r.regBeforeDataStart;
        String mode = StringUtils.isEmpty(q.getWindow()) ? (byReg ? "REG_D0" : "CUSTOM") : q.getWindow().toUpperCase(java.util.Locale.ROOT);
        if (!WINDOWS.contains(mode))
        {
            throw RiskException.field("window", "時間範圍只能是 " + String.join(" / ", WINDOWS));
        }
        // 按注册日期的快捷项（每项写实际日期；不可选的写原因）
        for (String w : WINDOWS.subList(0, 5))
        {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("code", w);
            if (!byReg)
            {
                o.put("disabled", true);
                o.put("reason", r.regBeforeDataStart ? "註冊早於資料起始日（" + r.dataStart.format(DAY) + "），無法按註冊日期查看" : "註冊日期未知");
            }
            else
            {
                LocalDate[] span = regSpan(w, regDate, today);
                o.put("from", span[0].format(DAY));
                o.put("to", span[1].format(DAY));
                boolean future = span[0].isAfter(today);
                o.put("disabled", future);
                o.put("reason", future ? "次日（" + span[0].format(MD) + "）還沒到" : null);
            }
            r.options.add(o);
        }
        if (mode.startsWith("REG_") && !byReg)
        {
            if (q.getWindow() != null)
            {
                throw RiskException.field("window", r.regBeforeDataStart ? "註冊早於資料起始日（" + r.dataStart.format(DAY) + "），無法按註冊日期查看"
                    : "註冊日期未知，請用自定義");
            }
            mode = "CUSTOM";
        }
        r.mode = mode;
        if (mode.startsWith("REG_"))
        {
            LocalDate[] span = regSpan(mode, regDate, today);
            if (span[0].isAfter(today))
            {
                throw RiskException.field("window", "次日（" + span[0].format(MD) + "）還沒到");
            }
            r.from = span[0];
            r.to = span[1];
            int n = days(mode);
            r.label = switch (mode)
            {
                case "REG_D0" -> "註冊當日 · " + r.from.format(MD);
                case "REG_D1" -> "註冊次日 · " + r.from.format(MD);
                default -> "註冊 " + n + " 日 · " + r.from.format(MD) + "–" + r.to.format(MD);
            };
            r.notice = "從註冊日起算，與名單的時間範圍不同";
            long have = ChronoUnit.DAYS.between(regDate, today) + 1;
            if (n > 1 && have < n)
            {
                r.progress = "註冊第 " + have + " 天，目前有 " + have + " 天資料";
            }
            return r;
        }
        // 自定义：没传 from/to 时取名单的时间范围
        LocalDate from = date(q.getFrom(), "from");
        LocalDate to = date(q.getTo(), "to");
        if (from == null || to == null)
        {
            LocalDateTime lf = slot(q.getListFrom(), "listFrom");
            LocalDateTime lt = slot(q.getListTo(), "listTo");
            from = lf == null ? today : lf.toLocalDate();
            to = lt == null ? today : lt.minusSeconds(1).toLocalDate();
        }
        if (to.isAfter(today))
        {
            to = today;
        }
        if (from.isAfter(to))
        {
            throw RiskException.field("from", "開始日期不能晚於結束日期");
        }
        if (regDate != null && !r.regBeforeDataStart && from.isBefore(regDate))
        {
            throw RiskException.field("from", "可選註冊日 " + regDate.format(DAY) + " 起");
        }
        if (r.dataStart != null && from.isBefore(r.dataStart))
        {
            throw RiskException.field("from", "可選資料起始日 " + r.dataStart.format(DAY) + " 起");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > props.getMaxSpanDays())
        {
            throw RiskException.field("to", "單次最多 " + props.getMaxSpanDays() + " 天");
        }
        r.from = from;
        r.to = to;
        r.label = "自定義 · " + from.format(MD) + "–" + to.format(MD);
        if (r.regBeforeDataStart)
        {
            r.notice = "註冊早於資料起始日（" + r.dataStart.format(DAY) + "），無法從註冊日起算；目前為名單的時間範圍";
        }
        return r;
    }

    static int days(String w)
    {
        return switch (w)
        {
            case "REG_7" -> 7;
            case "REG_30" -> 30;
            case "REG_90" -> 90;
            default -> 1;
        };
    }

    /** 當日 = 注册当天；次日 = 注册后第 2 天这一天；7 / 30 / 90 日 = 含注册当日共 N 天，取到资料截至 */
    static LocalDate[] regSpan(String w, LocalDate reg, LocalDate today)
    {
        if ("REG_D1".equals(w))
        {
            return new LocalDate[] { reg.plusDays(1), reg.plusDays(1) };
        }
        LocalDate end = reg.plusDays(days(w) - 1L);
        return new LocalDate[] { reg, end.isAfter(today) ? today : end };
    }

    private static LocalDate date(String v, String field)
    {
        if (StringUtils.isEmpty(v))
        {
            return null;
        }
        try
        {
            return LocalDate.parse(v.trim().substring(0, Math.min(10, v.trim().length())), DAY);
        }
        catch (RuntimeException e)
        {
            throw RiskException.field(field, "日期格式應為 yyyy-MM-dd");
        }
    }

    private static LocalDateTime slot(String v, String field)
    {
        if (StringUtils.isEmpty(v))
        {
            return null;
        }
        try
        {
            String s = v.trim().replace('T', ' ');
            return LocalDateTime.parse(s.length() > 16 ? s.substring(0, 16) : s, SLOT);
        }
        catch (RuntimeException e)
        {
            throw RiskException.field(field, "時間格式應為 yyyy-MM-dd HH:mm");
        }
    }

    // ================================================================== 抽屉

    /** 用户资料；没接入用户数据集时只有 userId */
    Map<String, Object> profile(String userId)
    {
        if (StringUtils.isEmpty(userId))
        {
            throw RiskException.field("userId", "請選擇用戶");
        }
        Map<String, Object> p = source.available(RiskDataSource.Kind.USER) ? source.userProfile(userId.trim()) : null;
        if (p == null && source.available(RiskDataSource.Kind.USER))
        {
            throw RiskException.notFound("用戶不存在");
        }
        return p == null ? Map.of("userId", userId.trim()) : p;
    }

    Map<String, Object> header(Map<String, Object> p, LocalDate regDate)
    {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("userId", str(p.get("userId")));
        h.put("account", RiskRankService.mask(str(p.get("account"))));
        h.put("agentId", str(p.get("agentId")));
        h.put("channelId", str(p.get("channelId")));
        h.put("parent", RiskRankService.mask(str(p.get("parent"))));
        h.put("parentId", str(p.get("parentId")));
        String reg = time(p.get("regTime"));
        h.put("regTime", reg);
        long ago = regDate == null ? -1 : ChronoUnit.DAYS.between(regDate, today());
        h.put("regDaysAgo", regDate == null ? null : ago);
        h.put("regText", regDate == null ? null : ago == 0 ? "今日" : ago + " 天前");
        h.put("pending", !source.available(RiskDataSource.Kind.USER));
        return h;
    }

    static LocalDate regDate(Map<String, Object> p)
    {
        String t = time(p.get("regTime"));
        try
        {
            return t == null ? null : LocalDate.parse(t.substring(0, 10), DAY);
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /** 时间范围内这个用户的汇总（没有活动时只有历史累计） */
    RiskUser user(String userId, Range r)
    {
        RiskFilter f = new RiskFilter(site(), r.start(), r.end(), List.of(userId));
        RiskRankData data = rank.data(f);
        RiskUser u = data.getUsers().get(userId);
        if (u != null)
        {
            return u;
        }
        // 时间范围内没有活动（R12）：指标为空，歷史累計照常取
        u = new RiskUser(userId);
        if (source.available(RiskDataSource.Kind.SNAPSHOT))
        {
            Map<String, RiskUser> one = new LinkedHashMap<>();
            one.put(userId, u);
            List<RiskColumn> cols = new ArrayList<>(catalog.rank().values());
            RiskAggregator.merge(one, cols, "SNAPSHOT", source.snapshots(List.of(userId), RiskAggregator.fieldsOf(cols, "SNAPSHOT")), one.keySet());
            RiskAggregator.calc(one.values(), cols, today());
        }
        return u;
    }

    List<Map<String, Object>> metrics(RiskUser u, List<String> codes, Map<String, String> unavailable)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String c : codes)
        {
            RiskColumn col = catalog.rank(c);
            if (col == null)
            {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", c);
            m.put("name", col.getName());
            m.put("valueFormat", col.getValueFormat());
            m.put("kind", col.getKind());
            m.put("value", unavailable.containsKey(c) ? null : u.get(c));
            if (unavailable.containsKey(c))
            {
                m.put("note", unavailable.get(c));
            }
            out.add(m);
        }
        return out;
    }

    /** 抽屉：抬头、时间范围、概況（9 项指标 + 歷史累計 + 趋势）、各页签数量 */
    public Map<String, Object> detail(RiskUserQuery q)
    {
        Map<String, Object> p = profile(q.getUserId());
        LocalDate reg = regDate(p);
        Range r = range(q, reg);
        String userId = str(p.get("userId"));
        RiskFilter f = new RiskFilter(site(), r.start(), r.end(), List.of(userId));
        RiskRankData data = rank.data(f);
        RiskUser u = user(userId, r);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", data.getAsOf());
        out.put("dataPending", data.isPending());
        out.put("header", header(p, reg));
        out.put("range", r.toMap(today()));
        boolean active = data.getUsers().containsKey(userId);
        out.put("empty", !active);
        out.put("emptyText", active ? null : "這段時間沒有存款與投注，可切換時間範圍");
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("metrics", metrics(u, props.getUserOverviewCols(), data.getUnavailable()));
        overview.put("historyTitle", "歷史累計 · 註冊至今");
        overview.put("history", metrics(u, props.getUserHistoryCols(), Map.of()));
        overview.put("historyPending", !source.available(RiskDataSource.Kind.SNAPSHOT));
        overview.put("trend", trend(userId, str(p.get("entityKey")), r));
        out.put("overview", overview);
        Map<String, Object> counts = new LinkedHashMap<>();
        int funds = 0;
        for (RiskDataSource.Kind k : List.of(RiskDataSource.Kind.DEPOSIT, RiskDataSource.Kind.WITHDRAW, RiskDataSource.Kind.BONUS))
        {
            funds += source.userOrders(k, userId, r.start(), r.end()).size();
        }
        counts.put("funds", funds);
        counts.put("games", gameRows(userId, r).size());
        out.put("tabCounts", counts);
        return out;
    }

    /**
     * 趋势（R6）：时间范围 ≥ 2 天按日，1 天按小时；累計存款、累計提款、累計存提差从时间范围起点累加。
     *
     * @param entityKey 用户唯一键，原样取自 risk_user_window_v1 的行；印度站不同商户下会有相同的 player_id，
     *                  靠它区分。拿不到时只按 player_id 过滤（可能把同号不同商户的数据并在一起）
     */
    Map<String, Object> trend(String userId, String entityKey, Range r)
    {
        String gran = r.from.equals(r.to) ? "HOUR" : "DAY";
        List<RiskColumn> cols = new ArrayList<>(catalog.rank().values());
        Map<String, RiskUser> buckets = new java.util.TreeMap<>();
        for (RiskDataSource.Kind k : List.of(RiskDataSource.Kind.USER_DAY, RiskDataSource.Kind.USER_GAME))
        {
            String src = k == RiskDataSource.Kind.USER_DAY ? "USER_DAY" : "USER_GAME";
            List<Map<String, Object>> rows = source.userSeries(k, userId, entityKey, r.start(), r.end(), gran,
                RiskAggregator.fieldsOf(cols, src));
            for (Map<String, Object> row : rows)
            {
                row.put(RiskAggregator.USER_ID, str(row.get("date")));
            }
            RiskAggregator.merge(buckets, cols, src, rows, null);
        }
        RiskAggregator.calc(buckets.values(), cols, today());
        List<Map<String, Object>> points = new ArrayList<>();
        BigDecimal cumDep = BigDecimal.ZERO;
        BigDecimal cumWd = BigDecimal.ZERO;
        for (RiskUser b : buckets.values())
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", b.getUserId());
            for (String c : props.getUserTrendCols())
            {
                m.put(c, b.get(c));
            }
            BigDecimal dep = b.num("dep");
            BigDecimal wd = b.num("wd");
            cumDep = dep == null ? cumDep : cumDep.add(dep);
            cumWd = wd == null ? cumWd : cumWd.add(wd);
            m.put("cumDep", cumDep);
            m.put("cumWd", cumWd);
            m.put("cumNet", cumDep.subtract(cumWd));
            points.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("granularity", gran);
        out.put("cols", props.getUserTrendCols());
        out.put("points", points);
        // TODO：按小时需要 UDS 的小时字段（risk.fields.hour）；没配或数据集未接入时为空
        out.put("pending", !source.available(RiskDataSource.Kind.USER_DAY) || ("HOUR".equals(gran) && StringUtils.isEmpty(props.field(
            StringUtils.isNotEmpty(props.getUserSeriesDataset()) ? props.getUserSeriesDataset() : props.getUserDayDataset(), "hour"))));
        return out;
    }

    // ================================================================== 資金流水

    /** 页面状态（存款 SUCCESS / FAILED / PENDING，提款 PAID / PENDING / REJECTED）与文字 */
    static final Map<String, Map<String, String>> STATUS_TEXT = Map.of(
        "DEPOSIT", linked("SUCCESS", "成功", "FAILED", "失敗", "PENDING", "處理中"),
        "WITHDRAW", linked("PAID", "已出款", "PENDING", "審核中", "REJECTED", "已拒絕"));

    private static Map<String, String> linked(String... kv)
    {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2)
        {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    String pageStatus(String type, Object raw)
    {
        String v = str(raw);
        Map<String, String> texts = STATUS_TEXT.get(type);
        if (texts == null || v == null)
        {
            return null;
        }
        String key = type.toLowerCase(java.util.Locale.ROOT);
        for (String st : texts.keySet())
        {
            if (props.statusValues(key, st).stream().anyMatch(x -> x.equalsIgnoreCase(v)))
            {
                return st;
            }
        }
        return null;
    }

    /** 資金流水（R7、R8）：存款 / 提款 / 优惠；状态多选、表头排序、每页 20；汇总按状态分开，金额只算成功的 */
    public Map<String, Object> funds(RiskUserQuery q)
    {
        String type = StringUtils.isEmpty(q.getType()) ? "DEPOSIT" : q.getType().toUpperCase(java.util.Locale.ROOT);
        RiskDataSource.Kind kind = switch (type)
        {
            case "DEPOSIT" -> RiskDataSource.Kind.DEPOSIT;
            case "WITHDRAW" -> RiskDataSource.Kind.WITHDRAW;
            case "BONUS" -> RiskDataSource.Kind.BONUS;
            default -> throw RiskException.field("type", "type 只能是 DEPOSIT、WITHDRAW 或 BONUS");
        };
        Map<String, Object> p = profile(q.getUserId());
        Range r = range(q, regDate(p));
        String userId = str(p.get("userId"));
        Map<String, String> texts = STATUS_TEXT.getOrDefault(type, Map.of());
        for (String st : q.getStatuses())
        {
            if (!texts.containsKey(st))
            {
                throw RiskException.field("statuses", "狀態只能是 " + String.join(" / ", texts.keySet()));
            }
        }
        List<Map<String, Object>> all = new ArrayList<>();
        for (Map<String, Object> row : source.userOrders(kind, userId, r.start(), r.end()))
        {
            Map<String, Object> m = new LinkedHashMap<>();
            String submit = time(row.get("submitTime"));
            String paid = time(row.get("paidTime"));
            m.put("orderNo", str(row.get("orderNo")));
            m.put("amount", RiskAggregator.num(row.get("orderAmount")));
            if ("BONUS".equals(type))
            {
                m.put("time", submit);
                m.put("bonusType", str(row.get("bonusType")));
            }
            else
            {
                String st = pageStatus(type, row.get("orderStatus"));
                m.put("createTime", submit);
                m.put("doneTime", paid);
                m.put("durationSeconds", seconds(row.get("submitTime"), row.get("paidTime")));
                m.put("status", st);
                m.put("statusText", st == null ? str(row.get("orderStatus")) : texts.get(st));
                m.put("rawStatus", str(row.get("orderStatus")));
                if ("DEPOSIT".equals(type))
                {
                    m.put("payMethod", str(row.get("payMethod")));
                }
            }
            all.add(m);
        }
        // 各状态笔数（选项上标）与汇总
        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        texts.keySet().forEach(k -> statusCounts.put(k, 0));
        BigDecimal okAmount = BigDecimal.ZERO;
        int ok = 0;
        long durSum = 0;
        int durN = 0;
        String okStatus = "DEPOSIT".equals(type) ? "SUCCESS" : "PAID";
        for (Map<String, Object> m : all)
        {
            Object st = m.get("status");
            if (st != null)
            {
                statusCounts.merge(String.valueOf(st), 1, Integer::sum);
            }
            if ("BONUS".equals(type) || okStatus.equals(st))
            {
                ok++;
                okAmount = m.get("amount") == null ? okAmount : okAmount.add((BigDecimal) m.get("amount"));
                if (m.get("durationSeconds") instanceof Long d)
                {
                    durSum += d;
                    durN++;
                }
            }
        }
        summary.put("amount", okAmount);
        summary.put("count", ok);
        summary.put("statusCounts", statusCounts);
        if (!"BONUS".equals(type))
        {
            summary.put("avgDurationSeconds", durN == 0 ? null : durSum / durN);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> m : all)
        {
            if (q.getStatuses().isEmpty() || q.getStatuses().contains(String.valueOf(m.get("status"))))
            {
                rows.add(m);
            }
        }
        rows.sort(fundOrder(type, q.getSortCol(), q.getSortDir()));
        int total = rows.size();
        int pageNum = q.getPageNum() == null || q.getPageNum() < 1 ? 1 : q.getPageNum();
        int from = Math.min(total, (pageNum - 1) * PAGE_SIZE);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("dataPending", !source.available(kind));
        out.put("range", r.toMap(today()));
        out.put("statuses", texts);
        out.put("summary", summary);
        out.put("total", total);
        out.put("pageNum", pageNum);
        out.put("pageSize", PAGE_SIZE);
        out.put("rows", new ArrayList<>(rows.subList(from, Math.min(total, from + PAGE_SIZE))));
        return out;
    }

    /** 表头排序：时间、金额、用时；默认创建时间从新到旧 */
    static Comparator<Map<String, Object>> fundOrder(String type, String col, String dir)
    {
        String timeKey = "BONUS".equals(type) ? "time" : "createTime";
        String key = col == null ? timeKey : switch (col)
        {
            case "amount" -> "amount";
            case "duration" -> "durationSeconds";
            case "doneTime" -> "doneTime";
            default -> timeKey;
        };
        boolean desc = col == null || !"asc".equalsIgnoreCase(dir);
        Comparator<Map<String, Object>> c = (a, b) -> {
            Object x = a.get(key);
            Object y = b.get(key);
            if (x == null && y == null)
            {
                return 0;
            }
            if (x == null)
            {
                return 1;
            }
            if (y == null)
            {
                return -1;
            }
            int v = x instanceof BigDecimal p && y instanceof BigDecimal q ? p.compareTo(q)
                : x instanceof Long p && y instanceof Long q ? p.compareTo(q) : String.valueOf(x).compareTo(String.valueOf(y));
            return desc ? -v : v;
        };
        return c.thenComparing(m -> String.valueOf(m.get(timeKey)), Comparator.reverseOrder());
    }

    static Long seconds(Object a, Object b)
    {
        String x = time(a);
        String y = time(b);
        if (x == null || y == null || x.length() < 16 || y.length() < 16)
        {
            return null;
        }
        try
        {
            DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            LocalDateTime s = LocalDateTime.parse(x.length() >= 19 ? x.substring(0, 19) : x + ":00", f);
            LocalDateTime e = LocalDateTime.parse(y.length() >= 19 ? y.substring(0, 19) : y + ":00", f);
            return Math.max(0, Duration.between(s, e).getSeconds());
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    // ================================================================== 遊戲

    /** 时间范围内按游戏汇总（投注、派彩、玩家输赢、RTP），按投注从高到低 */
    List<Map<String, Object>> gameRows(String userId, Range r)
    {
        if (!source.available(RiskDataSource.Kind.USER_GAME))
        {
            return List.of();
        }
        List<RiskColumn> cols = new ArrayList<>(catalog.rank().values());
        RiskFilter f = new RiskFilter(site(), r.start(), r.end(), List.of(userId));
        Map<String, RiskUser> byGame = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        List<Map<String, Object>> rows = source.userGameDays(f, RiskAggregator.fieldsOf(cols, "USER_GAME"));
        for (Map<String, Object> row : rows)
        {
            if (!userId.equals(str(row.get(RiskAggregator.USER_ID))) || row.get("game") == null)
            {
                continue;
            }
            names.putIfAbsent(str(row.get("game")), str(row.get("gameName")));
            row.put(RiskAggregator.USER_ID, str(row.get("game")));
        }
        rows.removeIf(row -> row.get("game") == null || !names.containsKey(str(row.get(RiskAggregator.USER_ID))));
        RiskAggregator.merge(byGame, cols, "USER_GAME", rows, null);
        RiskAggregator.calc(byGame.values(), cols, today());
        Map<String, Map<String, Object>> dict = config.games(site());
        List<Map<String, Object>> out = new ArrayList<>();
        for (RiskUser g : byGame.values())
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("game", g.getUserId());
            Map<String, Object> d = dict.get(g.getUserId());
            m.put("name", d != null && d.get("name") != null ? d.get("name") : names.getOrDefault(g.getUserId(), g.getUserId()));
            for (String c : props.getUserGameCols())
            {
                m.put(c, g.get(c));
            }
            out.add(m);
        }
        String sortCol = props.getUserGameCols().isEmpty() ? "bet" : props.getUserGameCols().get(0);
        out.sort(Comparator.comparing((Map<String, Object> m) -> m.get(sortCol) instanceof BigDecimal b ? b : BigDecimal.ZERO).reversed());
        return out;
    }

    /** 遊戲页签（R9）：指标格、按游戏、单笔最大派彩那一笔 */
    public Map<String, Object> games(RiskUserQuery q)
    {
        Map<String, Object> p = profile(q.getUserId());
        Range r = range(q, regDate(p));
        String userId = str(p.get("userId"));
        RiskUser u = user(userId, r);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataPending", !source.available(RiskDataSource.Kind.USER_GAME));
        out.put("range", r.toMap(today()));
        BigDecimal bet = u.num("bet");
        BigDecimal betN = StringUtils.isEmpty(props.getUserBetCountCol()) ? null : u.num(props.getUserBetCountCol());
        Map<String, Object> top = new LinkedHashMap<>();
        top.put("bet", bet);
        top.put("betCount", betN);
        top.put("pay", u.get("pay"));
        top.put("winLoss", u.get("winLoss"));
        top.put("rtp", u.get("rtp"));
        top.put("avgBet", bet == null || betN == null || betN.signum() == 0 ? null : bet.divide(betN, 2, RoundingMode.HALF_UP));
        // TODO：最大單注（含倍数）需要 UDS 的单注明细字段，接入前为空
        top.put("maxBet", null);
        top.put("maxBetPending", true);
        out.put("summary", top);
        out.put("cols", props.getUserGameCols());
        out.put("rows", gameRows(userId, r));
        Map<String, Object> best = new LinkedHashMap<>();
        Object game = u.get("winGame");
        Map<String, Object> d = game == null ? null : config.games(site()).get(String.valueOf(game));
        best.put("game", game);
        best.put("gameName", d != null && d.get("name") != null ? d.get("name") : game);
        best.put("time", u.get("winTime"));
        best.put("payout", u.get("maxPayout"));
        best.put("bet", u.get("maxPayoutBet"));
        best.put("multiple", u.get("payoutX"));
        out.put("maxPayout", game == null && u.get("maxPayout") == null ? null : best);
        return out;
    }

    // ================================================================== 工具

    static String str(Object v)
    {
        return v == null ? null : String.valueOf(v).trim();
    }

    static String time(Object v)
    {
        if (v == null)
        {
            return null;
        }
        String s = String.valueOf(v).trim().replace('T', ' ');
        return s.isEmpty() ? null : s.length() > 19 ? s.substring(0, 19) : s;
    }
}
