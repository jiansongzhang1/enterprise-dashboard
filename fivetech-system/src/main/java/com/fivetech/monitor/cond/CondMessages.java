package com.fivetech.monitor.cond;

import java.util.List;
import java.util.Map;
import com.fivetech.common.utils.StringUtils;

/**
 * 条件规则的 Lark 告警正文（纯文本，[文字](网址) 可点开）。开头标注「【站点名】」。
 *
 * @author fivetech
 */
public final class CondMessages
{
    private CondMessages()
    {
    }

    /**
     * @param outcomes 触发时各条件的结果（与 spec.conditions 同序）
     * @param eventUrl 事件详情页地址（为空不带链接）
     */
    public static String alert(String siteName, String ruleName, String ruleCode, CondSpec spec, Map<String, CondField> fields,
            CondRules.DimLabels labels, String symbol, java.time.LocalDateTime end, List<CondEvaluator.Outcome> outcomes,
            String eventUrl)
    {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.isNotEmpty(siteName))
        {
            sb.append("【").append(siteName).append("】");
        }
        sb.append("Yata 條件告警 · ").append(CondRules.LEVEL_TEXT.getOrDefault(spec.getLevel(), spec.getLevel())).append('\n');
        sb.append("規則：").append(ruleName).append(" · ").append(ruleCode).append('\n');
        sb.append("時段：").append(spec.getWindow().label(end)).append("（").append(spec.getWindow().getText());
        if (spec.getDurationCount() > 1)
        {
            sb.append("，連續 ").append(spec.getDurationCount()).append(" 次");
        }
        sb.append("）\n");
        if (spec.getConditions().size() > 1)
        {
            sb.append("條件（").append(spec.isAll() ? "全部滿足" : "任一滿足").append("）：\n");
        }
        else
        {
            sb.append("條件：");
        }
        for (CondCondition c : spec.getConditions())
        {
            CondField f = fields.get(c.getField());
            String label = CondRules.scopeLabel(c.getScope(), labels);
            if (spec.getConditions().size() > 1)
            {
                sb.append(c.getIndex()).append(". ");
            }
            if (label != null)
            {
                sb.append("［").append(label).append("］");
            }
            sb.append(CondRules.conditionText(c, f, symbol));
            CondEvaluator.Outcome o = outcomes.stream().filter(x -> x.index() == c.getIndex()).findFirst().orElse(null);
            if (o != null && o.value() != null)
            {
                sb.append("：").append(CondRules.valueText(c, f, o.value(), o.change(), symbol));
                if (spec.getConditions().size() > 1)
                {
                    sb.append(o.met() ? "（滿足）" : "（未滿足）");
                }
            }
            sb.append('\n');
        }
        if (StringUtils.isNotEmpty(eventUrl))
        {
            sb.append("[查看事件詳情](").append(eventUrl).append(")");
        }
        return sb.toString().trim();
    }

    /** 事件详情页地址：配置的页面地址带上 eventId */
    public static String eventUrl(String base, Long eventId)
    {
        if (StringUtils.isEmpty(base) || eventId == null)
        {
            return null;
        }
        return base + (base.contains("?") ? "&" : "?") + "eventId=" + eventId;
    }
}
