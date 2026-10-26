package com.fivetech.monitor.domain.vo;

/**
 * 可新建规则的指标。occupied = true 时已有规则（可能是别人的），occupiedBy 只给规则号和负责人，不返回阈值。
 *
 * @author fivetech
 */
public class MonitorMetricVO
{
    private String metricCode;

    private String metricName;

    private String unit;

    private String judgeType;

    private MinSampleVO minSample;

    private Boolean allowNegative;

    private Boolean occupied;

    private OccupiedByVO occupiedBy;

    public String getMetricCode()
    {
        return metricCode;
    }

    public void setMetricCode(String metricCode)
    {
        this.metricCode = metricCode;
    }

    public String getMetricName()
    {
        return metricName;
    }

    public void setMetricName(String metricName)
    {
        this.metricName = metricName;
    }

    public String getUnit()
    {
        return unit;
    }

    public void setUnit(String unit)
    {
        this.unit = unit;
    }

    public String getJudgeType()
    {
        return judgeType;
    }

    public void setJudgeType(String judgeType)
    {
        this.judgeType = judgeType;
    }

    public MinSampleVO getMinSample()
    {
        return minSample;
    }

    public void setMinSample(MinSampleVO minSample)
    {
        this.minSample = minSample;
    }

    public Boolean getAllowNegative()
    {
        return allowNegative;
    }

    public void setAllowNegative(Boolean allowNegative)
    {
        this.allowNegative = allowNegative;
    }

    public Boolean getOccupied()
    {
        return occupied;
    }

    public void setOccupied(Boolean occupied)
    {
        this.occupied = occupied;
    }

    public OccupiedByVO getOccupiedBy()
    {
        return occupiedBy;
    }

    public void setOccupiedBy(OccupiedByVO occupiedBy)
    {
        this.occupiedBy = occupiedBy;
    }
}
