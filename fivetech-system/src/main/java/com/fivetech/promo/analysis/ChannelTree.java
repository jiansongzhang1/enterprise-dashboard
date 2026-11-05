package com.fivetech.promo.analysis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.configcenter.ConfigCodes;
import com.fivetech.promo.analysis.query.ChannelTableQuery.PathItem;
import com.fivetech.promo.budget.PromoChannelDictionary;

/**
 * 渠道表现的行维度树：校验行维度路径、列出每一层的成员、组装单元格。取数接口与导出共用。
 *
 * <p>成员来自字典与时间范围（所选渠道范围内的推广商、推广渠道、日期即使没有数据也列出）；终端层只列字典里的终端，
 * 「未識別」在有未识别注册时才返回（数据接入后）。</p>
 *
 * @author fivetech
 */
final class ChannelTree
{
    static final String AGENT = "agent";
    static final String CHANNEL = "channel";
    static final String DATE = "date";
    static final String TERMINAL = "terminal";
    static final String RELATION = "relation";

    static final Map<String, String> DIM_NAMES = dimNames();

    /** 批次列的天数 */
    private static final Map<String, Integer> COHORT_DAYS = Map.of("roas7", 7, "roas30", 30, "regRet1", 1, "regRet3", 3,
        "regRet7", 7, "regRet15", 15, "regRet30", 30);

