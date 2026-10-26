package com.fivetech.monitor.domain.dto;

import java.math.BigDecimal;

/**
 * 阈值 {@code {"low": x, "high": y}}：lo 和 drop 只用 low，hi 只用 high，band 两个都用。
 * 用不到的一侧服务端会清空，不报错。
 *
 * <p>单位与指标展示一致：百分比按百分数（95.00 = 95%），跌幅 20 = 20%，金额为卢比，时长为分钟，倍数为倍。</p>
 *
 * @author fivetech
 */
public class Threshold
{
    private BigDecimal low;

    private BigDecimal high;

    public Threshold()
    {
    }

    public Threshold(BigDecimal low, BigDecimal high)
    {
        this.low = low;
        this.high = high;
    }

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

    /** 两侧都没填 */
    public boolean isBlank()
    {
        return low == null && high == null;
    }
}
