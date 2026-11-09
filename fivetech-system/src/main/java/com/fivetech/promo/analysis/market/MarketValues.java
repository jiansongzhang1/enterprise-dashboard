package com.fivetech.promo.analysis.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 页面指标 ← 市场数据集字段（只含对照文档里「可对接」的指标）。
 *
 * <table>
 *   <tr><td>reg 註冊人數</td><td>宽表 reg / {终端}_reg</td></tr>
 *   <tr><td>ftd 首存人數</td><td>宽表 ftd_players</td></tr>
 *   <tr><td>ftdR 首存轉化率</td><td>ftd_players ÷ reg</td></tr>
 *   <tr><td>ftdAmt 首存金額</td><td>宽表 ftd_amount</td></tr>
 *   <tr><td>rdAmt 複存金額</td><td>宽表 redeposit_amount</td></tr>
 *   <tr><td>rd 複存人數</td><td>精确 redeposit_users，逐日相加</td></tr>
 *   <tr><td>rdR 複存率</td><td>Σ redeposit_users ÷ ftd_players</td></tr>
 *   <tr><td>depU 存款人數</td><td>精确 deposit_users，逐日相加</td></tr>
 *   <tr><td>depR 存款率</td><td>Σ deposit_users ÷ reg</td></tr>
 *   <tr><td>dep 存款總額 / net 淨充值（= 存款总额）</td><td>精确 deposit_amount</td></tr>
 *   <tr><td>arppu</td><td>deposit_amount ÷ Σ deposit_users</td></tr>
 *   <tr><td>betU 投注人數 / bet 投注總額</td><td>精确 bet_users（逐日相加）/ bet_amount</td></tr>
 *   <tr><td>act 激活</td><td>宽表 android_first_open；没有终端拆分，只在「全部」与 Android 有值</td></tr>
 * </table>
 *
 * <p>比率为百分数（12.3 = 12.3%），保留 2 位；分母为 0 或缺失返回 null。</p>
 *
 * @author fivetech
 */
public final class MarketValues
{
    /** 可以从市场数据集取到的页面指标编码 */
    public static final Set<String> AVAILABLE = Set.of("reg", "ftd", "ftdR", "ftdAmt", "rdAmt", "rd", "rdR", "depU", "depR", "dep",
        "net", "arppu", "betU", "bet", "act");

    /** 有字段但口径与页面不同（产品确认前不展示） */
    public static final Set<String> CALIBER_DIFF = Set.of("lpPV", "lpUV", "dl", "regR", "uvCost");

    /** 比率类（PCT） */
    public static final Set<String> PCT = Set.of("ftdR", "rdR", "depR");

    /** 没有终端拆分的指标：只在全部与 Android 有值 */
    public static final Set<String> ANDROID_ONLY = Set.of("act");

    private MarketValues()
    {
    }

    /** 终端键 → UDS 的 terminal 取值（精确指标、会员快照过滤用） */
    public static String udsTerminal(String key)
    {
        if (key == null)
        {
            return null;
        }
        switch (key)
        {
            case "web":
                return "Web";
            case "android":
                return "Android";
            case "ios":
                return "iOS";
            case "unknown":
                return "Unknown";
            default:
                return null;
        }
    }

    /** 本系统终端编码 → 市场数据的终端键；all / 空 → all；认不出返回 null */
    public static String terminalKey(String code)
    {
        if (code == null || code.isBlank() || "all".equalsIgnoreCase(code.trim()))
        {
            return MarketCube.ALL;
        }
        String c = code.trim().toLowerCase(Locale.ROOT);
        switch (c)
        {
            case "web":
            case "h5":
            case "pc":
                return "web";
            case "android":
                return "android";
            case "ios":
                return "ios";
            case "unidentified":
            case "unknown":
                return "unknown";
            default:
                return null;
        }
    }

    /**
     * 计算一个页面指标。
     *
     * @param terminal 终端键（all / web / android / ios / unknown）
     */
    public static BigDecimal value(String code, MarketCube cube, String terminal, Predicate<LocalDateTime> buckets)
    {
        if (cube == null || code == null || terminal == null || !AVAILABLE.contains(code))
        {
            return null;
        }
        String t = terminal;
        switch (code)
        {
            case "reg":
                return cube.sum("reg", t, buckets);
            case "ftd":
                return cube.sum("ftd_players", t, buckets);
            case "ftdAmt":
                return cube.sum("ftd_amount", t, buckets);
            case "rdAmt":
                return cube.sum("redeposit_amount", t, buckets);
            case "rd":
                return cube.sum("redeposit_users", t, buckets);
            case "depU":
                return cube.sum("deposit_users", t, buckets);
            case "dep":
            case "net":
                return cube.sum("deposit_amount", t, buckets);
            case "betU":
                return cube.sum("bet_users", t, buckets);
            case "bet":
                return cube.sum("bet_amount", t, buckets);
            case "act":
                return MarketCube.ALL.equals(t) || "android".equals(t) ? cube.sum("android_first_open", MarketCube.ALL, buckets) : null;
            case "ftdR":
                return pct(cube.sum("ftd_players", t, buckets), cube.sum("reg", t, buckets));
            case "rdR":
                return pct(cube.sum("redeposit_users", t, buckets), cube.sum("ftd_players", t, buckets));
            case "depR":
                return pct(cube.sum("deposit_users", t, buckets), cube.sum("reg", t, buckets));
            case "arppu":
                return div(cube.sum("deposit_amount", t, buckets), cube.sum("deposit_users", t, buckets), 2);
            default:
                return null;
        }
    }

    public static BigDecimal pct(BigDecimal a, BigDecimal b)
    {
        BigDecimal r = div(a, b, 6);
        return r == null ? null : r.movePointRight(2).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal div(BigDecimal a, BigDecimal b, int scale)
    {
        if (a == null || b == null || b.signum() == 0)
        {
            return null;
        }
        return a.divide(b, scale, RoundingMode.HALF_UP);
    }

    /** 较上期：比率类给百分点 deltaPt，其余给变化率 deltaPct（上期为 0 时 null） */
    public static BigDecimal[] delta(String code, BigDecimal value, BigDecimal prev)
    {
        if (value == null || prev == null)
        {
            return new BigDecimal[] { null, null };
        }
        if (PCT.contains(code))
        {
            return new BigDecimal[] { null, value.subtract(prev).setScale(2, RoundingMode.HALF_UP) };
        }
        return new BigDecimal[] { prev.signum() == 0 ? null
            : value.subtract(prev).divide(prev.abs(), 6, RoundingMode.HALF_UP).movePointRight(2).setScale(2, RoundingMode.HALF_UP), null };
    }

    /** 状态与说明：拿不到的指标说明原因 */
    public static String noSourceNote(String code)
    {
        return CALIBER_DIFF.contains(code) ? "口徑待確認（數據平台字段與頁面口徑不同），暫不展示" : "數據待接入";
    }

    /** 用于结构对比：所有值 */
    public static Map<String, BigDecimal> all(MarketCube cube, String terminal, Predicate<LocalDateTime> buckets)
    {
        Map<String, BigDecimal> m = new java.util.LinkedHashMap<>();
        for (String c : AVAILABLE)
        {
            m.put(c, value(c, cube, terminal, buckets));
        }
        return m;
    }
}
