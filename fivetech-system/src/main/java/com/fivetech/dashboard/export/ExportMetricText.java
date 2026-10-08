package com.fivetech.dashboard.export;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导出用的指标文案（繁体，与《导出模板样例-v1_5》一致）：名称、分组、单位、口径、单位格式说明、数字格式。
 * <p>顺序即导出顺序（指标快照、时间序列的列序）。模板之外的指标由调用方用指标注册表的文案兜底。</p>
 *
 * @author fivetech
 */
public final class ExportMetricText
{
    private ExportMetricText()
    {
    }

    /** 一个指标的导出文案 */
    public static final class M
    {
        public final String code;

        public final String label;

        public final String group;

        /** 人數 / % / INR / min / 倍 */
        public final String unit;

        public final String note;

        /** 口径说明里「单位 / 格式」一栏 */
        public final String unitNote;

        /** Excel 数字格式，null 为 General */
        public final String format;

        M(String code, String label, String group, String unit, String note, String unitNote, String format)
        {
            this.code = code;
            this.label = label;
            this.group = group;
            this.unit = unit;
            this.note = note;
            this.unitNote = unitNote;
            this.format = format;
        }

        /** 表头：名称 + 单位后缀，人数类不带后缀 */
        public String header()
        {
            return label + suffix(unit);
        }
    }

    private static final String G_ACQ = "獲客與轉化";

    private static final String G_FUND = "資金流";

    private static final String G_PAY = "通道健康度";

    private static final String G_BET = "投注與盈收";

    private static final String U_INT = "人數，整數";

    private static final String U_PCT = "百分數值，2 位小數（16.67 表示 16.67%）";

    private static final String U_INR = "INR，純數值，2 位小數";

    private static final String U_MIN = "分鐘，1 位小數";

    public static final Map<String, M> ALL = new LinkedHashMap<>();

    static
    {
        put("reg", "註冊人數", G_ACQ, "人數", "完成會員註冊的人數", U_INT, null);
        put("ftd", "首存人數", G_ACQ, "人數", "完成生涯首筆存款的人數", U_INT, null);
        put("ftdr", "首存轉化率", G_ACQ, "%", "首存人數 ÷ 註冊人數 × 100%", U_PCT, "0.00");
        put("dep", "存款總額", G_FUND, "INR", "所有會員成功存款金額加總", U_INR, "0.00");
        put("wd", "提款總額", G_FUND, "INR", "所有會員成功提款金額加總", U_INR, "0.00");
        put("net", "存提差", G_FUND, "INR", "存款總額 − 提款總額", U_INR, "0.00");
        put("arppu", "ARPPU", G_FUND, "INR", "存款總額 ÷ 去重存款人數。同一人在區間內只計一次，因此區間值不等於各行的平均", U_INR, "0.00");
        put("ftdA", "首存ARPPU", G_ACQ, "INR", "首存總額 ÷ 首存人數", U_INR, "0.00");
        put("dOkR", "存款成功率", G_PAY, "%", "存款成功筆數 ÷ 存款總嘗試筆數 × 100%", U_PCT, "0.00");
        put("dT", "平均到帳時間", G_PAY, "min", "存款自送出到入帳的平均耗時（UPI / 銀行卡 / USDT 合併計算）", U_MIN, "0.0");
        put("wOkR", "出款成功率", G_PAY, "%", "出款成功筆數 ÷ 出款總嘗試筆數 × 100%", U_PCT, "0.00");
        put("wT", "平均出款時間", G_PAY, "min", "提款自申請到撥款完成的平均耗時", U_MIN, "0.0");
        put("bonus", "發放贈金總額", G_BET, "INR", "各贈金項目實際發放金額加總", U_INR, "0.00");
        put("bonusR", "贈金比", G_BET, "%", "發放贈金總額 ÷ 總流水 × 100%", U_PCT, "0.00");
        put("bet", "投注總額", G_BET, "INR", "所有投注金額加總（＝總流水）", U_INR, "0.00");
        put("ggr", "GGR", G_BET, "INR", "總流水 − 總派彩金額", U_INR, "0.00");
        put("killR", "平均殺率", G_BET, "%", "GGR ÷ 投注總額", U_PCT, "0.00");
        put("active", "活躍人數", G_BET, "人數", "有投注行為的去重會員數。去重規則同登錄人數", U_INT, null);
        put("login", "登錄人數", G_ACQ, "人數",
            "完成登錄的去重會員數。去重人數：各時間片各自去重，區間值按整個區間去重，因此各行相加大於區間值", U_INT, null);
        put("ngr", "NGR", G_BET, "INR", "GGR − 發放贈金總額", U_INR, "0.00");
        put("turnX", "流水倍數", G_FUND, "倍", "投注總額 ÷ 存款總額", "倍，2 位小數", "0.00");
    }

    private static void put(String code, String label, String group, String unit, String note, String unitNote,
            String format)
    {
        ALL.put(code, new M(code, label, group, unit, note, unitNote, format));
    }

    /** 模板里的指标顺序 */
    public static List<String> order()
    {
        return List.copyOf(ALL.keySet());
    }

    /**
     * 取指标文案；模板之外的指标用注册表文案兜底（转繁体），单位按值格式推断。
     *
     * @param valueFormat MONEY / PCT / MIN / X / INT ...
     */
    public static M of(String code, String fallbackLabel, String fallbackGroup, String fallbackNote, String valueFormat)
    {
        M m = ALL.get(code);
        if (m != null)
        {
            return m;
        }
        String unit = unitOf(valueFormat);
        String unitNote;
        String format;
        switch (unit)
        {
            case "INR":
                unitNote = U_INR;
                format = "0.00";
                break;
            case "%":
                unitNote = U_PCT;
                format = "0.00";
                break;
            case "min":
                unitNote = U_MIN;
                format = "0.0";
                break;
            case "倍":
                unitNote = "倍，2 位小數";
                format = "0.00";
                break;
            default:
                unitNote = U_INT;
                format = null;
        }
        return new M(code, ExportTemplate.t(fallbackLabel == null ? code : fallbackLabel),
            ExportTemplate.t(fallbackGroup), unit, ExportTemplate.t(fallbackNote), unitNote, format);
    }

    public static String unitOf(String valueFormat)
    {
        if (valueFormat == null)
        {
            return "人數";
        }
        switch (valueFormat)
        {
            case "MONEY":
            case "MONEY_CUR":
            case "MONEY1":
                return "INR";
            case "PCT":
                return "%";
            case "MIN":
                return "min";
            case "X":
                return "倍";
            case "HOUR":
                return "小時";
            default:
                return "人數";
        }
    }

    /** 表头单位后缀 */
    public static String suffix(String unit)
    {
        if (unit == null)
        {
            return "";
        }
        switch (unit)
        {
            case "INR":
                return "(INR)";
            case "%":
                return "(%)";
            case "min":
                return "(分鐘)";
            case "倍":
                return "(倍)";
            case "小時":
                return "(小時)";
            default:
                return "";
        }
    }
}
