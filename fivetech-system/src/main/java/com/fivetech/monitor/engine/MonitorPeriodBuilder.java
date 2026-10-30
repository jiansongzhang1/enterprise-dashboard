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
 *       平均到账 / 出款时间（后端相除组装：耗时合计 ÷ 单数）同样按合计重算；其余平均时长（AVG_WEIGHTED）按样本量指标加权；
 *       去重人数类取各小时平均（不能跨小时相加）。</li>
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
     * 一个检测周期的值、基准和样本量（FY-23 R7 / R8）。
     * <ul>
     *   <li>1 小时周期：取该小时的值，值为空即缺失。</li>
     *   <li>多小时周期（MON-10）：按时段重算，空值小时不参与——比率类 = 有数小时的分子合计 ÷ 分母合计，
     *       合计类 = 有数小时之和，均值类 = 有数小时的平均（或按样本量加权）；整个时段都没有数才算缺失。
     *       以前只要有一个小时为空（如没有提款的小时出款成功率为空）整个时段就不判定，多小时规则大量漏判。</li>
     *   <li>基准、样本量同样只合计有数的小时；基准不取整（MON-06），展示时再按指标格式取整。</li>
     * </ul>
     *
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

        BigDecimal raw = null;
        if (data != null)
        {
            raw = hours.size() == 1 ? values.get(0) : aggregate(def, rule, data, hours, values);
        }
        boolean missing = raw == null;
        BigDecimal value = raw == null ? null : normalizer.normalize(code, raw);

        BigDecimal baseline = null;
        if (JudgeRule.DROP.equals(rule.getJudgeType()) && data != null)
        {
            List<BigDecimal> base = present(series(data, MonitorDataFetcher.baseKey(code), hours));
            if (!base.isEmpty())
            {
                BigDecimal b = hours.size() == 1 ? base.get(0) : (additive(def) ? sum(base) : avg(base));
                baseline = normalizer.toDisplayUnit(code, b);
            }
        }

        BigDecimal sample = null;
        String sampleKey = rule.getMinSampleKey();
        if (data != null && StringUtils.isNotEmpty(sampleKey) && !"base".equalsIgnoreCase(sampleKey) && data.has(sampleKey))
        {
            List<BigDecimal> s = present(series(data, sampleKey, hours));
            sample = s.isEmpty() ? null : sum(s);
        }
        return new PeriodPoint(from, to, value, baseline, sample, missing);
    }

    /** 多小时周期的周期值；时段内全部小时都没有数时为 null */
    private BigDecimal aggregate(MetricDefinition def, MonitorEngineRule rule, HourlyFetch data,
            List<LocalDateTime> hours, List<BigDecimal> values)
    {
        // 后端相除组装的指标（平均到账 / 出款时间 = 耗时合计 ÷ 单数）和比率类一样：时段内分子合计 ÷ 分母合计
        String[] ratio = data.ratio(def.getCode());
        if (ratio != null || "DIVIDE".equalsIgnoreCase(def.getCalcType()))
        {
            List<BigDecimal> num = series(data, ratio != null ? ratio[0] : def.getLeftCode(), hours);
            List<BigDecimal> den = series(data, ratio != null ? ratio[1] : def.getRightCode(), hours);
            BigDecimal n = BigDecimal.ZERO;
            BigDecimal d = BigDecimal.ZERO;
            boolean any = false;
            for (int i = 0; i < hours.size(); i++)
            {
                if (num.get(i) != null && den.get(i) != null)
                {
                    n = n.add(num.get(i));
                    d = d.add(den.get(i));
                    any = true;
                }
            }
            return !any || d.signum() == 0 ? null : n.divide(d, 10, RoundingMode.HALF_UP);
        }
        List<BigDecimal> present = present(values);
        if (present.isEmpty())
        {
            return null;
        }
        if (additive(def))
        {
            return sum(present);
        }
        if ("AVG_WEIGHTED".equalsIgnoreCase(def.getAggregation()) && StringUtils.isNotEmpty(rule.getMinSampleKey())
                && data.has(rule.getMinSampleKey()))
        {
            List<BigDecimal> w = series(data, rule.getMinSampleKey(), hours);
            BigDecimal weighted = BigDecimal.ZERO;
            BigDecimal total = BigDecimal.ZERO;
            for (int i = 0; i < values.size(); i++)
            {
                if (values.get(i) != null && w.get(i) != null && w.get(i).signum() > 0)
                {
                    weighted = weighted.add(values.get(i).multiply(w.get(i)));
                    total = total.add(w.get(i));
                }
            }
            if (total.signum() > 0)
            {
                return weighted.divide(total, 10, RoundingMode.HALF_UP);
            }
        }
        return avg(present);
    }

    /** 可跨小时相加：合计类，或「相减」派生（各小时差值之和 = 合计之差） */
    private static boolean additive(MetricDefinition def)
    {
        return "SUBTRACT".equalsIgnoreCase(def.getCalcType()) || "SUM".equalsIgnoreCase(def.getAggregation());
    }

    /** 去掉空值 */
    private static List<BigDecimal> present(List<BigDecimal> list)
    {
        List<BigDecimal> out = new ArrayList<>(list.size());
        for (BigDecimal v : list)
        {
            if (v != null)
            {
                out.add(v);
            }
        }
        return out;
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
