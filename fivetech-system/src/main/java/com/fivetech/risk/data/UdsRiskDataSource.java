package com.fivetech.risk.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.gateway.uds.UdsClient;
import com.fivetech.dashboard.gateway.uds.UdsQueryResult;
import com.fivetech.dashboard.gateway.uds.UdsRow;
import com.fivetech.risk.RiskException;
import com.fivetech.risk.RiskProperties;

/**
 * 风控分析向 UDS 取数（{@code POST /v1/query}）：数据集名、维度字段来自 {@link RiskProperties}（yml / 环境变量），
 * 指标字段由调用方按指标管理的配置传入。站点由请求头 X-Uds-Site 区分（UdsClient 统一加）。
 *
 * <p>UDS 单页最多 1000 行，这里自动翻页；读入行数超过 {@code risk.max-rows} 时返回 400，提示缩小范围。</p>
 *
 * @author fivetech
 */
@Component
public class UdsRiskDataSource implements RiskDataSource
{
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 用户 × 日数据集的维度（逻辑名） */
    static final List<String> USER_DIMS = List.of("userId", "account", "regTime", "agentId", "channelId", "parent");

    static final List<String> USER_GAME_DIMS = List.of("userId", "game", "gameName", "platform", "date");

    static final List<String> ORDER_DIMS = List.of("orderNo", "userId", "account", "submitTime", "paidTime", "agentId", "channelId");

    private final UdsClient client;

    private final RiskProperties props;

    public UdsRiskDataSource(UdsClient client, RiskProperties props)
    {
        this.client = client;
        this.props = props;
    }

    @Override
    public boolean available(Kind kind)
    {
        if (!props.isEnabled())
        {
            return false;
        }
        return StringUtils.isNotEmpty(dataset(kind));
    }

    private String dataset(Kind kind)
    {
        return switch (kind)
        {
            case USER_DAY -> props.getUserDayDataset();
            case USER_GAME -> props.getUserGameDayDataset();
            case WITHDRAW -> props.getWithdrawOrderDataset();
            case GAME -> props.getGameDataset();
            case SNAPSHOT -> props.getPlayerSnapshotDataset();
            // TODO：渠道获客（注册、首存）的 UDS 口径未定，接入前不可用
            case CHANNEL_ACQ -> "";
            case USER -> props.getUserDataset();
            case DEPOSIT -> props.getDepositOrderDataset();
            case BONUS -> props.getBonusDataset();
        };
    }

    @Override
    public List<Map<String, Object>> userDays(RiskFilter f, Collection<String> fields)
    {
        List<String> dims = new ArrayList<>(USER_DIMS);
        if (props.isUserDayByDate())
        {
            dims.add("date");
        }
        List<Map<String, Object>> and = new ArrayList<>();
        promoFilters(f, and);
        return query(dataset(Kind.USER_DAY), dims, fields, dateTime(f), and);
    }

    @Override
    public List<Map<String, Object>> userGameDays(RiskFilter f, Collection<String> fields)
    {
        List<Map<String, Object>> and = new ArrayList<>();
        promoFilters(f, and);
        gameFilters(f, and);
        return query(dataset(Kind.USER_GAME), USER_GAME_DIMS, fields, dateTime(f), and);
    }

