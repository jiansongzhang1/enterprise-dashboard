package com.fivetech.risk.rank;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.risk.RiskException;
import com.fivetech.risk.RiskQuery;
import com.fivetech.risk.config.RiskBoards;
import com.fivetech.risk.data.RiskDataSource;
import com.fivetech.risk.data.RiskFilter;

/**
 * 渠道风险榜（FY-71）：按推广商 › 推广渠道统计风险用户的人数与提款，结合注册、首存、存款看渠道质量。
 *
 * <ul>
 *   <li>风险用户 = 命中所选风险类型任一类（按人去重，各类人数分别计）；风险类型的门槛沿用对应榜单的入榜条件。</li>
 *   <li>列定义（名称、分组、格式、能否排行 / 作条件）在 RISK_RANK_BOARDS 的 channelCols；算法按列编码固定在这里。</li>
 *   <li>筛选只作用在渠道这一层：推广商行 = 留下的渠道合计，一个渠道都不剩的推广商不显示；合计行 = 留下的渠道合计。不分页。</li>
 *   <li>註冊用戶、首存人數、首存金額来自 UDS 的渠道获客数据（{@link RiskDataSource#channelAcquisition}），
 *       TODO：UDS 口径未定，接入前这三列为空、列上带说明。</li>
 * </ul>
 *
 * @author fivetech
 */
final class RiskChannelRank
{
    /** 各风险类型人数的列编码前缀：risk:newWin */
    static final String TYPE_PREFIX = "risk:";

    /** 列设置里代表「各風險類型人數（按所选风险类型展开）」的编码 */
    static final String TYPE_COUNTS = "riskTypeCounts";

    static final String REG_USERS = "regUsers";

    static final Set<String> ACQ = Set.of("regUsers", "ftdUsers", "ftdAmt");

    static final String ACQ_PENDING = "註冊、首存數據待接入";

    private final RiskRankService svc;

    RiskChannelRank(RiskRankService svc)
    {
        this.svc = svc;
    }

    /** 一个推广渠道（或推广商汇总、合计） */
    static final class Node
    {
        String agentId;

        String channelId;

        String name;

        final Map<String, BigDecimal> v = new LinkedHashMap<>();

        final List<Node> children = new ArrayList<>();

        void add(String k, BigDecimal x)
        {
            if (x != null)
            {
                v.merge(k, x, BigDecimal::add);
            }
        }

        BigDecimal get(String k)
        {
            return v.get(k);
        }
    }

    // ------------------------------------------------------------------ 计算

    /** 按渠道汇总：活跃、存款、提款、投注、玩家输赢、风险用户与各类人数；再并上获客数据（注册、首存） */
    List<Node> channels(RiskFilter f, RiskRankData data, RiskBoards cfg, List<String> types, boolean[] acqOk)
    {
        Map<String, Node> byKey = new LinkedHashMap<>();
        RiskBoards.Board all = cfg.board("all");
        List<RiskBoards.RiskType> chosen = new ArrayList<>();
        for (String t : types)
        {
            RiskBoards.RiskType x = cfg.riskType(t);
            if (x != null)
            {
                chosen.add(x);
            }
        }
        for (RiskUser u : data.users.values())
        {
            Node n = node(byKey, u.text(RiskAggregator.AGENT_ID), u.text(RiskAggregator.CHANNEL_ID));
            boolean active = all == null || RiskRankService.match(u, all.scope, cfg, null);
            n.add("activeUsers", active ? BigDecimal.ONE : BigDecimal.ZERO);
            BigDecimal dep = u.num("dep");
            n.add("depUsers", dep != null && dep.signum() > 0 ? BigDecimal.ONE : BigDecimal.ZERO);
            n.add("depAmt", dep);
            n.add("wd", u.num("wd"));
            n.add("bet", u.num("bet"));
            n.add("winLoss", u.num("winLoss"));
            boolean risky = false;
            for (RiskBoards.RiskType t : chosen)
            {
                boolean hit = RiskRankService.isRiskType(u, t, cfg);
                n.add(TYPE_PREFIX + t.code, hit ? BigDecimal.ONE : BigDecimal.ZERO);
                risky |= hit;
            }
            n.add("riskUsers", risky ? BigDecimal.ONE : BigDecimal.ZERO);
            n.add("riskWd", risky ? u.num("wd") : null);
        }
        acqOk[0] = svc.source().available(RiskDataSource.Kind.CHANNEL_ACQ);
        if (acqOk[0])
        {
            // 有注册但没有活跃用户的渠道也列出（FY-71 R5）
            for (Map<String, Object> row : svc.source().channelAcquisition(f))
            {
                Node n = node(byKey, str(row.get("agentId")), str(row.get("channelId")));
                for (String k : ACQ)
                {
                    BigDecimal x = RiskAggregator.num(row.get(k));
                    n.add(k, x == null ? BigDecimal.ZERO : x);
                }
            }
            for (Node n : byKey.values())
            {
                for (String k : ACQ)
                {
                    n.v.putIfAbsent(k, BigDecimal.ZERO);
                }
            }
        }
        Map<String, String[]> names = svc.channelNames();
        List<Node> out = new ArrayList<>(byKey.values());
        for (Node n : out)
        {
            ratios(n);
            String[] nm = names.get(n.channelId == null ? "" : n.channelId);
            n.name = nm != null ? nm[1] : StringUtils.isEmpty(n.channelId) ? "未歸屬渠道" : n.channelId;
        }
        return out;
    }

