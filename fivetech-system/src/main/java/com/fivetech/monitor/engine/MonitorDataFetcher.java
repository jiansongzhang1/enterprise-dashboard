package com.fivetech.monitor.engine;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.domain.MetricDefinition;
import com.fivetech.dashboard.gateway.HourlyFetch;
import com.fivetech.dashboard.gateway.MetricDataGateway;
import com.fivetech.dashboard.service.MetricRegistry;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.monitor.engine.domain.MonitorEngineRule;
import com.fivetech.monitor.engine.judge.JudgeRule;

/**
 * 判定引擎取数：一个站点一轮只发一次 UDS 请求，从 dashboard_overview_hour_v2 一次取回全部字段
 * （启用规则的指标、派生指标的分子分母、样本量指标、跌幅基准）。第一次失败（非「数据未就绪」）才发第二次，最多两次。
 * <p>调用方负责站点上下文（SiteContext.callWith），请求 UDS 时的站点头（X-Uds-Site）和水位缓存都按站点区分。</p>
 *
 * @author fivetech
 */
@Component
public class MonitorDataFetcher
{
    private static final Logger log = LoggerFactory.getLogger(MonitorDataFetcher.class);

    /** 跌幅基准在 HourlyFetch 里的 key 前缀：base:{metricCode} */
    public static final String BASE_PREFIX = "base:";

    private final MetricDataGateway gateway;

    private final MetricRegistry registry;

    private final MonitorProperties properties;

    public MonitorDataFetcher(MetricDataGateway gateway, MetricRegistry registry, MonitorProperties properties)
    {
        this.gateway = gateway;
        this.registry = registry;
        this.properties = properties;
    }

    /** 某条跌幅类规则的基准在 HourlyFetch 里的 key */
    public static String baseKey(String metricCode)
    {
        return BASE_PREFIX + metricCode;
    }

    /** 跌幅类指标在 dashboard_overview_hour_v2 里的基准列名；没配置返回 null */
    public String baselineColumn(String metricCode)
    {
        String column = properties.getEngine().getBaselineFields().get(metricCode);
        return StringUtils.isEmpty(column) ? null : column.trim();
    }

    /** 这些规则要取的指标编码（去重、保持顺序） */
    public List<String> metricCodesOf(List<MonitorEngineRule> rules)
    {
        Set<String> codes = new LinkedHashSet<>();
        for (MonitorEngineRule rule : rules)
        {
            String code = rule.getMetricCode();
            codes.add(code);
            MetricDefinition def = registry.get(code);
            if (def != null && "DIVIDE".equalsIgnoreCase(def.getCalcType()))
            {
                // 检测周期 > 1 小时时比率要按合计后的分子分母重算
                addIfPresent(codes, def.getLeftCode());
                addIfPresent(codes, def.getRightCode());
            }
            String sampleKey = rule.getMinSampleKey();
            if (StringUtils.isNotEmpty(sampleKey) && !"base".equalsIgnoreCase(sampleKey))
            {
                codes.add(sampleKey);
            }
        }
        return new ArrayList<>(codes);
    }

    /** 附加列：跌幅类规则的基准 */
    public Map<String, String> extraColumnsOf(List<MonitorEngineRule> rules)
    {
        Map<String, String> extras = new LinkedHashMap<>();
        for (MonitorEngineRule rule : rules)
        {
            if (JudgeRule.DROP.equals(rule.getJudgeType()))
            {
                String column = baselineColumn(rule.getMetricCode());
                if (column != null)
                {
                    extras.put(baseKey(rule.getMetricCode()), column);
                }
            }
        }
        return extras;
    }

    /**
     * 取 [from, to) 的逐小时数据。最多请求 {@code monitor.engine.uds-max-calls} 次（1～2）：
     * 第 1 次就拿全部字段；「数据未就绪」不是故障，直接返回 notReady，不重试。
     *
     * @throws RuntimeException 两次都失败
     */
    public HourlyFetch fetch(List<MonitorEngineRule> rules, LocalDateTime from, LocalDateTime to)
    {
        List<String> codes = metricCodesOf(rules);
        Map<String, String> extras = extraColumnsOf(rules);
        int maxCalls = Math.max(1, Math.min(2, properties.getEngine().getUdsMaxCalls()));
        RuntimeException last = null;
        for (int call = 1; call <= maxCalls; call++)
        {
            try
            {
                return gateway.queryHourly(codes, extras, from, to);
            }
            catch (RuntimeException e)
            {
                last = e;
                log.warn("[monitor] UDS 取数失败（第 {}/{} 次）{} ~ {} 字段 {} 个：{}",
                        call, maxCalls, from, to, codes.size() + extras.size(), e.getMessage());
                if (!extras.isEmpty() && isFieldRejected(e))
                {
                    // 字段不被接受（多半是跌幅基准列 UDS 还没上）：第 2 次去掉基准列，其他规则照常判定，
                    // 跌幅类规则因取不到基准写 judge_error
                    log.error("[monitor] UDS 不接受请求字段，第 2 次去掉跌幅基准列 {} 重试", extras.values());
                    extras = new LinkedHashMap<>();
                }
            }
        }
        throw last;
    }

    /** UDS 4001（参数不被接受）/ 4002（数据集或字段不可用）：字段名不对或还没上线 */
    private static boolean isFieldRejected(RuntimeException e)
    {
        if (e instanceof com.fivetech.dashboard.gateway.uds.UdsQueryException)
        {
            int code = ((com.fivetech.dashboard.gateway.uds.UdsQueryException) e).getUdsCode();
            return code == 4001 || code == 4002;
        }
        return false;
    }

    private static void addIfPresent(Set<String> codes, String code)
    {
        if (StringUtils.isNotEmpty(code))
        {
            codes.add(code);
        }
    }
}
