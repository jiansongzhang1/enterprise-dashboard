package com.fivetech.risk.rank;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fivetech.risk.RiskProperties;
import com.fivetech.risk.catalog.RiskColumn;

/**
 * 把数据源的行按列目录汇总成每个用户一行（{@link RiskUser}）。怎么汇总全由列的配置决定：
 * <ul>
 *   <li>SUM / MAX / MIN / LAST：同一用户的多行（多天、多游戏）合并；</li>
 *   <li>FOLLOW：取 follow 那一列取最大值的那一行（如中奖游戏、中奖时间、该笔投注额都取自派彩最高的那一笔注单）；
 *       并列时按 follow 列的 tieBreak：先比 值 ÷ ratioOf 大的，再比 timeOf 早的；</li>
 *   <li>ARGMAX：按游戏汇总 argOf 那一列，取最大的那款（positive = 只取 &gt; 0，都不满足为空）；并列取 tieBy 列大的，再按编码；
 *       uds_day_field 填游戏维度（game）；</li>
 *   <li>CALC：由其他列算（DIVIDE / SUBTRACT / ADD / DAYS_SINCE）。</li>
 * </ul>
 * 维度字段（userId、account、regTime、agentId、channelId、parent、game、gameName、platform）在行里用逻辑名，
 * 列的 uds_day_field 写逻辑名即表示取维度。
 *
 * @author fivetech
 */
public final class RiskAggregator
{
    public static final String USER_ID = "userId";

    public static final String ACCOUNT = "account";

    /** 用户所属推广商 / 推广渠道（渠道风险榜按它分组；值放在 RiskUser 里，不是列） */
    public static final String AGENT_ID = "agentId";

    public static final String CHANNEL_ID = "channelId";

    /** 行里的逻辑维度名（其余为 UDS 指标字段） */
    static final Set<String> DIMS = RiskProperties.defaultFields().keySet();

    private RiskAggregator()
    {
    }

    /** 要向某个来源取的 UDS 指标字段（去掉维度名） */
    public static Set<String> fieldsOf(Collection<RiskColumn> columns, String source)
    {
        Set<String> out = new LinkedHashSet<>();
        for (RiskColumn c : columns)
        {
            if (source.equals(c.getSource()) && c.getUdsField() != null && !c.getUdsField().isEmpty() && !DIMS.contains(c.getUdsField()))
            {
                out.add(c.getUdsField());
            }
        }
        return out;
    }

