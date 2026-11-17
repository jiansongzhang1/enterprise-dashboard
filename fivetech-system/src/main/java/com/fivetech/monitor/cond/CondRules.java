package com.fivetech.monitor.cond;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.fivetech.dashboard.service.MetricExt;

/**
 * 条件规则的校验与文案（纯函数，不读库）：表单 → {@link CondSpec}；条件、范围、摘要的繁体文字。
 *
 * @author fivetech
 */
public final class CondRules
{
    public static final String TYPE = "COND";

    public static final Set<String> HOWS = Set.of("CUR", "BASE", "YD");

    public static final List<String> CUR_OPS = List.of("GT", "GE", "LT", "LE", "OUT");

    public static final List<String> CHANGE_OPS = List.of("UP", "DOWN", "CHG");

    public static final Map<String, String> HOW_TEXT = ordered("CUR", "當前值", "BASE", "較基準", "YD", "較昨日同時段");

    public static final Map<String, String> OP_TEXT = ordered("GT", "大於", "GE", "大於等於", "LT", "小於", "LE", "小於等於",
        "OUT", "超出區間", "UP", "上升超過", "DOWN", "下降超過", "CHG", "變動超過");

    public static final Map<String, String> LEVEL_TEXT = ordered("CRIT", "嚴重", "WARN", "注意");

    /** 维度的显示名 */
    public static final Map<String, String> DIM_TEXT = ordered("ch", "渠道", "pay", "支付通道", "pf", "遊戲平台", "gty", "遊戲類型",
        "game", "遊戲", "term", "終端");

    private static final BigDecimal LIMIT = new BigDecimal("1000000000000");

    private CondRules()
    {
    }

    /** 维度值的显示名；值不存在返回 null */
    public interface DimLabels
    {
        String label(String dim, String value);
    }

    /** 校验后的规则 */
    public static class Checked
    {
        public String name;

        public String description;

        public boolean notifyEnabled;

        public List<String> notifyChatIds = new ArrayList<>();

        public int muteHours;

        public CondSpec spec;
    }

    /** 整条规则（新建 / 编辑）；错误写进 errors，键为字段路径 */
    public static Checked check(CondRuleBody b, Map<String, CondField> fields, DimLabels labels, CondProperties p,
            Map<String, String> errors)
    {
        Checked c = new Checked();
        String name = trim(b.getName());
        if (name == null)
        {
            errors.put("name", "請填寫規則名稱");
        }
        else if (len(name) > p.getNameMax())
        {
            errors.put("name", "規則名稱不能超過 " + p.getNameMax() + " 字");
        }
        c.name = name;
        String desc = trim(b.getDescription());
        if (desc != null && len(desc) > p.getDescriptionMax())
        {
            errors.put("description", "說明不能超過 " + p.getDescriptionMax() + " 字");
        }
        c.description = desc;
        c.spec = spec(b, fields, labels, p, errors);
        c.notifyEnabled = b.getNotifyEnabled() == null || b.getNotifyEnabled();
        Set<String> chats = new LinkedHashSet<>();
        if (b.getNotifyChatIds() != null)
        {
            for (String id : b.getNotifyChatIds())
            {
                String t = trim(id);
                if (t != null)
                {
                    chats.add(t);
                }
            }
        }
        if (c.notifyEnabled && chats.isEmpty())
        {
            errors.put("notifyChatIds", "開啟通知時請選擇通知群");
        }
        if (chats.size() > p.getMaxChats())
        {
            errors.put("notifyChatIds", "通知群最多 " + p.getMaxChats() + " 個");
        }
        c.notifyChatIds = new ArrayList<>(chats);
        Integer mute = b.getMuteHours() == null ? p.getDefaultMuteHours() : b.getMuteHours();
        if (!p.getMuteHours().contains(mute))
        {
            errors.put("muteHours", "靜默時長只能是 " + join(p.getMuteHours()) + " 小時");
        }
        c.muteHours = mute;
        return c;
    }