    @Override
    public List<Map<String, Object>> snapshots(Collection<String> userIds, Collection<String> fields)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(userIds));
        Map<String, Object> time = new LinkedHashMap<>();
        time.put("dimension", "snapshot_at");
        time.put("granularity", "HOUR");
        time.put("from", "now");
        time.put("to", "now");
        for (int i = 0; i < ids.size(); i += 1000)
        {
            List<Map<String, Object>> and = new ArrayList<>();
            and.add(cond(props.field("userId"), "IN", ids.subList(i, Math.min(ids.size(), i + 1000))));
            out.addAll(query(dataset(Kind.SNAPSHOT), List.of("userId"), fields, time, and));
        }
        return out;
    }

    /** 成功出款的提款订单：按申请时间（submitTime）落在所选时间内（FY-70 R1） */
    @Override
    public List<Map<String, Object>> paidWithdrawals(RiskFilter f, BigDecimal minAmount)
    {
        List<Map<String, Object>> and = new ArrayList<>();
        promoFilters(f, and);
        if (StringUtils.isNotEmpty(props.getPaidStatus()) && StringUtils.isNotEmpty(props.field("orderStatus")))
        {
            and.add(cond(props.field("orderStatus"), "EQ", props.getPaidStatus()));
        }
        Map<String, Object> time = new LinkedHashMap<>();
        time.put("dimension", props.field("submitTime"));
        time.put("granularity", "HOUR");
        time.put("from", f.from.format(DATETIME));
        time.put("to", f.to.minusSeconds(1).format(DATETIME));
        String amount = props.field("orderAmount");
        List<Map<String, Object>> rows = query(dataset(Kind.WITHDRAW), ORDER_DIMS, List.of(amount), time, and);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows)
        {
            Object v = r.remove(amount);
            BigDecimal a = num(v);
            if (a != null && (minAmount == null || a.compareTo(minAmount) >= 0))
            {
                r.put("orderAmount", a);
                out.add(r);
            }
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> games(RiskFilter f, List<String> dims, Map<String, String> eq, Collection<String> fields)
    {
        List<Map<String, Object>> and = new ArrayList<>();
        gameFilters(f, and);
        if (eq != null)
        {
            for (Map.Entry<String, String> e : eq.entrySet())
            {
                String col = props.field(e.getKey());
                if (StringUtils.isNotEmpty(col))
                {
                    and.add(cond(col, "EQ", e.getValue()));
                }
            }
        }
        List<String> all = new ArrayList<>(dims);
        if (all.contains("game") && !all.contains("gameName"))
        {
            all.add("gameName");
        }
        return query(dataset(Kind.GAME), all, fields, dateTime(f), and);
    }

    @Override
    public List<Map<String, Object>> gameDays(String siteCode, String game, LocalDate from, LocalDate to, Collection<String> fields)
    {
        List<Map<String, Object>> and = new ArrayList<>();
        and.add(cond(props.field("game"), "EQ", game));
        Map<String, Object> time = new LinkedHashMap<>();
        time.put("dimension", props.field("date"));
        time.put("granularity", "DAY");
        time.put("from", from.format(DATE));
        time.put("to", to.format(DATE));
        return query(dataset(Kind.GAME), List.of("date"), fields, time, and);
    }

    // ------------------------------------------------------------------ 用户详情抽屉、全部用户搜索

    static final List<String> PROFILE_DIMS = List.of("userId", "account", "regTime", "agentId", "channelId", "parent", "parentId");

    static final List<String> ORDER_DETAIL_DIMS = List.of("orderNo", "orderStatus", "submitTime", "paidTime", "payMethod", "bonusType");

    /** 用户资料数据集不按时间切，传一个覆盖全部的区间（TODO：以 UDS 用户维表的查询方式为准） */
    private static Map<String, Object> allTime(String dim)
    {
        Map<String, Object> time = new LinkedHashMap<>();
        time.put("dimension", dim);
        time.put("granularity", "DAY");
        time.put("from", "2000-01-01");
        time.put("to", LocalDate.now().format(DATE));
        return time;
    }

    /**
     * 账号包含或用户 ID 开头（FY-67 R18）。TODO：UDS 的模糊匹配操作符待确认，这里先用 CONTAINS / STARTS_WITH。
     */
    @Override
    public List<Map<String, Object>> searchUsers(String keyword, int limit)
    {
        if (!available(Kind.USER))
        {
            return List.of();
        }
        List<Map<String, Object>> any = new ArrayList<>();
        any.add(cond(props.field("account"), "CONTAINS", keyword));
        any.add(cond(props.field("userId"), "STARTS_WITH", keyword));
        List<Map<String, Object>> and = new ArrayList<>();
        and.add(Map.of("or", any));
        List<Map<String, Object>> rows = queryPage(dataset(Kind.USER), List.of("userId", "account", "regTime", "agentId", "channelId"),
            List.of(), allTime(props.field("regTime")), and, Math.max(1, Math.min(limit, 50)));
        return rows.size() > limit ? rows.subList(0, limit) : rows;
    }

    @Override
    public Map<String, Object> userProfile(String userId)
    {
        if (!available(Kind.USER))
        {
            return null;
        }
        List<Map<String, Object>> and = new ArrayList<>();
        and.add(cond(props.field("userId"), "EQ", userId));
        List<Map<String, Object>> rows = queryPage(dataset(Kind.USER), PROFILE_DIMS, List.of(), allTime(props.field("regTime")), and, 1);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<Map<String, Object>> userSeries(Kind kind, String userId, LocalDateTime from, LocalDateTime to, String granularity,
        Collection<String> fields)
    {
        if (!available(kind))
        {
            return List.of();
        }
        boolean hour = "HOUR".equals(granularity);
        // TODO：按小时需要 UDS 的小时字段（risk.fields.hour），没配时不取
        String dim = hour ? props.field("hour") : props.field("date");
        if (StringUtils.isEmpty(dim))
        {
            return List.of();
        }
        List<Map<String, Object>> and = new ArrayList<>();
        and.add(cond(props.field("userId"), "IN", List.of(userId)));
        Map<String, Object> time = new LinkedHashMap<>();
        time.put("dimension", props.field("date"));
        time.put("granularity", hour ? "HOUR" : "DAY");
        time.put("from", hour ? from.format(DATETIME) : from.toLocalDate().format(DATE));
        LocalDateTime last = to.minusSeconds(1);
        time.put("to", hour ? last.format(DATETIME) : last.toLocalDate().format(DATE));
        List<Map<String, Object>> rows = query(dataset(kind), List.of(hour ? "hour" : "date"), fields, time, and);
        if (hour)
        {
            rows.forEach(r -> r.put("date", r.remove("hour")));
        }
        return rows;
    }

    @Override
    public List<Map<String, Object>> userOrders(Kind kind, String userId, LocalDateTime from, LocalDateTime to)
    {
        if (!available(kind))
        {
            return List.of();
        }
        List<Map<String, Object>> and = new ArrayList<>();
        and.add(cond(props.field("userId"), "EQ", userId));
        Map<String, Object> time = new LinkedHashMap<>();
        time.put("dimension", props.field("submitTime"));
        time.put("granularity", "HOUR");
        time.put("from", from.format(DATETIME));
        time.put("to", to.minusSeconds(1).format(DATETIME));
        String amount = props.field("orderAmount");
        List<String> dims = new ArrayList<>(ORDER_DETAIL_DIMS);
        if (kind == Kind.BONUS)
        {
            dims.removeAll(List.of("orderStatus", "paidTime", "payMethod"));
        }
        List<Map<String, Object>> rows = query(dataset(kind), dims, List.of(amount), time, and);
        for (Map<String, Object> r : rows)
        {
            r.put("orderAmount", num(r.remove(amount)));
        }
        return rows;
    }

    /** 只取一页（不翻页），最多 limit 行 */
    private List<Map<String, Object>> queryPage(String dataset, List<String> dims, Collection<String> fields, Map<String, Object> time,
        List<Map<String, Object>> and, int limit)
    {
        List<Map<String, Object>> rows = query(dataset, dims, fields, time, and, limit);
        return rows.size() > limit ? new ArrayList<>(rows.subList(0, limit)) : rows;
    }

    // ------------------------------------------------------------------ 公共

    private Map<String, Object> dateTime(RiskFilter f)
    {
        Map<String, Object> time = new LinkedHashMap<>();
        time.put("dimension", props.field("date"));
        time.put("granularity", "DAY");
        time.put("from", f.from.toLocalDate().format(DATE));
        LocalDateTime last = f.to.minusSeconds(1);
        time.put("to", (last.isBefore(f.from) ? f.from : last).toLocalDate().format(DATE));
        return time;
    }

    private void promoFilters(RiskFilter f, List<Map<String, Object>> and)
    {
        if (!f.userIds.isEmpty())
        {
            and.add(cond(props.field("userId"), "IN", f.userIds));
        }
        // 推广商与推广渠道取并集：只选推广商 = 该推广商全部渠道
        if (!f.agents.isEmpty() && f.channels.isEmpty() && StringUtils.isNotEmpty(props.field("agentId")))
        {
            and.add(cond(props.field("agentId"), "IN", f.agents));
        }
        else if (!f.channels.isEmpty() && StringUtils.isNotEmpty(props.field("channelId")))
        {
            Map<String, Object> or = new LinkedHashMap<>();
            List<Map<String, Object>> any = new ArrayList<>();
            any.add(cond(props.field("channelId"), "IN", f.channels));
            if (!f.agents.isEmpty() && StringUtils.isNotEmpty(props.field("agentId")))
            {
                any.add(cond(props.field("agentId"), "IN", f.agents));
            }
            if (any.size() == 1)
            {
                and.add(any.get(0));
            }
            else
            {
                or.put("or", any);
                and.add(or);
            }
        }
    }

    private void gameFilters(RiskFilter f, List<Map<String, Object>> and)
    {
        if (!f.platforms.isEmpty() && StringUtils.isNotEmpty(props.field("platform")))
        {
            and.add(cond(props.field("platform"), "IN", f.platforms));
        }
        if (!f.brands.isEmpty() && StringUtils.isNotEmpty(props.field("brand")))
        {
            and.add(cond(props.field("brand"), "IN", f.brands));
        }
        if (!f.gameTypes.isEmpty() && StringUtils.isNotEmpty(props.field("gameType")))
        {
            and.add(cond(props.field("gameType"), "IN", f.gameTypes));
        }
    }

    static Map<String, Object> cond(String field, String op, Object value)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("op", op);
        m.put("value", value);
        return m;
    }

    /**
     * 发查询并翻页；dims 为逻辑名（没配字段的跳过），行里维度换回逻辑名，指标保持 UDS 字段名。
     */
    List<Map<String, Object>> query(String dataset, List<String> dims, Collection<String> fields, Map<String, Object> time,
        List<Map<String, Object>> and)
    {
        return query(dataset, dims, fields, time, and, null);
    }

    /** onePage 不为空时只取一页、每页 onePage 行 */
    List<Map<String, Object>> query(String dataset, List<String> dims, Collection<String> fields, Map<String, Object> time,
        List<Map<String, Object>> and, Integer onePage)
    {
        Map<String, String> udsDims = new LinkedHashMap<>();
        for (String d : dims)
        {
            String col = props.field(d);
            if (StringUtils.isNotEmpty(col))
            {
                udsDims.put(d, col);
            }
        }
        Set<String> metrics = new LinkedHashSet<>();
        for (String f : fields)
        {
            if (StringUtils.isNotEmpty(f))
            {
                metrics.add(f.trim());
            }
        }
        if (metrics.isEmpty() && StringUtils.isNotEmpty(props.field("rowCount")))
        {
            metrics.add(props.field("rowCount"));
        }
        int limit = Math.max(1, Math.min(1000, onePage != null ? onePage : props.getPageSize()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataset", dataset);
        body.put("dimensions", new ArrayList<>(udsDims.values()));
        body.put("metrics", new ArrayList<>(metrics));
        body.put("time", time);
        if (!and.isEmpty())
        {
            body.put("filters", Map.of("and", and));
        }
        List<Map<String, Object>> order = new ArrayList<>();
        udsDims.values().forEach(d -> order.add(Map.of("field", d, "dir", "ASC")));
        body.put("orderBy", order);
        body.put("limit", limit);
        body.put("options", Map.of("channel", "ONLINE"));
        List<Map<String, Object>> out = new ArrayList<>();
        int offset = 0;
        while (true)
        {
            body.put("offset", offset);
            UdsQueryResult res = client.queryWithMeta(new LinkedHashMap<>(body));
            List<UdsRow> rows = res.getRows();
            for (UdsRow r : rows)
            {
                Map<String, Object> m = new LinkedHashMap<>();
                for (Map.Entry<String, String> e : udsDims.entrySet())
                {
                    m.put(e.getKey(), r.raw(e.getValue()));
                }
                for (String f : metrics)
                {
                    m.put(f, r.raw(f));
                }
                out.add(m);
            }
            if (out.size() > props.getMaxRows())
            {
                throw RiskException.tooLarge("資料量超過 " + props.getMaxRows() + " 行，請縮小時間範圍或加篩選");
            }
            if (rows.size() < limit || onePage != null)
            {
                return out;
            }
            offset += limit;
        }
    }

    static BigDecimal num(Object v)
    {
        if (v == null)
        {
            return null;
        }
        if (v instanceof BigDecimal b)
        {
            return b;
        }
        if (v instanceof Number n)
        {
            return new BigDecimal(n.toString());
        }
        try
        {
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? null : new BigDecimal(s);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }
}
