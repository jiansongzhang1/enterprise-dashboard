package com.fivetech.monitor.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.domain.MetricDefinition;
import com.fivetech.dashboard.gateway.HourlyFetch;
import com.fivetech.dashboard.service.MetricRegistry;
import com.fivetech.dashboard.service.MetricValueNormalizer;
import com.fivetech.monitor.engine.domain.MonitorEngineRule;
import com.fivetech.monitor.engine.judge.JudgeRule;
import com.fivetech.monitor.engine.judge.PeriodPoint;

/**
 * 把逐小时的 UDS 原始值聚合成一个检测周期的值（展示单位）。
 * <ul>
 *   <li>1 小时周期：直接取该小时的值。</li>
 *   <li>多小时周期：比率类（calc_type = DIVIDE）用合计后的分子 ÷ 分母重算；金额、笔数等可加的取合计；
 *       平均时长（AVG_WEIGHTED）按样本量指标（成功笔数）加权；去重人数类取各小时平均（不能跨小时相加）。</li>
 *   <li>周期内任一小时值为空：整个周期标记缺数据，不判定。</li>
 *   <li>跌幅基准由 UDS 提供，按同样口径聚合（可加的取合计，其余取平均）。</li>
 * </ul>
 *
 * @author fivetech
 */
@Component
public class MonitorPeriodBuilder
{
    private final MetricRegistry registry;

    private final MetricValueNormalizer normalizer;

    public MonitorPeriodBuilder(MetricRegistry registry, MetricValueNormalizer normalizer)
    {
        this.registry = registry;
        this.normalizer = normalizer;
    }

    /**
     * @param data 本轮取回的数据；为 null 表示没有数据（整个周期缺数据）
     */
    public PeriodPoint build(MonitorEngineRule rule, HourlyFetch data, LocalDateTime from, LocalDateTime to)
    {
        String code = rule.getMetricCode();
        MetricDefinition def = registry.get(code);
        if (def == null)
        {
            throw new IllegalStateException("指標配置不存在：" + code);
        }
        List<LocalDateTime> hours = hours(from, to);
        List<BigDecimal> values = series(data, code, hours);
        boolean missing = data == null || values.contains(null);

        BigDecimal raw = null;
        if (!missing)
        {
            raw = hours.size() == 1 ? values.get(0) : aggregate(def, rule, data, hours, values);
        }
        BigDecimal value = raw == null ? null : normalizer.normalize(code, raw);

        BigDecimal baseline = null;
        if (JudgeRule.DROP.equals(rule.getJudgeType()) && data != null)
        {
            List<BigDecimal> base = series(data, MonitorDataFetcher.baseKey(code), hours);
            if (!base.contains(null))
            {
                BigDecimal b = hours.size() == 1 ? base.get(0) : (additive(def) ? sum(base) : avg(base));
                baseline = normalizer.normalize(code, b);
            }
        }

        BigDecimal sample = null;
        String sampleKey = rule.getMinSampleKey();
        if (data != null && StringUtils.isNotEmpty(sampleKey) && !"base".equalsIgnoreCase(sampleKey) && data.has(sampleKey))
        {
            List<BigDecimal> s = series(data, sampleKey, hours);
            sample = s.contains(null) ? null : sum(s);
        }
        return new PeriodPoint(from, to, value, baseline, sample, missing);
    }

    private BigDecimal aggregate(MetricDefinition def, MonitorEngineRule rule, HourlyFetch data,
            List<LocalDateTime> hours, List<BigDecimal> values)
    {
        if ("DIVIDE".equalsIgnoreCase(def.getCalcType()))
        {
            List<BigDecimal> num = series(data, def.getLeftCode(), hours);
            List<BigDecimal> den = series(data, def.getRightCode(), hours);
            if (num.contains(null) || den.contains(null))
            {
                return null;
            }
            BigDecimal d = sum(den);
            return d.signum() == 0 ? null : sum(num).divide(d, 10, RoundingMode.HALF_UP);
        }
        if (additive(def))
        {
            return sum(values);
        }
        if ("AVG_WEIGHTED".equalsIgnoreCase(def.getAggregation()) && StringUtils.isNotEmpty(rule.getMinSampleKey())
                && data.has(rule.getMinSampleKey()))
        {
            List<BigDecimal> w = series(data, rule.getMinSampleKey(), hours);
            if (!w.contains(null) && sum(w).signum() > 0)
            {
                BigDecimal weighted = BigDecimal.ZERO;
                for (int i = 0; i < values.size(); i++)
                {
                    weighted = weighted.add(values.get(i).multiply(w.get(i)));
                }
                return weighted.divide(sum(w), 10, RoundingMode.HALF_UP);
            }
        }
        return avg(values);
    }

    /** 可跨小时相加：合计类，或「相减」派生（各小时差值之和 = 合计之差） */
    private static boolean additive(MetricDefinition def)
    {
        return "SUBTRACT".equalsIgnoreCase(def.getCalcType()) || "SUM".equalsIgnoreCase(def.getAggregation());
    }

    private static List<LocalDateTime> hours(LocalDateTime from, LocalDateTime to)
    {
        List<LocalDateTime> list = new ArrayList<>();
        for (LocalDateTime h = from; h.isBefore(to); h = h.plusHours(1))
        {
            list.add(h);
        }
        return list;
    }

    private static List<BigDecimal> series(HourlyFetch data, String key, List<LocalDateTime> hours)
    {
        List<BigDecimal> list = new ArrayList<>(hours.size());
        for (LocalDateTime h : hours)
        {
            list.add(data == null || key == null ? null : data.at(key, h));
        }
        return list;
    }

    private static BigDecimal sum(List<BigDecimal> list)
    {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal v : list)
        {
            total = total.add(v);
        }
        return total;
    }

    private static BigDecimal avg(List<BigDecimal> list)
    {
        return list.isEmpty() ? null : sum(list).divide(BigDecimal.valueOf(list.size()), 10, RoundingMode.HALF_UP);
    }
}
