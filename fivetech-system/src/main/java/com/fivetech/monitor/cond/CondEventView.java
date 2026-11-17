package com.fivetech.monitor.cond;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.monitor.domain.vo.MonitorEventVO;

/**
 * 条件规则事件的展示（纯函数）：事件里存的规则快照 + 各条件的值 → 异常事件列表的行、事件详情的条件明细。
 *
 * @author fivetech
 */
public final class CondEventView
{
    static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private CondEventView()
    {
    }

    /** 快照里的设置 */
    public record Snap(Map<String, Object> raw, CondSpec spec, Map<Integer, String> scopeLabels)
    {
    }

    @SuppressWarnings("unchecked")
    public static Snap snap(String snapshotJson)
    {
        Map<String, Object> raw = CondEventWriter.parse(snapshotJson);
        CondSpec s = new CondSpec();
        s.setMatchMode("ANY".equals(raw.get("matchMode")) ? "ANY" : "ALL");
        CondWindow w = CondWindow.of(raw.get("window") == null ? null : String.valueOf(raw.get("window")));
        s.setWindow(w == null ? CondWindow.H1 : w);
        s.setDurationCount(raw.get("durationCount") instanceof Number n ? n.intValue() : 1);
        s.setLevel(raw.get("level") == null ? null : String.valueOf(raw.get("level")));
        List<CondCondition> conds = new ArrayList<>();
        Map<Integer, String> labels = new LinkedHashMap<>();
        if (raw.get("conditions") instanceof List<?> l)
        {
            for (Object o : l)
            {
                if (!(o instanceof Map<?, ?> m))
                {
                    continue;
                }
                CondCondition c = new CondCondition();
                c.setIndex(m.get("index") instanceof Number n ? n.intValue() : conds.size() + 1);
                c.setField(str(m.get("field")));
                Map<String, String> scope = new LinkedHashMap<>();
                if (m.get("scope") instanceof Map<?, ?> sm)
                {
                    sm.forEach((k, v) -> {
                        if (v != null)
                        {
                            scope.put(String.valueOf(k), String.valueOf(v));
                        }
                    });
                }
                c.setScope(scope);
                c.setHow(str(m.get("how")));
                c.setOp(str(m.get("op")));
                c.setUnit(str(m.get("unit")));
                Object th = m.get("threshold");
                if (th instanceof List<?> pair && pair.size() == 2)
                {
                    c.setLow(CondRules.num(pair.get(0)));
                    c.setHigh(CondRules.num(pair.get(1)));
                }
                else
                {
                    c.setLow(CondRules.num(th));
                }
                c.setMinSample(CondRules.num(m.get("minSample")));
                conds.add(c);
                labels.put(c.getIndex(), str(m.get("scopeLabel")));
            }
        }
        s.setConditions(conds);
        return new Snap(raw, s, labels);
    }

    /** detail.triggered / latest 的各条件结果 */
    @SuppressWarnings("unchecked")
    public static Map<Integer, Map<String, Object>> outcomes(Map<String, Object> detail, String key)
    {
        Map<Integer, Map<String, Object>> out = new LinkedHashMap<>();
        if (detail.get(key) instanceof Map<?, ?> cp && cp.get("outcomes") instanceof List<?> l)
        {
            for (Object o : l)
            {
                if (o instanceof Map<?, ?> m && m.get("index") instanceof Number n)
                {
                    out.put(n.intValue(), (Map<String, Object>) m);
                }
            }
        }
        return out;
    }

