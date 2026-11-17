package com.fivetech.risk.rank;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.risk.RiskException;
import com.fivetech.risk.RiskProperties;
import com.fivetech.risk.RiskQuery;
import com.fivetech.risk.catalog.RiskCatalog;
import com.fivetech.risk.catalog.RiskColumn;
import com.fivetech.risk.config.RiskBoards;
import com.fivetech.risk.config.RiskConfigService;
import com.fivetech.risk.data.RiskDataSource;
import com.fivetech.risk.data.RiskFilter;
import com.fivetech.risk.mapper.RiskMyBoardMapper;
import com.fivetech.risk.my.RiskMyBoard;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 风控分析 · 排行榜（7.1.2）：卡片、完整名单、导出。
 *
 * <p>榜单的入榜条件、排行字段、占比口径来自配置中心 RISK_RANK_BOARDS / RISK_RANK_THRESHOLDS，列与名单条件字段来自
 * 指标管理（RISK_RANK），这里只按配置计算：取数 → 按用户汇总（{@link RiskAggregator}）→ 入榜 → 名单条件 → 按排行字段定名次
 * → 占比 / 累计占比 → Top X → 表格排序（只改顺序，名次跟着人走）→ 分页。</p>
 *
 * @author fivetech
 */
@Service
public class RiskRankService
{
    private static final Logger log = LoggerFactory.getLogger(RiskRankService.class);

    static final ObjectMapper JSON = JsonMapper.builder().build();

    static final DateTimeFormatter ASOF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    static final Set<Integer> PAGE_SIZES = Set.of(20, 50, 100);

    /** 名单条件里的「风险类型」：选项来自 RISK_RANK_BOARDS.riskTypes（命中哪张榜的入榜条件） */
    public static final String RISK_TYPE = "riskType";

    private final RiskProperties props;

    private final RiskCatalog catalog;

    private final RiskDataSource source;

    private final RiskConfigService config;

    private final RiskMyBoardMapper myBoards;

    private final DashboardProperties dashboard;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** 渠道风险榜（FY-71） */
    final RiskChannelRank channels = new RiskChannelRank(this);

    private record Cached(long at, RiskRankData data)
    {
    }

    public RiskRankService(RiskProperties props, RiskCatalog catalog, RiskDataSource source, RiskConfigService config,
        RiskMyBoardMapper myBoards, DashboardProperties dashboard)
    {
        this.props = props;
        this.catalog = catalog;
        this.source = source;
        this.config = config;
        this.myBoards = myBoards;
        this.dashboard = dashboard;
    }

    /** 推广商 › 推广渠道字典（渠道风险榜显示名称）；没注入时显示编码 */
    private com.fivetech.promo.budget.PromoChannelDictionary promo;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPromo(com.fivetech.promo.budget.PromoChannelDictionary promo)
    {
        this.promo = promo;
    }

    /** 个人风险类型从个人视图配置位读（{@code risk.rank.riskTypes}） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setViews(com.fivetech.dashboard.view.UserViewService views)
    {
        if (views != null)
        {
            setPersonalRiskTypes(() -> views.itemsOf(com.fivetech.risk.RiskViewItemSource.RANK_RISK_TYPES));
        }
    }

    RiskDataSource source()
    {
        return source;
    }

    RiskProperties props()
    {
        return props;
    }

    String siteName()
    {
        return dashboard == null ? "" : StringUtils.trimToEmpty(dashboard.siteName(site()));
    }

    /** 渠道编码 → [推广商名称, 渠道名称] */
    Map<String, String[]> channelNames()
    {
        Map<String, String[]> out = new LinkedHashMap<>();
        if (promo == null)
        {
            return out;
        }
        for (com.fivetech.promo.budget.PromoChannelDictionary.Promoter p : promo.promotersOf(site()))
        {
            for (com.fivetech.promo.budget.PromoChannelDictionary.Channel c : p.getChannels())
            {
                out.put(c.getId(), new String[] { p.getName(), c.getName() });
            }
        }
        return out;
    }

    /** 推广商编码 → 名称 */
    Map<String, String> agentNames()
    {
        Map<String, String> out = new LinkedHashMap<>();
        if (promo != null)
        {
            promo.promotersOf(site()).forEach(p -> out.put(p.getId(), p.getName()));
        }
        return out;
    }

    String channelConditionText(List<RiskQuery.Condition> conds, RiskBoards.Board b)
    {
        List<String> parts = new ArrayList<>();
        for (RiskQuery.Condition c : conds)
        {
            RiskBoards.ChannelCol col = b.channelCol(c.getField());
            parts.add((col == null ? c.getField() : col.name) + " " + (c.getMin() == null ? "" : plain(c.getMin())) + "–"
                + (c.getMax() == null ? "" : plain(c.getMax())));
        }
        return String.join("；", parts);
    }

    // ================================================================== 取数与汇总

    String site()
    {
        return dashboard == null ? null : dashboard.currentSite();
    }

    ZoneId zone()
    {
        return dashboard == null ? ZoneId.systemDefault() : dashboard.currentZone();
    }

    /** 按筛选汇总出全部用户（缓存 risk.cache-seconds 秒） */
    public RiskRankData data(RiskFilter f)
    {
        long now = System.currentTimeMillis();
        String key = f.key();
        Cached c = cache.get(key);
        if (c != null && now - c.at() < props.getCacheSeconds() * 1000L)
        {
            return c.data();
        }
        RiskRankData d = load(f);
        if (props.getCacheSeconds() > 0)
        {
            if (cache.size() > 50)
            {
                cache.clear();
            }
            cache.put(key, new Cached(now, d));
        }
        return d;
    }

    RiskRankData load(RiskFilter f)
    {
        Map<String, RiskColumn> cols = catalog.rank();
        String asOf = LocalDateTime.now(zone()).format(ASOF);
        if (!source.available(RiskDataSource.Kind.USER_DAY))
        {
            return new RiskRankData(new LinkedHashMap<>(), new LinkedHashMap<>(), true, asOf);
        }
        Map<String, String> unavailable = new LinkedHashMap<>();
        Map<String, RiskUser> users = new LinkedHashMap<>();
        List<Map<String, Object>> rows = source.userDays(f, RiskAggregator.fieldsOf(cols.values(), "USER_DAY"));
        RiskAggregator.merge(users, cols.values(), "USER_DAY", rows, null);
        RiskAggregator.dims(users, cols.values(), rows);
        if (users.size() > props.getMaxUsers())
        {
            throw RiskException.tooLarge("用戶數超過 " + props.getMaxUsers() + "，請縮小時間範圍或加篩選");
        }
        Set<String> gameFields = RiskAggregator.fieldsOf(cols.values(), "USER_GAME");
        boolean hasGameCols = cols.values().stream().anyMatch(x -> "USER_GAME".equals(x.getSource()));
        if (hasGameCols && source.available(RiskDataSource.Kind.USER_GAME))
        {
            List<Map<String, Object>> g = source.userGameDays(f, gameFields);
            RiskAggregator.merge(users, cols.values(), "USER_GAME", g, users.keySet());
        }
        else if (hasGameCols)
        {
            markUnavailable(cols.values(), "USER_GAME", "遊戲數據待接入", unavailable);
        }
        boolean hasSnap = cols.values().stream().anyMatch(x -> "SNAPSHOT".equals(x.getSource()));
        if (hasSnap && source.available(RiskDataSource.Kind.SNAPSHOT) && !users.isEmpty())
        {
            List<Map<String, Object>> s = source.snapshots(users.keySet(), RiskAggregator.fieldsOf(cols.values(), "SNAPSHOT"));
            RiskAggregator.merge(users, cols.values(), "SNAPSHOT", s, users.keySet());
        }
        else if (hasSnap)
        {
            markUnavailable(cols.values(), "SNAPSHOT", "玩家快照待接入", unavailable);
        }
        RiskAggregator.calc(users.values(), cols.values(), LocalDate.now(zone()));
        return new RiskRankData(users, unavailable, false, asOf);
    }

    private static void markUnavailable(Collection<RiskColumn> cols, String src, String reason, Map<String, String> out)
    {
        for (RiskColumn c : cols)
        {
            if (src.equals(c.getSource()))
            {
                out.put(c.getCode(), reason);
            }
        }
    }

    // ================================================================== 榜单解析

    /** 一张榜（系统榜、我的榜单或渠道风险榜的下钻名单）解析后的样子 */
    static final class Resolved
    {
        String code;

        String name;

        RiskBoards.Board sys;

        /** 我的榜单 */
        boolean my;

        /** 我的榜单保存的名单条件（= 名单的默认条件） */
        List<RiskQuery.Condition> saved = new ArrayList<>();

        String rankCol;

        String rankDir;

        /** 排行字段固定（大额提款榜、下钻名单），请求里的 rankCol 不生效 */
        boolean fixedRank;

        String shareMode = "NONE";

