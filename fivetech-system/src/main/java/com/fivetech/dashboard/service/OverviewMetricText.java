package com.fivetech.dashboard.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.fivetech.dashboard.domain.MetricDefinition;
import com.fivetech.dashboard.export.ExportTemplate;

/**
 * 运营总览指标卡的文案，全部以库为准（dashboard_metric_card，后台「系统管理 · 指标管理」维护），代码里不再写死。
 * <ul>
 *   <li>名称：metric_name 原样返回；</li>
 *   <li>统计口径（expression）：remark 里「口徑：」那一行（「口径：」、半角冒号也认）；没填口径的派生指标用两个操作数的名称拼
 *       「A ÷ B」/「A − B」，原子指标返回 null；</li>
 *   <li>分组名：dashboard_metric_group 里是简体，返回时按字转繁体。</li>
 * </ul>
 * 改了名称或口径，保存后立即生效（{@link MetricRegistry} 重载；其他实例由 {@link MetricRegistryRefresher} 跟上）。
 *
 * @author fivetech
 */
public final class OverviewMetricText
{
    /** remark 里的口径行：「口徑：…」「口径：…」，冒号全角半角都认 */
    private static final Pattern CALIBER = Pattern.compile("^\\s*口[径徑]\\s*[：:]\\s*(.*?)\\s*$");

    private OverviewMetricText()
    {
    }

    /** 指标名称：库里的 metric_name 原样返回（为空时用编码） */
    public static String label(String code, String fallback)
    {
        return fallback == null || fallback.trim().isEmpty() ? code : fallback;
    }

    /** 繁体分组名 */
    public static String group(String name)
    {
        return ExportTemplate.t(name);
    }

    /**
     * 统计口径：库里 remark 的「口徑：」行；没有时派生指标用两个操作数的名称拼「A ÷ B」/「A − B」，原子指标返回 null。
     */
    public static String caliber(String code, MetricDefinition def, MetricRegistry registry)
    {
        String fromDb = def == null ? null : caliberOf(def.getRemark());
        if (fromDb != null)
        {
            return fromDb;
        }
        if (def == null || def.getLeftCode() == null || def.getRightCode() == null)
        {
            return null;
        }
        String op = "DIVIDE".equalsIgnoreCase(def.getCalcType()) ? " ÷ " : " − ";
        return nameOf(def.getLeftCode(), registry) + op + nameOf(def.getRightCode(), registry);
    }

    /** remark 里第一行「口徑：…」的内容；没有或为空返回 null */
    public static String caliberOf(String remark)
    {
        if (remark == null || remark.trim().isEmpty())
        {
            return null;
        }
        for (String line : remark.split("\\r?\\n"))
        {
            Matcher m = CALIBER.matcher(line);
            if (m.matches())
            {
                String v = m.group(1);
                return v == null || v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    private static String nameOf(String code, MetricRegistry registry)
    {
        MetricDefinition d = registry == null ? null : registry.get(code);
        return label(code, d == null ? code : d.getLabel());
    }
}