    /** 判定相关的部分（预览也用）：条件关系、窗口、连续次数、等级、条件 */
    public static CondSpec spec(CondRuleBody b, Map<String, CondField> fields, DimLabels labels, CondProperties p,
            Map<String, String> errors)
    {
        CondSpec s = new CondSpec();
        String mode = upper(b.getMatchMode());
        if (mode == null)
        {
            mode = "ALL";
        }
        if (!"ALL".equals(mode) && !"ANY".equals(mode))
        {
            errors.put("matchMode", "條件關係只能是 ALL（全部滿足）或 ANY（任一滿足）");
        }
        s.setMatchMode(mode);
        CondWindow w = CondWindow.of(b.getWindow());
        if (w == null)
        {
            errors.put("window", "檢測時段只能是 " + String.join(" / ", CondWindow.codes()));
            w = CondWindow.H1;
        }
        s.setWindow(w);
        int max = p.durationMax(w.getCode());
        int d = b.getDurationCount() == null ? 1 : b.getDurationCount();
        if (d < 1 || d > max)
        {
            errors.put("durationCount", "連續次數為 1–" + max);
        }
        s.setDurationCount(d);
        String level = upper(b.getLevel());
        if (level == null || !LEVEL_TEXT.containsKey(level))
        {
            errors.put("level", "請選擇等級：CRIT（嚴重）或 WARN（注意）");
        }
        s.setLevel(level);
        List<Map<String, Object>> list = b.getConditions() == null ? List.of() : b.getConditions();
        if (list.isEmpty())
        {
            errors.put("conditions", "請至少添加 1 個條件");
        }
        else if (list.size() > p.getMaxConditions())
        {
            errors.put("conditions", "條件最多 " + p.getMaxConditions() + " 個");
        }
        List<CondCondition> out = new ArrayList<>();
        for (int i = 0; i < list.size() && i < p.getMaxConditions(); i++)
        {
            out.add(condition(i, list.get(i), fields, labels, errors));
        }
        s.setConditions(out);
        return s;
    }

    public static CondCondition condition(int i, Map<String, Object> m, Map<String, CondField> fields, DimLabels labels,
            Map<String, String> errors)
    {
        String k = "conditions[" + i + "].";
        CondCondition c = new CondCondition();
        c.setIndex(i + 1);
        Map<String, Object> in = m == null ? Map.of() : m;
        String code = trim(in.get("field"));
        CondField f = code == null ? null : fields.get(code);
        if (f == null)
        {
            errors.put(k + "field", "請選擇指標");
        }
        c.setField(code);

        Map<String, String> scope = new LinkedHashMap<>();
        Object sc = in.get("scope");
        if (sc != null && !(sc instanceof Map<?, ?>))
        {
            errors.put(k + "scope", "範圍格式不正確");
        }
        else if (sc instanceof Map<?, ?> sm)
        {
            for (Map.Entry<?, ?> e : sm.entrySet())
            {
                String dim = String.valueOf(e.getKey());
                Object raw = e.getValue();
                if (raw instanceof Collection<?> col)
                {
                    if (col.size() > 1)
                    {
                        errors.put(k + "scope", "每個維度只能選 1 個值");
                    }
                    raw = col.isEmpty() ? null : col.iterator().next();
                }
                String v = trim(raw);
                if (v == null)
                {
                    continue;
                }
                if (!MetricExt.COND_DIMS.contains(dim))
                {
                    errors.put(k + "scope", "不支持的維度：" + dim);
                    continue;
                }
                if (f != null && !f.getDims().contains(dim))
                {
                    errors.put(k + "scope", f.getName() + "不能按" + DIM_TEXT.get(dim) + "篩選");
                    continue;
                }
                if ("ch".equals(dim) && !v.matches("^(g:[^|]+|c:[^|]+\\|.+)$"))
                {
                    errors.put(k + "scope", "渠道的值須為 g:推廣商ID 或 c:推廣商ID|渠道ID");
                    continue;
                }
                if (v.length() > 128 || (labels != null && labels.label(dim, v) == null))
                {
                    errors.put(k + "scope", DIM_TEXT.get(dim) + "「" + v + "」不存在，請重新選擇");
                    continue;
                }
                scope.put(dim, v);
            }
        }
        c.setScope(scope);

        String how = upper(in.get("how"));
        if (how == null && f != null)
        {
            how = f.getDefaultHow();
        }
        if (how == null || !HOWS.contains(how))
        {
            errors.put(k + "how", "取值方式只能是 CUR（當前值）、BASE（較基準）、YD（較昨日同時段）");
        }
        else if (f != null && f.isSnapshot() && !"CUR".equals(how))
        {
            errors.put(k + "how", f.getName() + "是整點快照，只能用當前值");
        }
        c.setHow(how);
        String op = upper(in.get("op"));
        boolean change = "BASE".equals(how) || "YD".equals(how);
        List<String> ops = change ? CHANGE_OPS : CUR_OPS;
        if (op == null || !ops.contains(op))
        {
            errors.put(k + "op", "比較只能是 " + String.join(" / ", ops));
        }
        c.setOp(op);
        if (change)
        {
            String unit = upper(in.get("unit"));
            if (unit == null)
            {
                unit = "PCT";
            }
            if (!"PCT".equals(unit) && !"ABS".equals(unit))
            {
                errors.put(k + "unit", "變化單位只能是 PCT（百分比）或 ABS（絕對值）");
            }
            c.setUnit(unit);
        }

        Object th = in.get("threshold");
        if ("OUT".equals(op))
        {
            List<?> pair = th instanceof List<?> l ? l : null;
            BigDecimal lo = pair != null && pair.size() == 2 ? num(pair.get(0)) : null;
            BigDecimal hi = pair != null && pair.size() == 2 ? num(pair.get(1)) : null;
            if (lo == null || hi == null)
            {
                errors.put(k + "threshold", "超出區間請填寫下限與上限");
            }
            else if (lo.compareTo(hi) >= 0)
            {
                errors.put(k + "threshold", "下限要小於上限");
            }
            c.setLow(lo);
            c.setHigh(hi);
        }
        else
        {
            BigDecimal v = num(th);
            if (v == null)
            {
                errors.put(k + "threshold", "請填寫閾值");
            }
            else if (change && v.signum() <= 0)
            {
                errors.put(k + "threshold", "變化幅度要大於 0");
            }
            else if (v.abs().compareTo(LIMIT) >= 0)
            {
                errors.put(k + "threshold", "閾值超出範圍");
            }
            c.setLow(v);
        }
        Object ms = in.get("minSample");
        if (ms != null && !(ms instanceof String str && str.isBlank()))
        {
            BigDecimal v = num(ms);
            if (v == null || v.signum() < 0)
            {
                errors.put(k + "minSample", "最小樣本量要是不小於 0 的數字");
            }
            else if (f != null && f.getSampleField() == null)
            {
                errors.put(k + "minSample", f.getName() + "沒有樣本量口徑，不能設置最小樣本量");
            }
            c.setMinSample(v);
        }
        return c;
    }