        String shareBase = "ENTRY";

        /** 默认列（为空用 sys.cols） */
        List<String> defaultCols;

        /** 名单范围之外的固定筛选（下钻：渠道 + 风险类型） */
        java.util.function.Predicate<RiskUser> filter;

        boolean unavailable;

        String reason;

        boolean isOrder()
        {
            return sys != null && sys.isOrder();
        }

        boolean isChannel()
        {
            return sys != null && sys.isChannel();
        }
    }

    Resolved resolve(String code, RiskBoards cfg, Long userId)
    {
        if (StringUtils.isEmpty(code))
        {
            throw RiskException.field("board", "請選擇榜單");
        }
        Resolved r = new Resolved();
        r.code = code;
        if (code.startsWith("m:"))
        {
            Long id;
            try
            {
                id = Long.valueOf(code.substring(2));
            }
            catch (NumberFormatException e)
            {
                throw RiskException.notFound("榜單不存在");
            }
            RiskMyBoard b = myBoards == null ? null : myBoards.selectMineById(userId, site(), id);
            if (b == null)
            {
                throw RiskException.notFound("榜單不存在");
            }
            r.name = b.getBoardName();
            r.my = true;
            r.sys = cfg.board("all");
            r.saved = conditions(b.getConditions());
            r.rankCol = b.getRankCol();
            r.rankDir = b.getRankDir();
            String why = unknownFields(r.saved, cfg);
            if (why != null || (r.rankCol != null && catalog.rank(r.rankCol) == null))
            {
                r.unavailable = true;
                r.reason = why != null ? why : "排行字段「" + r.rankCol + "」已下線";
            }
            return r;
        }
        RiskBoards.Board b = cfg.board(code);
        if (b == null)
        {
            throw RiskException.field("board", "榜單不存在或已停用：" + code);
        }
        r.name = b.name;
        r.sys = b;
        r.rankCol = b.rankCol;
        r.rankDir = b.rankDir;
        r.shareMode = b.shareMode;
        r.shareBase = b.shareBase;
        r.fixedRank = b.isOrder();
        return r;
    }

    /**
     * 渠道风险榜的下钻名单（FY-71 R9、R10）：该推广商 / 渠道里命中风险类型的用户；排行固定按提款从高到低，
     * 默认列取榜单的 drillCols，不显示占比，不能存为我的榜单。
     */
    Resolved drill(Resolved channel, RiskQuery.Drill d, List<String> riskTypes, RiskBoards cfg)
    {
        List<String> types = riskTypes;
        if (StringUtils.isNotEmpty(d.getRiskType()))
        {
            if (!riskTypes.contains(d.getRiskType()))
            {
                throw RiskException.field("drill", "風險類型不在所選範圍內：" + d.getRiskType());
            }
            types = List.of(d.getRiskType());
        }
        Resolved r = new Resolved();
        r.code = channel.code;
        r.sys = cfg.board("all");
        r.rankCol = channel.sys.drillRankCol;
        r.rankDir = "desc";
        r.fixedRank = true;
        r.defaultCols = channel.sys.drillCols;
        List<String> fixed = types;
        String agent = StringUtils.trimToNull(d.getAgentId());
        String ch = StringUtils.trimToNull(d.getChannelId());
        r.filter = u -> (agent == null || agent.equals(u.text(RiskAggregator.AGENT_ID)))
            && (ch == null || ch.equals(u.text(RiskAggregator.CHANNEL_ID))) && riskType(u, fixed, cfg);
        List<String> parts = new ArrayList<>(List.of(channel.name));
        if (ch != null || agent != null)
        {
            parts.add(ch != null ? ch : agent);
        }
        if (StringUtils.isNotEmpty(d.getRiskType()))
        {
            RiskBoards.RiskType t = cfg.riskType(d.getRiskType());
            parts.add(t == null ? d.getRiskType() : t.name);
        }
        r.name = String.join(" › ", parts);
        return r;
    }

    /** 我的榜单里用到已下线或当前站点不支持的字段时的原因；都在返回 null */
    String unknownFields(List<RiskQuery.Condition> conds, RiskBoards cfg)
    {
        for (RiskQuery.Condition c : conds)
        {
            if (RISK_TYPE.equals(c.getField()))
            {
                continue;
            }
            RiskColumn col = catalog.rank(c.getField());
            if (col == null || col.getCondition() == null)
            {
                return "條件「" + (col == null ? c.getField() : col.getName()) + "」已下線";
            }
        }
        return null;
    }