    /**
     * 合并一个来源的行到 users（不存在的用户新建）。
     *
     * @param keepUsers 只合并这些用户；null = 全部
     */
    public static void merge(Map<String, RiskUser> users, Collection<RiskColumn> columns, String source,
        List<Map<String, Object>> rows, Set<String> keepUsers)
    {
        Map<String, List<Map<String, Object>>> byUser = new LinkedHashMap<>();
        for (Map<String, Object> r : rows)
        {
            Object id = r.get(USER_ID);
            if (id == null)
            {
                continue;
            }
            String key = String.valueOf(id).trim();
            if (keepUsers != null && !keepUsers.contains(key))
            {
                continue;
            }
            byUser.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        Map<String, RiskColumn> byCode = new LinkedHashMap<>();
        for (RiskColumn c : columns)
        {
            byCode.put(c.getCode(), c);
        }
        for (Map.Entry<String, List<Map<String, Object>>> e : byUser.entrySet())
        {
            RiskUser u = users.computeIfAbsent(e.getKey(), RiskUser::new);
            List<Map<String, Object>> list = e.getValue();
            for (String dim : List.of(ACCOUNT, AGENT_ID, CHANNEL_ID))
            {
                if (u.get(dim) == null)
                {
                    u.put(dim, lastText(list, dim));
                }
            }
            for (RiskColumn c : columns)
            {
                if (!source.equals(c.getSource()) || c.getUdsField() == null)
                {
                    continue;
                }
                Object v = switch (c.getAgg())
                {
                    case "FOLLOW" -> follow(list, c, byCode);
                    case "ARGMAX" -> argmax(list, c, byCode);
                    default -> c.isNumber() ? numeric(list, c.getUdsField(), c.getAgg()) : lastText(list, c.getUdsField());
                };
                u.put(c.getCode(), "TIME".equals(c.getKind()) && v != null ? time(String.valueOf(v)) : v);
            }
        }
    }

    /** 维度列（source = DIM）：取用户行里的维度值 */
    public static void dims(Map<String, RiskUser> users, Collection<RiskColumn> columns, List<Map<String, Object>> rows)
    {
        merge(users, dimAsSource(columns), "DIM", rows, null);
    }

    private static List<RiskColumn> dimAsSource(Collection<RiskColumn> columns)
    {
        List<RiskColumn> out = new ArrayList<>();
        for (RiskColumn c : columns)
        {
            if ("DIM".equals(c.getSource()))
            {
                out.add(c);
            }
        }
        return out;
    }

    /** 计算列：按依赖多轮计算（最多 5 层） */
    public static void calc(Collection<RiskUser> users, Collection<RiskColumn> columns, LocalDate today)
    {
        List<RiskColumn> calcs = new ArrayList<>();
        for (RiskColumn c : columns)
        {
            if ("CALC".equals(c.getSource()) && c.isCalc())
            {
                calcs.add(c);
            }
        }
        if (calcs.isEmpty())
        {
            return;
        }
        for (RiskUser u : users)
        {
            for (int pass = 0; pass < 5; pass++)
            {
                for (RiskColumn c : calcs)
                {
                    u.put(c.getCode(), calcOne(u, c, today));
                }
            }
        }
    }

    static Object calcOne(RiskUser u, RiskColumn c, LocalDate today)
    {
        if ("DAYS_SINCE".equals(c.getCalcOp()))
        {
            String t = u.text(c.getCalcLeft());
            if (t == null || t.length() < 10)
            {
                return null;
            }
            try
            {
                long days = ChronoUnit.DAYS.between(LocalDate.parse(t.substring(0, 10)), today);
                return BigDecimal.valueOf(Math.max(0, days));
            }
            catch (RuntimeException e)
            {
                return null;
            }
        }
        BigDecimal a = u.num(c.getCalcLeft());
        BigDecimal b = u.num(c.getCalcRight());
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

    // ------------------------------------------------------------------ 汇总方式

    static BigDecimal numeric(List<Map<String, Object>> rows, String field, String agg)
    {
        BigDecimal out = null;
        for (Map<String, Object> r : rows)
        {
            BigDecimal v = num(r.get(field));
            if (v == null)
            {
                continue;
            }
            if (out == null)
            {
                out = v;
                continue;
            }
            out = switch (agg)
            {
                case "MAX" -> out.max(v);
                case "MIN" -> out.min(v);
                case "LAST" -> v;
                default -> out.add(v);
            };
        }
        return out;
    }

    static String lastText(List<Map<String, Object>> rows, String field)
    {
        String out = null;
        for (Map<String, Object> r : rows)
        {
            Object v = r.get(field);
            if (v != null && !String.valueOf(v).isBlank())
            {
                out = String.valueOf(v).trim();
            }
        }
        return out;
    }

    /** 取 follow 列最大的那一行里本列的值 */
    static Object follow(List<Map<String, Object>> rows, RiskColumn c, Map<String, RiskColumn> byCode)
    {
        RiskColumn lead = byCode.get(c.getFollow());
        if (lead == null || lead.getUdsField() == null)
        {
            return null;
        }
        Map<String, Object> best = bestRow(rows, lead, byCode);
        if (best == null)
        {
            return null;
        }
        Object v = best.get(c.getUdsField());
        return c.isNumber() ? num(v) : v == null ? null : String.valueOf(v).trim();
    }

    /** lead 列最大的一行；并列先比 lead ÷ ratioOf 大，再比 timeOf 早。lead 全为空或 0 返回 null */
    static Map<String, Object> bestRow(List<Map<String, Object>> rows, RiskColumn lead, Map<String, RiskColumn> byCode)
    {
        RiskColumn ratioOf = lead.getTieRatioOf() == null ? null : byCode.get(lead.getTieRatioOf());
        RiskColumn timeOf = lead.getTieTimeOf() == null ? null : byCode.get(lead.getTieTimeOf());
        Map<String, Object> best = null;
        for (Map<String, Object> r : rows)
        {
            BigDecimal v = num(r.get(lead.getUdsField()));
            if (v == null || v.signum() <= 0)
            {
                continue;
            }
            if (best == null || better(r, best, lead, ratioOf, timeOf) > 0)
            {
                best = r;
            }
        }
        return best;
    }

    private static int better(Map<String, Object> a, Map<String, Object> b, RiskColumn lead, RiskColumn ratioOf, RiskColumn timeOf)
    {
        BigDecimal va = num(a.get(lead.getUdsField()));
        BigDecimal vb = num(b.get(lead.getUdsField()));
        int c = va.compareTo(vb);
        if (c != 0)
        {
            return c;
        }
        if (ratioOf != null)
        {
            // 派彩倍数高的（即投注额小的）优先；投注额为 0（免费旋转等）倍数为空，排在后面
            BigDecimal ra = ratio(va, num(a.get(ratioOf.getUdsField())));
            BigDecimal rb = ratio(vb, num(b.get(ratioOf.getUdsField())));
            if (ra != null || rb != null)
            {
                if (ra == null)
                {
                    return -1;
                }
                if (rb == null)
                {
                    return 1;
                }
                c = ra.compareTo(rb);
                if (c != 0)
                {
                    return c;
                }
            }
        }
        if (timeOf != null)
        {
            String ta = text(a.get(timeOf.getUdsField()));
            String tb = text(b.get(timeOf.getUdsField()));
            if (ta != null && tb != null)
            {
                return -ta.compareTo(tb);
            }
        }
        return 0;
    }

    private static BigDecimal ratio(BigDecimal v, BigDecimal base)
    {
        return base == null || base.signum() == 0 ? null : v.divide(base, 6, RoundingMode.HALF_UP);
    }

    /** 按游戏汇总 argOf 列，取最大的游戏编码 */
    static String argmax(List<Map<String, Object>> rows, RiskColumn c, Map<String, RiskColumn> byCode)
    {
        RiskColumn of = byCode.get(c.getArgOf());
        if (of == null || of.getUdsField() == null)
        {
            return null;
        }
        String gameField = c.getUdsField();
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        Map<String, BigDecimal> ties = new LinkedHashMap<>();
        RiskColumn tie = c.getTieBy() == null ? null : byCode.get(c.getTieBy());
        for (Map<String, Object> r : rows)
        {
            String g = text(r.get(gameField));
            BigDecimal v = num(r.get(of.getUdsField()));
            if (g == null || v == null)
            {
                continue;
            }
            sums.merge(g, v, BigDecimal::add);
            if (tie != null && tie.getUdsField() != null)
            {
                BigDecimal t = num(r.get(tie.getUdsField()));
                if (t != null)
                {
                    ties.merge(g, t, BigDecimal::add);
                }
            }
        }
        return sums.entrySet().stream()
            .filter(e -> !c.isPositive() || e.getValue().signum() > 0)
            .max(Comparator.<Map.Entry<String, BigDecimal>, BigDecimal>comparing(Map.Entry::getValue)
                .thenComparing(e -> ties.getOrDefault(e.getKey(), BigDecimal.ZERO))
                .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    /** 时间统一为 yyyy-MM-dd HH:mm（日期只有 10 位的保留日期） */
    static String time(String t)
    {
        String s = t.trim().replace('T', ' ');
        return s.length() >= 16 ? s.substring(0, 16) : s;
    }

    static String text(Object v)
    {
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).trim();
    }

    public static BigDecimal num(Object v)
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
