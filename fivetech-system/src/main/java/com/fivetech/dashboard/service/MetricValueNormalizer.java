package com.fivetech.dashboard.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import com.fivetech.dashboard.domain.MetricDefinition;

/**
 * 把 UDS 的原始量纲换算成本系统的展示量纲。
 *
 * <p>数据侧的联调说明写得很清楚：<b>比率返回 0～1，时长返回秒</b>。
 * 而本系统的约定是「入库值即展示数值」——比率存 15.82 不是 0.1582，时长存分钟不是秒。
 * 这一步不做，两处会立刻出事：</p>
 *
 * <ol>
 *   <li>页面上所有比率显示成 0.18% 而不是 18.18%；</li>
 *   <li><b>更危险的是告警</b>：{@code dashboard_metric_card} 里存的阈值是
 *       「存款成功率 ≥ 95」「平均杀率 3～6」这种百分数刻度。拿 0.9496 去比 95，
 *       每一个比率指标都会瞬间判定为严重异常，而且看上去「系统在正常工作」。</li>
 * </ol>
 *
 * <p>换算<b>只在这一处发生</b>。网关保持"拿到什么给什么"，服务层拿到的已经是展示量纲，
 * 再往下没有第二次缩放——两处各缩放一次是这类 bug 最常见的来源。</p>
 *
 * @author fivetech
 */
@Component
public class MetricValueNormalizer
{
    /** 比率：0.1818 → 18.18 */
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** 时长：2097 秒 → 34.95 分钟 */
    private static final BigDecimal SECONDS_PER_MINUTE = BigDecimal.valueOf(60);

    private final MetricRegistry metricRegistry;

    public MetricValueNormalizer(MetricRegistry metricRegistry)
    {
        this.metricRegistry = metricRegistry;
    }

    /**
     * 换算单个值。
     *
     * @param code 指标编码
     * @param raw  UDS 原始值；<b>null 原样返回 null</b>，绝不当成 0
     */
    public BigDecimal normalize(String code, BigDecimal raw)
    {
        if (raw == null)
        {
            return null;
        }
        MetricDefinition definition = metricRegistry.get(code);
        String format = definition == null ? null : definition.getFormat();
        if (format == null)
        {
            return raw;
        }
        switch (format.toUpperCase())
        {
            case "PCT":
                // 0～1 → 0～100，保留两位与页面展示位数一致
                return raw.multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
            case "MIN":
                // 秒 → 分钟，保留一位
                return raw.divide(SECONDS_PER_MINUTE, 1, RoundingMode.HALF_UP);
            case "MULTIPLE":
                return raw.setScale(2, RoundingMode.HALF_UP);
            case "MONEY":
                // 小数位由指标配置决定：ARPPU 类 1 位，其余 0 位
                Integer decimals = definition.getDecimals();
                return raw.setScale(decimals == null ? 0 : decimals, RoundingMode.HALF_UP);
            case "INT":
                return raw.setScale(0, RoundingMode.HALF_UP);
            default:
                return raw;
        }
    }

    /**
     * 换算一整条序列。保持长度不变——缺的位置仍是 null，
     * 数组长度是前端对齐时间片标签的契约。
     */
    public List<BigDecimal> normalizeSeries(String code, List<BigDecimal> raw)
    {
        if (raw == null)
        {
            return null;
        }
        List<BigDecimal> result = new ArrayList<>(raw.size());
        for (BigDecimal value : raw)
        {
            result.add(normalize(code, value));
        }
        return result;
    }

    /**
     * 计算环比。
     *
     * <p>{@code PCT} 类指标返回的是<b>百分点</b>差值，其余返回<b>百分比</b>变化率——
     * 两者永不同时有值。混了的话「首存转化率掉了 1.52 个点」会被读成「掉了 9.6%」。</p>
     *
     * @return 长度为 2 的数组：[deltaPct, deltaPt]，未适用的那个为 null
     */
    public BigDecimal[] delta(String code, BigDecimal current, BigDecimal prev)
    {
        if (current == null || prev == null)
        {
            return new BigDecimal[] { null, null };
        }
        MetricDefinition definition = metricRegistry.get(code);
        boolean pct = definition != null && "PCT".equalsIgnoreCase(definition.getFormat());
        if (pct)
        {
            return new BigDecimal[] { null,
                current.subtract(prev).setScale(2, RoundingMode.HALF_UP) };
        }
        if (prev.signum() == 0)
        {
            // 对比期为 0 时变化率是无穷大，返回 null 而不是 +100 或 ∞——后者没有信息量
            return new BigDecimal[] { null, null };
        }
        BigDecimal rate = current.subtract(prev)
            .divide(prev.abs(), 4, RoundingMode.HALF_UP)
            .multiply(HUNDRED)
            .setScale(1, RoundingMode.HALF_UP);
        return new BigDecimal[] { rate, null };
    }
}
