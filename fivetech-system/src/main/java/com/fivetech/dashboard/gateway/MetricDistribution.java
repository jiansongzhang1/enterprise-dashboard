package com.fivetech.dashboard.gateway;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一个指标在一段时间内、按某个检测周期切分后的周期值分布（UDS metric_distribution 数据集，四值 + 极值时间）。
 * <p>UDS 计算 p5 / p95（最近排名法，不插值）、min / max 和它们所在周期的起点；后端不再自己统计。
 * 数值保持 UDS 原始量纲（比率 0～1、时长秒），换算由调用方交给 MetricValueNormalizer。</p>
 *
 * @author fivetech
 */
public class MetricDistribution
{
    /** 没有配置 dashboard.gateway.uds.distribution 或该指标没有映射 */
    private boolean configured;

    /** 上游未就绪（4403） */
    private boolean notReady;

    /** 时间范围内没有有效周期（UDS 不返回行，不能当成 0） */
    private boolean empty;

    private BigDecimal p5;

    private BigDecimal p95;

    private BigDecimal min;

    private BigDecimal max;

    /** 最小值所在周期的起点 */
    private LocalDateTime minAt;

    /** 最大值所在周期的起点 */
    private LocalDateTime maxAt;

    /** 有效周期数（UDS valid_point_count：参与四值统计的周期数） */
    private Integer count;

    /** 本次响应自报的水位 */
    private LocalDateTime watermark;

    private boolean stale;

    public static MetricDistribution notConfigured()
    {
        return new MetricDistribution();
    }

    public boolean isConfigured() { return configured; }

    public void setConfigured(boolean configured) { this.configured = configured; }

    public boolean isNotReady() { return notReady; }

    public void setNotReady(boolean notReady) { this.notReady = notReady; }

    public boolean isEmpty() { return empty; }

    public void setEmpty(boolean empty) { this.empty = empty; }

    public BigDecimal getP5() { return p5; }

    public void setP5(BigDecimal p5) { this.p5 = p5; }

    public BigDecimal getP95() { return p95; }

    public void setP95(BigDecimal p95) { this.p95 = p95; }

    public BigDecimal getMin() { return min; }

    public void setMin(BigDecimal min) { this.min = min; }

    public BigDecimal getMax() { return max; }

    public void setMax(BigDecimal max) { this.max = max; }

    public LocalDateTime getMinAt() { return minAt; }

    public void setMinAt(LocalDateTime minAt) { this.minAt = minAt; }

    public LocalDateTime getMaxAt() { return maxAt; }

    public void setMaxAt(LocalDateTime maxAt) { this.maxAt = maxAt; }

    public Integer getCount() { return count; }

    public void setCount(Integer count) { this.count = count; }

    public LocalDateTime getWatermark() { return watermark; }

    public void setWatermark(LocalDateTime watermark) { this.watermark = watermark; }

    public boolean isStale() { return stale; }

    public void setStale(boolean stale) { this.stale = stale; }
}