    private static Node node(Map<String, Node> byKey, String agent, String channel)
    {
        String a = agent == null ? "" : agent;
        String c = channel == null ? "" : channel;
        return byKey.computeIfAbsent(a + "|" + c, k -> {
            Node n = new Node();
            n.agentId = a;
            n.channelId = c;
            return n;
        });
    }

    private static String str(Object v)
    {
        return v == null ? null : String.valueOf(v).trim();
    }

    /** 比率列按合计数重算：平均存款金额 = 存款金额 ÷ 存款人数；风险用户占比 = 风险用户 ÷ 活跃用户 */
    static void ratios(Node n)
    {
        n.v.put("avgDep", div(n.get("depAmt"), n.get("depUsers"), 2));
        n.v.put("riskRate", div(n.get("riskUsers"), n.get("activeUsers"), 6));
    }

    private static BigDecimal div(BigDecimal a, BigDecimal b, int scale)
    {
        return a == null || b == null || b.signum() == 0 ? null : a.divide(b, scale, RoundingMode.HALF_UP);
    }

    /** 若干节点的合计（比率重算） */
    static Node sum(List<Node> nodes, boolean acqOk)
    {
        Node t = new Node();
        for (Node n : nodes)
        {
            for (Map.Entry<String, BigDecimal> e : n.v.entrySet())
            {
                if (!"avgDep".equals(e.getKey()) && !"riskRate".equals(e.getKey()))
                {
                    t.add(e.getKey(), e.getValue());
                }
            }
        }
        if (acqOk)
        {
            ACQ.forEach(k -> t.v.putIfAbsent(k, BigDecimal.ZERO));
        }
        ratios(t);
        return t;
    }

    // ------------------------------------------------------------------ 卡片

