package com.fivetech.monitor.engine.judge;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一个检测周期的数据（展示单位）。
 *
 * @author fivetech
 */
public class PeriodPoint
{
    /** 周期开始（含） */
    private final LocalDateTime from;

    /** 周期结束（不含） */
    private final LocalDateTime to;

    private final BigDecimal value;

    /** 跌幅基准；非跌幅类为 null */
    private final BigDecimal baseline;

    /** 样本量（分母指标）；样本量看基准时不用它 */
    private final BigDecimal sample;

    /** 周期内有小时缺数据：整个周期不判定 */
    private final boolean missing;

    public PeriodPoint(LocalDateTime from, LocalDateTime to, BigDecimal value, BigDecimal baseline, BigDecimal sample,
            boolean missing)
    {
        this.from = from;
        this.to = to;
        this.value = value;
        this.baseline = baseline;
        this.sample = sample;
        this.missing = missing;
    }

    public LocalDateTime getFrom()
    {
        return from;
    }

    public LocalDateTime getTo()
    {
        return to;
    }

    public BigDecimal getValue()
    {
        return value;
    }

    public BigDecimal getBaseline()
    {
        return baseline;
    }

    public BigDecimal getSample()
    {
        return sample;
    }

    public boolean isMissing()
    {
        return missing;
    }
}
