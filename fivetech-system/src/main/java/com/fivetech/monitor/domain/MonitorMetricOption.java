package com.fivetech.monitor.domain;

import java.math.BigDecimal;

/**
 * 可建告警规则的指标（dashboard_metric_card 中 monitorable = true 且启用的行）。
 *
 * 指标清单、判定类型和样本量口径都来自指标配置表，不在代码里写死。
 *
 * @author fivetech
 */
public class MonitorMetricOption
{
    private String metricCode;

    private String metricName;

    /** INT / MONEY / PCT / MIN / MULTIPLE */
    private String valueFormat;

    private Integer valueDecimals;

    private String judgeType;

    /** base = 跌幅基准，其余为分母指标的 metric_code */
    private String minSampleKey;

    /** 样本量口径的显示名：base 为「基準」，其余取对应指标名称 */
    private String minSampleLabel;

    private BigDecimal minSampleDefault;

    private boolean allowNegative;

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

    public String getValueFormat()
    {
        return valueFormat;
    }

    public void setValueFormat(String valueFormat)
    {
        this.valueFormat = valueFormat;
    }

    public Integer getValueDecimals()
    {
        return valueDecimals;
    }

    public void setValueDecimals(Integer valueDecimals)
    {
        this.valueDecimals = valueDecimals;
    }

    public String getJudgeType()
    {
        return judgeType;
    }

    public void setJudgeType(String judgeType)
    {
        this.judgeType = judgeType;
    }

    public String getMinSampleKey()
    {
        return minSampleKey;
    }

    public void setMinSampleKey(String minSampleKey)
    {
        this.minSampleKey = minSampleKey;
    }

    public String getMinSampleLabel()
    {
        return minSampleLabel;
    }

    public void setMinSampleLabel(String minSampleLabel)
    {
        this.minSampleLabel = minSampleLabel;
    }

    public BigDecimal getMinSampleDefault()
    {
        return minSampleDefault;
    }

    public void setMinSampleDefault(BigDecimal minSampleDefault)
    {
        this.minSampleDefault = minSampleDefault;
    }

    public boolean isAllowNegative()
    {
        return allowNegative;
    }

    public void setAllowNegative(boolean allowNegative)
    {
        this.allowNegative = allowNegative;
    }

    /** 百分比类指标：阈值必须在 0–100 之间 */
    public boolean isPercent()
    {
        return "PCT".equalsIgnoreCase(valueFormat);
    }

}
