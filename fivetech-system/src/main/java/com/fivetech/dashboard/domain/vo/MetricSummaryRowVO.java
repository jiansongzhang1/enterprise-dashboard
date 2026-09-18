package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 指标汇总表的一行：一个时间片 × 多个指标。
 *
 * @author fivetech
 */
public class MetricSummaryRowVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 时间片展示文案，如 "09:00 – 10:00" 或 "2026-09-12" */
    private String label;

    /** 时间片开始，下钻时直接写进明细表的时间筛选 */
    private String slotFrom;

    /** 时间片结束 */
    private String slotTo;

    /**
     * 本时间片的指标值，key 为指标编码。
     * 值为 null 表示无数据，<b>不是 0</b>，前端须显示「—」。
     */
    private Map<String, BigDecimal> values = new LinkedHashMap<>();

    /** 对比期同位置的指标值，无对比时为空 */
    private Map<String, BigDecimal> compareValues = new LinkedHashMap<>();

    /** 该时间片是否落在延迟窗口内 */
    private boolean delayed;

    public String getLabel()
    {
        return label;
    }

    public void setLabel(String label)
    {
        this.label = label;
    }

    public String getSlotFrom()
    {
        return slotFrom;
    }

    public void setSlotFrom(String slotFrom)
    {
        this.slotFrom = slotFrom;
    }

    public String getSlotTo()
    {
        return slotTo;
    }

    public void setSlotTo(String slotTo)
    {
        this.slotTo = slotTo;
    }

    public Map<String, BigDecimal> getValues()
    {
        return values;
    }

    public void setValues(Map<String, BigDecimal> values)
    {
        this.values = values;
    }

    public Map<String, BigDecimal> getCompareValues()
    {
        return compareValues;
    }

    public void setCompareValues(Map<String, BigDecimal> compareValues)
    {
        this.compareValues = compareValues;
    }

    public boolean isDelayed()
    {
        return delayed;
    }

    public void setDelayed(boolean delayed)
    {
        this.delayed = delayed;
    }
}
