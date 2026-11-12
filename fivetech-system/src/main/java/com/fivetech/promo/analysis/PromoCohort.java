package com.fivetech.promo.analysis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import com.fivetech.dashboard.gateway.uds.UdsRow;

/**
 * 留存与回收的取值规则（不调接口，便于单测）。数据来自 UDS {@code dashboard_market_cohort_v1}（见 MarketGateway#cohort）。
 *
 * <ul>
 *   <li>第 N 日的到期人数 {@code matured_dN}：注册后第 N 日已结束的会员数；为 0 = 该注册日还没到期（PENDING），其余列为 null；</li>
 *   <li>注册留存 = Σ retained_dN ÷ Σ matured_dN × 100（%）；LTV = Σ deposit_amount_dN ÷ Σ matured_dN（金额）；</li>
 *   <li>ROAS = Σ deposit_amount_dN ÷ Σ 消耗 × 100（%），只算有消耗（&gt; 0）的到期注册日（有效格子）；</li>
 *   <li>多个注册日的比率一律用分子分母各自相加再除（按到期人数加权），不拿 rt_dN 平均。</li>
 * </ul>
 *
 * @author fivetech
 */
final class PromoCohort
{
    static final String REG_RET = "regRet";

    static final String LTV = "ltv";

    static final String ROAS = "roas";

    private PromoCohort()
    {
    }

    /** 一个注册日（或一组：按终端时没有日期）的分群数 */
    static final class Day
    {
        final LocalDate date;

        final BigDecimal reg;

        /** 天数 N → [matured, retained, deposit_amount]（没到期时后两项为 null） */
        final Map<Integer, BigDecimal[]> byN = new LinkedHashMap<>();

        Day(LocalDate date, BigDecimal reg)
        {
            this.date = date;
            this.reg = reg;
        }

        BigDecimal matured(int n)
        {
            BigDecimal[] v = byN.get(n);
            return v == null || v[0] == null ? BigDecimal.ZERO : v[0];
        }

        boolean isMatured(int n)
        {
            return matured(n).signum() > 0;
        }

        BigDecimal retained(int n)
        {
            BigDecimal[] v = byN.get(n);
            return v == null ? null : v[1];
        }

        BigDecimal deposit(int n)
        {
            BigDecimal[] v = byN.get(n);
            return v == null ? null : v[2];
        }
    }

    /** UDS 一行 → Day；date 由调用方解析（按 reg_dt 分组时取 reg_dt，按终端分组为 null） */
    static Day day(UdsRow row, LocalDate date, List<Integer> offsets)
    {
        Day d = new Day(date, row.num("reg"));
        for (int n : offsets)
        {
            d.byN.put(n, new BigDecimal[] { row.num("matured_d" + n), row.num("retained_d" + n), row.num("deposit_amount_d" + n) });
        }
        return d;
    }

