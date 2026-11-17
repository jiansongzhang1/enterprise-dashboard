package com.fivetech.risk.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.fivetech.dashboard.service.MetricExt;

/**
 * 榜单设置（配置中心 RISK_RANK_BOARDS）与榜单条件（RISK_RANK_THRESHOLDS）的结构。
 *
 * <p>系统榜单不写在代码里：每张榜的入榜条件、排行字段、占比口径、默认列都在 RISK_RANK_BOARDS 里配，
 * 条件里的门槛写 {@code "$newDays"} 这样引用 RISK_RANK_THRESHOLDS 的某一项；列编码是指标管理里 RISK_RANK 列的接口编码。</p>
 *
 * <pre>
 * RISK_RANK_THRESHOLDS：{ "values": { "bigOne": 50000, "newDays": 7, ... },
 *                         "limits": { "bigOne": { "min": 1000, "max": 100000000 }, ... },
 *                         "names":  { "bigOne": "大額提款榜 · 單筆提款 ≥", ... } }
 * RISK_RANK_BOARDS：{ "myBoardLimit": 10, "bands": [1, 5, 10], "defaultCards": ["pnl", ...],
 *                     "riskTypes": [{ "code": "newWin", "name": "新號盈利", "board": "new" }, ...],
 *                     "boards": [{ "code": "pnl", "name": "盈利榜", "kind": "USER",
 *                                  "entry": [{ "field": "winLoss", "gt": 0 }], "rankCol": "winLoss",
 *                                  "shareMode": "SHARE", "shareBase": "ENTRY", "cols": ["bet", "rtp", "net"] }, ...] }
 * </pre>
 *
 * @author fivetech
 */
public final class RiskBoards
{
    /** 卡片中间一条：SHARE 头部占比条 / GAME_DIST 游戏分布（distCol）/ MULTIPLE_BUCKET 倍数分档 / RTP_DIFF 比全部用户 RTP 高多少 / NONE */
    public static final Set<String> SHARE_MODES = Set.of("SHARE", "GAME_DIST", "MULTIPLE_BUCKET", "RTP_DIFF", "NONE");

    /** 榜单种类：USER 按用户 / ORDER 按订单（大额提款榜）/ CHANNEL 按推广渠道（渠道风险榜） */
    public static final Set<String> KINDS = Set.of("USER", "ORDER", "CHANNEL");

    /** 卡片 3 个数支持的算法（见 {@link Stat}） */
    public static final Set<String> STAT_CODES = Set.of("entry", "sum", "activeShare", "ratio", "shareOfAll", "shareOfPositive", "max",
        "maxOf", "countAtLeast", "sumOf", "riskChannels", "riskUsers", "riskWd");

    /** 一个条件：field + gt / min / max / lt（值为数字或 "$门槛键"）；或 any = 任一满足 */
    public static final class Cond
    {
        public String field;

        /** GT / GE / LE / LT */
        public String op;

        /** 数字，或门槛键（来自 "$key"） */
        public BigDecimal value;

        public String ref;

        public List<Cond> any;

        public BigDecimal resolve(Map<String, BigDecimal> thresholds, Map<String, BigDecimal> overrides)
        {
            if (ref == null)
            {
                return value;
            }
            if (overrides != null && overrides.get(ref) != null)
            {
                return overrides.get(ref);
            }
            return thresholds.get(ref);
        }
    }

    public static final class Bucket
    {
        public String label;

        public BigDecimal min;

        public BigDecimal max;
    }

