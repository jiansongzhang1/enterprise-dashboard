package com.fivetech.monitor.service;

import java.util.Map;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.service.OverviewMetricText;

/**
 * 监控模块返回给前端、写进 Lark 消息的繁体文案（不改库，不影响判定）。
 *
 * <ul>
 *   <li>指标名：与运营总览同一份（{@link OverviewMetricText#label}），即 dashboard_metric_card.metric_name（库里存繁体，后台「指标管理」可改）。</li>
 *   <li>最小样本量文案：按原型（Yata-V0.2 优化版 i18n.js MINNL；OPT-02 出款總訂單數、OPT-06 存款人數）；不在表里的按指标名转繁体。</li>
 * </ul>
 *
 * @author fivetech
 */
public final class MonitorText
{
    /** 样本量口径（min_sample_key）→ 繁体文案，取自原型 MINNL */
    private static final Map<String, String> SAMPLE = Map.ofEntries(
            Map.entry("base", "基準值"),
            Map.entry("dTry", "存款總訂單數"),
            Map.entry("dOk", "存款成功筆數"),
            Map.entry("wTry", "出款總訂單數"),
            Map.entry("wOk", "提款成功筆數"),
            Map.entry("betU", "投注人數"),
            Map.entry("active", "投注人數"),
            Map.entry("depU", "存款人數"),
            Map.entry("reg", "註冊人數"),
            Map.entry("ftd", "首存人數"));

    private MonitorText()
    {
    }

    /** 指标名：库里的名称（fallback）原样返回；为空时用编码 */
    public static String metricName(String metricCode, String fallback)
    {
        if (StringUtils.isEmpty(metricCode))
        {
            return fallback;
        }
        return OverviewMetricText.label(metricCode, StringUtils.isEmpty(fallback) ? metricCode : fallback);
    }

    /** 繁体样本量文案；key 为空返回 null */
    public static String sampleLabel(String sampleKey, String fallback)
    {
        if (StringUtils.isEmpty(sampleKey))
        {
            return null;
        }
        String label = SAMPLE.get(sampleKey);
        return label != null ? label : metricName(sampleKey, fallback);
    }
}
