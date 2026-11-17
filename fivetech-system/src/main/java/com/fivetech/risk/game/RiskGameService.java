package com.fivetech.risk.game;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.risk.RiskException;
import com.fivetech.risk.RiskProperties;
import com.fivetech.risk.RiskQuery;
import com.fivetech.risk.catalog.RiskCatalog;
import com.fivetech.risk.catalog.RiskColumn;
import com.fivetech.risk.config.RiskConfigService;
import com.fivetech.risk.data.RiskDataSource;
import com.fivetech.risk.data.RiskFilter;
import com.fivetech.risk.rank.RiskAggregator;
import com.fivetech.risk.rank.RiskRankData;
import com.fivetech.risk.rank.RiskRankService;
import com.fivetech.risk.rank.RiskSheet;
import com.fivetech.risk.rank.RiskUser;

/**
 * 风控分析 · 游戏分析（7.1.4）：层级表、游戏抽屉、导出。
 *
 * <p>列来自指标管理（RISK_GAME），数据来自游戏数据集。每一层都按该层的维度单独向 UDS 汇总（去重人数每层单独去重，
 * 上层可能小于下级相加），不在后端把下级相加。</p>
 *
 * @author fivetech
 */
@Service
public class RiskGameService
{
    /** 可选的行维度 */
    public static final List<String> DIMS = List.of("platform", "brand", "gameType", "game", "date");

    private final RiskProperties props;

    private final RiskCatalog catalog;

    private final RiskDataSource source;

    private final RiskConfigService config;

    private final RiskRankService rank;

    private final DashboardProperties dashboard;

    public RiskGameService(RiskProperties props, RiskCatalog catalog, RiskDataSource source, RiskConfigService config,
        RiskRankService rank, DashboardProperties dashboard)
    {
        this.props = props;
        this.catalog = catalog;
        this.source = source;
        this.config = config;
        this.rank = rank;
        this.dashboard = dashboard;
    }

    String site()
    {
        return dashboard == null ? null : dashboard.currentSite();
    }

    /** 行维度校验：1–5 个、不重复；品牌要在平台之下，游戏要在平台 / 品牌 / 类型之下 */
    static List<String> path(List<String> req, List<String> fallback)
    {
        List<String> p = req == null || req.isEmpty() ? fallback : req;
        if (p.isEmpty() || p.size() > 5)
        {
            throw RiskException.field("path", "行維度要選 1–5 個");
        }
        if (new LinkedHashSet<>(p).size() != p.size())
        {
            throw RiskException.field("path", "行維度不能重複");
        }
        for (String d : p)
        {
            if (!DIMS.contains(d))
            {
                throw RiskException.field("path", "不支持的行維度：" + d);
            }
        }
        int pf = p.indexOf("platform");
        int br = p.indexOf("brand");
        int gt = p.indexOf("gameType");
        int gm = p.indexOf("game");
        if (br >= 0 && pf > br)
        {
            throw RiskException.field("path", "品牌要在平台之下");
        }
        if (gm >= 0 && ((pf >= 0 && pf > gm) || (br >= 0 && br > gm) || (gt >= 0 && gt > gm)))
        {
            throw RiskException.field("path", "遊戲要在平台 / 品牌 / 類型之下");
        }
        return List.copyOf(p);
    }

    /** 要返回的列；空 = 标准列 */
    List<RiskColumn> columns(List<String> req)
    {
        Map<String, RiskColumn> all = catalog.game();
        List<RiskColumn> out = new ArrayList<>();
        if (req == null || req.isEmpty())
        {
            for (RiskColumn c : all.values())
            {
                if (c.isStandard())
                {
                    out.add(c);
                }
            }
            return out;
        }
        for (String code : req)
        {
            RiskColumn c = all.get(code);
            if (c == null)
            {
                throw RiskException.field("cols", "不認識的列：" + code);
            }
            out.add(c);
        }
        return out;
    }

    /** 列（含计算列依赖的列）要向 UDS 取的字段 */
    static Set<String> fields(Map<String, RiskColumn> all, List<RiskColumn> cols)
    {
        Set<String> out = new LinkedHashSet<>();
        for (RiskColumn c : cols)
        {
            collect(all, c, out, 0);
        }
        return out;
    }