    /**
     * 卡片上的一个数：code 为算法，label 为页面上的短标题。
     * <ul>
     *   <li>entry 入榜人数（大额提款榜为笔数）；sum 排行字段合计（大额提款榜为金额合计）</li>
     *   <li>activeShare 入榜人数 ÷ 活跃用户（所选时间内有存款或投注）</li>
     *   <li>ratio 入榜用户 num 合计 ÷ den 合计（如提存比 wd / dep）</li>
     *   <li>shareOfAll 合计 ÷ 全部用户 col 合计；shareOfPositive 合计 ÷ 全部 col &gt; 0 用户的 col 合计（佔全部盈利）</li>
     *   <li>max 排行字段最大值；maxOf / sumOf 入榜用户 col 的最大值 / 合计；countAtLeast col ≥ value 的人数</li>
     *   <li>渠道风险榜：riskChannels 有风险用户的渠道数；riskUsers 风险用户（去重）；riskWd 风险用户提款</li>
     * </ul>
     */
    public static final class Stat
    {
        public String code;

        public String label;

        public String col;

        public String num;

        public String den;

        public BigDecimal value;
    }

    /** 渠道风险榜的一列（名称、分组、格式都在配置里；算法按 code 固定，见 RiskChannelRank） */
    public static final class ChannelCol
    {
        public String code;

        public String name;

        /** ACQ 獲客 / FUND 資金 / RISK 風險 / GAME 遊戲 */
        public String group;

        public String valueFormat;

        /** 能作排行字段 */
        public boolean rank;

        /** 能作范围条件 */
        public boolean condition;

        /** 固定列（註冊用戶），不可隐藏 */
        public boolean fixed;

        /** 悬停口径 */
        public String remark;
    }

    public static final class Board
    {
        public String code;

        public String name;

        /** USER 按用户 / ORDER 按订单（大额提款榜）/ CHANNEL 按推广渠道（渠道风险榜） */
        public String kind = "USER";

        /** 入榜条件 = 名单的默认筛选条件（FY-67 R1），名单里可以改 */
        public List<Cond> entry = new ArrayList<>();

        /** 名单范围（改条件也一直生效），如全部用户 = 有存款或投注 */
        public List<Cond> scope = new ArrayList<>();

        public String rankCol;

        public String rankDir;

        public String shareMode = "NONE";

        /** ENTRY = 入榜用户的排行字段合计 / ALL = 全部用户的合计 */
        public String shareBase = "ENTRY";

        /** GAME_DIST：按哪一列分布（中奖游戏 winGame / 盈利游戏 profitGame），前 distTop 款单独成段，其余为「其他」 */
        public String distCol;

        public int distTop = 2;

        /** 卡片 3 个数 */
        public List<Stat> stats = new ArrayList<>();

        public List<Bucket> buckets = new ArrayList<>();

        /** 分档按哪一列（如派彩倍数） */
        public String bucketCol;

        public List<String> cols = new ArrayList<>();

        public boolean enabled = true;

        /** 能不能显示为卡片（全部用户不做卡片） */
        public boolean card = true;

        /** ORDER：单笔门槛（数字或 "$key"） */
        public Cond threshold;

        /** ORDER：单笔门槛之外，名单能加的范围条件（用户字段，按订单所属用户在所选时间内的数据判断） */
        public List<String> filters = new ArrayList<>();

        /** ORDER：列设置的可选列（为空 = 不限） */
        public List<String> colOptions = new ArrayList<>();

        /** CHANNEL：列定义 */
        public List<ChannelCol> channelCols = new ArrayList<>();

        /** CHANNEL：下钻名单的默认列与排行字段（固定从高到低） */
        public List<String> drillCols = new ArrayList<>();

        public String drillRankCol = "wd";

        public boolean isOrder()
        {
            return "ORDER".equals(kind);
        }

        public boolean isChannel()
        {
            return "CHANNEL".equals(kind);
        }

        /** 下拉与编辑卡片的分组：USER 按用戶 / ORDER 按訂單 / CHANNEL 按渠道 */
        public String group()
        {
            return kind;
        }

        public ChannelCol channelCol(String code)
        {
            for (ChannelCol c : channelCols)
            {
                if (c.code.equals(code))
                {
                    return c;
                }
            }
            return null;
        }
    }

    public static final class RiskType
    {
        public String code;

        public String name;

        /** 命中这张榜的入榜条件即算这个风险类型 */
        public String board;