    // ------------------------------------------------------------------ 文案

    /** 范围标签：ROI 推廣 · Android；不限范围为 null */
    public static String scopeLabel(Map<String, String> scope, DimLabels labels)
    {
        if (scope == null || scope.isEmpty())
        {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String dim : MetricExt.COND_DIMS)
        {
            String v = scope.get(dim);
            if (v != null)
            {
                String l = labels == null ? null : labels.label(dim, v);
                parts.add(l == null ? v : l);
            }
        }
        return String.join(" · ", parts);
    }

    /** 范围拆开：[{dim, value, label}] */
    public static List<Map<String, Object>> scopeItems(Map<String, String> scope, DimLabels labels)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        if (scope == null)
        {
            return out;
        }
        for (String dim : MetricExt.COND_DIMS)
        {
            String v = scope.get(dim);
            if (v != null)
            {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("dim", dim);
                m.put("value", v);
                String l = labels == null ? null : labels.label(dim, v);
                m.put("label", l == null ? v : l);
                out.add(m);
            }
        }
        return out;
    }

    /** 「較基準 上升超過（%）」 */
    public static String howText(CondCondition c)
    {
        String s = HOW_TEXT.getOrDefault(c.getHow(), "") + " " + OP_TEXT.getOrDefault(c.getOp(), "");
        if (c.isChange())
        {
            s += "PCT".equals(c.getUnit()) ? "（%）" : "（絕對值）";
        }
        return "CUR".equals(c.getHow()) ? OP_TEXT.getOrDefault(c.getOp(), "") : s;
    }

    /** 门槛文字：50%、₹10,000、3% – 8% */
    public static String thresholdText(CondCondition c, CondField f, String symbol)
    {
        if ("OUT".equals(c.getOp()))
        {
            return value(f, c.getLow(), symbol) + " – " + value(f, c.getHigh(), symbol);
        }
        if (c.isChange())
        {
            return "PCT".equals(c.getUnit()) ? plain(c.getLow()) + "%" : absText(f, c.getLow(), symbol);
        }
        return value(f, c.getLow(), symbol);
    }

    /** 一个条件的文字（不含范围）：註冊人數 較基準 上升超過 50% / 首存轉化率 小於 5% */
    public static String conditionText(CondCondition c, CondField f, String symbol)
    {
        String name = f == null ? c.getField() : f.getName();
        String how = "CUR".equals(c.getHow()) ? "" : HOW_TEXT.getOrDefault(c.getHow(), "") + " ";
        return name + " " + how + OP_TEXT.getOrDefault(c.getOp(), "") + " " + thresholdText(c, f, symbol);
    }

    /** 检测说明：每 1 小時 / 今日累計 */
    public static String detectText(CondSpec s)
    {
        return s.getWindow().getText();
    }

    /** 一句话摘要：每 1 小時檢測，連續 2 次滿足以下全部條件時記為嚴重：［ROI 推廣］註冊人數 …；首存轉化率 … */
    public static String summary(CondSpec s, Map<String, CondField> fields, DimLabels labels, String symbol)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(s.getWindow() == CondWindow.TODAY ? "每小時檢測今日累計" : s.getWindow().getText() + "檢測");
        sb.append(s.getDurationCount() > 1 ? "，連續 " + s.getDurationCount() + " 次滿足" : "，滿足");
        if (s.getConditions().size() > 1)
        {
            sb.append(s.isAll() ? "以下全部條件" : "以下任一條件");
        }
        sb.append("時記為").append(LEVEL_TEXT.getOrDefault(s.getLevel(), "")).append("：");
        boolean anyScope = s.getConditions().stream().anyMatch(c -> !c.getScope().isEmpty());
        List<String> parts = new ArrayList<>();
        for (CondCondition c : s.getConditions())
        {
            String label = scopeLabel(c.getScope(), labels);
            String prefix = label != null ? "［" + label + "］" : (anyScope ? "［不限範圍］" : "");
            parts.add(prefix + conditionText(c, fields.get(c.getField()), symbol));
        }
        sb.append(String.join(s.isAll() ? "；並且 " : "；或者 ", parts));
        return sb.toString();
    }

    /** 字段值的显示：92.5%、₹1,234、3.2 min、1.25×、1,024 */
    public static String value(CondField f, BigDecimal v, String symbol)
    {
        if (v == null)
        {
            return "—";
        }
        String fmt = f == null ? "INT" : f.getValueFormat();
        String sign = v.signum() < 0 ? "−" : "";
        BigDecimal a = v.abs();
        switch (fmt)
        {
            case "PCT":
                return sign + dec(a, f.getDecimals() == null ? 2 : f.getDecimals()) + "%";
            case "MONEY":
                return sign + (symbol == null ? "" : symbol) + dec(a, f.getDecimals() == null ? 0 : f.getDecimals());
            case "MIN":
                return sign + dec(a, f.getDecimals() == null ? 1 : f.getDecimals()) + " min";
            case "MULTIPLE":
                return sign + dec(a, f.getDecimals() == null ? 2 : f.getDecimals()) + "×";
            default:
                return sign + dec(a, f == null || f.getDecimals() == null ? 0 : f.getDecimals());
        }
    }

    /** 绝对值变化的显示：比率为 pt，其他同字段格式 */
    static String absText(CondField f, BigDecimal v, String symbol)
    {
        if (f != null && f.isPercent())
        {
            return dec(v.abs(), 2) + "pt";
        }
        return value(f, v.abs(), symbol);
    }

    /** 变化的显示：+120.5%、−3.2pt、+₹1,000 */
    public static String changeText(CondCondition c, CondField f, BigDecimal change, String symbol)
    {
        if (change == null)
        {
            return null;
        }
        String sign = change.signum() < 0 ? "−" : "+";
        if ("PCT".equals(c.getUnit()))
        {
            return sign + dec(change.abs(), 1) + "%";
        }
        return sign + absText(f, change, symbol);
    }

    /** 「412（較基準 +120.5%）」/「1.2%」 */
    public static String valueText(CondCondition c, CondField f, BigDecimal value, BigDecimal change, String symbol)
    {
        String v = value(f, value, symbol);
        if (!c.isChange() || change == null)
        {
            return v;
        }
        return v + "（" + HOW_TEXT.get(c.getHow()) + " " + changeText(c, f, change, symbol) + "）";
    }

    static String dec(BigDecimal v, int scale)
    {
        DecimalFormat df = new DecimalFormat(scale <= 0 ? "#,##0" : "#,##0." + "#".repeat(scale),
            DecimalFormatSymbols.getInstance(Locale.US));
        return df.format(v.setScale(Math.max(0, scale), RoundingMode.HALF_UP));
    }

    static String plain(BigDecimal v)
    {
        if (v == null)
        {
            return "";
        }
        BigDecimal s = v.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }

    // ------------------------------------------------------------------ 工具

    static Map<String, String> ordered(String... kv)
    {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2)
        {
            m.put(kv[i], kv[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    static String trim(Object o)
    {
        if (o == null)
        {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    static String upper(Object o)
    {
        String s = trim(o);
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    static BigDecimal num(Object o)
    {
        if (o == null || o instanceof Boolean)
        {
            return null;
        }
        try
        {
            return new BigDecimal(String.valueOf(o).trim());
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    static int len(String s)
    {
        return s.codePointCount(0, s.length());
    }

    private static String join(List<Integer> list)
    {
        List<String> s = new ArrayList<>();
        list.forEach(i -> s.add(String.valueOf(i)));
        return String.join("、", s);
    }
}