    static List<RiskQuery.Condition> conditions(String json)
    {
        if (StringUtils.isEmpty(json))
        {
            return new ArrayList<>();
        }
        try
        {
            RiskQuery.Condition[] arr = JSON.readValue(json, RiskQuery.Condition[].class);
            return new ArrayList<>(List.of(arr));
        }
        catch (RuntimeException e)
        {
            log.warn("[risk] 我的榜单条件解析失败：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    // ================================================================== 条件判断

    /** 入榜条件（配置里的 Cond） */
    static boolean match(RiskUser u, List<RiskBoards.Cond> conds, RiskBoards cfg, Map<String, BigDecimal> overrides)
    {
        for (RiskBoards.Cond c : conds)
        {
            if (!match(u, c, cfg, overrides))
            {
                return false;
            }
        }
        return true;
    }

    static boolean match(RiskUser u, RiskBoards.Cond c, RiskBoards cfg, Map<String, BigDecimal> overrides)
    {
        if (c.any != null)
        {
            for (RiskBoards.Cond x : c.any)
            {
                if (match(u, x, cfg, overrides))
                {
                    return true;
                }
            }
            return c.any.isEmpty();
        }
        BigDecimal v = u.num(c.field);
        BigDecimal t = c.resolve(cfg.thresholds, overrides);
        if (v == null || t == null)
        {
            return false;
        }
        int cmp = v.compareTo(t);
        return switch (c.op)
        {
            case "GT" -> cmp > 0;
            case "GE" -> cmp >= 0;
            case "LE" -> cmp <= 0;
            default -> cmp < 0;
        };
    }

    /** 名单范围：全部用户的范围（有存款或投注）+ 下钻的固定筛选 */
    static boolean inScope(RiskUser u, Resolved r, RiskBoards cfg)
    {
        return (r.sys == null || match(u, r.sys.scope, cfg, null)) && (r.filter == null || r.filter.test(u));
    }

    /** 名单条件：不同字段「且」，选择字段命中任一 */
    boolean matchList(RiskUser u, List<RiskQuery.Condition> conds, RiskBoards cfg)
    {
        for (RiskQuery.Condition c : conds)
        {
            if (RISK_TYPE.equals(c.getField()))
            {
                if (!riskType(u, c.getValues(), cfg))
                {
                    return false;
                }
                continue;
            }
            RiskColumn col = catalog.rank(c.getField());
            if (col == null)
            {
                return false;
            }
            if ("SELECT".equals(col.getCondition()))
            {
                if (c.getValues() == null || c.getValues().isEmpty())
                {
                    continue;
                }
                String v = u.text(col.getCode());
                if (v == null || !c.getValues().contains(v))
                {
                    return false;
                }
                continue;
            }
            if (!inRange(u.num(col.getCode()), c))
            {
                return false;
            }
        }
        return true;
    }

    /** 范围条件，两端都包含；min、max 都空 = 不限 */
    static boolean inRange(BigDecimal v, RiskQuery.Condition c)
    {
        if (c.getMin() == null && c.getMax() == null)
        {
            return true;
        }
        return v != null && (c.getMin() == null || v.compareTo(c.getMin()) >= 0) && (c.getMax() == null || v.compareTo(c.getMax()) <= 0);
    }

    /** 命中所选风险类型任一（风险类型 = 对应榜单的入榜条件，加上类型自己的附加条件，如高 RTP 要 RTP ≥ 100%） */
    static boolean riskType(RiskUser u, List<String> values, RiskBoards cfg)
    {
        if (values == null || values.isEmpty())
        {
            return true;
        }
        for (RiskBoards.RiskType t : cfg.riskTypes)
        {
            if (values.contains(t.code) && isRiskType(u, t, cfg))
            {
                return true;
            }
        }
        return false;
    }

    static boolean isRiskType(RiskUser u, RiskBoards.RiskType t, RiskBoards cfg)
    {
        RiskBoards.Board b = cfg.board(t.board);
        return b != null && match(u, b.entry, cfg, null) && match(u, t.and, cfg, null);
    }

    /** 校验名单条件：字段要能做条件，范围条件 min ≤ max */
    void validateConditions(List<RiskQuery.Condition> conds, RiskBoards cfg)
    {
        Map<String, String> errors = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (RiskQuery.Condition c : conds)
        {
            String f = c.getField();
            if (StringUtils.isEmpty(f))
            {
                errors.put("conditions", "條件缺少 field");
                continue;
            }
            if (!seen.add(f))
            {
                errors.put("conditions", "條件字段重複：" + f);
                continue;
            }
            if (RISK_TYPE.equals(f))
            {
                Set<String> codes = new HashSet<>();
                cfg.riskTypes.forEach(t -> codes.add(t.code));
                for (String v : c.getValues() == null ? List.<String>of() : c.getValues())
                {
                    if (!codes.contains(v))
                    {
                        errors.put("conditions", "風險類型不存在：" + v);
                    }
                }
                continue;
            }
            RiskColumn col = catalog.rank(f);
            if (col == null || col.getCondition() == null)
            {
                errors.put("conditions", "不支持的條件字段：" + f);
                continue;
            }
            if ("RANGE".equals(col.getCondition()) && c.getMin() != null && c.getMax() != null && c.getMin().compareTo(c.getMax()) > 0)
            {
                errors.put("conditions", "「" + col.getName() + "」的最小值不能大於最大值");
            }
        }
        RiskException.failIfAny(errors);
    }

    // ================================================================== 排名

    /** 排名结果：按名次排好的行（含占比） */
    static final class Ranked
    {
        final List<RiskUser> users = new ArrayList<>();

        final List<BigDecimal> shares = new ArrayList<>();

        final List<BigDecimal> cumShares = new ArrayList<>();

        /** 默认条件下的用户（榜单的入榜条件 / 我的榜单保存的条件），卡片与分母用 */
        final List<RiskUser> entry = new ArrayList<>();

        BigDecimal base;

        boolean shareAvailable;

        /** 条件或排行字段和本榜默认不同（「已修改」） */
        boolean modified;

        /** 我的榜单卡片模板：A 金额类 / B 比率类 / C 其他（FY-68 表 3-1） */
        String template;

        String rankCol;

        String rankDir;
    }

    /** 排行字段的默认方向：榜单配置的，或该字段的风险方向 */
    String defaultDir(Resolved r)
    {
        if (StringUtils.isNotEmpty(r.rankDir))
        {
            return r.rankDir;
        }
        RiskColumn rc = r.rankCol == null ? null : catalog.rank(r.rankCol);
        return rc == null ? "desc" : rc.getRiskDir();
    }

    Ranked rank(RiskRankData data, Resolved r, List<RiskQuery.Condition> listConds, boolean custom, String rankCol, String rankDir,
        Integer topX, RiskBoards cfg)
    {
        Ranked out = new Ranked();
        String col = !r.fixedRank && StringUtils.isNotEmpty(rankCol) ? rankCol : r.rankCol;
        RiskColumn rc = catalog.rank(col);
        if (rc == null || !rc.isRankable())
        {
            throw RiskException.field("rankCol", "不能按「" + (rc == null ? col : rc.getName()) + "」排行");
        }
        String dir = !r.fixedRank && StringUtils.isNotEmpty(rankDir) ? rankDir.toLowerCase(java.util.Locale.ROOT)
            : col.equals(r.rankCol) ? defaultDir(r) : rc.getRiskDir();
        if (!"asc".equals(dir) && !"desc".equals(dir))
        {
            throw RiskException.field("rankDir", "排行方向只能是 asc 或 desc");
        }
        out.rankCol = col;
        out.rankDir = dir;
        out.modified = custom || !listConds.isEmpty() || !col.equals(r.rankCol) || !dir.equals(defaultDir(r));
        // custom：名单条件替换默认条件（入榜条件 / 我的榜单保存的条件）；否则加在默认条件之上
        List<RiskQuery.Condition> listAll = new ArrayList<>(custom ? List.of() : r.saved);
        listAll.addAll(listConds);
        List<RiskUser> list = new ArrayList<>();
        for (RiskUser u : data.users.values())
        {
            if (!inScope(u, r, cfg))
            {
                continue;
            }
            boolean entry = (r.sys == null || match(u, r.sys.entry, cfg, null)) && matchList(u, r.saved, cfg);
            if (entry)
            {
                out.entry.add(u);
            }
            if (!custom && r.sys != null && !match(u, r.sys.entry, cfg, null))
            {
                continue;
            }
            if (listAll.isEmpty() || matchList(u, listAll, cfg))
            {
                list.add(u);
            }
        }
        Comparator<RiskUser> order = byValue(col, "desc".equals(dir));
        list.sort(order);
        out.entry.sort(order);
        boolean amount = amountLike(col, dir, custom ? listConds : listAll, custom ? List.of() : entryConds(r), cfg);
        if (r.my)
        {
            out.template = amount ? "A" : cfg.ratioCols.contains(col) ? "B" : "C";
        }
        // 占比（FY-69）：系统榜没改过用榜单的分母；改过（或我的榜单模板 A）且排行字段为金额类、从高到低时，分母 = 当前名单合计
        BigDecimal base = null;
        if (!r.my && !out.modified && "SHARE".equals(r.shareMode))
        {
            base = positiveSum("ALL".equals(r.shareBase) ? data.users.values() : out.entry, col);
        }
        else if ((r.my || "SHARE".equals(r.shareMode)) && amount)
        {
            base = positiveSum(list, col);
        }
        if (topX != null && topX > 0 && list.size() > topX)
        {
            list = new ArrayList<>(list.subList(0, topX));
        }
        out.users.addAll(list);
        out.shareAvailable = base != null;
        if (out.shareAvailable)
        {
            out.base = base;
            BigDecimal cum = BigDecimal.ZERO;
            for (RiskUser u : out.users)
            {
                BigDecimal v = u.num(col);
                if (v != null)
                {
                    cum = cum.add(v);
                }
                out.shares.add(v == null || base.signum() == 0 ? null : v.divide(base, 6, RoundingMode.HALF_UP));
                out.cumShares.add(base.signum() == 0 ? null : cum.divide(base, 6, RoundingMode.HALF_UP));
            }
        }
        return out;
    }

    private static List<RiskBoards.Cond> entryConds(Resolved r)
    {
        return r.sys == null ? List.of() : r.sys.entry;
    }

    static BigDecimal positiveSum(Collection<RiskUser> users, String col)
    {
        BigDecimal s = BigDecimal.ZERO;
        for (RiskUser u : users)
        {
            BigDecimal v = u.num(col);
            if (v != null && v.signum() > 0)
            {
                s = s.add(v);
            }
        }
        return s;
    }

    static BigDecimal sum(Collection<RiskUser> users, String col)
    {
        BigDecimal s = BigDecimal.ZERO;
        for (RiskUser u : users)
        {
            BigDecimal v = u.num(col);
            s = v == null ? s : s.add(v);
        }
        return s;
    }

    /**
     * 金额类、从高到低：排行字段在 shareCols 里；或在 sharePositiveCols 里（玩家输赢）且条件要求它 &gt; 0
     * （名单条件最小值 &gt; 0，或入榜条件 &gt; 0）。
     */
    static boolean amountLike(String col, String dir, List<RiskQuery.Condition> listConds, List<RiskBoards.Cond> entry, RiskBoards cfg)
    {
        if (!"desc".equals(dir))
        {
            return false;
        }
        if (cfg.shareCols.contains(col))
        {
            return true;
        }
        if (!cfg.sharePositiveCols.contains(col))
        {
            return false;
        }
        for (RiskQuery.Condition c : listConds)
        {
            if (col.equals(c.getField()) && c.getMin() != null && c.getMin().signum() > 0)
            {
                return true;
            }
        }
        for (RiskBoards.Cond c : entry)
        {
            BigDecimal v = c.resolve(cfg.thresholds, null);
            if (col.equals(c.field) && v != null && (("GT".equals(c.op) && v.signum() >= 0) || ("GE".equals(c.op) && v.signum() > 0)))
            {
                return true;
            }
        }
        return false;
    }

    /** 按某列排序，空值排最后；同值按用户 ID，保证名次稳定 */
    static Comparator<RiskUser> byValue(String col, boolean desc)
    {
        return (a, b) -> {
            Object va = a.get(col);
            Object vb = b.get(col);
            if (va == null && vb == null)
            {
                return a.getUserId().compareTo(b.getUserId());
            }
            if (va == null)
            {
                return 1;
            }
            if (vb == null)
            {
                return -1;
            }
            int c = va instanceof BigDecimal x && vb instanceof BigDecimal y ? x.compareTo(y) : String.valueOf(va).compareTo(String.valueOf(vb));
            if (c != 0)
            {
                return desc ? -c : c;
            }
            return a.getUserId().compareTo(b.getUserId());
        };
    }

    // ================================================================== 卡片

    public Map<String, Object> cards(RiskQuery q, Long userId)
    {
        RiskBoards cfg = config.boards(site());
        RiskFilter f = q.filter(site(), props.getMaxSpanDays());
        List<String> codes = q.getBoards().isEmpty() ? cfg.defaultCards : q.getBoards();
        if (codes.size() > 9)
        {
            throw RiskException.field("boards", "最多 9 張卡片");
        }
        List<Integer> bands = q.getBands().isEmpty() ? cfg.bands : q.getBands();
        if (bands.size() > 3 || bands.stream().anyMatch(b -> b == null || b < 1 || b > 500))
        {
            throw RiskException.field("bands", "佔比檔位最多 3 個，取值 1–500");
        }
        RiskRankData data = data(f);
        List<Map<String, Object>> cards = new ArrayList<>();
        for (String code : codes)
        {
            Resolved r = resolve(code, cfg, userId);
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("board", code);
            card.put("name", r.name);
            card.put("group", r.my ? "MY" : r.sys.group());
            if (r.unavailable)
            {
                card.put("unavailable", true);
                card.put("reason", r.reason);
                cards.add(card);
                continue;
            }
            if (r.isOrder())
            {
                orderCard(card, r, f, data, cfg, bands);
            }
            else if (r.isChannel())
            {
                channels.card(card, r, f, data, cfg, riskTypes(q, cfg));
            }
            else
            {
                userCard(card, r, data, cfg, bands);
            }
            cards.add(card);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", data.asOf);
        out.put("dataPending", data.pending);
        out.put("cards", cards);
        return out;
    }

    /** 活跃用户：所选时间内有存款或投注（全部用户榜的范围；没配全部用户榜时 = 有数据的全部用户） */
    static List<RiskUser> active(RiskRankData data, RiskBoards cfg)
    {
        RiskBoards.Board all = cfg.board("all");
        List<RiskUser> out = new ArrayList<>();
        for (RiskUser u : data.users.values())
        {
            if (all == null || match(u, all.scope, cfg, null))
            {
                out.add(u);
            }
        }
        return out;
    }

    private void userCard(Map<String, Object> card, Resolved r, RiskRankData data, RiskBoards cfg, List<Integer> bands)
    {
        Ranked k = rank(data, r, List.of(), false, null, null, null, cfg);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("users", k.users.size());
        summary.put("amount", sum(k.users, k.rankCol));
        summary.put("base", k.base);
        card.put("summary", summary);
        card.put("rankCol", k.rankCol);
        card.put("rankDir", k.rankDir);
        RiskColumn rc = catalog.rank(k.rankCol);
        BigDecimal allRatio = "B".equals(k.template) ? ratioOfSums(active(data, cfg), rc) : null;
        List<Map<String, Object>> stats = new ArrayList<>();
        if (r.my)
        {
            card.put("template", k.template);
            stats.add(stat("entry", "入榜", BigDecimal.valueOf(k.users.size()), "INT"));
            switch (k.template)
            {
                case "A" -> {
                    BigDecimal total = sum(k.users, k.rankCol);
                    BigDecimal all = positiveSum(active(data, cfg), k.rankCol);
                    stats.add(stat("sum", "合計" + rc.getName(), total, rc.getValueFormat()));
                    stats.add(stat("shareOfAll", "佔全部", div(total, all), "PCT"));
                }
                case "B" -> {
                    stats.add(stat("entryRatio", "入榜整體", ratioOfSums(k.users, rc), rc.getValueFormat()));
                    stats.add(stat("allRatio", "全部用戶", allRatio, rc.getValueFormat()));
                }
                default -> {
                    stats.add(stat("min", "最小", k.users.isEmpty() ? null : k.users.get("desc".equals(k.rankDir) ? k.users.size() - 1 : 0).num(k.rankCol),
                        rc.getValueFormat()));
                    stats.add(stat("max", "最大", k.users.isEmpty() ? null : k.users.get("desc".equals(k.rankDir) ? 0 : k.users.size() - 1).num(k.rankCol),
                        rc.getValueFormat()));
                }
            }
        }
        else
        {
            for (RiskBoards.Stat s : r.sys.stats)
            {
                stats.add(userStat(s, k, rc, data, cfg));
            }
        }
        card.put("stats", stats);
        List<Map<String, Object>> top = new ArrayList<>();
        for (int i = 0; i < Math.min(5, k.users.size()); i++)
        {
            RiskUser u = k.users.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", i + 1);
            m.put("userId", u.getUserId());
            m.put("account", mask(u.getAccount()));
            m.put("value", u.get(k.rankCol));
            m.put("share", k.shareAvailable ? k.shares.get(i) : null);
            if (allRatio != null && u.num(k.rankCol) != null)
            {
                // 模板 B：比全部用户高或低多少
                m.put("diff", u.num(k.rankCol).subtract(allRatio));
            }
            top.add(m);
        }
        card.put("top", top);
        if (k.shareAvailable)
        {
            List<Map<String, Object>> bs = new ArrayList<>();
            for (Integer n : bands)
            {
                BigDecimal s = BigDecimal.ZERO;
                for (int i = 0; i < Math.min(n, k.users.size()); i++)
                {
                    BigDecimal v = k.users.get(i).num(k.rankCol);
                    s = v == null ? s : s.add(v);
                }
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("top", n);
                b.put("amount", s);
                b.put("share", div(s, k.base));
                bs.add(b);
            }
            card.put("bands", bs);
        }
        if (r.my)
        {
            return;
        }
        if ("GAME_DIST".equals(r.shareMode) && r.sys.distCol != null)
        {
            card.put("distCol", r.sys.distCol);
            card.put("distribution", distribution(k.users, r.sys.distCol, r.sys.distTop));
        }
        if ("MULTIPLE_BUCKET".equals(r.shareMode) && r.sys.bucketCol != null)
        {
            List<Map<String, Object>> bk = new ArrayList<>();
            for (RiskBoards.Bucket b : r.sys.buckets)
            {
                int n = 0;
                for (RiskUser u : k.users)
                {
                    BigDecimal v = u.num(r.sys.bucketCol);
                    if (v != null && (b.min == null || v.compareTo(b.min) >= 0) && (b.max == null || v.compareTo(b.max) < 0))
                    {
                        n++;
                    }
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("label", b.label);
                m.put("minMultiple", b.min);
                m.put("maxMultiple", b.max);
                m.put("users", n);
                bk.add(m);
            }
            card.put("buckets", bk);
        }
        if ("RTP_DIFF".equals(r.shareMode))
        {
            BigDecimal base = ratioOfSums(data.users.values(), rc);
            card.put("baseRtp", base);
            for (Map<String, Object> m : top)
            {
                BigDecimal v = RiskAggregator.num(m.get("value"));
                m.put("rtpDiffPt", v == null || base == null ? null : v.subtract(base).movePointRight(2).setScale(2, RoundingMode.HALF_UP));
            }
        }
    }

    /** 游戏分布（FY-66 R8）：按人数，前 top 款单独成段，其余（含没有值的人）合并为「其他」 */
    List<Map<String, Object>> distribution(List<RiskUser> users, String col, int top)
    {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int none = 0;
        for (RiskUser u : users)
        {
            String v = u.text(col);
            if (v == null)
            {
                none++;
            }
            else
            {
                counts.merge(v, 1, Integer::sum);
            }
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        Map<String, Map<String, Object>> games = config.games(site());
        List<Map<String, Object>> out = new ArrayList<>();
        int other = none;
        for (int i = 0; i < sorted.size(); i++)
        {
            if (i < top)
            {
                Map<String, Object> g = games.get(sorted.get(i).getKey());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", sorted.get(i).getKey());
                m.put("name", g == null || g.get("name") == null ? sorted.get(i).getKey() : g.get("name"));
                m.put("users", sorted.get(i).getValue());
                out.add(m);
            }
            else
            {
                other += sorted.get(i).getValue();
            }
        }
        if (other > 0)
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", null);
            m.put("name", "其他");
            m.put("users", other);
            out.add(m);
        }
        return out;
    }

    /** 比率列（CALC DIVIDE）在一组用户上的「合计分子 ÷ 合计分母」 */
    static BigDecimal ratioOfSums(Collection<RiskUser> users, RiskColumn c)
    {
        if (c == null || !"DIVIDE".equals(c.getCalcOp()))
        {
            return null;
        }
        BigDecimal a = BigDecimal.ZERO;
        BigDecimal b = BigDecimal.ZERO;
        for (RiskUser u : users)
        {
            BigDecimal x = u.num(c.getCalcLeft());
            BigDecimal y = u.num(c.getCalcRight());
            if (x != null && y != null)
            {
                a = a.add(x);
                b = b.add(y);
            }
        }
        return b.signum() == 0 ? null : a.divide(b, 6, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal v)
    {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    static Map<String, Object> stat(String code, String label, Object value, String format)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("label", label);
        m.put("value", value);
        m.put("valueFormat", format);
        return m;
    }

    static BigDecimal div(BigDecimal a, BigDecimal b)
    {
        return a == null || b == null || b.signum() == 0 ? null : a.divide(b, 6, RoundingMode.HALF_UP);
    }

    /** 卡片上的一个数（算法见 {@link RiskBoards.Stat}） */
    Map<String, Object> userStat(RiskBoards.Stat s, Ranked k, RiskColumn rc, RiskRankData data, RiskBoards cfg)
    {
        RiskColumn col = s.col == null ? rc : catalog.rank(s.col);
        String colCode = col == null ? s.col : col.getCode();
        String fmt = col == null ? null : col.getValueFormat();
        Object v = switch (s.code)
        {
            case "entry" -> {
                fmt = "INT";
                yield BigDecimal.valueOf(k.users.size());
            }
            case "sum", "sumOf" -> sum(k.users, colCode);
            case "activeShare" -> {
                fmt = "PCT";
                yield div(BigDecimal.valueOf(k.users.size()), BigDecimal.valueOf(active(data, cfg).size()));
            }
            case "ratio" -> {
                fmt = "PCT";
                yield div(sum(k.users, s.num), sum(k.users, s.den));
            }
            case "shareOfAll" -> {
                fmt = "PCT";
                yield div(sum(k.users, s.col == null ? k.rankCol : s.col), sum(data.users.values(), s.col == null ? k.rankCol : s.col));
            }
            case "shareOfPositive" -> {
                fmt = "PCT";
                yield div(sum(k.users, colCode), positiveSum(data.users.values(), colCode));
            }
            case "max", "maxOf" -> {
                BigDecimal m = null;
                for (RiskUser u : k.users)
                {
                    BigDecimal x = u.num(colCode);
                    m = x == null ? m : m == null || x.compareTo(m) > 0 ? x : m;
                }
                yield m;
            }
            case "countAtLeast" -> {
                fmt = "INT";
                int n = 0;
                for (RiskUser u : k.users)
                {
                    BigDecimal x = u.num(colCode);
                    if (x != null && s.value != null && x.compareTo(s.value) >= 0)
                    {
                        n++;
                    }
                }
                yield BigDecimal.valueOf(n);
            }
            default -> null;
        };
        return stat(s.code, s.label, v, fmt);
    }

    private void orderCard(Map<String, Object> card, Resolved r, RiskFilter f, RiskRankData data, RiskBoards cfg, List<Integer> bands)
    {
        if (!source.available(RiskDataSource.Kind.WITHDRAW))
        {
            card.put("dataPending", true);
            card.put("summary", Map.of("users", 0, "amount", BigDecimal.ZERO));
            card.put("stats", List.of());
            card.put("top", List.of());
            return;
        }
        List<Map<String, Object>> orders = orders(f, r, cfg, List.of(), data);
        BigDecimal amount = BigDecimal.ZERO;
        for (Map<String, Object> o : orders)
        {
            amount = amount.add((BigDecimal) o.get("amount"));
        }
        BigDecimal allWd = sum(data.users.values(), "wd");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("users", orders.size());
        summary.put("amount", amount);
        summary.put("base", allWd.signum() == 0 ? null : allWd);
        card.put("summary", summary);
        List<Map<String, Object>> stats = new ArrayList<>();
        for (RiskBoards.Stat s : r.sys.stats)
        {
            Object v = switch (s.code)
            {
                case "entry" -> BigDecimal.valueOf(orders.size());
                case "sum" -> amount;
                case "shareOfAll" -> div(amount, sum(data.users.values(), s.col == null ? "wd" : s.col));
                default -> null;
            };
            stats.add(stat(s.code, s.label, v, "entry".equals(s.code) ? "INT" : "sum".equals(s.code) ? "MONEY" : "PCT"));
        }
        card.put("stats", stats);
        List<Map<String, Object>> top = new ArrayList<>();
        for (int i = 0; i < Math.min(5, orders.size()); i++)
        {
            Map<String, Object> o = orders.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", i + 1);
            m.put("orderNo", o.get("orderNo"));
            m.put("userId", o.get("userId"));
            m.put("account", o.get("account"));
            m.put("amount", o.get("amount"));
            m.put("submitTime", o.get("submitTime"));
            m.put("paidTime", o.get("paidTime"));
            m.put("share", div((BigDecimal) o.get("amount"), amount));
            top.add(m);
        }
        card.put("top", top);
        // 头部占比的分母 = 大额提款金额合计（FY-69 表 3-1）
        List<Map<String, Object>> bs = new ArrayList<>();
        for (Integer n : bands)
        {
            BigDecimal s = BigDecimal.ZERO;
            for (int i = 0; i < Math.min(n, orders.size()); i++)
            {
                s = s.add((BigDecimal) orders.get(i).get("amount"));
            }
            bs.add(Map.of("top", n, "amount", s, "share", amount.signum() == 0 ? BigDecimal.ZERO : s.divide(amount, 6, RoundingMode.HALF_UP)));
        }
        card.put("bands", bs);
    }

    /** 大额提款榜名单条件里的单笔提款金额（不是用户字段） */
    public static final String ORDER_AMOUNT = "amount";

    /**
     * 大额提款榜名单条件的校验（FY-70 R3）：单笔提款最小值必填，在榜单条件的上下限内；其余只能是榜单 filters 里的用户字段，都是范围。
     */
    void validateOrderConditions(List<RiskQuery.Condition> conds, Resolved r, RiskBoards cfg)
    {
        Map<String, String> errors = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (RiskQuery.Condition c : conds)
        {
            String fld = c.getField();
            if (StringUtils.isEmpty(fld) || !seen.add(fld))
            {
                errors.put("conditions", StringUtils.isEmpty(fld) ? "條件缺少 field" : "條件字段重複：" + fld);
                continue;
            }
            if (c.getMin() != null && c.getMax() != null && c.getMin().compareTo(c.getMax()) > 0)
            {
                errors.put("conditions", "「" + orderFieldName(fld) + "」的最小值不能大於最大值");
                continue;
            }
            if (ORDER_AMOUNT.equals(fld))
            {
                BigDecimal[] lim = cfg.limits.getOrDefault(r.sys.threshold == null || r.sys.threshold.ref == null ? "" : r.sys.threshold.ref,
                    new BigDecimal[2]);
                if (c.getMin() == null || (lim[0] != null && c.getMin().compareTo(lim[0]) < 0) || (lim[1] != null && c.getMin().compareTo(lim[1]) > 0))
                {
                    errors.put("conditions", "「單筆提款」最小值請填 " + money(lim[0]) + " – " + money(lim[1]) + " 之間的金額");
                }
                continue;
            }
            RiskColumn col = catalog.rank(fld);
            if (!r.sys.filters.contains(fld) || col == null || !"RANGE".equals(col.getCondition()))
            {
                errors.put("conditions", "大額提款榜不支持的條件：" + fld);
            }
        }
        RiskException.failIfAny(errors);
    }

    private String orderFieldName(String fld)
    {
        if (ORDER_AMOUNT.equals(fld))
        {
            return "單筆提款";
        }
        RiskColumn c = catalog.rank(fld);
        return c == null ? fld : c.getName();
    }

    private String money(BigDecimal v)
    {
        String sym = dashboard == null ? "" : StringUtils.trimToEmpty(dashboard.siteCurrencySymbol(site()));
        return v == null ? "" : sym + String.format("%,d", v.longValue());
    }

    /**
     * 大额提款榜的订单（FY-70）：只算成功出款，按申请时间落在所选时间内；单笔 ≥ 门槛（名单条件里的单笔提款优先，否则榜单条件），
     * 再按订单所属用户在所选时间内的数据过滤；按金额从高到低，同额按申请时间晚的在前。
     */
    List<Map<String, Object>> orders(RiskFilter f, Resolved r, RiskBoards cfg, List<RiskQuery.Condition> conds, RiskRankData data)
    {
        RiskQuery.Condition amountCond = null;
        List<RiskQuery.Condition> userConds = new ArrayList<>();
        for (RiskQuery.Condition c : conds)
        {
            if (ORDER_AMOUNT.equals(c.getField()))
            {
                amountCond = c;
            }
            else
            {
                userConds.add(c);
            }
        }
        BigDecimal min = amountCond != null ? amountCond.getMin()
            : r.sys.threshold == null ? null : r.sys.threshold.resolve(cfg.thresholds, null);
        BigDecimal max = amountCond == null ? null : amountCond.getMax();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : source.paidWithdrawals(f, min))
        {
            BigDecimal amount = RiskAggregator.num(row.get("orderAmount"));
            if (amount == null || (min != null && amount.compareTo(min) < 0) || (max != null && amount.compareTo(max) > 0))
            {
                continue;
            }
            String uid = row.get("userId") == null ? null : String.valueOf(row.get("userId"));
            RiskUser u = uid == null ? null : data.users.get(uid);
            boolean ok = true;
            for (RiskQuery.Condition c : userConds)
            {
                if (!inRange(u == null ? null : u.num(c.getField()), c))
                {
                    ok = false;
                }
            }
            if (!ok)
            {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderNo", row.get("orderNo"));
            m.put("userId", uid);
            m.put("account", mask(row.get("account") != null ? String.valueOf(row.get("account")) : u == null ? null : u.getAccount()));
            m.put("amount", amount);
            m.put("submitTime", time(row.get("submitTime")));
            m.put("paidTime", time(row.get("paidTime")));
            m.put("agentId", row.get("agentId"));
            m.put("channelId", row.get("channelId"));
            m.put("user", u);
            out.add(m);
        }
        out.sort(Comparator.<Map<String, Object>, BigDecimal>comparing(m -> (BigDecimal) m.get("amount")).reversed()
            .thenComparing(m -> String.valueOf(m.get("submitTime")), Comparator.reverseOrder())
            .thenComparing(m -> String.valueOf(m.get("orderNo"))));
        return out;
    }

    static String time(Object v)
    {
        if (v == null)
        {
            return null;
        }
        String s = String.valueOf(v).trim().replace('T', ' ');
        return s.length() >= 16 ? s.substring(0, 16) : s;
    }

    /** 账号打码：ab***12；4 位及以下保留首位 */
    public static String mask(String account)
    {
        if (account == null || account.isEmpty())
        {
            return account;
        }
        if (account.length() <= 4)
        {
            return account.charAt(0) + "***";
        }
        return account.substring(0, 2) + "***" + account.substring(account.length() - 2);
    }

    // ================================================================== 完整名单

    /** 列表 / 导出共用：解析参数、排名、排序，返回全部行（未分页） */
    final class ListResult
    {
        Resolved board;

        RiskRankData data;

        Ranked ranked;

        List<String> cols;

        /** ORDER 榜的行 */
        List<Map<String, Object>> orders;

        /** 显示顺序（名次跟着人走）：下标指向 ranked.users / orders */
        List<Integer> order;

        boolean showShare;

        /** 大额提款榜：条件改过（「已修改」） */
        boolean modified;
    }

    /** 渠道风险榜的风险类型：请求里传的 → 个人设置 → 全局默认 → 系统默认 */
    public List<String> riskTypes(RiskQuery q, RiskBoards cfg)
    {
        if (q != null && !q.getRiskTypes().isEmpty())
        {
            List<String> known = cfg.known(q.getRiskTypes());
            if (known.size() != q.getRiskTypes().size())
            {
                throw RiskException.field("riskTypes", "風險類型不存在：" + q.getRiskTypes().stream().filter(x -> !known.contains(x)).toList());
            }
            return known;
        }
        @SuppressWarnings("unchecked")
        List<String> types = (List<String>) cfg.channelRiskTypes(personalRiskTypes.get())[0];
        return types;
    }

    /** 当前用户的个人风险类型（配置位 risk.rank.riskTypes；没设过返回 null） */
    private java.util.function.Supplier<List<String>> personalRiskTypes = () -> null;

    public void setPersonalRiskTypes(java.util.function.Supplier<List<String>> s)
    {
        personalRiskTypes = s == null ? () -> null : s;
    }

    ListResult compute(RiskQuery q, Long userId)
    {
        RiskBoards cfg = config.boards(site());
        RiskFilter f = q.filter(site(), props.getMaxSpanDays());
        Resolved r = resolve(q.getBoard(), cfg, userId);
        if (r.unavailable)
        {
            throw RiskException.field("board", r.reason);
        }
        if (r.isChannel())
        {
            if (q.getDrill() == null)
            {
                throw RiskException.field("board", "渠道風險榜的名單按推廣商 › 渠道返回，請不帶 drill 調用渠道列表");
            }
            r = drill(r, q.getDrill(), riskTypes(q, cfg), cfg);
        }
        if (q.getTopX() != null && (q.getTopX() < 1 || q.getTopX() > 500))
        {
            throw RiskException.field("topX", "Top X 取值 1–500");
        }
        ListResult out = new ListResult();
        out.board = r;
        out.data = data(f);
        out.cols = columns(q, r);
        if (r.isOrder())
        {
            List<RiskQuery.Condition> conds = new ArrayList<>(q.getConditions());
            if (q.getBigThreshold() != null && conds.stream().noneMatch(c -> ORDER_AMOUNT.equals(c.getField())))
            {
                RiskQuery.Condition c = new RiskQuery.Condition();
                c.setField(ORDER_AMOUNT);
                c.setMin(q.getBigThreshold());
                conds.add(c);
            }
            if (q.isCustom() && conds.stream().noneMatch(c -> ORDER_AMOUNT.equals(c.getField())))
            {
                RiskQuery.Condition c = new RiskQuery.Condition();
                c.setField(ORDER_AMOUNT);
                conds.add(c);
            }
            validateOrderConditions(conds, r, cfg);
            out.modified = !conds.isEmpty();
            out.orders = source.available(RiskDataSource.Kind.WITHDRAW) ? orders(f, r, cfg, conds, out.data) : new ArrayList<>();
            out.order = viewOrder(out.orders.size(), i -> out.orders.get(i), q, true);
            return out;
        }
        validateConditions(q.getConditions(), cfg);
        out.ranked = rank(out.data, r, q.getConditions(), q.isCustom(), q.getRankCol(), q.getRankDir(), q.getTopX(), cfg);
        // 下钻名单不显示占比（FY-71 R10）
        out.showShare = r.filter == null && !Boolean.FALSE.equals(q.getShowShare()) && out.ranked.shareAvailable;
        out.modified = out.ranked.modified;
        out.order = viewOrder(out.ranked.users.size(), i -> out.ranked.users.get(i).getValues(), q, false);
        return out;
    }

    /** 表格排序：只改顺序，不改名次；空 = 按名次 */
    List<Integer> viewOrder(int n, java.util.function.IntFunction<Map<String, Object>> row, RiskQuery q, boolean order)
    {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < n; i++)
        {
            idx.add(i);
        }
        String col = q.getViewSortCol();
        if (StringUtils.isEmpty(col))
        {
            return idx;
        }
        boolean desc = !"asc".equalsIgnoreCase(q.getViewSortDir());
        if (!order && catalog.rank(col) == null)
        {
            throw RiskException.field("viewSortCol", "不能按「" + col + "」排序");
        }
        idx.sort((a, b) -> {
            Object va = value(row.apply(a), col);
            Object vb = value(row.apply(b), col);
            if (va == null && vb == null)
            {
                return Integer.compare(a, b);
            }
            if (va == null)
            {
                return 1;
            }
            if (vb == null)
            {
                return -1;
            }
            int c = va instanceof BigDecimal x && vb instanceof BigDecimal y ? x.compareTo(y) : String.valueOf(va).compareTo(String.valueOf(vb));
            return c != 0 ? (desc ? -c : c) : Integer.compare(a, b);
        });
        return idx;
    }

    private static Object value(Map<String, Object> row, String col)
    {
        Object v = row.get(col);
        if (v == null && row.get("user") instanceof RiskUser u)
        {
            v = u.get(col);
        }
        return v;
    }

    /** 要返回的列：请求的（去掉不认识的）；空 = 榜单默认列；排行字段固定在最前 */
    List<String> columns(RiskQuery q, Resolved r)
    {
        List<String> out = new ArrayList<>();
        String rankCol = !r.fixedRank && StringUtils.isNotEmpty(q.getRankCol()) ? q.getRankCol() : r.rankCol;
        if (!r.isOrder())
        {
            out.add(rankCol);
        }
        List<String> defaults = r.defaultCols != null ? r.defaultCols : r.sys != null ? r.sys.cols : List.of();
        List<String> req = q.getCols().isEmpty() ? defaults : q.getCols();
        for (String c : req)
        {
            RiskColumn col = catalog.rank(c);
            if (col == null || !col.isColumn())
            {
                throw RiskException.field("cols", "不認識的列：" + c);
            }
            if (r.isOrder() && !r.sys.colOptions.isEmpty() && !r.sys.colOptions.contains(c))
            {
                throw RiskException.field("cols", "大額提款榜只能加這些列：" + r.sys.colOptions);
            }
            if (!out.contains(c))
            {
                out.add(c);
            }
        }
        // 换了排行字段时，原排行字段那一列保留在最后
        if (!r.isOrder() && r.rankCol != null && !out.contains(r.rankCol))
        {
            out.add(r.rankCol);
        }
        return out;
    }

    public Map<String, Object> list(RiskQuery q, Long userId)
    {
        RiskBoards cfg = config.boards(site());
        if (q.getDrill() == null && isChannelBoard(q.getBoard(), cfg))
        {
            return channels.list(q, resolve(q.getBoard(), cfg, userId), cfg, riskTypes(q, cfg));
        }
        ListResult lr = compute(q, userId);
        int pageSize = pageSize(q);
        int total = lr.order.size();
        int pageNum = q.getPageNum() == null || q.getPageNum() < 1 ? 1 : q.getPageNum();
        Map<String, Object> locate = null;
        if (StringUtils.isNotEmpty(q.getLocateKey()))
        {
            locate = new LinkedHashMap<>();
            locate.put("found", false);
            Integer pos = locate(lr, q.getLocateKey().trim());
            if (pos != null)
            {
                pageNum = pos / pageSize + 1;
                locate.put("found", true);
                locate.put("pageNum", pageNum);
                locate.put("index", pos % pageSize);
                if (lr.orders != null)
                {
                    locate.put("count", matches(lr, q.getLocateKey().trim()).size());
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", lr.data.asOf);
        out.put("dataPending", lr.data.pending || (lr.orders != null && !source.available(RiskDataSource.Kind.WITHDRAW)));
        out.put("board", q.getBoard());
        out.put("name", lr.board.name);
        out.put("total", total);
        out.put("pageNum", pageNum);
        out.put("pageSize", pageSize);
        out.put("modified", lr.modified);
        out.put("columns", columnMeta(lr.cols, lr.data));
        List<Map<String, Object>> rows = new ArrayList<>();
        int from = (pageNum - 1) * pageSize;
        for (int pos = from; pos < Math.min(total, from + pageSize); pos++)
        {
            rows.add(row(lr, lr.order.get(pos)));
        }
        if (lr.orders != null)
        {
            BigDecimal sum = BigDecimal.ZERO;
            for (Map<String, Object> o : lr.orders)
            {
                sum = sum.add((BigDecimal) o.get("amount"));
            }
            out.put("totals", Map.of("count", total, "amount", sum));
        }
        else
        {
            out.put("rankCol", lr.ranked.rankCol);
            out.put("rankDir", lr.ranked.rankDir);
            out.put("totals", totals(lr.ranked.users, lr.cols));
            out.put("shareAvailable", lr.showShare);
            if (lr.board.filter != null)
            {
                out.put("riskTypes", riskTypes(q, cfg));
            }
        }
        out.put("rows", rows);
        out.put("locate", locate);
        return out;
    }

    private static int pageSize(RiskQuery q)
    {
        int pageSize = q.getPageSize() == null ? 20 : q.getPageSize();
        if (!PAGE_SIZES.contains(pageSize))
        {
            throw RiskException.field("pageSize", "每頁只能 20、50 或 100 條");
        }
        return pageSize;
    }

    private static boolean isChannelBoard(String code, RiskBoards cfg)
    {
        RiskBoards.Board b = code == null || code.startsWith("m:") ? null : cfg.board(code);
        return b != null && b.isChannel();
    }

    /** 名单里匹配 key（用户 ID、账号，大额提款榜还认订单号）的显示位置 */
    List<Integer> matches(ListResult lr, String key)
    {
        List<Integer> out = new ArrayList<>();
        for (int pos = 0; pos < lr.order.size(); pos++)
        {
            int i = lr.order.get(pos);
            String uid;
            String acct;
            String orderNo = null;
            if (lr.orders != null)
            {
                Map<String, Object> o = lr.orders.get(i);
                uid = String.valueOf(o.get("userId"));
                acct = o.get("user") instanceof RiskUser u ? u.getAccount() : null;
                orderNo = String.valueOf(o.get("orderNo"));
            }
            else
            {
                RiskUser u = lr.ranked.users.get(i);
                uid = u.getUserId();
                acct = u.getAccount();
            }
            if (key.equals(uid) || key.equalsIgnoreCase(String.valueOf(acct)) || key.equals(orderNo))
            {
                out.add(pos);
            }
        }
        return out;
    }

    /** 定位：用户类榜单取那一行；大额提款榜按账号合并，定位到最近一笔（申请时间最晚，FY-67 R18） */
    Integer locate(ListResult lr, String key)
    {
        List<Integer> hits = matches(lr, key);
        if (hits.isEmpty())
        {
            return null;
        }
        if (lr.orders == null)
        {
            return hits.get(0);
        }
        Integer best = null;
        String bestTime = null;
        for (Integer pos : hits)
        {
            Map<String, Object> o = lr.orders.get(lr.order.get(pos));
            if (key.equals(String.valueOf(o.get("orderNo"))))
            {
                return pos;
            }
            String t = String.valueOf(o.get("submitTime"));
            if (bestTime == null || t.compareTo(bestTime) > 0)
            {
                best = pos;
                bestTime = t;
            }
        }
        return best;
    }

    /**
     * 名单内搜索（FY-67 R18）：账号包含或用户 ID 开头，2 个字符起，最多 8 条，按名单当前顺序；
     * 每条给名次与所在页；大额提款榜按账号合并，写笔数并定位到最近一笔。渠道风险榜的渠道列表只搜全部用户。
     */
    public Map<String, Object> search(RiskQuery q, Long userId)
    {
        String kw = StringUtils.trimToEmpty(q.getKeyword());
        if (kw.length() < 2)
        {
            throw RiskException.field("keyword", "至少輸入 2 個字");
        }
        RiskBoards cfg = config.boards(site());
        if (q.getDrill() == null && isChannelBoard(q.getBoard(), cfg))
        {
            throw RiskException.field("board", "渠道列表只能搜尋全部用戶");
        }
        ListResult lr = compute(q, userId);
        int pageSize = pageSize(q);
        String low = kw.toLowerCase(java.util.Locale.ROOT);
        Map<String, List<Integer>> byUser = new LinkedHashMap<>();
        Map<String, String> accounts = new LinkedHashMap<>();
        for (int pos = 0; pos < lr.order.size(); pos++)
        {
            int i = lr.order.get(pos);
            String uid;
            String acct;
            if (lr.orders != null)
            {
                Map<String, Object> o = lr.orders.get(i);
                uid = String.valueOf(o.get("userId"));
                acct = o.get("user") instanceof RiskUser u ? u.getAccount() : null;
            }
            else
            {
                RiskUser u = lr.ranked.users.get(i);
                uid = u.getUserId();
                acct = u.getAccount();
            }
            boolean hit = uid.startsWith(kw) || (acct != null && acct.toLowerCase(java.util.Locale.ROOT).contains(low));
            if (!hit)
            {
                continue;
            }
            if (!byUser.containsKey(uid) && byUser.size() >= 8)
            {
                continue;
            }
            byUser.computeIfAbsent(uid, k -> new ArrayList<>()).add(pos);
            accounts.putIfAbsent(uid, acct);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : byUser.entrySet())
        {
            Integer pos = lr.orders == null ? e.getValue().get(0) : locate(lr, e.getKey());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", e.getKey());
            m.put("account", mask(accounts.get(e.getKey())));
            m.put("rank", lr.order.get(pos) + 1);
            m.put("pageNum", pos / pageSize + 1);
            m.put("index", pos % pageSize);
            if (lr.orders != null)
            {
                m.put("count", e.getValue().size());
                m.put("orderNo", lr.orders.get(lr.order.get(pos)).get("orderNo"));
            }
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keyword", kw);
        out.put("items", items);
        return out;
    }

    Map<String, Object> row(ListResult lr, int i)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        if (lr.orders != null)
        {
            Map<String, Object> o = lr.orders.get(i);
            RiskUser u = o.get("user") instanceof RiskUser x ? x : null;
            m.put("rank", i + 1);
            m.put("orderNo", o.get("orderNo"));
            m.put("userId", o.get("userId"));
            m.put("account", o.get("account"));
            m.put("amount", o.get("amount"));
            m.put("submitTime", o.get("submitTime"));
            m.put("paidTime", o.get("paidTime"));
            m.put("agentId", o.get("agentId"));
            m.put("channelId", o.get("channelId"));
            m.put("regDays", u == null ? null : u.get("regDays"));
            m.put("values", values(u, lr.cols, lr.data));
            return m;
        }
        RiskUser u = lr.ranked.users.get(i);
        m.put("rank", i + 1);
        m.put("userId", u.getUserId());
        m.put("account", mask(u.getAccount()));
        m.put("values", values(u, lr.cols, lr.data));
        if (lr.showShare)
        {
            m.put("share", lr.ranked.shares.get(i));
            m.put("cumShare", lr.ranked.cumShares.get(i));
        }
        return m;
    }

    Map<String, Object> values(RiskUser u, List<String> cols, RiskRankData data)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        Map<String, Map<String, Object>> games = null;
        for (String c : cols)
        {
            if (u == null || data.unavailable.containsKey(c))
            {
                v.put(c, null);
                continue;
            }
            RiskColumn col = catalog.rank(c);
            Object x = u.get(c);
            if (x != null && col != null && !col.isNumber() && "game".equals(col.getUdsField()))
            {
                // 游戏编码 → 名称（游戏字典）
                if (games == null)
                {
                    games = config.games(site());
                }
                Map<String, Object> g = games.get(String.valueOf(x));
                x = g == null || g.get("name") == null ? x : g.get("name");
            }
            v.put(c, x);
        }
        return v;
    }

    /** 合计行：可相加的列求和；比率列用合计分子 ÷ 合计分母；其余为空 */
    Map<String, Object> totals(List<RiskUser> users, List<String> cols)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String c : cols)
        {
            RiskColumn col = catalog.rank(c);
            if (col == null || !col.isNumber())
            {
                continue;
            }
            if ("SUM".equals(col.getAgg()) && !col.isCalc())
            {
                BigDecimal s = BigDecimal.ZERO;
                for (RiskUser u : users)
                {
                    BigDecimal v = u.num(c);
                    s = v == null ? s : s.add(v);
                }
                out.put(c, s);
            }
            else if ("DIVIDE".equals(col.getCalcOp()))
            {
                out.put(c, ratioOfSums(users, col));
            }
            else if ("SUBTRACT".equals(col.getCalcOp()) || "ADD".equals(col.getCalcOp()))
            {
                BigDecimal s = BigDecimal.ZERO;
                for (RiskUser u : users)
                {
                    BigDecimal v = u.num(c);
                    s = v == null ? s : s.add(v);
                }
                out.put(c, s);
            }
        }
        return out;
    }

    List<Map<String, Object>> columnMeta(List<String> cols, RiskRankData data)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String c : cols)
        {
            RiskColumn col = catalog.rank(c);
            if (col == null)
            {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", c);
            m.put("name", col.getName());
            m.put("group", col.getGroup());
            m.put("valueFormat", col.getValueFormat());
            m.put("kind", col.getKind());
            if (data.unavailable.containsKey(c))
            {
                m.put("note", data.unavailable.get(c));
            }
            out.add(m);
        }
        return out;
    }

    /** 导出的表头与行（第一列「名次」） */
    public RiskSheet export(RiskQuery q, Long userId)
    {
        RiskBoards cfg = config.boards(site());
        if (q.getDrill() == null && isChannelBoard(q.getBoard(), cfg))
        {
            return channels.export(q, resolve(q.getBoard(), cfg, userId), cfg, riskTypes(q, cfg));
        }
        ListResult lr = compute(q, userId);
        if (lr.order.size() > props.getMaxExportRows())
        {
            throw RiskException.tooLarge("超過 " + String.format("%,d", props.getMaxExportRows()) + " 行，請縮小範圍");
        }
        StringBuilder title = new StringBuilder();
        title.append(dashboard == null ? "" : StringUtils.trimToEmpty(dashboard.siteName(site()))).append(" · 風控分析 · ")
            .append(lr.board.name).append(" · ").append(q.getSlotFrom()).append(" – ").append(q.getSlotTo());
        if (lr.board.filter != null)
        {
            title.append(" · 風險用戶＝").append(riskTypeNames(riskTypes(q, cfg), cfg));
        }
        if (!q.getConditions().isEmpty())
        {
            title.append(" · 條件：").append(conditionText(q.getConditions()));
        }
        RiskSheet sheet = new RiskSheet(lr.board.name, "");
        sheet.column("名次", RiskSheet.INT);
        if (lr.orders != null)
        {
            sheet.column("提款時間", RiskSheet.TEXT).column("訂單號", RiskSheet.TEXT).column("金額", RiskSheet.MONEY)
                .column("用戶帳號", RiskSheet.TEXT).column("用戶 ID", RiskSheet.TEXT).column("註冊天數", RiskSheet.INT);
        }
        else
        {
            RiskColumn rc = catalog.rank(lr.ranked.rankCol);
            title.append(" · 排行：").append(rc.getName()).append("desc".equals(lr.ranked.rankDir) ? " 從高到低" : " 從低到高");
            sheet.column("用戶帳號", RiskSheet.TEXT).column("用戶 ID", RiskSheet.TEXT);
        }
        if (StringUtils.isNotEmpty(q.getViewSortCol()))
        {
            RiskColumn vc = catalog.rank(q.getViewSortCol());
            title.append(" · 表格按").append(vc == null ? q.getViewSortCol() : vc.getName()).append("asc".equalsIgnoreCase(q.getViewSortDir()) ? "從低到高" : "從高到低").append("排列");
        }
        title.append(" · 資料截至 ").append(lr.data.asOf);
        sheet.title = title.toString();
        for (String c : lr.cols)
        {
            RiskColumn col = catalog.rank(c);
            sheet.column(col == null ? c : col.getName(), col == null || !col.isNumber() ? RiskSheet.TEXT : RiskSheet.format(col.getValueFormat()));
        }
        if (lr.showShare)
        {
            sheet.column("佔比", RiskSheet.PCT).column("累計佔比", RiskSheet.PCT);
        }
        // 合计行放在表头下第一行
        Object[] total = sheet.newRow();
        total[0] = "合計（" + lr.order.size() + (lr.orders != null ? " 筆）" : " 人）");
        int offset = lr.orders != null ? 7 : 3;
        if (lr.orders != null)
        {
            BigDecimal sum = BigDecimal.ZERO;
            for (Map<String, Object> o : lr.orders)
            {
                sum = sum.add((BigDecimal) o.get("amount"));
            }
            total[3] = sum;
        }
        else
        {
            Map<String, Object> t = totals(lr.ranked.users, lr.cols);
            for (int j = 0; j < lr.cols.size(); j++)
            {
                total[offset + j] = cell(t.get(lr.cols.get(j)), catalog.rank(lr.cols.get(j)));
            }
        }
        for (int i : lr.order)
        {
            Map<String, Object> r = row(lr, i);
            Object[] x = sheet.newRow();
            x[0] = BigDecimal.valueOf(((Number) r.get("rank")).longValue());
            if (lr.orders != null)
            {
                Map<String, Object> o = lr.orders.get(i);
                RiskUser u = o.get("user") instanceof RiskUser uu ? uu : null;
                x[1] = r.get("submitTime");
                x[2] = r.get("orderNo");
                x[3] = r.get("amount");
                x[4] = u == null ? r.get("account") : u.getAccount();
                x[5] = r.get("userId");
                x[6] = RiskAggregator.num(r.get("regDays"));
            }
            else
            {
                RiskUser u = lr.ranked.users.get(i);
                x[1] = u.getAccount();
                x[2] = u.getUserId();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> vals = (Map<String, Object>) r.get("values");
            for (int j = 0; j < lr.cols.size(); j++)
            {
                x[offset + j] = cell(vals.get(lr.cols.get(j)), catalog.rank(lr.cols.get(j)));
            }
            if (lr.showShare)
            {
                x[offset + lr.cols.size()] = pct(r.get("share"));
                x[offset + lr.cols.size() + 1] = pct(r.get("cumShare"));
            }
        }
        sheet.fileName = (dashboard == null ? "" : StringUtils.trimToEmpty(dashboard.siteName(site()))) + "_" + lr.board.name + "_"
            + compact(q.getSlotFrom()) + "-" + compact(q.getSlotTo());
        return sheet;
    }

    private static String compact(String slot)
    {
        return slot == null ? "" : slot.replaceAll("[^0-9]", "").substring(0, Math.min(8, slot.replaceAll("[^0-9]", "").length()));
    }

    static Object cell(Object v, RiskColumn col)
    {
        if (v == null)
        {
            return null;
        }
        if (col != null && col.isNumber() && v instanceof BigDecimal b)
        {
            return "PCT".equals(col.getValueFormat()) ? b.movePointRight(2) : b;
        }
        return String.valueOf(v);
    }

    static Object pct(Object v)
    {
        return v instanceof BigDecimal b ? b.movePointRight(2) : null;
    }

    static String riskTypeNames(List<String> codes, RiskBoards cfg)
    {
        List<String> names = new ArrayList<>();
        for (String c : codes)
        {
            RiskBoards.RiskType t = cfg.riskType(c);
            names.add(t == null ? c : t.name);
        }
        return String.join(" / ", names);
    }

    String conditionText(List<RiskQuery.Condition> conds)
    {
        List<String> parts = new ArrayList<>();
        for (RiskQuery.Condition c : conds)
        {
            RiskColumn col = catalog.rank(c.getField());
            String name = ORDER_AMOUNT.equals(c.getField()) ? "單筆提款" : RISK_TYPE.equals(c.getField()) ? "風險類型"
                : col == null ? c.getField() : col.getName();
            if (c.getValues() != null)
            {
                parts.add(name + "＝" + String.join("、", c.getValues()));
            }
            else
            {
                parts.add(name + " " + (c.getMin() == null ? "" : plain(c.getMin())) + "–" + (c.getMax() == null ? "" : plain(c.getMax())));
            }
        }
        return String.join("；", parts);
    }

    /** 测试用 */
    void clearCache()
    {
        cache.clear();
    }

    /** 用户集合里所有用户 ID（游戏抽屉「常玩此游戏」用） */
    public Set<String> userIds(RiskRankData data)
    {
        return new LinkedHashSet<>(data.users.keySet());
    }
}