        /** 入榜条件之外再加的条件（如高 RTP 还要 RTP ≥ 100%） */
        public List<Cond> and = new ArrayList<>();
    }

    // ------------------------------------------------------------------ 内容

    public Map<String, BigDecimal> thresholds = new LinkedHashMap<>();

    public Map<String, BigDecimal[]> limits = new LinkedHashMap<>();

    public Map<String, String> thresholdNames = new LinkedHashMap<>();

    public int myBoardLimit = 10;

    public List<Integer> bands = new ArrayList<>(List.of(1, 5, 10));

    public List<String> defaultCards = new ArrayList<>();

    public List<RiskType> riskTypes = new ArrayList<>();

    /** 金额类、可相加的列：排行字段是它们且从高到低时才算占比（FY-69 R4、FY-68 模板 A） */
    public List<String> shareCols = new ArrayList<>(List.of("dep", "wd", "bet", "pay", "bonus", "maxPayout"));

    /** 条件要求 &gt; 0 时才算金额类的列（玩家输赢） */
    public List<String> sharePositiveCols = new ArrayList<>(List.of("winLoss"));

    /** 比率类排行字段（我的榜单模板 B） */
    public List<String> ratioCols = new ArrayList<>(List.of("rtp", "turnX", "bonusR"));

    /** 渠道风险榜的系统默认风险类型（个人设置 → 全局默认 → 这里） */
    public List<String> channelRiskDefault = new ArrayList<>();

    /** 渠道风险榜的全局默认风险类型（RISK_RANK_THRESHOLDS.channelRiskTypes，按站点，有修改权限的人改） */
    public List<String> channelRiskGlobal = new ArrayList<>();

    public List<Board> boards = new ArrayList<>();