    void card(Map<String, Object> card, RiskRankService.Resolved r, RiskFilter f, RiskRankData data, RiskBoards cfg, List<String> types)
    {
        boolean[] acq = new boolean[1];
        List<Node> chs = channels(f, data, cfg, types, acq);
        Node total = sum(chs, acq[0]);
        int riskChannels = 0;
        for (Node n : chs)
        {
            if (n.get("riskUsers") != null && n.get("riskUsers").signum() > 0)
            {
                riskChannels++;
            }
        }
        List<Map<String, Object>> stats = new ArrayList<>();
        for (RiskBoards.Stat s : r.sys.stats)
        {
            Map<String, Object> st = switch (s.code)
            {
                case "riskChannels" -> RiskRankService.stat(s.code, s.label, BigDecimal.valueOf(riskChannels), "INT");
                case "riskUsers" -> {
                    Map<String, Object> m = RiskRankService.stat(s.code, s.label, total.get("riskUsers"), "INT");
                    // 后写「（佔活躍用戶 x%）」
                    m.put("activeShare", total.get("riskRate"));
                    yield m;
                }
                case "riskWd" -> RiskRankService.stat(s.code, s.label, total.get("riskWd"), "MONEY");
                default -> RiskRankService.stat(s.code, s.label, null, null);
            };
            stats.add(st);
        }
        card.put("stats", stats);
        card.put("rankCol", r.rankCol);
        card.put("rankDir", "desc");
        card.put("riskTypes", riskTypeInfo(types, cfg));
        List<Map<String, Object>> comp = new ArrayList<>();
        for (String t : types)
        {
            RiskBoards.RiskType x = cfg.riskType(t);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", t);
            m.put("name", x == null ? t : x.name);
            m.put("users", total.get(TYPE_PREFIX + t) == null ? BigDecimal.ZERO : total.get(TYPE_PREFIX + t));
            comp.add(m);
        }
        card.put("composition", comp);
        List<Node> top = new ArrayList<>(chs);
        top.removeIf(n -> n.get("riskUsers") == null || n.get("riskUsers").signum() == 0);
        top.sort(order("riskWd", true));
        Map<String, String[]> names = svc.channelNames();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < Math.min(5, top.size()); i++)
        {
            Node n = top.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", i + 1);
            m.put("agentId", n.agentId);
            m.put("channelId", n.channelId);
            m.put("agentName", agentName(names, n));
            m.put("name", n.name);
            m.put("riskWd", n.get("riskWd"));
            m.put("riskUsers", n.get("riskUsers"));
            rows.add(m);
        }
        card.put("top", rows);
    }

    Map<String, Object> riskTypeInfo(List<String> types, RiskBoards cfg)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("selected", types);
        m.put("names", RiskRankService.riskTypeNames(types, cfg));
        return m;
    }

    private String agentName(Map<String, String[]> names, Node n)
    {
        String[] nm = names.get(n.channelId == null ? "" : n.channelId);
        if (nm != null && nm[0] != null)
        {
            return nm[0];
        }
        String an = svc.agentNames().get(n.agentId);
        return an != null ? an : StringUtils.isEmpty(n.agentId) ? "未歸屬推廣商" : n.agentId;
    }

    // ------------------------------------------------------------------ 名单

    /** 校验并取出渠道层的范围条件（11 项，见 channelCols 的 condition） */
    List<RiskQuery.Condition> conditions(RiskQuery q, RiskBoards.Board b)
    {
        Map<String, String> errors = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (RiskQuery.Condition c : q.getConditions())
        {
            RiskBoards.ChannelCol col = c.getField() == null ? null : b.channelCol(c.getField());
            if (col == null || !col.condition)
            {
                errors.put("conditions", "渠道風險榜不支持的條件：" + c.getField());
                continue;
            }
            if (!seen.add(c.getField()))
            {
                errors.put("conditions", "條件字段重複：" + c.getField());
                continue;
            }
            if (c.getMin() != null && c.getMax() != null && c.getMin().compareTo(c.getMax()) > 0)
            {
                errors.put("conditions", "「" + col.name + "」的最小值不能大於最大值");
            }
        }
        RiskException.failIfAny(errors);
        return q.getConditions();
    }

    /** 排行字段：channelCols 里能排行的列，或某风险类型人数 */
    String rankCol(String requested, RiskBoards.Board b, List<String> types, String field)
    {
        String col = StringUtils.isEmpty(requested) ? b.rankCol : requested;
        RiskBoards.ChannelCol c = b.channelCol(col);
        boolean ok = (c != null && c.rank) || (col.startsWith(TYPE_PREFIX) && types.contains(col.substring(TYPE_PREFIX.length())));
        if (!ok)
        {
            throw RiskException.field(field, "不能按「" + col + "」排序");
        }
        return col;
    }

    static Comparator<Node> order(String col, boolean desc)
    {
        return (a, b) -> {
            BigDecimal x = a.get(col);
            BigDecimal y = b.get(col);
            if (x == null && y == null)
            {
                return String.valueOf(a.name).compareTo(String.valueOf(b.name));
            }
            if (x == null)
            {
                return 1;
            }
            if (y == null)
            {
                return -1;
            }
            int c = x.compareTo(y);
            return c != 0 ? (desc ? -c : c) : String.valueOf(a.name).compareTo(String.valueOf(b.name));
        };
    }

    /** 名单的树：推广商（留下的渠道合计）› 渠道；按排行字段排序，推广商之间、每个推广商内各自排序 */
    final class Tree
    {
        List<Node> agents = new ArrayList<>();

        Node total;

        int channels;

        String rankCol;

        String rankDir;

        List<String> cols;

        boolean acqOk;

        boolean pending;

        String asOf;
    }

    Tree tree(RiskQuery q, RiskRankService.Resolved r, RiskBoards cfg, List<String> types)
    {
        RiskFilter f = q.filter(svc.site(), svc.props().getMaxSpanDays());
        RiskBoards.Board b = r.sys;
        List<RiskQuery.Condition> conds = conditions(q, b);
        String rankCol = rankCol(q.getRankCol(), b, types, "rankCol");
        String dir = StringUtils.isEmpty(q.getRankDir()) ? "desc" : q.getRankDir().toLowerCase(java.util.Locale.ROOT);
        if (!"asc".equals(dir) && !"desc".equals(dir))
        {
            throw RiskException.field("rankDir", "排行方向只能是 asc 或 desc");
        }
        RiskRankData data = svc.data(f);
        Tree t = new Tree();
        boolean[] acq = new boolean[1];
        List<Node> chs = channels(f, data, cfg, types, acq);
        t.acqOk = acq[0];
        t.pending = data.pending;
        t.asOf = data.asOf;
        t.rankCol = rankCol;
        t.rankDir = dir;
        t.cols = columns(q, b, types, rankCol);
        List<Node> kept = new ArrayList<>();
        for (Node n : chs)
        {
            boolean ok = true;
            for (RiskQuery.Condition c : conds)
            {
                if (!RiskRankService.inRange(n.get(c.getField()), c))
                {
                    ok = false;
                }
            }
            if (ok)
            {
                kept.add(n);
            }
        }
        Map<String, Node> agents = new LinkedHashMap<>();
        Map<String, String[]> names = svc.channelNames();
        for (Node n : kept)
        {
            Node a = agents.computeIfAbsent(n.agentId, k -> {
                Node x = new Node();
                x.agentId = n.agentId;
                return x;
            });
            a.children.add(n);
            a.name = agentName(names, n);
        }
        // 表头临时排序：每层内按该列排；否则按排行字段
        String viewCol = StringUtils.isEmpty(q.getViewSortCol()) ? null : rankCol(q.getViewSortCol(), b, types, "viewSortCol");
        String sortCol = viewCol != null ? viewCol : rankCol;
        boolean desc = viewCol != null ? !"asc".equalsIgnoreCase(q.getViewSortDir()) : "desc".equals(dir);
        for (Node a : agents.values())
        {
            Node s = sum(a.children, t.acqOk);
            a.v.clear();
            a.v.putAll(s.v);
            a.children.sort(order(sortCol, desc));
        }
        t.agents.addAll(agents.values());
        t.agents.sort(order(sortCol, desc));
        t.total = sum(kept, t.acqOk);
        t.channels = kept.size();
        return t;
    }

    /** 列：註冊用戶固定在第一列；其余为请求的或默认列，「各風險類型人數」按所选风险类型展开；排行字段不在列里时加到最后 */
    List<String> columns(RiskQuery q, RiskBoards.Board b, List<String> types, String rankCol)
    {
        List<String> out = new ArrayList<>(List.of(REG_USERS));
        List<String> req = q.getCols().isEmpty() ? b.cols : q.getCols();
        for (String c : req)
        {
            if (TYPE_COUNTS.equals(c))
            {
                types.forEach(t -> add(out, TYPE_PREFIX + t));
                continue;
            }
            if (c.startsWith(TYPE_PREFIX) && types.contains(c.substring(TYPE_PREFIX.length())))
            {
                add(out, c);
                continue;
            }
            if (b.channelCol(c) == null)
            {
                throw RiskException.field("cols", "不認識的列：" + c);
            }
            add(out, c);
        }
        add(out, rankCol);
        return out;
    }

    private static void add(List<String> list, String c)
    {
        if (!list.contains(c))
        {
            list.add(c);
        }
    }

    List<Map<String, Object>> columnMeta(List<String> cols, RiskBoards.Board b, RiskBoards cfg, boolean acqOk)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String c : cols)
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", c);
            if (c.startsWith(TYPE_PREFIX))
            {
                RiskBoards.RiskType t = cfg.riskType(c.substring(TYPE_PREFIX.length()));
                m.put("name", (t == null ? c : t.name) + "人數");
                m.put("group", "RISK");
                m.put("valueFormat", "INT");
            }
            else
            {
                RiskBoards.ChannelCol col = b.channelCol(c);
                m.put("name", col.name);
                m.put("group", col.group);
                m.put("valueFormat", col.valueFormat);
                m.put("fixed", col.fixed);
                m.put("remark", col.remark);
            }
            if (ACQ.contains(c) && !acqOk)
            {
                m.put("note", ACQ_PENDING);
            }
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> values(Node n, List<String> cols)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        cols.forEach(c -> v.put(c, n.get(c)));
        return v;
    }

    Map<String, Object> list(RiskQuery q, RiskRankService.Resolved r, RiskBoards cfg, List<String> types)
    {
        Tree t = tree(q, r, cfg, types);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", t.asOf);
        out.put("dataPending", t.pending);
        out.put("board", r.code);
        out.put("name", r.name);
        out.put("riskTypes", riskTypeInfo(types, cfg));
        out.put("rankCol", t.rankCol);
        out.put("rankDir", t.rankDir);
        out.put("modified", !q.getConditions().isEmpty() || !t.rankCol.equals(r.sys.rankCol) || !"desc".equals(t.rankDir));
        out.put("acquisitionPending", !t.acqOk);
        out.put("columns", columnMeta(t.cols, r.sys, cfg, t.acqOk));
        Map<String, Object> totals = values(t.total, t.cols);
        totals.put("channels", t.channels);
        out.put("totals", totals);
        out.put("total", t.channels);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Node a : t.agents)
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("level", "AGENT");
            m.put("agentId", a.agentId);
            m.put("name", a.name);
            m.put("values", values(a, t.cols));
            List<Map<String, Object>> children = new ArrayList<>();
            for (Node c : a.children)
            {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("level", "CHANNEL");
                x.put("agentId", c.agentId);
                x.put("channelId", c.channelId);
                x.put("name", c.name);
                // 有注册但没有活跃用户的渠道名称不可下钻
                x.put("drillable", c.get("riskUsers") != null && c.get("riskUsers").signum() > 0);
                x.put("values", values(c, t.cols));
                children.add(x);
            }
            m.put("children", children);
            rows.add(m);
        }
        out.put("rows", rows);
        return out;
    }

    RiskSheet export(RiskQuery q, RiskRankService.Resolved r, RiskBoards cfg, List<String> types)
    {
        Tree t = tree(q, r, cfg, types);
        String site = svc.siteName();
        StringBuilder title = new StringBuilder(site).append(" · 風控分析 · 排行榜 · ").append(r.name).append(" · ")
            .append(q.getSlotFrom()).append(" – ").append(q.getSlotTo()).append(" · 風險用戶＝").append(RiskRankService.riskTypeNames(types, cfg));
        if (!q.getConditions().isEmpty())
        {
            title.append(" · 條件：").append(svc.channelConditionText(q.getConditions(), r.sys));
        }
        List<Map<String, Object>> meta = columnMeta(t.cols, r.sys, cfg, t.acqOk);
        title.append(" · 排行：").append(name(meta, t.rankCol)).append("desc".equals(t.rankDir) ? " 從高到低" : " 從低到高");
        if (StringUtils.isNotEmpty(q.getViewSortCol()))
        {
            title.append(" · 表格按").append(name(meta, q.getViewSortCol())).append("asc".equalsIgnoreCase(q.getViewSortDir()) ? "從低到高" : "從高到低")
                .append("排列");
        }
        title.append(" · 資料截至 ").append(t.asOf);
        RiskSheet sheet = new RiskSheet(r.name, title.toString());
        sheet.column("推廣商 › 渠道", RiskSheet.TEXT);
        for (Map<String, Object> m : meta)
        {
            sheet.column(String.valueOf(m.get("name")), RiskSheet.format(String.valueOf(m.get("valueFormat"))));
        }
        Object[] total = sheet.newRow();
        total[0] = "合計（" + t.channels + " 個渠道）";
        fill(total, t.total, t.cols, meta);
        for (Node a : t.agents)
        {
            Object[] x = sheet.newRow();
            x[0] = a.name;
            fill(x, a, t.cols, meta);
            for (Node c : a.children)
            {
                Object[] y = sheet.newRow();
                y[0] = a.name + " › " + c.name;
                fill(y, c, t.cols, meta);
            }
        }
        sheet.fileName = site + "_" + r.name + "_" + compact(q.getSlotFrom()) + "-" + compact(q.getSlotTo());
        return sheet;
    }

    private static String name(List<Map<String, Object>> meta, String code)
    {
        for (Map<String, Object> m : meta)
        {
            if (code.equals(m.get("code")))
            {
                return String.valueOf(m.get("name"));
            }
        }
        return code;
    }

    private static void fill(Object[] row, Node n, List<String> cols, List<Map<String, Object>> meta)
    {
        for (int j = 0; j < cols.size(); j++)
        {
            BigDecimal v = n.get(cols.get(j));
            row[1 + j] = v != null && "PCT".equals(meta.get(j).get("valueFormat")) ? v.movePointRight(2) : v;
        }
    }

    private static String compact(String slot)
    {
        String d = slot == null ? "" : slot.replaceAll("[^0-9]", "");
        return d.substring(0, Math.min(8, d.length()));
    }
}