    private static void collect(Map<String, RiskColumn> all, RiskColumn c, Set<String> out, int depth)
    {
        if (c == null || depth > 5)
        {
            return;
        }
        if (c.isCalc())
        {
            collect(all, all.get(c.getCalcLeft()), out, depth + 1);
            collect(all, all.get(c.getCalcRight()), out, depth + 1);
        }
        else if (StringUtils.isNotEmpty(c.getUdsField()))
        {
            out.add(c.getUdsField());
        }
    }

    /** 一行 UDS 数据 → 列编码 → 值（计算列按依赖算） */
    static Map<String, Object> values(Map<String, RiskColumn> all, List<RiskColumn> cols, Map<String, Object> row)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        for (RiskColumn c : cols)
        {
            out.put(c.getCode(), value(all, c, row, 0));
        }
        return out;
    }

    static BigDecimal value(Map<String, RiskColumn> all, RiskColumn c, Map<String, Object> row, int depth)
    {
        if (c == null || depth > 5)
        {
            return null;
        }
        if (!c.isCalc())
        {
            return RiskAggregator.num(row.get(c.getUdsField()));
        }
        BigDecimal a = value(all, all.get(c.getCalcLeft()), row, depth + 1);
        BigDecimal b = value(all, all.get(c.getCalcRight()), row, depth + 1);
        if (a == null || b == null)
        {
            return null;
        }
        return switch (c.getCalcOp())
        {
            case "DIVIDE" -> b.signum() == 0 ? null : a.divide(b, 6, RoundingMode.HALF_UP);
            case "SUBTRACT" -> a.subtract(b);
            default -> a.add(b);
        };
    }

    // ------------------------------------------------------------------ 层级表

    public Map<String, Object> table(RiskQuery q)
    {
        RiskFilter f = q.filter(site(), props.getMaxSpanDays());
        List<String> path = path(q.getPath(), props.getGameDefaultPath());
        List<RiskColumn> cols = columns(q.getCols());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        out.put("columns", meta(cols));
        out.put("dataPending", !source.available(RiskDataSource.Kind.GAME));
        if (!source.available(RiskDataSource.Kind.GAME))
        {
            out.put("rows", List.of());
            return out;
        }
        Map<String, RiskColumn> all = catalog.game();
        Set<String> fields = fields(all, cols);
        Map<String, String> eq = new LinkedHashMap<>();
        int level = 0;
        if (StringUtils.isNotEmpty(q.getExpandKey()))
        {
            for (String part : q.getExpandKey().split("\\|"))
            {
                int i = part.indexOf(':');
                if (i <= 0 || level >= path.size() || !path.get(level).equals(part.substring(0, i)))
                {
                    throw RiskException.field("expandKey", "展開的行與行維度不一致，請重新查詢");
                }
                eq.put(part.substring(0, i), part.substring(i + 1));
                level++;
            }
            if (level >= path.size())
            {
                throw RiskException.field("expandKey", "已經是最後一層");
            }
        }
        else
        {
            List<Map<String, Object>> total = source.games(f, List.of(), Map.of(), fields);
            out.put("total", total.isEmpty() ? new LinkedHashMap<>() : values(all, cols, total.get(0)));
        }
        List<String> dims = new ArrayList<>(path.subList(0, level + 1));
        List<Map<String, Object>> rows = source.games(f, dims, eq, fields);
        out.put("rows", levelRows(rows, path, level, eq, cols, all, q));
        return out;
    }

    List<Map<String, Object>> levelRows(List<Map<String, Object>> rows, List<String> path, int level, Map<String, String> eq,
        List<RiskColumn> cols, Map<String, RiskColumn> all, RiskQuery q)
    {
        String dim = path.get(level);
        Map<String, Map<String, Object>> games = config.games(site());
        Map<String, String> names = dimNames();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows)
        {
            Object code = r.get(dim);
            if (code == null)
            {
                continue;
            }
            String c = "date".equals(dim) ? String.valueOf(code).substring(0, Math.min(10, String.valueOf(code).length())) : String.valueOf(code);
            StringBuilder key = new StringBuilder();
            eq.forEach((k, v) -> key.append(k).append(':').append(v).append('|'));
            key.append(dim).append(':').append(c);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key.toString());
            m.put("level", level + 1);
            m.put("dimension", dim);
            m.put("code", c);
            m.put("name", name(dim, c, r, games, names));
            m.put("hasChildren", level + 1 < path.size());
            m.put("values", values(all, cols, r));
            out.add(m);
        }
        sort(out, dim, q);
        return out;
    }

    /** 每一层各自排序；日期层固定从新到旧 */
    @SuppressWarnings("unchecked")
    void sort(List<Map<String, Object>> rows, String dim, RiskQuery q)
    {
        if ("date".equals(dim))
        {
            rows.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("code"))).reversed());
            return;
        }
        String col = StringUtils.isNotEmpty(q.getSortCol()) ? q.getSortCol() : null;
        if (col == null)
        {
            List<RiskColumn> std = columns(List.of());
            col = std.isEmpty() ? null : std.get(0).getCode();
        }
        if (col == null)
        {
            return;
        }
        final String sc = col;
        boolean desc = !"asc".equalsIgnoreCase(q.getSortDir());
        rows.sort((a, b) -> {
            BigDecimal x = RiskAggregator.num(((Map<String, Object>) a.get("values")).get(sc));
            BigDecimal y = RiskAggregator.num(((Map<String, Object>) b.get("values")).get(sc));
            if (x == null && y == null)
            {
                return String.valueOf(a.get("code")).compareTo(String.valueOf(b.get("code")));
            }
            if (x == null)
            {
                return 1;
            }
            if (y == null)
            {
                return -1;
            }
            int c = desc ? y.compareTo(x) : x.compareTo(y);
            return c != 0 ? c : String.valueOf(a.get("code")).compareTo(String.valueOf(b.get("code")));
        });
    }

    /** 平台、品牌、类型的名称（游戏字典） */
    @SuppressWarnings("unchecked")
    Map<String, String> dimNames()
    {
        Map<String, String> out = new LinkedHashMap<>();
        Object ps = config.gameDict(site()).get("platforms");
        if (ps instanceof List<?> list)
        {
            for (Object o : list)
            {
                if (o instanceof Map<?, ?> p)
                {
                    out.put("platform:" + p.get("code"), String.valueOf(p.get("name")));
                    for (String k : List.of("brands", "gameTypes"))
                    {
                        if (p.get(k) instanceof List<?> sub)
                        {
                            for (Object x : sub)
                            {
                                if (x instanceof Map<?, ?> s)
                                {
                                    out.put(("brands".equals(k) ? "brand:" : "gameType:") + s.get("code"), String.valueOf(s.get("name")));
                                }
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    static String name(String dim, String code, Map<String, Object> row, Map<String, Map<String, Object>> games, Map<String, String> names)
    {
        if ("game".equals(dim))
        {
            Object n = row.get("gameName");
            if (n != null && !String.valueOf(n).isBlank())
            {
                return String.valueOf(n);
            }
            Map<String, Object> g = games.get(code);
            return g == null || g.get("name") == null ? code : String.valueOf(g.get("name"));
        }
        if ("date".equals(dim))
        {
            return code;
        }
        return names.getOrDefault(dim + ":" + code, code);
    }

    List<Map<String, Object>> meta(List<RiskColumn> cols)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RiskColumn c : cols)
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", c.getCode());
            m.put("name", c.getName());
            m.put("group", c.getGroup());
            m.put("valueFormat", c.getValueFormat());
            m.put("standard", c.isStandard());
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ 游戏抽屉

    public Map<String, Object> detail(RiskQuery q)
    {
        if (StringUtils.isEmpty(q.getGameCode()))
        {
            throw RiskException.field("gameCode", "請選擇遊戲");
        }
        RiskFilter f = q.filter(site(), props.getMaxSpanDays());
        String game = q.getGameCode().trim();
        Map<String, Map<String, Object>> games = config.games(site());
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("code", game);
        Map<String, Object> dict = games.get(game);
        g.put("name", dict == null ? game : dict.get("name"));
        g.put("platform", dict == null ? null : dict.get("platform"));
        g.put("brand", dict == null ? null : dict.get("brand"));
        g.put("gameType", dict == null ? null : dict.get("gameType"));
        out.put("game", g);
        Map<String, RiskColumn> all = catalog.game();
        out.put("dataPending", !source.available(RiskDataSource.Kind.GAME));
        // 所选时间的汇总：全部游戏分析列
        List<RiskColumn> cols = new ArrayList<>(all.values());
        if (source.available(RiskDataSource.Kind.GAME))
        {
            List<Map<String, Object>> rows = source.games(f, List.of(), Map.of("game", game), fields(all, cols));
            if (rows.isEmpty() && dict == null)
            {
                throw RiskException.notFound("遊戲不存在");
            }
            out.put("summary", rows.isEmpty() ? new LinkedHashMap<>() : values(all, cols, rows.get(0)));
            // 近 7 日（含今天）
            LocalDate today = LocalDate.now(dashboard == null ? java.time.ZoneId.systemDefault() : dashboard.currentZone());
            List<RiskColumn> dayCols = new ArrayList<>();
            for (String c : props.getDrawerDayCols())
            {
                if (all.get(c) != null)
                {
                    dayCols.add(all.get(c));
                }
            }
            Map<String, Map<String, Object>> byDate = new LinkedHashMap<>();
            for (Map<String, Object> r : source.gameDays(site(), game, today.minusDays(6), today, fields(all, dayCols)))
            {
                Object d = r.get("date");
                if (d != null)
                {
                    byDate.put(String.valueOf(d).substring(0, Math.min(10, String.valueOf(d).length())), r);
                }
            }
            List<Map<String, Object>> days = new ArrayList<>();
            for (int i = 0; i < 7; i++)
            {
                LocalDate d = today.minusDays(i);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("date", d.toString());
                m.put("today", i == 0);
                Map<String, Object> r = byDate.get(d.toString());
                for (RiskColumn c : dayCols)
                {
                    BigDecimal v = r == null ? null : value(all, c, r, 0);
                    // 没有投注的日子各值为 0，比率为空
                    m.put(c.getCode(), v != null ? v : c.isCalc() && "DIVIDE".equals(c.getCalcOp()) ? null : BigDecimal.ZERO);
                }
                days.add(m);
            }
            out.put("days", days);
        }
        else
        {
            out.put("summary", new LinkedHashMap<>());
            out.put("days", List.of());
        }
        out.put("topUsers", topUsers(f, game));
        return out;
    }

    /** 盈利最多的用户（常玩此游戏）：「投注最多的游戏」是这款的用户，按配置的列排，取前 10 */
    List<Map<String, Object>> topUsers(RiskFilter f, String game)
    {
        String gameCol = props.getDrawerTopGameColumn();
        String rankCol = props.getDrawerTopUsersRankCol();
        if (catalog.rank(gameCol) == null || catalog.rank(rankCol) == null)
        {
            return List.of();
        }
        RiskRankData data = rank.data(f);
        if (data.getUnavailable().containsKey(gameCol))
        {
            return List.of();
        }
        List<RiskUser> users = new ArrayList<>();
        for (RiskUser u : data.getUsers().values())
        {
            if (game.equals(u.text(gameCol)))
            {
                users.add(u);
            }
        }
        users.sort((a, b) -> {
            BigDecimal x = a.num(rankCol);
            BigDecimal y = b.num(rankCol);
            if (x == null || y == null)
            {
                return x == null ? (y == null ? 0 : 1) : -1;
            }
            int c = y.compareTo(x);
            return c != 0 ? c : a.getUserId().compareTo(b.getUserId());
        });
        List<Map<String, Object>> out = new ArrayList<>();
        for (RiskUser u : users.subList(0, Math.min(10, users.size())))
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", u.getUserId());
            m.put("account", RiskRankService.mask(u.getAccount()));
            for (String c : props.getDrawerTopUsersCols())
            {
                m.put(c, u.get(c));
            }
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ 导出

    /** 所有层平铺导出：上层行名称后加「（小計）」，行按层级、每层按排序 */
    @SuppressWarnings("unchecked")
    public RiskSheet export(RiskQuery q)
    {
        RiskFilter f = q.filter(site(), props.getMaxSpanDays());
        List<String> path = path(q.getPath(), props.getGameDefaultPath());
        List<RiskColumn> cols = columns(q.getCols());
        Map<String, RiskColumn> all = catalog.game();
        RiskSheet sheet = new RiskSheet("遊戲分析", (dashboard == null ? "" : StringUtils.trimToEmpty(dashboard.siteName(site())))
            + " · 風控分析 · 遊戲分析 · " + q.getSlotFrom() + " – " + q.getSlotTo());
        sheet.column("層級", RiskSheet.TEXT);
        for (String d : path)
        {
            sheet.column(dimLabel(d), RiskSheet.TEXT);
        }
        for (RiskColumn c : cols)
        {
            sheet.column(c.getName(), RiskSheet.format(c.getValueFormat()));
        }
        sheet.fileName = (dashboard == null ? "" : StringUtils.trimToEmpty(dashboard.siteName(site()))) + "_遊戲分析_"
            + q.getSlotFrom().replaceAll("[^0-9]", "").substring(0, 8) + "-" + q.getSlotTo().replaceAll("[^0-9]", "").substring(0, 8);
        if (!source.available(RiskDataSource.Kind.GAME))
        {
            return sheet;
        }
        Set<String> fields = fields(all, cols);
        // 每一层一次查询：键 → 行
        List<List<Map<String, Object>>> levels = new ArrayList<>();
        long count = 0;
        for (int lv = 0; lv < path.size(); lv++)
        {
            List<Map<String, Object>> rows = source.games(f, path.subList(0, lv + 1), Map.of(), fields);
            List<Map<String, Object>> built = new ArrayList<>();
            for (Map<String, Object> r : rows)
            {
                StringBuilder key = new StringBuilder();
                for (int i = 0; i <= lv; i++)
                {
                    String d = path.get(i);
                    Object v = r.get(d);
                    String c = v == null ? "" : "date".equals(d) ? String.valueOf(v).substring(0, Math.min(10, String.valueOf(v).length())) : String.valueOf(v);
                    key.append(i == 0 ? "" : "|").append(d).append(':').append(c);
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", key.toString());
                m.put("row", r);
                m.put("code", r.get(path.get(lv)) == null ? "" : String.valueOf(r.get(path.get(lv))));
                m.put("values", values(all, cols, r));
                built.add(m);
            }
            sort(built, path.get(lv), q);
            levels.add(built);
            count += built.size();
            if (count > props.getMaxExportRows())
            {
                throw RiskException.tooLarge("超過 " + String.format("%,d", props.getMaxExportRows()) + " 行，請縮小範圍");
            }
        }
        Map<String, Map<String, Object>> games = config.games(site());
        Map<String, String> names = dimNames();
        walk(sheet, levels, 0, "", path, cols, games, names);
        return sheet;
    }

    @SuppressWarnings("unchecked")
    private void walk(RiskSheet sheet, List<List<Map<String, Object>>> levels, int lv, String prefix, List<String> path,
        List<RiskColumn> cols, Map<String, Map<String, Object>> games, Map<String, String> names)
    {
        for (Map<String, Object> m : levels.get(lv))
        {
            String key = (String) m.get("key");
            if (lv > 0 && !key.startsWith(prefix + "|"))
            {
                continue;
            }
            Object[] x = sheet.newRow();
            x[0] = String.valueOf(lv + 1);
            Map<String, Object> r = (Map<String, Object>) m.get("row");
            for (int i = 0; i <= lv; i++)
            {
                String d = path.get(i);
                Object v = r.get(d);
                String code = v == null ? "" : String.valueOf(v);
                String n = name(d, "date".equals(d) ? code.substring(0, Math.min(10, code.length())) : code, r, games, names);
                x[1 + i] = i == lv && lv + 1 < path.size() ? n + "（小計）" : n;
            }
            Map<String, Object> vals = (Map<String, Object>) m.get("values");
            for (int j = 0; j < cols.size(); j++)
            {
                Object v = vals.get(cols.get(j).getCode());
                x[1 + path.size() + j] = v instanceof BigDecimal b && "PCT".equals(cols.get(j).getValueFormat()) ? b.movePointRight(2) : v;
            }
            if (lv + 1 < levels.size())
            {
                walk(sheet, levels, lv + 1, key, path, cols, games, names);
            }
        }
    }

    static String dimLabel(String d)
    {
        return switch (d)
        {
            case "platform" -> "遊戲平台";
            case "brand" -> "遊戲品牌";
            case "gameType" -> "遊戲類型";
            case "game" -> "遊戲";
            default -> "日期";
        };
    }
}