    /** reg_dt 的值 → 日期（"2026-11-10" 或带时间）；解析不了返回 null */
    static LocalDate regDate(String value)
    {
        if (value == null || value.length() < 10)
        {
            return null;
        }
        try
        {
            return LocalDate.parse(value.substring(0, 10));
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /** 按 reg_dt 分组的行 → 注册日 → Day（同一天多行相加，一般只有一行） */
    static Map<LocalDate, Day> byDate(List<UdsRow> rows, List<Integer> offsets)
    {
        Map<LocalDate, Day> out = new LinkedHashMap<>();
        if (rows == null)
        {
            return out;
        }
        for (UdsRow row : rows)
        {
            LocalDate date = regDate(row.str("reg_dt"));
            if (date == null)
            {
                continue;
            }
            Day d = day(row, date, offsets);
            Day old = out.get(date);
            out.put(date, old == null ? d : plus(old, d, offsets));
        }
        return out;
    }

    private static Day plus(Day a, Day b, List<Integer> offsets)
    {
        Day d = new Day(a.date, add(a.reg, b.reg));
        for (int n : offsets)
        {
            d.byN.put(n, new BigDecimal[] { add(a.matured(n), b.matured(n)), add(a.retained(n), b.retained(n)), add(a.deposit(n), b.deposit(n)) });
        }
        return d;
    }

    static BigDecimal add(BigDecimal a, BigDecimal b)
    {
        return a == null ? b : b == null ? a : a.add(b);
    }

    /** 第 N 日的合计：只累加已到期的注册日；ROAS 另外只算有消耗的注册日 */
    static final class Agg
    {
        BigDecimal matured = BigDecimal.ZERO;

        BigDecimal retained = BigDecimal.ZERO;

        BigDecimal deposit = BigDecimal.ZERO;

        /** 只 ROAS：参与计算的注册日的消耗合计 */
        BigDecimal spend = BigDecimal.ZERO;

        /** 参与计算的注册日个数（m）与首尾日期 */
        int days;

        LocalDate first;

        LocalDate last;

        /** 只 ROAS：有到期注册日，但都没有消耗 */
        boolean noSpend;
    }

    /**
     * 第 N 日合计。
     *
     * @param spendOf ROAS 时传每个注册日的消耗（没有记录为 null）；其他指标传 null
     */
    static Agg agg(Collection<Day> days, int n, Function<LocalDate, BigDecimal> spendOf)
    {
        Agg a = new Agg();
        boolean maturedAny = false;
        for (Day d : days)
        {
            if (!d.isMatured(n))
            {
                continue;
            }
            maturedAny = true;
            BigDecimal s = null;
            if (spendOf != null)
            {
                s = d.date == null ? null : spendOf.apply(d.date);
                if (s == null || s.signum() <= 0)
                {
                    continue;
                }
            }
            a.matured = a.matured.add(d.matured(n));
            a.retained = a.retained.add(nz(d.retained(n)));
            a.deposit = a.deposit.add(nz(d.deposit(n)));
            if (s != null)
            {
                a.spend = a.spend.add(s);
            }
            a.days++;
            if (d.date != null)
            {
                a.first = a.first == null || d.date.isBefore(a.first) ? d.date : a.first;
                a.last = a.last == null || d.date.isAfter(a.last) ? d.date : a.last;
            }
        }
        a.noSpend = spendOf != null && maturedAny && a.days == 0;
        return a;
    }

    private static BigDecimal nz(BigDecimal v)
    {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 指标值：留存、ROAS 为 %（两位小数），LTV 为金额（两位小数）；分母为 0 返回 null */
    static BigDecimal value(String metric, Agg a)
    {
        if (a == null || a.days == 0)
        {
            return null;
        }
        if (ROAS.equals(metric))
        {
            return a.spend.signum() == 0 ? null : a.deposit.multiply(BigDecimal.valueOf(100)).divide(a.spend, 2, RoundingMode.HALF_UP);
        }
        if (a.matured.signum() == 0)
        {
            return null;
        }
        if (LTV.equals(metric))
        {
            return a.deposit.divide(a.matured, 2, RoundingMode.HALF_UP);
        }
        return a.retained.multiply(BigDecimal.valueOf(100)).divide(a.matured, 2, RoundingMode.HALF_UP);
    }

    /** 该点没有值的原因：PENDING 未到期 / NO_SPEND 没有消耗（只 ROAS）；有值为 null */
    static String mark(Agg a)
    {
        if (a.days > 0)
        {
            return null;
        }
        return a.noSpend ? "NO_SPEND" : "PENDING";
    }

    /** 每个天数一个值 */
    static List<BigDecimal> values(String metric, Collection<Day> days, List<Integer> offsets, Function<LocalDate, BigDecimal> spendOf)
    {
        List<BigDecimal> out = new ArrayList<>();
        for (int n : offsets)
        {
            out.add(value(metric, agg(days, n, ROAS.equals(metric) ? spendOf : null)));
        }
        return out;
    }
}
