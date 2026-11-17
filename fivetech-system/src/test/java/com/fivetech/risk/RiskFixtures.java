package com.fivetech.risk;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fivetech.configcenter.ConfigCenterService;
import com.fivetech.configcenter.domain.ConfigItem;
import com.fivetech.configcenter.mapper.ConfigItemMapper;
import com.fivetech.dashboard.domain.MetricDictItem;
import com.fivetech.risk.catalog.RiskCatalog;
import com.fivetech.risk.config.RiskConfigService;
import com.fivetech.risk.data.RiskDataSource;
import com.fivetech.risk.data.RiskFilter;
import com.fivetech.risk.mapper.RiskMyBoardMapper;
import com.fivetech.risk.my.RiskMyBoard;
import com.fivetech.risk.rank.RiskRankService;
import tools.jackson.databind.json.JsonMapper;

/**
 * 风控单测的公共数据：指标管理里的排行榜 / 游戏分析列（同 SQL 里登记的口径）、内存数据源、配置中心、我的榜单。
 */
public final class RiskFixtures
{
    public final List<MetricDictItem> rankRows = new ArrayList<>();

    public final List<MetricDictItem> gameRows = new ArrayList<>();

    public final FakeSource source = new FakeSource();

    /** 配置中心：编码 → 值（全站通用） */
    public final Map<String, ConfigItem> configs = new LinkedHashMap<>();

    public final Map<Long, RiskMyBoard> boards = new LinkedHashMap<>();

    public final RiskProperties props = new RiskProperties();

    public RiskCatalog catalog;

    public RiskConfigService config;

    public RiskRankService rank;

    public static final LocalDate TODAY = LocalDate.now();

