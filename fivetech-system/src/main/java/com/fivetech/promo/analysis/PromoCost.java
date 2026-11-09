package com.fivetech.promo.analysis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 成本类指标（CPA、首存成本、D0 ROI、UV 成本）：后端按「有效格子」自己算。
 *
 * <p>有效格子 = 已录入消耗且 &gt; 0 的「日 × 推广渠道」。分子、分母都只在有效格子里相加：</p>
 * <ul>
 *   <li>CPA = Σ消耗 ÷ Σ注册人数；首存成本 = Σ消耗 ÷ Σ首存人数；UV 成本 = Σ消耗 ÷ ΣUV-落地頁（金额，2 位小数）；</li>
 *   <li>D0 ROI = Σ淨充值 ÷ Σ消耗 − 1（百分数，2 位小数）。</li>
 * </ul>
 * <p>行为数据（注册、首存、淨充值、UV）要能按推广渠道拆才能对上有效格子。数据平台还没有这个维度
 * （《推广分析 · 指标缺失与待确认清单》U1），{@link ChannelBehavior#NONE} 返回 null，这几个指标为 NO_SOURCE；
 * U1 到位后只需实现 {@link ChannelBehavior}，概览、渠道表现、投放日报都会出数。</p>
 *
 * @author fivetech
 */
final class PromoCost
{
    /** 后端自己算的成本类指标 */
    static final Set<String> CODES = Set.of("cpa", "ftdCost", "roi", "uvCost");

    /** 行为数据还不能按推广渠道拆时的说明 */
    static final String PENDING_NOTE = "按推廣渠道的註冊 / 首存 / 淨充值待數據平台提供，暫不能按有效格子計算";

    /** 有行为数据、但范围内没有有效格子（没录入消耗或消耗为 0） */
    static final String NO_SPEND_NOTE = "所選範圍內沒有已錄入的消耗";

    private PromoCost()
    {
    }

    /** 一个格子：日 × 推广渠道（idKey） */
    record Cell(LocalDate day, String channel)
    {
    }

    /** 按推广渠道的行为数据。metric：reg / ftd / net / lpUV；返回 null = 还不能按推广渠道拆 */
    interface ChannelBehavior
    {
        Map<Cell, BigDecimal> byChannel(String metric, LocalDate from, LocalDate to);

        /** 现状：数据平台还没有推广渠道维度（U1） */
        ChannelBehavior NONE = (metric, from, to) -> null;
    }

    /** 计算结果：state = OK / NO_SPEND / NO_SOURCE */
    record Result(String state, BigDecimal value, String note)
    {
    }

    /** 指标要用的行为数据 */
    static String behaviorMetric(String code)
    {
        switch (code)
        {
            case "cpa":
                return "reg";
            case "ftdCost":
                return "ftd";
            case "roi":
                return "net";
            default:
                return "lpUV";
        }
    }

    /** 有效格子：消耗已录入且 > 0 */
    static Set<Cell> effective(Map<Cell, BigDecimal> spend)
    {
        Set<Cell> out = new LinkedHashSet<>();
        spend.forEach((k, v) -> {
            if (v != null && v.signum() > 0)
            {
                out.add(k);
            }
        });
        return out;
    }

    /**
     * 算一个成本类指标。
     *
     * @param spend    范围内按格子的消耗（已按日期、推广商 / 推广渠道筛好）
     * @param behavior 同一范围按格子的行为数据；null = 还不能按推广渠道拆
     */
    static Result evaluate(String code, Map<Cell, BigDecimal> spend, Map<Cell, BigDecimal> behavior)
    {
        if (behavior == null)
        {
            return new Result("NO_SOURCE", null, note(code));
        }
        Set<Cell> cells = effective(spend);
        if (cells.isEmpty())
        {
            return new Result("NO_SPEND", null, NO_SPEND_NOTE);
        }
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal other = BigDecimal.ZERO;
        for (Cell c : cells)
        {
            cost = cost.add(spend.get(c));
            BigDecimal b = behavior.get(c);
            if (b != null)
            {
                other = other.add(b);
            }
        }
        if ("roi".equals(code))
        {
            BigDecimal r = other.divide(cost, 6, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
            return new Result("OK", r.movePointRight(2).setScale(2, RoundingMode.HALF_UP), null);
        }
        return new Result("OK", other.signum() == 0 ? null : cost.divide(other, 2, RoundingMode.HALF_UP), null);
    }

    /** NO_SOURCE 的说明：UV 成本还要等 UV-落地頁口径 */
    static String note(String code)
    {
        return "uvCost".equals(code) ? PENDING_NOTE + "；UV-落地頁口徑待確認" : PENDING_NOTE;
    }

    /** 按格子筛：日期在 [from, to]、渠道满足条件 */
    static Map<Cell, BigDecimal> filter(Map<Cell, BigDecimal> cells, LocalDate from, LocalDate to, Predicate<String> channel)
    {
        if (cells == null)
        {
            return null;
        }
        Map<Cell, BigDecimal> out = new java.util.LinkedHashMap<>();
        cells.forEach((k, v) -> {
            if (!k.day().isBefore(from) && !k.day().isAfter(to) && channel.test(k.channel()))
            {
                out.put(k, v);
            }
        });
        return out;
    }
}
