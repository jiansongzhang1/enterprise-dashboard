package com.fivetech.monitor.domain.vo;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 最小样本量：key 为口径（base 或分母指标），label 为显示名。
 * 规则详情里用 value，「可新建的指标」里用 defaultValue，另一个为 null 不输出。
 *
 * @author fivetech
 */
public class MinSampleVO
{
    private String key;

    private String label;

    private BigDecimal value;

    private BigDecimal defaultValue;

    public String getKey()
    {
        return key;
    }

    public void setKey(String key)
    {
        this.key = key;
    }

    public String getLabel()
    {
        return label;
    }

    public void setLabel(String label)
    {
        this.label = label;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public BigDecimal getValue()
    {
        return value;
    }

    public void setValue(BigDecimal value)
    {
        this.value = value;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public BigDecimal getDefaultValue()
    {
        return defaultValue;
    }

    public void setDefaultValue(BigDecimal defaultValue)
    {
        this.defaultValue = defaultValue;
    }
}
