package com.fivetech.monitor.service;

import java.math.BigDecimal;
import java.util.Map;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.monitor.engine.judge.JudgeRule;
import tools.jackson.databind.ObjectMapper;

/**
 * 事件的规则快照（monitor_event.rule_snapshot）→ JudgeRule。阈值以触发时为准，之后改规则不影响。
 *
 * @author fivetech
 */
public final class MonitorSnapshot
{
    private MonitorSnapshot()
    {
    }

    @SuppressWarnings("unchecked")
    public static JudgeRule parse(ObjectMapper json, String snapshot)
    {
        Map<String, Object> m = StringUtils.isEmpty(snapshot) ? Map.of() : json.readValue(snapshot, Map.class);
        Map<String, Object> warn = m.get("warn") instanceof Map ? (Map<String, Object>) m.get("warn") : Map.of();
        Map<String, Object> crit = m.get("crit") instanceof Map ? (Map<String, Object>) m.get("crit") : Map.of();
        Object escalate = m.get("escalateHours");
        return new JudgeRule(String.valueOf(m.get("judgeType")),
            decimal(warn.get("low")), decimal(warn.get("high")), decimal(crit.get("low")), decimal(crit.get("high")),
            intOf(m.get("periodHours"), 1), intOf(m.get("durationCount"), 1),
            escalate == null ? null : intOf(escalate, 0), decimal(m.get("minSample")), false);
    }

    static BigDecimal decimal(Object o)
    {
        if (o == null || "null".equals(String.valueOf(o)))
        {
            return null;
        }
        return o instanceof BigDecimal ? (BigDecimal) o : new BigDecimal(o.toString());
    }

    static int intOf(Object o, int fallback)
    {
        if (o == null)
        {
            return fallback;
        }
        try
        {
            return new BigDecimal(o.toString()).intValue();
        }
        catch (NumberFormatException e)
        {
            return fallback;
        }
    }
}