    public Board board(String code)
    {
        for (Board b : boards)
        {
            if (b.code.equals(code) && b.enabled)
            {
                return b;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 解析（errors 为 null 时跳过无效项）

    /** 解析 RISK_RANK_THRESHOLDS；错误写进 errors（键为 json 路径） */
    @SuppressWarnings("unchecked")
    public void readThresholds(Object root, List<String> errors)
    {
        if (!(root instanceof Map<?, ?> m))
        {
            add(errors, "應為對象：{ \"values\": {...}, \"limits\": {...}, \"names\": {...} }");
            return;
        }
        Object values = m.get("values");
        if (!(values instanceof Map<?, ?> vs) || vs.isEmpty())
        {
            add(errors, "values 應為非空對象，如 { \"bigOne\": 50000 }");
            return;
        }
        Map<String, Object> lim = m.get("limits") instanceof Map<?, ?> l ? (Map<String, Object>) l : Map.of();
        Map<String, Object> names = m.get("names") instanceof Map<?, ?> n ? (Map<String, Object>) n : Map.of();
        for (Map.Entry<?, ?> e : vs.entrySet())
        {
            String key = String.valueOf(e.getKey());
            BigDecimal v = num(e.getValue());
            if (v == null)
            {
                add(errors, "values." + key + " 應為數字");
                continue;
            }
            thresholds.put(key, v);
            BigDecimal[] range = { null, null };
            if (lim.get(key) instanceof Map<?, ?> r)
            {
                range[0] = num(r.get("min"));
                range[1] = num(r.get("max"));
                if (range[0] != null && range[1] != null && range[0].compareTo(range[1]) > 0)
                {
                    add(errors, "limits." + key + " 的 min 不能大於 max");
                }
                if ((range[0] != null && v.compareTo(range[0]) < 0) || (range[1] != null && v.compareTo(range[1]) > 0))
                {
                    add(errors, "values." + key + " 超出 limits 的範圍");
                }
            }
            limits.put(key, range);
            String name = MetricExt.str(names.get(key));
            thresholdNames.put(key, name == null ? key : name);
        }
        // 渠道风险榜的全局默认风险类型（按站点，和门槛一起在「榜單條件」里改）
        channelRiskGlobal = strings(m.get("channelRiskTypes"));
    }

    static List<String> strings(Object raw)
    {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> l)
        {
            for (Object o : l)
            {
                String v = MetricExt.str(o);
                if (v != null && !out.contains(v))
                {
                    out.add(v);
                }
            }
        }
        return out;
    }

    /** 解析 RISK_RANK_BOARDS；columns = 指标管理里排行榜列的接口编码（为 null 不校验列） */
    @SuppressWarnings("unchecked")
    public void readBoards(Object root, Set<String> columns, List<String> errors)
    {
        if (!(root instanceof Map<?, ?> m))
        {
            add(errors, "應為對象：{ \"boards\": [...], \"defaultCards\": [...], \"bands\": [...], \"myBoardLimit\": 10 }");
            return;
        }
        if (m.get("myBoardLimit") != null)
        {
            BigDecimal n = num(m.get("myBoardLimit"));
            if (n == null || n.intValue() < 0 || n.intValue() > 50)
            {
                add(errors, "myBoardLimit 應為 0–50");
            }
            else
            {
                myBoardLimit = n.intValue();
            }
        }
        if (m.get("bands") instanceof List<?> bs)
        {
            List<Integer> list = new ArrayList<>();
            for (Object o : bs)
            {
                BigDecimal n = num(o);
                if (n == null || n.intValue() < 1 || n.intValue() > 500)
                {
                    add(errors, "bands 每項應為 1–500");
                    continue;
                }
                list.add(n.intValue());
            }
            if (list.size() > 3)
            {
                add(errors, "bands 最多 3 個");
            }
            bands = list;
        }
        Object list = m.get("boards");
        if (!(list instanceof List<?> items) || items.isEmpty())
        {
            add(errors, "boards 應為非空數組");
            return;
        }
        for (Object o : items)
        {
            if (!(o instanceof Map<?, ?> bm))
            {
                add(errors, "boards 每項應為對象");
                continue;
            }
            Board b = new Board();
            b.code = MetricExt.str(bm.get("code"));
            b.name = MetricExt.str(bm.get("name"));
            if (b.code == null || !b.code.matches("^[a-z][A-Za-z0-9]{0,15}$") || b.name == null)
            {
                add(errors, "榜單要有 code（小寫字母開頭，最多 16 字）與 name");
                continue;
            }
            if (board(b.code) != null)
            {
                add(errors, "榜單 code 重複：" + b.code);
                continue;
            }
            String kind = MetricExt.str(bm.get("kind"));
            b.kind = kind == null ? "USER" : kind.toUpperCase(Locale.ROOT);
            if (!KINDS.contains(b.kind))
            {
                add(errors, "榜單 " + b.code + " 的 kind 只能是 USER、ORDER 或 CHANNEL");
            }
            b.entry = conds(bm.get("entry"), b.code + ".entry", columns, errors);
            b.scope = conds(bm.get("scope"), b.code + ".scope", columns, errors);
            b.rankCol = MetricExt.str(bm.get("rankCol"));
            String dir = MetricExt.str(bm.get("rankDir"));
            b.rankDir = dir == null ? null : dir.toLowerCase(Locale.ROOT);
            if (b.isChannel())
            {
                readChannel(b, bm, columns, errors);
            }
            else if (!b.isOrder())
            {
                if (b.rankCol == null)
                {
                    add(errors, "榜單 " + b.code + " 要填排行字段 rankCol");
                }
                else if (columns != null && !columns.contains(b.rankCol))
                {
                    add(errors, "榜單 " + b.code + " 的排行字段 " + b.rankCol + " 不在指標管理的排行榜列裡");
                }
            }
            String mode = MetricExt.str(bm.get("shareMode"));
            b.shareMode = mode == null ? "NONE" : mode.toUpperCase(Locale.ROOT);
            if (!SHARE_MODES.contains(b.shareMode))
            {
                add(errors, "榜單 " + b.code + " 的 shareMode 只能是 " + String.join(" / ", SHARE_MODES));
            }
            String base = MetricExt.str(bm.get("shareBase"));
            b.shareBase = base == null ? "ENTRY" : base.toUpperCase(Locale.ROOT);
            if (bm.get("buckets") instanceof List<?> bks)
            {
                for (Object x : bks)
                {
                    if (x instanceof Map<?, ?> xm)
                    {
                        Bucket k = new Bucket();
                        k.label = MetricExt.str(xm.get("label"));
                        k.min = num(xm.get("min"));
                        k.max = num(xm.get("max"));
                        b.buckets.add(k);
                    }
                }
            }
            b.bucketCol = MetricExt.str(bm.get("bucketCol"));
            b.distCol = MetricExt.str(bm.get("distCol"));
            if ("GAME_DIST".equals(b.shareMode) && b.distCol == null)
            {
                add(errors, "榜單 " + b.code + " 的 shareMode 為 GAME_DIST 時要填 distCol");
            }
            BigDecimal top = num(bm.get("distTop"));
            b.distTop = top == null ? 2 : Math.max(1, Math.min(10, top.intValue()));
            b.stats = stats(bm.get("stats"), b.code, errors);
            if (bm.get("cols") instanceof List<?> cs && !b.isChannel())
            {
                for (Object c : cs)
                {
                    String code = MetricExt.str(c);
                    if (code != null && (columns == null || columns.contains(code)))
                    {
                        b.cols.add(code);
                    }
                    else if (code != null)
                    {
                        add(errors, "榜單 " + b.code + " 的列 " + code + " 不在指標管理的排行榜列裡");
                    }
                }
            }
            b.enabled = !(bm.get("enabled") instanceof Boolean e) || e;
            b.card = !(bm.get("card") instanceof Boolean c) || c;
            if (b.isOrder())
            {
                b.threshold = cond(Map.of("field", "amount", "min", bm.get("threshold") == null ? "$bigOne" : bm.get("threshold")),
                    b.code + ".threshold", null, errors);
                b.filters = codes(bm.get("filters"), b.code + ".filters", columns, errors);
                b.colOptions = codes(bm.get("colOptions"), b.code + ".colOptions", columns, errors);
            }
            boards.add(b);
        }
        if (m.get("defaultCards") instanceof List<?> dc)
        {
            for (Object o : dc)
            {
                String c = MetricExt.str(o);
                if (c != null && board(c) != null)
                {
                    defaultCards.add(c);
                }
                else
                {
                    add(errors, "defaultCards 裡的 " + c + " 不是啟用的榜單");
                }
            }
        }
        if (defaultCards.size() > 9)
        {
            add(errors, "defaultCards 最多 9 個");
        }
        if (m.get("shareCols") instanceof List<?>)
        {
            shareCols = strings(m.get("shareCols"));
        }
        if (m.get("sharePositiveCols") instanceof List<?>)
        {
            sharePositiveCols = strings(m.get("sharePositiveCols"));
        }
        if (m.get("ratioCols") instanceof List<?>)
        {
            ratioCols = strings(m.get("ratioCols"));
        }
        if (m.get("riskTypes") instanceof List<?> rts)
        {
            for (Object o : rts)
            {
                if (o instanceof Map<?, ?> rm)
                {
                    RiskType t = new RiskType();
                    t.code = MetricExt.str(rm.get("code"));
                    t.name = MetricExt.str(rm.get("name"));
                    t.board = MetricExt.str(rm.get("board"));
                    if (t.code == null || t.name == null || t.board == null || board(t.board) == null)
                    {
                        add(errors, "riskTypes 每項要有 code、name，board 要是啟用的榜單");
                        continue;
                    }
                    t.and = conds(rm.get("and"), "riskTypes." + t.code + ".and", columns, errors);
                    riskTypes.add(t);
                }
            }
        }
        for (String c : strings(m.get("channelRiskDefault")))
        {
            if (riskType(c) == null)
            {
                add(errors, "channelRiskDefault 裡的 " + c + " 不是風險類型");
                continue;
            }
            channelRiskDefault.add(c);
        }
        if (channelRiskDefault.isEmpty() && !riskTypes.isEmpty())
        {
            riskTypes.forEach(t -> channelRiskDefault.add(t.code));
        }
    }

    public RiskType riskType(String code)
    {
        for (RiskType t : riskTypes)
        {
            if (t.code.equals(code))
            {
                return t;
            }
        }
        return null;
    }

    /**
     * 渠道风险榜生效的风险类型：个人设置 → 全局默认 → 系统默认；只认配置里的风险类型，去掉后一类不剩时按下一级（FY-71 R2）。
     *
     * @return [类型列表, 来源 PERSONAL / GLOBAL / SYSTEM]
     */
    public Object[] channelRiskTypes(List<String> personal)
    {
        List<String> p = known(personal);
        if (!p.isEmpty())
        {
            return new Object[] { p, "PERSONAL" };
        }
        List<String> g = known(channelRiskGlobal);
        if (!g.isEmpty())
        {
            return new Object[] { g, "GLOBAL" };
        }
        return new Object[] { known(channelRiskDefault), "SYSTEM" };
    }

    /** 去掉不认识的风险类型（按配置顺序） */
    public List<String> known(List<String> codes)
    {
        List<String> out = new ArrayList<>();
        if (codes == null)
        {
            return out;
        }
        for (RiskType t : riskTypes)
        {
            if (codes.contains(t.code))
            {
                out.add(t.code);
            }
        }
        return out;
    }

    /** 全局默认（没配时 = 系统默认） */
    public List<String> channelGlobalOrDefault()
    {
        List<String> g = known(channelRiskGlobal);
        return g.isEmpty() ? known(channelRiskDefault) : g;
    }

    private List<Stat> stats(Object raw, String board, List<String> errors)
    {
        List<Stat> out = new ArrayList<>();
        if (!(raw instanceof List<?> list))
        {
            return out;
        }
        for (Object o : list)
        {
            if (!(o instanceof Map<?, ?> sm))
            {
                continue;
            }
            Stat st = new Stat();
            st.code = MetricExt.str(sm.get("code"));
            st.label = MetricExt.str(sm.get("label"));
            st.col = MetricExt.str(sm.get("col"));
            st.num = MetricExt.str(sm.get("num"));
            st.den = MetricExt.str(sm.get("den"));
            st.value = num(sm.get("value"));
            if (st.code == null || !STAT_CODES.contains(st.code) || st.label == null)
            {
                add(errors, "榜單 " + board + " 的 stats 每項要有 code（" + String.join(" / ", STAT_CODES) + "）與 label");
                continue;
            }
            out.add(st);
        }
        if (out.size() > 3)
        {
            add(errors, "榜單 " + board + " 的 stats 最多 3 個");
        }
        return out;
    }

    private List<String> codes(Object raw, String path, Set<String> columns, List<String> errors)
    {
        List<String> out = strings(raw);
        if (columns != null)
        {
            for (String c : out)
            {
                if (!columns.contains(c))
                {
                    add(errors, path + " 的 " + c + " 不在指標管理的排行榜列裡");
                }
            }
        }
        return out;
    }

    private void readChannel(Board b, Map<?, ?> bm, Set<String> columns, List<String> errors)
    {
        if (bm.get("channelCols") instanceof List<?> cl)
        {
            for (Object o : cl)
            {
                if (!(o instanceof Map<?, ?> cm))
                {
                    continue;
                }
                ChannelCol c = new ChannelCol();
                c.code = MetricExt.str(cm.get("code"));
                c.name = MetricExt.str(cm.get("name"));
                c.group = MetricExt.str(cm.get("group"));
                c.valueFormat = MetricExt.str(cm.get("valueFormat"));
                c.rank = Boolean.TRUE.equals(cm.get("rank"));
                c.condition = Boolean.TRUE.equals(cm.get("condition"));
                c.fixed = Boolean.TRUE.equals(cm.get("fixed"));
                c.remark = MetricExt.str(cm.get("remark"));
                if (c.code == null || c.name == null)
                {
                    add(errors, "榜單 " + b.code + " 的 channelCols 每項要有 code 與 name");
                    continue;
                }
                b.channelCols.add(c);
            }
        }
        if (b.channelCols.isEmpty())
        {
            add(errors, "榜單 " + b.code + " 要配 channelCols");
        }
        if (b.rankCol == null || b.channelCol(b.rankCol) == null || !b.channelCol(b.rankCol).rank)
        {
            add(errors, "榜單 " + b.code + " 的排行字段要是 channelCols 裡能排行的列");
        }
        for (String c : strings(bm.get("cols")))
        {
            if (b.channelCol(c) == null)
            {
                add(errors, "榜單 " + b.code + " 的列 " + c + " 不在 channelCols 裡");
                continue;
            }
            b.cols.add(c);
        }
        b.drillCols = codes(bm.get("drillCols"), b.code + ".drillCols", columns, errors);
        String drill = MetricExt.str(bm.get("drillRankCol"));
        b.drillRankCol = drill == null ? "wd" : drill;
    }

    @SuppressWarnings("unchecked")
    private List<Cond> conds(Object raw, String path, Set<String> columns, List<String> errors)
    {
        List<Cond> out = new ArrayList<>();
        if (raw == null)
        {
            return out;
        }
        if (!(raw instanceof List<?> list))
        {
            add(errors, path + " 應為數組");
            return out;
        }
        for (Object o : list)
        {
            if (o instanceof Map<?, ?> m)
            {
                Cond c = cond((Map<String, Object>) m, path, columns, errors);
                if (c != null)
                {
                    out.add(c);
                }
            }
        }
        return out;
    }

    private Cond cond(Map<String, Object> m, String path, Set<String> columns, List<String> errors)
    {
        Cond c = new Cond();
        if (m.get("any") != null)
        {
            c.any = conds(m.get("any"), path + ".any", columns, errors);
            return c;
        }
        c.field = MetricExt.str(m.get("field"));
        if (c.field == null)
        {
            add(errors, path + " 的條件缺少 field");
            return null;
        }
        if (columns != null && !columns.contains(c.field))
        {
            add(errors, path + " 的條件字段 " + c.field + " 不在指標管理的排行榜列裡");
        }
        for (String op : List.of("gt", "min", "max", "lt"))
        {
            if (m.get(op) != null)
            {
                c.op = switch (op)
                {
                    case "gt" -> "GT";
                    case "min" -> "GE";
                    case "max" -> "LE";
                    default -> "LT";
                };
                Object v = m.get(op);
                String s = MetricExt.str(v);
                if (s != null && s.startsWith("$"))
                {
                    c.ref = s.substring(1);
                    if (!thresholds.isEmpty() && !thresholds.containsKey(c.ref))
                    {
                        add(errors, path + " 引用了不存在的榜單條件 " + s);
                    }
                }
                else
                {
                    c.value = num(v);
                    if (c.value == null)
                    {
                        add(errors, path + " 的 " + op + " 應為數字或 \"$門檻鍵\"");
                    }
                }
                return c;
            }
        }
        add(errors, path + " 的條件要有 gt / min / max / lt 其中之一");
        return null;
    }

    private static void add(List<String> errors, String msg)
    {
        if (errors != null)
        {
            errors.add(msg);
        }
    }

    /** 数字（整数去掉小数位：50000.0 → 50000） */
    public static BigDecimal num(Object v)
    {
        BigDecimal b;
        if (v == null)
        {
            return null;
        }
        if (v instanceof BigDecimal x)
        {
            b = x;
        }
        else
        {
            try
            {
                b = new BigDecimal(String.valueOf(v).trim());
            }
            catch (NumberFormatException e)
            {
                return null;
            }
        }
        BigDecimal s = b.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }
}