    /** 存的结果 → 展示：{value, base, change, text, met, noBase?, lowSample?} */
    public static Map<String, Object> outcomeView(CondCondition c, CondField f, Map<String, Object> o, String symbol)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        if (o == null)
        {
            m.put("text", "—");
            m.put("met", false);
            return m;
        }
        BigDecimal value = CondRules.num(o.get("value"));
        BigDecimal change = CondRules.num(o.get("change"));
        m.put("value", value);
        m.put("base", CondRules.num(o.get("base")));
        m.put("change", change);
        m.put("text", value == null ? "—" : CondRules.valueText(c, f, value, change, symbol));
        String status = str(o.get("status"));
        m.put("met", "MET".equals(status));
        if ("NO_BASE".equals(status))
        {
            m.put("noBase", true);
        }
        if ("LOW_SAMPLE".equals(status))
        {
            m.put("lowSample", Map.of("value", o.get("sample") == null ? 0 : o.get("sample"),
                "min", c.getMinSample() == null ? 0 : c.getMinSample()));
        }
        if ("NO_DATA".equals(status))
        {
            m.put("noData", true);
        }
        return m;
    }

    /** 异常事件列表的条件规则行 */
    public static void applyListRow(MonitorEventVO vo, String ruleName, String snapshotJson, String detailJson,
            Map<String, CondField> fields, String symbol)
    {
        Snap snap = snap(snapshotJson);
        Map<String, Object> detail = CondEventWriter.parse(detailJson);
        Map<Integer, Map<String, Object>> latest = outcomes(detail, "latest");
        vo.setRuleType(CondRules.TYPE);
        String name = StringUtils.isNotEmpty(ruleName) ? ruleName : str(snap.raw().get("name"));
        vo.setRuleName(name);
        vo.setMetricName(name);
        vo.setCanDetail(true);
        List<Map<String, Object>> values = new ArrayList<>();
        List<Map<String, Object>> basis = new ArrayList<>();
        int met = 0;
        String single = null;
        for (CondCondition c : snap.spec().getConditions())
        {
            CondField f = fields.get(c.getField());
            Map<String, Object> ov = outcomeView(c, f, latest.get(c.getIndex()), symbol);
            if (Boolean.TRUE.equals(ov.get("met")))
            {
                met++;
            }
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("index", c.getIndex());
            v.put("scopeLabel", snap.scopeLabels().get(c.getIndex()));
            v.put("fieldName", f == null ? c.getField() : f.getName());
            v.put("text", ov.get("text"));
            values.add(v);
            single = String.valueOf(ov.get("text"));
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("scopeLabel", snap.scopeLabels().get(c.getIndex()));
            b.put("text", CondRules.conditionText(c, f, symbol));
            basis.add(b);
        }
        int total = snap.spec().getConditions().size();
        Map<String, Object> cond = new LinkedHashMap<>();
        cond.put("matchMode", snap.spec().getMatchMode());
        cond.put("met", met);
        cond.put("total", total);
        cond.put("values", values);
        cond.put("basis", basis);
        vo.setCond(cond);
        vo.setCurrentText(total == 1 ? single : "滿足 " + met + "/" + total);
        vo.setJudgeBasis(new ArrayList<>());
        vo.setJudgeTip(judgeTip(snap.spec().getWindow(), detail));
    }

    /** 「判定時間 11:05；這次檢測統計 10:00 – 11:00 的資料，每 1 小時檢測一次」 */
    public static String judgeTip(CondWindow w, Map<String, Object> detail)
    {
        LocalDateTime judged = time(detail.get("judgedAt"));
        LocalDateTime end = detail.get("triggered") instanceof Map<?, ?> t ? time(t.get("end")) : null;
        StringBuilder sb = new StringBuilder();
        if (judged != null)
        {
            sb.append("判定時間 ").append(judged.format(HM)).append("；");
        }
        if (end != null)
        {
            sb.append("這次檢測統計 ").append(w.label(end)).append(" 的資料，");
        }
        sb.append(w == CondWindow.TODAY ? "每小時檢測今日累計" : w.getText() + "檢測一次");
        return sb.toString();
    }

    static LocalDateTime time(Object o)
    {
        String s = str(o);
        if (s == null)
        {
            return null;
        }
        try
        {
            return LocalDateTime.parse(s, MINUTE);
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    static String str(Object o)
    {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o);
    }
}