    public RiskFixtures()
    {
        props.setCacheSeconds(0);
        props.setCatalogCacheSeconds(0);
        rank("dep", "存款", "MONEY", "dep_amt", m("group", "FUND", "source", "USER_DAY", "condition", "RANGE", "common", true));
        rank("wd", "提款", "MONEY", "wd_amt", m("group", "FUND", "source", "USER_DAY", "condition", "RANGE", "common", true));
        rank("wdN", "提款筆數", "INT", "wd_cnt", m("group", "FUND", "source", "USER_DAY"));
        rank("bet", "有效投注", "MONEY", "bet_amt", m("group", "GAME", "source", "USER_GAME", "condition", "RANGE", "common", true));
        rank("pay", "派彩", "MONEY", "pay_amt", m("group", "GAME", "source", "USER_GAME"));
        rank("gWinLoss", "遊戲輸贏", "MONEY", "win_loss", m("group", "GAME", "source", "USER_GAME", "column", false, "rankable", false));
        rank("winLoss", "玩家輸贏", "MONEY", null, m("group", "GAME", "source", "CALC", "calc", m("op", "SUBTRACT", "left", "pay", "right", "bet"),
            "condition", "RANGE", "common", true));
        rank("rtp", "RTP", "PCT", null, m("group", "GAME", "source", "CALC", "calc", m("op", "DIVIDE", "left", "pay", "right", "bet"), "condition", "RANGE"));
        rank("net", "存提差", "MONEY", null, m("group", "FUND", "source", "CALC", "calc", m("op", "SUBTRACT", "left", "dep", "right", "wd"), "riskDir", "asc"));
        rank("turnX", "打碼倍數", "MULTIPLE", null, m("group", "FUND", "source", "CALC", "calc", m("op", "DIVIDE", "left", "bet", "right", "dep"),
            "riskDir", "asc", "condition", "RANGE"));
        rank("maxPayout", "單筆最大派彩", "MONEY", "max_payout", m("group", "GAME", "source", "USER_GAME", "agg", "MAX",
            "tieBreak", m("ratioOf", "maxPayoutBet", "timeOf", "winTime"), "condition", "RANGE"));
        rank("maxPayoutBet", "該筆投注額", "MONEY", "max_payout_bet", m("group", "GAME", "source", "USER_GAME", "agg", "FOLLOW", "follow", "maxPayout",
            "rankable", false));
        rank("winTime", "中獎時間", "INT", "max_payout_time", m("group", "GAME", "source", "USER_GAME", "kind", "TIME", "agg", "FOLLOW", "follow", "maxPayout"));
        rank("winGame", "中獎遊戲", "INT", "game", m("group", "GAME", "source", "USER_GAME", "kind", "TEXT", "agg", "FOLLOW", "follow", "maxPayout",
            "condition", "SELECT"));
        rank("payoutX", "派彩倍數", "MULTIPLE", null, m("group", "GAME", "source", "CALC", "calc", m("op", "DIVIDE", "left", "maxPayout", "right", "maxPayoutBet")));
        rank("profitGame", "盈利遊戲", "INT", "game", m("group", "GAME", "source", "USER_GAME", "kind", "TEXT", "agg", "ARGMAX", "argOf", "gWinLoss",
            "positive", true, "tieBy", "bet", "condition", "SELECT"));
        rank("topGame", "投注最多的遊戲", "INT", "game", m("group", "GAME", "source", "USER_GAME", "kind", "TEXT", "agg", "ARGMAX", "argOf", "bet",
            "column", false));
        rank("regTime", "註冊時間", "INT", "regTime", m("group", "ACCOUNT", "source", "DIM", "kind", "TIME", "riskDir", "desc"));
        rank("regDays", "註冊天數", "INT", null, m("group", "ACCOUNT", "source", "CALC", "calc", m("op", "DAYS_SINCE", "left", "regTime"),
            "riskDir", "asc", "condition", "RANGE", "column", false));
        rank("parent", "上級帳號", "INT", "parent", m("group", "ACCOUNT", "source", "DIM", "kind", "TEXT", "condition", "SELECT"));
        rank("channel", "推廣渠道", "INT", "channelId", m("group", "ACCOUNT", "source", "DIM", "kind", "TEXT", "rankable", false));
        rank("firstDep", "首存時間", "INT", "first_dep_time", m("group", "HISTORY", "source", "SNAPSHOT", "kind", "TIME"));

        game("bet", "有效投注", "MONEY", "bet_amt", m("group", "BET", "standard", true));
        game("betU", "投注人數", "INT", "bet_users", m("group", "BET", "standard", true));
        game("pay", "派彩", "MONEY", "pay_amt", m("group", "WINLOSS"));
        game("ggr", "GGR", "MONEY", "ggr", m("group", "WINLOSS", "standard", true));
        game("rtp", "RTP", "PCT", null, m("group", "WINLOSS", "standard", true, "calc", m("op", "DIVIDE", "left", "pay", "right", "bet")));

        catalog = RiskCatalog.of(props, scope -> "RISK_RANK".equals(scope) ? rankRows : gameRows);
        ConfigItemMapper cm = (ConfigItemMapper) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { ConfigItemMapper.class },
            (p, method, args) -> switch (method.getName())
            {
                case "selectOne" -> "".equals(args[1]) || args[1] == null ? configs.get((String) args[0]) : null;
                case "countKey" -> 0;
                case "insert" -> {
                    ConfigItem it = (ConfigItem) args[0];
                    it.setId((long) (configs.size() + 1));
                    configs.put(it.getConfigCode(), it);
                    yield 1;
                }
                case "update" -> {
                    ConfigItem it = (ConfigItem) args[0];
                    configs.put(it.getConfigCode(), it);
                    yield 1;
                }
                case "selectById" -> configs.values().stream().filter(c -> c.getId().equals(args[0])).findFirst().orElse(null);
                default -> throw new UnsupportedOperationException(method.getName());
            });
        ConfigCenterService centre = new ConfigCenterService(cm, null, JsonMapper.builder().build());
        config = new RiskConfigService(centre, catalog);
        rank = new RiskRankService(props, catalog, source, config, myBoards(), null);
    }

    RiskMyBoardMapper myBoards()
    {
        return (RiskMyBoardMapper) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { RiskMyBoardMapper.class },
            (p, method, args) -> switch (method.getName())
            {
                case "selectMineById" -> {
                    RiskMyBoard b = boards.get((Long) args[2]);
                    yield b != null && b.getUserId().equals(args[0]) ? b : null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    void rank(String code, String name, String format, String field, Map<String, Object> ext)
    {
        rankRows.add(row("rk_" + code, name, format, field, code, ext, rankRows.size()));
    }

    void game(String code, String name, String format, String field, Map<String, Object> ext)
    {
        gameRows.add(row("gm_" + code, name, format, field, code, ext, gameRows.size()));
    }

    static MetricDictItem row(String metricCode, String name, String format, String field, String api, Map<String, Object> ext, int sort)
    {
        MetricDictItem it = new MetricDictItem();
        it.setMetricCode(metricCode);
        it.setMetricName(name);
        it.setValueFormat(format);
        it.setUdsDayField(field);
        it.setStatus("0");
        it.setSortNo(sort * 10);
        Map<String, Object> e = new LinkedHashMap<>(ext);
        e.put("apiCode", api);
        it.setExt(e);
        return it;
    }

    /** 整数不带小数位（与 UDS 返回的整数一致） */
    static BigDecimal bd(double d)
    {
        return d == Math.rint(d) ? new BigDecimal(String.valueOf((long) d)) : new BigDecimal(String.valueOf(d));
    }

    public static Map<String, Object> m(Object... kv)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2)
        {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    /** 用户 × 日：一行 = 一个用户（UDS 按区间汇总） */
    public void user(String id, String account, int regAgo, String parent, double dep, double wd, int wdN)
    {
        source.userDays.add(m("userId", id, "account", account, "regTime", TODAY.minusDays(regAgo) + " 10:00:00", "parent", parent,
            "dep_amt", bd(dep), "wd_amt", bd(wd), "wd_cnt", bd(wdN)));
    }

    /** 给用户设推广商 / 推广渠道 */
    public void channel(String id, String agent, String channel)
    {
        for (Map<String, Object> r : source.userDays)
        {
            if (id.equals(r.get("userId")))
            {
                r.put("agentId", agent);
                r.put("channelId", channel);
            }
        }
    }

    /** 用户 × 游戏 × 日 */
    public void play(String id, String game, String date, double bet, double pay, double maxPayout, double maxBet, String winTime)
    {
        source.userGameDays.add(m("userId", id, "game", game, "date", date, "bet_amt", bd(bet), "pay_amt", bd(pay),
            "win_loss", bd(pay - bet), "max_payout", bd(maxPayout), "max_payout_bet", bd(maxBet),
            "max_payout_time", winTime));
    }

    public RiskQuery query(String board)
    {
        RiskQuery q = new RiskQuery();
        q.setSlotFrom(TODAY.minusDays(7) + " 00:00");
        q.setSlotTo(TODAY.plusDays(1) + " 00:00");
        q.setBoard(board);
        return q;
    }

    /** 内存数据源 */
    public static final class FakeSource implements RiskDataSource
    {
        public final Set<Kind> on = EnumSet.allOf(Kind.class);

        public final List<Map<String, Object>> userDays = new ArrayList<>();

        public final List<Map<String, Object>> userGameDays = new ArrayList<>();

        public final List<Map<String, Object>> snapshots = new ArrayList<>();

        public final List<Map<String, Object>> orders = new ArrayList<>();

        /** 渠道获客（注册、首存） */
        public final List<Map<String, Object>> acquisition = new ArrayList<>();

        /** 用户资料：userId → 行 */
        public final Map<String, Map<String, Object>> profiles = new LinkedHashMap<>();

        /** 用户订单：DEPOSIT / WITHDRAW / BONUS → 行 */
        public final Map<Kind, List<Map<String, Object>>> userOrders = new java.util.EnumMap<>(Kind.class);

        /** 用户序列：USER_DAY / USER_GAME → 行 */
        public final Map<Kind, List<Map<String, Object>>> series = new java.util.EnumMap<>(Kind.class);

        public String lastGranularity;

        /** 游戏数据：维度键（如 "platform=PG|game=g1"）→ 行 */
        public final Map<String, Map<String, Object>> games = new LinkedHashMap<>();

        public final List<Map<String, Object>> gameDays = new ArrayList<>();

        /** 游戏抽屉的汇总：游戏编码 → 行 */
        public final Map<String, Map<String, Object>> gameTotals = new LinkedHashMap<>();

        public int userDayCalls;

        public BigDecimal lastMin;

        public final List<String> gameQueries = new ArrayList<>();

        @Override
        public boolean available(Kind kind)
        {
            return on.contains(kind);
        }

        @Override
        public List<Map<String, Object>> userDays(RiskFilter filter, Collection<String> fields)
        {
            userDayCalls++;
            return only(copy(userDays), filter);
        }

        @Override
        public List<Map<String, Object>> userGameDays(RiskFilter filter, Collection<String> fields)
        {
            return only(copy(userGameDays), filter);
        }

        static List<Map<String, Object>> only(List<Map<String, Object>> rows, RiskFilter f)
        {
            if (f != null && !f.userIds.isEmpty())
            {
                rows.removeIf(r -> !f.userIds.contains(String.valueOf(r.get("userId"))));
            }
            return rows;
        }

        @Override
        public List<Map<String, Object>> channelAcquisition(RiskFilter filter)
        {
            return copy(acquisition);
        }

        @Override
        public List<Map<String, Object>> searchUsers(String keyword, int limit)
        {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> p : profiles.values())
            {
                if ((String.valueOf(p.get("account")).contains(keyword) || String.valueOf(p.get("userId")).startsWith(keyword)) && out.size() < limit)
                {
                    out.add(new LinkedHashMap<>(p));
                }
            }
            return out;
        }

        @Override
        public Map<String, Object> userProfile(String userId)
        {
            Map<String, Object> p = profiles.get(userId);
            return p == null ? null : new LinkedHashMap<>(p);
        }

        @Override
        public List<Map<String, Object>> userSeries(Kind kind, String userId, java.time.LocalDateTime from, java.time.LocalDateTime to,
            String granularity, Collection<String> fields)
        {
            lastGranularity = granularity;
            return copy(series.getOrDefault(kind, List.of()));
        }

        @Override
        public List<Map<String, Object>> userOrders(Kind kind, String userId, java.time.LocalDateTime from, java.time.LocalDateTime to)
        {
            return copy(userOrders.getOrDefault(kind, List.of()));
        }

        @Override
        public List<Map<String, Object>> snapshots(Collection<String> userIds, Collection<String> fields)
        {
            return copy(snapshots);
        }

        @Override
        public List<Map<String, Object>> paidWithdrawals(RiskFilter filter, BigDecimal minAmount)
        {
            lastMin = minAmount;
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> o : orders)
            {
                if (minAmount == null || ((BigDecimal) o.get("orderAmount")).compareTo(minAmount) >= 0)
                {
                    out.add(new LinkedHashMap<>(o));
                }
            }
            return out;
        }

        @Override
        public List<Map<String, Object>> games(RiskFilter filter, List<String> dims, Map<String, String> eq, Collection<String> fields)
        {
            gameQueries.add(dims + " " + eq);
            List<Map<String, Object>> out = new ArrayList<>();
            if (dims.isEmpty() && eq.containsKey("game"))
            {
                Map<String, Object> g = gameTotals.get(eq.get("game"));
                if (g != null)
                {
                    out.add(new LinkedHashMap<>(g));
                }
                return out;
            }
            for (Map.Entry<String, Map<String, Object>> e : games.entrySet())
            {
                Map<String, String> key = new LinkedHashMap<>();
                if (!e.getKey().isEmpty())
                {
                    for (String part : e.getKey().split("\\|"))
                    {
                        String[] kv = part.split("=");
                        key.put(kv[0], kv[1]);
                    }
                }
                if (!key.keySet().equals(new java.util.LinkedHashSet<>(dims)))
                {
                    continue;
                }
                boolean ok = true;
                for (Map.Entry<String, String> x : eq.entrySet())
                {
                    if (key.containsKey(x.getKey()) && !x.getValue().equals(key.get(x.getKey())))
                    {
                        ok = false;
                    }
                }
                if (ok)
                {
                    Map<String, Object> r = new LinkedHashMap<>(e.getValue());
                    r.putAll(key);
                    out.add(r);
                }
            }
            return out;
        }

        @Override
        public List<Map<String, Object>> gameDays(String siteCode, String game, LocalDate from, LocalDate to, Collection<String> fields)
        {
            return copy(gameDays);
        }

        static List<Map<String, Object>> copy(List<Map<String, Object>> in)
        {
            List<Map<String, Object>> out = new ArrayList<>();
            in.forEach(r -> out.add(new LinkedHashMap<>(r)));
            return out;
        }
    }
}
