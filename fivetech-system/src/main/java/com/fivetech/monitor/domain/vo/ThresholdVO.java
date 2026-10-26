package com.fivetech.monitor.domain.vo;

import java.math.BigDecimal;

/**
 * 阈值 {@code {low, high}}，用不到的一侧为 null。
 *
 * @author fivetech
 */
public class ThresholdVO
{
    private BigDecimal low;

    private BigDecimal high;

    public BigDecimal getLow()
    {
        return low;
    }

    public void setLow(BigDecimal low)
    {
        this.low = low;
    }

    public BigDecimal getHigh()
    {
        return high;
    }

    public void setHigh(BigDecimal high)
    {
        this.high = high;
    }

    public static ThresholdVO of(BigDecimal low, BigDecimal high)
    {
        ThresholdVO v = new ThresholdVO();
        v.setLow(low);
        v.setHigh(high);
        return v;
    }

}
