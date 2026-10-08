package com.fivetech.dashboard.service;

import java.util.LinkedHashMap;
import java.util.Map;
import com.fivetech.dashboard.domain.MetricDefinition;
import com.fivetech.dashboard.export.ExportTemplate;

/**
 * 运营总览指标卡的繁体文案：名称与统计口径，逐字取自原型（原型-Yata-MVP-V1.0，指标定义 dH）。
 * <p>
 * 指标名称、分组名在库里（dashboard_metric_card / dashboard_metric_group）是简体，供指标汇总等其他页面共用，
 * 这里只在运营总览返回时换成繁体，不改库。原型里没有的指标：名称按字转繁体，口径用「分子 ÷ 分母」的繁体名称拼出。
 * </p>
 *
 * @author fivetech
 */
public final class OverviewMetricText
{
    private OverviewMetricText()
    {
    }

    /** 指标编码 → {名称, 统计口径} */
    private static final Map<String, String[]> TEXT = new LinkedHashMap<>();

    static
    {
        put("reg", "註冊人數", "完成會員註冊的人數");
        put("ftd", "首存人數", "完成生涯首筆存款的人數");
        put("ftdr", "首存轉化率", "首存人數 ÷ 註冊人數 × 100%");
        put("dep", "存款總額", "所有會員成功存款金額加總");
        put("wd", "提款總額", "所有會員成功提款金額加總（USDT 提款由資料源折為 INR 後計入）");
        put("net", "存提差", "存款總額 − 提款總額");
        put("arppu", "ARPPU", "存款總額 ÷ 去重存款人數。同一人在區間內只計一次：區間值的分母按整個區間去重，"
            + "不能用各行存款人數相加，也不等於各行 ARPPU 的平均");
        put("ftdA", "首存ARPPU", "首存總金額 ÷ 首存人數");
        put("dOkR", "存款成功率", "存款成功筆數 ÷ 存款總嘗試筆數 × 100%");
        put("dT", "平均到帳時間", "存款自送出到入帳的平均耗時（UPI / 銀行卡 / USDT 合併計算）");
        put("wOkR", "出款成功率", "出款成功筆數 ÷ 出款總嘗試筆數 × 100%");
        put("wT", "平均出款時間", "提款自申請到撥款完成的平均耗時");
        put("bonus", "發放贈金總額", "各贈金項目實際發放金額加總");
        put("bonusR", "贈金比", "贈金發放總額 ÷ 投注總額 × 100%");
        put("bet", "投注總額", "所有投注金額加總");
        put("ggr", "GGR", "投注總額 − 總派彩金額");
        put("killR", "平均殺率", "GGR ÷ 投注總額");
        put("active", "活躍人數", "有投注行為的去重會員數");
        put("login", "登錄人數", "完成登入的去重會員數");
        put("ngr", "NGR", "GGR − 發放贈金總額");
        put("turnX", "流水倍數", "投注總額 ÷ 存款總額");
    }

    private static void put(String code, String label, String caliber)
    {
        TEXT.put(code, new String[] { label, caliber });
    }

    /** 繁体名称；原型里没有的按字转繁体 */
    public static String label(String code, String fallback)
    {
        String[] t = TEXT.get(code);
        if (t != null)
        {
            return t[0];
        }
        return ExportTemplate.t(fallback == null ? code : fallback);
    }

    /** 繁体分组名 */
    public static String group(String name)
    {
        return ExportTemplate.t(name);
    }

    /**
     * 繁体统计口径。原型里没有的派生指标，用两个操作数的繁体名称拼「A ÷ B」/「A − B」；原子指标没有口径返回 null。
     */
    public static String caliber(String code, MetricDefinition def, MetricRegistry registry)
    {
        String[] t = TEXT.get(code);
        if (t != null)
        {
            return t[1];
        }
        if (def == null || def.getLeftCode() == null || def.getRightCode() == null)
        {
            return null;
        }
        String op = "DIVIDE".equalsIgnoreCase(def.getCalcType()) ? " ÷ " : " − ";
        return nameOf(def.getLeftCode(), registry) + op + nameOf(def.getRightCode(), registry);
    }

    private static String nameOf(String code, MetricRegistry registry)
    {
        MetricDefinition d = registry.get(code);
        return label(code, d == null ? code : d.getLabel());
    }
}