    private static Map<String, String> dimNames()
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(AGENT, "推廣商");
        m.put(CHANNEL, "推廣渠道");
        m.put(DATE, "日期");
        m.put(TERMINAL, "終端");
        m.put(RELATION, "推廣關係");
        return m;
    }

    /** 一层里的一个成员 */
    static final class Member
    {
        final String key;
        final String name;
        /** 只在推广渠道层：所属推广商名称 */
        final String agent;
        final boolean disabled;

        Member(String key, String name, String agent, boolean disabled)
        {
            this.key = key;
            this.name = name;
            this.agent = agent;
            this.disabled = disabled;
        }
    }

    private final List<String> dims;

    private final PromoScope scope;

    private final PromoTerminalDictionary.Config terminals;

    private final PromoTime.Range range;

    private final LocalDate yesterday;

    private ChannelTree(List<String> dims, PromoScope scope, PromoTerminalDictionary.Config terminals, PromoTime.Range range,
        LocalDate yesterday)
    {
        this.dims = dims;
        this.scope = scope;
        this.terminals = terminals;
        this.range = range;
        this.yesterday = yesterday;
    }

    /** 校验行维度路径（1–5 层、不重复、channel 在 agent 之后），不合法 4003 */
    static ChannelTree of(List<String> rows, PromoScope scope, PromoTerminalDictionary.Config terminals, PromoTime.Range range,
        LocalDate yesterday)
    {
        if (rows == null || rows.isEmpty() || rows.size() > 5)
        {
            throw new ServiceException("rows（行維度）須為 1–5 層", PromoErrors.UNKNOWN_CODE);
        }
        List<String> dims = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : rows)
        {
            String d = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (!DIM_NAMES.containsKey(d))
            {
                throw new ServiceException("不認識的行維度：" + raw + "（agent / channel / date / terminal / relation）", PromoErrors.UNKNOWN_CODE);
            }
            if (!seen.add(d))
            {
                throw new ServiceException("行維度重複：" + raw, PromoErrors.UNKNOWN_CODE);
            }
            dims.add(d);
        }
        int a = dims.indexOf(AGENT);
        int c = dims.indexOf(CHANNEL);
        if (a >= 0 && c >= 0 && c < a)
        {
            throw new ServiceException("推廣渠道須在推廣商之後", PromoErrors.UNKNOWN_CODE);
        }
        return new ChannelTree(dims, scope, terminals, range, yesterday);
    }

    List<String> dims()
    {
        return dims;
    }

    /** 默认行维度：推廣商 › 推廣渠道 */
    boolean isDefault()
    {
        return dims.equals(List.of(AGENT, CHANNEL));
    }

    /** 校验 parent 路径：维度与 rows 逐层一致、成员存在；不合法 4003 */
    void checkParent(List<PathItem> parent)
    {
        if (parent.size() >= dims.size())
        {
            throw new ServiceException("parent 已是最後一層，沒有下一層", PromoErrors.UNKNOWN_CODE);
        }
        List<PathItem> prefix = new ArrayList<>();
        for (int i = 0; i < parent.size(); i++)
        {
            PathItem p = parent.get(i);
            if (p == null || p.getDim() == null || !dims.get(i).equals(p.getDim().trim().toLowerCase(Locale.ROOT)))
            {
                throw new ServiceException("parent 第 " + (i + 1) + " 層應為 " + dims.get(i), PromoErrors.UNKNOWN_CODE);
            }
            String key = p.getKey() == null ? "" : p.getKey().trim();
            String dim = dims.get(i);
            boolean found = members(i, prefix).stream().anyMatch(m -> sameKey(dim, m.key, key));
            if (!found)
            {
                throw new ServiceException("parent 第 " + (i + 1) + " 層的成員不存在：" + p.getKey(), PromoErrors.UNKNOWN_CODE);
            }
            prefix.add(new PathItem(dims.get(i), key));
        }
    }

    private static boolean sameKey(String dim, String a, String b)
    {
        return AGENT.equals(dim) || CHANNEL.equals(dim) ? ConfigCodes.idKey(a).equals(ConfigCodes.idKey(b)) : a.equalsIgnoreCase(b);
    }

    /** 第 level 层（0 起）的成员；path 为上面各层已选的成员 */
    List<Member> members(int level, List<PathItem> path)
    {
        String dim = dims.get(level);
        List<Member> out = new ArrayList<>();
        switch (dim)
        {
            case AGENT:
                for (PromoChannelDictionary.Promoter p : scope.promotersInScope())
                {
                    out.add(new Member(p.getId(), p.getName(), null, false));
                }
                break;
            case CHANNEL:
                String agentKey = keyOf(path, AGENT);
                for (PromoChannelDictionary.Promoter p : scope.promotersInScope())
                {
                    if (agentKey != null && !ConfigCodes.idKey(p.getId()).equals(ConfigCodes.idKey(agentKey)))
                    {
                        continue;
                    }
                    for (PromoChannelDictionary.Channel c : scope.channelsOf(p))
                    {
                        out.add(new Member(c.getId(), c.getName(), p.getName(), c.isDisabled()));
                    }
                }
                break;
            case DATE:
                for (LocalDate d = range.lastDay(); !d.isBefore(range.firstDay()); d = d.minusDays(1))
                {
                    out.add(new Member(d.toString(), d.toString(), null, false));
                }
                break;
            case TERMINAL:
                for (PromoTerminalDictionary.Terminal t : terminals.getTerminals())
                {
                    out.add(new Member(t.getCode(), t.getName(), null, false));
                }
                break;
            default:
                out.add(new Member("direct", "直推", null, false));
                out.add(new Member("fission", "裂變", null, false));
                break;
        }
        return out;
    }

    private static String keyOf(List<PathItem> path, String dim)
    {
        for (PathItem p : path)
        {
            if (dim.equals(p.getDim()))
            {
                return p.getKey();
            }
        }
        return null;
    }

    /**
     * 一行（或合计行）的单元格：数据接入前 value 为 null。
     * 路径上有终端或推广关系时依赖消耗的列为 NA；批次列带 matured，全部未到期为 PENDING。
     */
    Map<String, Object> cells(List<PromoMetricCatalog.Column> columns, List<PathItem> path)
    {
        boolean byTerminal = keyOf(path, TERMINAL) != null;
        boolean byRelation = keyOf(path, RELATION) != null;
        String day = keyOf(path, DATE);
        Map<String, Object> cells = new LinkedHashMap<>();
        for (PromoMetricCatalog.Column col : columns)
        {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("value", null);
            String state = "OK";
            String note = null;
            if (col.isSpendBased() && (byTerminal || byRelation))
            {
                state = "NA";
                note = byTerminal ? "消耗不按終端拆分" : "消耗不按推廣關係拆分";
            }
            Map<String, Object> matured = null;
            if (col.isCohort())
            {
                int n = COHORT_DAYS.get(col.getCode());
                LocalDate from = day == null ? range.firstDay() : LocalDate.parse(day);
                LocalDate to = day == null ? range.lastDay() : LocalDate.parse(day);
                int total = 0;
                int done = 0;
                for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1))
                {
                    total++;
                    if (!d.plusDays(n).isAfter(yesterday))
                    {
                        done++;
                    }
                }
                matured = new LinkedHashMap<>();
                matured.put("m", done);
                matured.put("n", total);
                if (done == 0 && "OK".equals(state))
                {
                    state = "PENDING";
                }
            }
            v.put("state", state);
            v.put("matured", matured);
            v.put("note", note);
            cells.put(col.getCode(), v);
        }
        return cells;
    }

    /** 从 level 层往下（含）全部展开的行数，超过 cap 时提前返回 cap + 1 */
    long count(int level, List<PathItem> path, long cap)
    {
        long total = 0;
        for (Member m : members(level, path))
        {
            total++;
            if (level + 1 < dims.size())
            {
                List<PathItem> next = new ArrayList<>(path);
                next.add(new PathItem(dims.get(level), m.key));
                total += count(level + 1, next, cap - total);
            }
            if (total > cap)
            {
                return cap + 1;
            }
        }
        return total;
    }
}
