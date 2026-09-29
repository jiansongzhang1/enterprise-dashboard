package com.fivetech.dashboard.domain;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * {@code dashboard_metric_card} 的一行。
 * <p>
 * 这是<b>配置的原样映射</b>，不做任何解释。解释后的运行时视图是 {@link MetricDefinition}，
 * 校验则直接吃这个原样对象——校验要报的是「配置写错了」，必须指着配置说话。
 *
 * @author fivetech
 */
public class MetricCardConfig implements Serializable
{
    private static final long serialVersionUID = 1L;

    private String metricCode;

    private String metricName;

    private String metricNameEn;

    private String groupCode;

    private String sourceTable;

    private String sourceField;

    private String chartType;

    /** INT / MONEY / PCT / MIN / MULTIPLE */
    private String valueFormat;

    /** 覆盖默认小数位，null 表示用 valueFormat 的默认值 */
    private Integer valueDecimals;

    private boolean showAsCard;

    private Integer sortNo;

    /** NONE / DIVIDE / SUBTRACT */
    private String calcType;

    /** DIVIDE 的分子 / SUBTRACT 的被减数 */
    private String leftCode;

    /** DIVIDE 的分母 / SUBTRACT 的减数 */
    private String rightCode;

    /** SUM / AVG_WEIGHTED / DISTINCT / FORMULA */
    private String aggType;

    private String weightField;

    private boolean alertEnabled;

    private BigDecimal alertMin;

    private BigDecimal alertMax;

    private BigDecimal tolerancePct;

    /** '0' 正常 / '1' 停用 */
    private String status;

    private String remark;

    public boolean isEnabled()
    {
        return status == null || "0".equals(status);
    }

    public boolean isDerived()
    {
        return calcType != null && !"NONE".equalsIgnoreCase(calcType);
    }

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

    public String getMetricNameEn()
    {
        return metricNameEn;
    }

    public void setMetricNameEn(String metricNameEn)
    {
        this.metricNameEn = metricNameEn;
    }

    public String getGroupCode()
    {
        return groupCode;
    }

    public void setGroupCode(String groupCode)
    {
        this.groupCode = groupCode;
    }

    public String getSourceTable()
    {
        return sourceTable;
    }

    public void setSourceTable(String sourceTable)
    {
        this.sourceTable = sourceTable;
    }

    public String getSourceField()
    {
        return sourceField;
    }

    public void setSourceField(String sourceField)
    {
        this.sourceField = sourceField;
    }

    public String getChartType()
    {
        return chartType;
    }

    public void setChartType(String chartType)
    {
        this.chartType = chartType;
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

    public boolean isShowAsCard()
    {
        return showAsCard;
    }

    public void setShowAsCard(boolean showAsCard)
    {
        this.showAsCard = showAsCard;
    }

    public Integer getSortNo()
    {
        return sortNo;
    }

    public void setSortNo(Integer sortNo)
    {
        this.sortNo = sortNo;
    }

    public String getCalcType()
    {
        return calcType;
    }

    public void setCalcType(String calcType)
    {
        this.calcType = calcType;
    }

    public String getLeftCode()
    {
        return leftCode;
    }

    public void setLeftCode(String leftCode)
    {
        this.leftCode = leftCode;
    }

    public String getRightCode()
    {
        return rightCode;
    }

    public void setRightCode(String rightCode)
    {
        this.rightCode = rightCode;
    }

    public String getAggType()
    {
        return aggType;
    }

    public void setAggType(String aggType)
    {
        this.aggType = aggType;
    }

    public String getWeightField()
    {
        return weightField;
    }

    public void setWeightField(String weightField)
    {
        this.weightField = weightField;
    }

    public boolean isAlertEnabled()
    {
        return alertEnabled;
    }

    public void setAlertEnabled(boolean alertEnabled)
    {
        this.alertEnabled = alertEnabled;
    }

    public BigDecimal getAlertMin()
    {
        return alertMin;
    }

    public void setAlertMin(BigDecimal alertMin)
    {
        this.alertMin = alertMin;
    }

    public BigDecimal getAlertMax()
    {
        return alertMax;
    }

    public void setAlertMax(BigDecimal alertMax)
    {
        this.alertMax = alertMax;
    }

    public BigDecimal getTolerancePct()
    {
        return tolerancePct;
    }

    public void setTolerancePct(BigDecimal tolerancePct)
    {
        this.tolerancePct = tolerancePct;
    }

    public String getStatus()
    {
        return status;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public String getRemark()
    {
        return remark;
    }

    public void setRemark(String remark)
    {
        this.remark = remark;
    }
}
