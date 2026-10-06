package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;
import com.fivetech.dashboard.enums.CompareType;

/**
 * 对比期。{@code compareType = NONE} 时整个对象为 null，
 * 卡片上所有 {@code prevValue} / {@code delta*} 一并为 null。
 *
 * @author fivetech
 */
public class OverviewCompareVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    private String from;

    private String to;

    private CompareType type;

    private String label;

    public String getFrom()
    {
        return from;
    }

    public void setFrom(String from)
    {
        this.from = from;
    }

    public String getTo()
    {
        return to;
    }

    public void setTo(String to)
    {
        this.to = to;
    }

    public CompareType getType()
    {
        return type;
    }

    public void setType(CompareType type)
    {
        this.type = type;
    }

    public String getLabel()
    {
        return label;
    }

    public void setLabel(String label)
    {
        this.label = label;
    }
}
