package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 派生指标的一个操作数（分子/分母，或被减数/减数）。
 *
 * <p>字段刻意与 {@link MetricCardVO} 的数值部分同名同义，前端渲染
 * 「首存人数 47 ↓11.3%」和渲染一张卡用的是同一套逻辑。</p>
 *
 * @author fivetech
 */
public class MetricOperandVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 指标编码 */
    private String code;

    /** 指标名称，直接用于展示 */
    private String label;

    /** 值的语义单位，决定前端加什么符号 */
    private String valueFormat;

    /** 覆盖默认小数位 */
    private Integer decimals;

    /** 当期值；{@link #available} 为 false 时恒为 null */
    private BigDecimal value;

    /** 对比期值 */
    private BigDecimal prevValue;

    /** 绝对差 */
    private BigDecimal delta;

    /** 相对变化率 %，PCT 类为 null */
    private BigDecimal deltaPct;

    /** 百分点差值，仅 PCT 类有值 */
    private BigDecimal deltaPt;

    /**
     * 这个操作数的数值是否可得。
     *
     * <p>为 false 表示它没有在上游注册成可查指标（例如「首存金额」只作为
     * 首存 ARPPU 的分子存在于配置里，UDS 没有对应的 metric）。这时
     * {@link #code} 和 {@link #label} 仍然返回，前端可以显示
     * 「首存金额 — ÷ 首存人数 2」这样的构成，把缺口摆在明面上，
     * 而不是整块构成消失、让人以为这个指标没有构成。</p>
     */
    private boolean available;

    public String getCode()
    {
        return code;
    }

    public void setCode(String code)
    {
        this.code = code;
    }

    public String getLabel()
    {
        return label;
    }

    public void setLabel(String label)
    {
        this.label = label;
    }

    public String getValueFormat()
    {
        return valueFormat;
    }

    public void setValueFormat(String valueFormat)
    {
        this.valueFormat = valueFormat;
    }

    public Integer getDecimals()
    {
        return decimals;
    }

    public void setDecimals(Integer decimals)
    {
        this.decimals = decimals;
    }

    public BigDecimal getValue()
    {
        return value;
    }

    public void setValue(BigDecimal value)
    {
        this.value = value;
    }

    public BigDecimal getPrevValue()
    {
        return prevValue;
    }

    public void setPrevValue(BigDecimal prevValue)
    {
        this.prevValue = prevValue;
    }

    public BigDecimal getDelta()
    {
        return delta;
    }

    public void setDelta(BigDecimal delta)
    {
        this.delta = delta;
    }

    public BigDecimal getDeltaPct()
    {
        return deltaPct;
    }

    public void setDeltaPct(BigDecimal deltaPct)
    {
        this.deltaPct = deltaPct;
    }

    public BigDecimal getDeltaPt()
    {
        return deltaPt;
    }

    public void setDeltaPt(BigDecimal deltaPt)
    {
        this.deltaPt = deltaPt;
    }

    public boolean isAvailable()
    {
        return available;
    }

    public void setAvailable(boolean available)
    {
        this.available = available;
    }
}
