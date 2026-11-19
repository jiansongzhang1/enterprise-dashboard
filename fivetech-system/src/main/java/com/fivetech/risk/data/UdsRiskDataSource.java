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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(UdsRiskDataSource.class);

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 用户 × 日数据集的维度（逻辑名） */
    static final List<String> USER_DIMS = List.of("userId", "account", "regTime", "agentId", "channelId", "parent");

    static final List<String> USER_GAME_DIMS = List.of("userId", "game", "gameName", "platform", "date");

    /**
     * 游戏两个数据集（judgement / cube）固定的五个轴，顺序与 UDS 请求说明一致。
     * <p>不参与本次聚合的轴返回 null；全写的好处是下钻只改 scope_mask 和过滤条件，dimensions 不用重排。</p>
     */
    static final List<String> GAME_AXES = List.of("date", "platform", "brand", "gameType", "game");

    /**
     * scope_mask 的位：node_dt = 1、provider_code = 2、sub_provider_name = 4、game_type = 8、game_key = 16。
     * <p>scope_mask = 本次要展开的轴 + 已用等值条件固定住的父层轴，各位相加。写错不报错，只会返回空行或错的节点
     * （例如 mask = 4 再加 provider_code 等值 → 0 行，因为 mask = 4 的行上 provider_code 为空）。</p>
     */
    static final Map<String, Integer> GAME_AXIS_BITS = Map.of(
        "date", 1, "platform", 2, "brand", 4, "gameType", 8, "game", 16);

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
            case CHANNEL_ACQ -> props.getChannelAcqDataset();
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
        gameScope(f, and);
        return query(dataset(Kind.USER_DAY), dims, fields, dateTime(f), and);
    }

    /**
     * 用户窗口数据集的游戏范围（risk.game-scope-field）：只选一个平台取该平台（V:编码），其余取全部（A）。
     * 选多个平台时 UDS 没有「几个平台合计」的行，资金类按全部、游戏类另由用户 × 游戏数据集按平台过滤。
     */
    private void gameScope(RiskFilter f, List<Map<String, Object>> and)
    {
        if (StringUtils.isEmpty(props.getGameScopeField()))
        {
            return;
        }
        String v = f.platforms.size() == 1 ? props.getGameScopePlatformPrefix() + f.platforms.get(0) : props.getGameScopeAll();
        and.add(cond(props.getGameScopeField(), "EQ", v));
    }

    /** 渠道获客：按推广商、推广渠道汇总所选时间内的注册、首存（risk.channel-acq-dataset） */
    @Override
    public List<Map<String, Object>> channelAcquisition(RiskFilter f)
    {
        if (!available(Kind.CHANNEL_ACQ))
        {
            return List.of();
        }
        Map<String, String> acq = props.getChannelAcqFields();
        List<String> fields = new ArrayList<>();
        acq.values().forEach(v -> {
            if (StringUtils.isNotEmpty(v))
            {
                fields.add(v.trim());
            }
        });
        List<Map<String, Object>> and = new ArrayList<>();
        promoFilters(f, and);
        List<Map<String, Object>> rows = query(dataset(Kind.CHANNEL_ACQ), List.of("agentId", "channelId"), fields, dateTime(f), and);
        for (Map<String, Object> r : rows)
        {
            acq.forEach((logical, field) -> {
                if (StringUtils.isNotEmpty(field))
                {
                    r.put(logical, num(r.remove(field.trim())));
                }
            });
        }
        return rows;
    }

    @Override
    public List<Map<String, Object>> userGameDays(RiskFilter f, Collection<String> fields)
    {
        String ds = dataset(Kind.USER_GAME);
        List<Map<String, Object>> and = new ArrayList<>();
        promoFilters(f, and);
        gameFilters(f, and, ds);
        // 立方体和判定集同一套维度：五个轴全写，再按展开层级带 scope_mask
        List<String> dims = new ArrayList<>(USER_GAME_DIMS);
        for (String axis : GAME_AXES)
        {
            if (!dims.contains(axis))
            {
                dims.add(axis);
            }
        }
        return query(ds, dims, fields, dateTime(f), gameScoped(ds, GAME_AXES, null, and));
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
        String ds = dataset(Kind.GAME);
        List<Map<String, Object>> and = new ArrayList<>();
        gameFilters(f, and, ds);
        // 父层轴用等值固定住；值为空 = 该层是空节点，走 IS_NULL
        if (eq != null)
        {
            eq.forEach((logical, value) -> axisEq(and, ds, logical, value));
        }
        return query(ds, gameDims(ds, dims), fields, dateTime(f), gameScoped(ds, dims, eq, and));
    }

    /**
     * 游戏数据集的维度：固定五个轴，展示游戏时另加 game_id、game_provider_code、game_name_en。
     * <p>等值过滤与翻页稳定键一律用 game_key——同一个 game_id 在不同平台是不同游戏。</p>
     */
    private List<String> gameDims(String dataset, List<String> dims)
    {
        List<String> all = new ArrayList<>(GAME_AXES);
        if (dims != null && dims.contains("game"))
        {
            for (String extra : List.of("gameId", "gameProvider", "gameName"))
            {
                if (StringUtils.isNotEmpty(props.field(dataset, extra)))
                {
                    all.add(extra);
                }
            }
        }
        return all;
    }

    /** 加上必填的 scope_mask 等值条件；不带 UDS 返回 400 / 4001 */
    private List<Map<String, Object>> gameScoped(String dataset, List<String> dims, Map<String, String> eq,
        List<Map<String, Object>> and)
    {
        int mask = scopeMask(dims, eq == null ? List.of() : eq.keySet());
        and.add(cond("scope_mask", "EQ", mask));
        log.debug("[risk] {} scope_mask={} dims={} eq={}", dataset, mask, dims, eq == null ? Map.of() : eq.keySet());
        return and;
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
    public List<Map<String, Object>> userSeries(Kind kind, String userId, String entityKey, LocalDateTime from, LocalDateTime to,
        String granularity, Collection<String> fields)
    {
        if (!available(kind))
        {
            return List.of();
        }
        boolean hour = "HOUR".equals(granularity);
        String ds = kind == Kind.USER_DAY && StringUtils.isNotEmpty(props.getUserSeriesDataset()) ? props.getUserSeriesDataset() : dataset(kind);
        // 按小时需要该数据集的小时字段（risk.fields.hour / dataset-fields），没有时不取
        String dim = props.field(ds, hour ? "hour" : "date");
        if (StringUtils.isEmpty(dim))
        {
            return List.of();
        }
        List<Map<String, Object>> and = new ArrayList<>();
        // player_id 必填，不带 UDS 返回 400；entity_key 用来区分印度站不同商户下相同的 player_id，
        // 取 risk_user_window_v1 行里的原值直接传，不要自己拼
        and.add(cond(props.field("userId"), "EQ", userId));
        if (StringUtils.isNotEmpty(entityKey) && StringUtils.isNotEmpty(props.field(ds, "entityKey")))
        {
            and.add(cond(props.field(ds, "entityKey"), "EQ", entityKey));
        }
        // 窗口数据集的时间维固定 uds_hh；stat_dt 是维度，已经在 dims 里
        Map<String, Object> time = new LinkedHashMap<>();
        time.put("dimension", HOUR_DIMENSION);
        time.put("granularity", "HOUR");
        time.put("from", from.withMinute(0).withSecond(0).withNano(0).format(DATETIME));
        LocalDateTime last = to.minusSeconds(1).withMinute(0).withSecond(0).withNano(0);
        time.put("to", (last.isBefore(from) ? from.withMinute(0).withSecond(0).withNano(0) : last).format(DATETIME));
        List<Map<String, Object>> rows = query(ds, List.of(hour ? "hour" : "date"), fields, time, and);
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

    /**
     * 窗口数据集统一的时间维（UDS 2026-11-19）：固定 uds_hh、HOUR 粒度，from / to 是业务小时、<b>闭区间</b>，
     * 结果里没有时间桶，一个窗口一份结果。
     * <p>stat_dt、node_dt 都<b>不是</b>时间维：前者进 {@link #userDayTrend} 的 dimensions，后者是游戏五轴之一。</p>
     */
    private Map<String, Object> dateTime(RiskFilter f)
    {
        Map<String, Object> time = new LinkedHashMap<>();
        time.put("dimension", HOUR_DIMENSION);
        time.put("granularity", "HOUR");
        time.put("from", f.from.withMinute(0).withSecond(0).withNano(0).format(DATETIME));
        // 本系统区间左闭右开，UDS 要闭区间：退到最后一个包含的整点
        LocalDateTime last = f.to.minusSeconds(1).withMinute(0).withSecond(0).withNano(0);
        time.put("to", (last.isBefore(f.from) ? f.from.withMinute(0).withSecond(0).withNano(0) : last).format(DATETIME));
        return time;
    }

    /** 窗口数据集的时间维名；与 stat_dt / node_dt 无关 */
    static final String HOUR_DIMENSION = "uds_hh";

    /**
     * 本次请求的 scope_mask：要展开的轴（dims）加上已用等值固定住的父层轴（eq）。
     * <p>人数与盈亏是按节点算的，少写一个展开的轴会把多个节点合并——同一用户在子平台 A 盈利、B 亏损，
     * 会同时计入盈利人数和亏损人数，而且不报错。金额类不受影响。</p>
     */
    static int scopeMask(Collection<String> dims, Collection<String> eqAxes)
    {
        int mask = 0;
        for (String d : dims == null ? List.<String>of() : dims)
        {
            mask |= GAME_AXIS_BITS.getOrDefault(d, 0);
        }
        for (String d : eqAxes == null ? List.<String>of() : eqAxes)
        {
            mask |= GAME_AXIS_BITS.getOrDefault(d, 0);
        }
        return mask;
    }

    /**
     * 游戏数据集的父层等值条件：值为空表示该层本身就是空节点，要写 IS_NULL，不能写空字符串。
     */
    private void axisEq(List<Map<String, Object>> and, String dataset, String logical, String value)
    {
        String col = props.field(dataset, logical);
        if (StringUtils.isEmpty(col))
        {
            return;
        }
        if (StringUtils.isEmpty(value))
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", col);
            m.put("op", "IS_NULL");
            and.add(m);
            return;
        }
        and.add(cond(col, "EQ", value));
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

    /** 平台 / 品牌 / 类型条件，字段按该数据集的（品牌全局为空、游戏立方体是 sub_provider_name） */
    private void gameFilters(RiskFilter f, List<Map<String, Object>> and, String dataset)
    {
        in(and, props.field(dataset, "platform"), f.platforms);
        in(and, props.field(dataset, "brand"), f.brands);
        in(and, props.field(dataset, "gameType"), f.gameTypes);
    }

    private static void in(List<Map<String, Object>> and, String field, List<String> values)
    {
        if (!values.isEmpty() && StringUtils.isNotEmpty(field))
        {
            and.add(cond(field, "IN", values));
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

    /**
     * onePage 不为空时只取一页、每页 onePage 行。
     * <p>指标字段写成「数据集.字段」时向那个数据集另取（同样的维度、时间、条件），按维度值并回同一行，行里的键仍是「数据集.字段」；
     * 另取失败只记日志、那几列为空，不影响主数据集。</p>
     */
    List<Map<String, Object>> query(String dataset, List<String> dims, Collection<String> fields, Map<String, Object> time,
        List<Map<String, Object>> and, Integer onePage)
    {
        Map<String, List<String>> other = new LinkedHashMap<>();
        List<String> own = new ArrayList<>();
        for (String f : fields)
        {
            if (StringUtils.isEmpty(f))
            {
                continue;
            }
            String t = f.trim();
            int dot = t.indexOf('.');
            if (dot > 0 && dot < t.length() - 1 && !t.substring(0, dot).equals(dataset))
            {
                other.computeIfAbsent(t.substring(0, dot), k -> new ArrayList<>()).add(t);
            }
            else
            {
                own.add(dot > 0 && t.substring(0, dot).equals(dataset) ? t.substring(dot + 1) : t);
            }
        }
        List<Map<String, Object>> rows = queryOne(dataset, dims, own, time, and, onePage);
        if (other.isEmpty())
        {
            return rows;
        }
        Map<List<Object>, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> r : rows)
        {
            byKey.put(key(r, dims), r);
        }
        for (Map.Entry<String, List<String>> e : other.entrySet())
        {
            List<String> plain = new ArrayList<>();
            e.getValue().forEach(q -> plain.add(q.substring(e.getKey().length() + 1)));
            List<Map<String, Object>> extra;
            try
            {
                extra = queryOne(e.getKey(), dims, plain, time, and, onePage);
            }
            catch (RuntimeException ex)
            {
                if (ex instanceof com.fivetech.common.exception.ServiceException se && se.getCode() != null && se.getCode() == 400)
                {
                    throw ex;
                }
                log.warn("[risk] 另取数据集 {} 失败，{} 留空：{}", e.getKey(), e.getValue(), ex.getMessage());
                continue;
            }
            for (Map<String, Object> x : extra)
            {
                Map<String, Object> r = byKey.get(key(x, dims));
                if (r == null)
                {
                    continue;
                }
                for (int i = 0; i < plain.size(); i++)
                {
                    r.put(e.getValue().get(i), x.get(plain.get(i)));
                }
            }
        }
        return rows;
    }

    private static List<Object> key(Map<String, Object> r, List<String> dims)
    {
        List<Object> k = new ArrayList<>();
        for (String d : dims)
        {
            Object v = r.get(d);
            k.add(v == null ? null : String.valueOf(v).trim());
        }
        return k;
    }

    /**
     * 向一个数据集取数：dims 为逻辑名，按该数据集的字段名（risk.dataset-fields 覆盖全局 risk.fields）翻译；
     * 时间维度、条件里的全局字段名也换成该数据集的；加上 risk.dataset-filters 的固定条件。
     */
    List<Map<String, Object>> queryOne(String dataset, List<String> dims, Collection<String> fields, Map<String, Object> time,
        List<Map<String, Object>> and, Integer onePage)
    {
        Map<String, String> rename = rename(dataset);
        Map<String, String> udsDims = new LinkedHashMap<>();
        for (String d : dims)
        {
            String col = props.field(dataset, d);
            if (StringUtils.isNotEmpty(col))
            {
                udsDims.put(d, col);
            }
        }
        Map<String, String> metricCols = new LinkedHashMap<>();
        for (String f : fields)
        {
            if (StringUtils.isNotEmpty(f))
            {
                String col = rename.getOrDefault(f.trim(), f.trim());
                if (StringUtils.isNotEmpty(col))
                {
                    metricCols.put(f.trim(), col);
                }
            }
        }
        Set<String> metrics = new LinkedHashSet<>(metricCols.values());
        if (metrics.isEmpty() && StringUtils.isNotEmpty(props.field(dataset, "rowCount")))
        {
            metrics.add(props.field(dataset, "rowCount"));
        }
        time = renameTime(time, rename);
        and = renameFilters(and, rename);
        Map<String, String> fixed = props.getDatasetFilters().get(dataset);
        if (fixed != null && !fixed.isEmpty())
        {
            and = new ArrayList<>(and);
            for (Map.Entry<String, String> e : fixed.entrySet())
            {
                if (StringUtils.isNotEmpty(e.getKey()) && e.getValue() != null)
                {
                    and.add(cond(e.getKey().trim(), "EQ", e.getValue().trim()));
                }
            }
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
                for (Map.Entry<String, String> e : metricCols.entrySet())
                {
                    m.put(e.getKey(), r.raw(e.getValue()));
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

    /** 全局字段名 → 该数据集的字段名（只含被覆盖的；空串 = 该数据集没有） */
    Map<String, String> rename(String dataset)
    {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, String> o = dataset == null ? null : props.getDatasetFields().get(dataset);
        if (o == null)
        {
            return out;
        }
        for (Map.Entry<String, String> e : o.entrySet())
        {
            String global = props.field(e.getKey());
            if (StringUtils.isNotEmpty(global))
            {
                out.put(global, e.getValue() == null ? "" : e.getValue().trim());
            }
        }
        return out;
    }

    private static Map<String, Object> renameTime(Map<String, Object> time, Map<String, String> rename)
    {
        if (time == null || rename.isEmpty() || !(time.get("dimension") instanceof String d) || !rename.containsKey(d)
            || StringUtils.isEmpty(rename.get(d)))
        {
            return time;
        }
        Map<String, Object> t = new LinkedHashMap<>(time);
        t.put("dimension", rename.get(d));
        return t;
    }

    /** 条件里的字段换成该数据集的；该数据集没有这个字段（覆盖为空串）的条件去掉 */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> renameFilters(List<Map<String, Object>> and, Map<String, String> rename)
    {
        if (rename.isEmpty() || and.isEmpty())
        {
            return and;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : and)
        {
            if (c.get("or") instanceof List<?> any)
            {
                List<Map<String, Object>> sub = renameFilters((List<Map<String, Object>>) any, rename);
                if (!sub.isEmpty())
                {
                    out.add(Map.of("or", sub));
                }
                continue;
            }
            Object f = c.get("field");
            if (f instanceof String name && rename.containsKey(name))
            {
                if (StringUtils.isEmpty(rename.get(name)))
                {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>(c);
                m.put("field", rename.get(name));
                out.add(m);
            }
            else
            {
                out.add(c);
            }
        }
        return out;
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
