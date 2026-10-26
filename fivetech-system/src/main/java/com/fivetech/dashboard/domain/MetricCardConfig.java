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

    /** 更新频率 realtime / hour / day。取值固定，来自 update_frequency 列 */
    private String updateFrequency;

    /** 仅支持单日查询：区间跨天时不查、不展示（如活跃人数、ARPPU） */
    private boolean singleDayOnly;

    private String chartType;

    /** 涨跌好坏方向 up / down / flat / range，来自 direction 列 */
    private String direction;

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

    /**
     * 能否在监控管理中建告警规则。告警规则本身（阈值、周期、负责人）按站点存在 monitor_rule，
     * 这里只放指标自带、不随站点变化的属性，新建规则时带出并复制到规则上
     */
    private boolean monitorable;

    /** 判定类型：lo 下限 / hi 上限 / band 区间 / drop 跌幅 */
    private String judgeType;

    /** 最小样本量看哪个值：base = 跌幅类的基准；其余为分母指标的 metric_code */
    private String minSampleKey;

    /** 新建规则时的默认最小样本量 */
    private BigDecimal minSampleDefault;

    /** 阈值能否为负（仅存提差） */
    private boolean allowNegative;

    /** 卡片门槛条刻度下限（展示单位）；与 gaugeMax 都有值时卡片画门槛条 */
    private BigDecimal gaugeMin;

    /** 卡片门槛条刻度上限（展示单位） */
    private BigDecimal gaugeMax;

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

    public String getUpdateFrequency()
    {
        return updateFrequency;
    }

    public void setUpdateFrequency(String updateFrequency)
    {
        this.updateFrequency = updateFrequency;
    }

    public String getChartType()
    {
        return chartType;
    }

    public void setChartType(String chartType)
    {
        this.chartType = chartType;
    }

    public String getDirection()
    {
        return direction;
    }

    public void setDirection(String direction)
    {
        this.direction = direction;
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

    public boolean isMonitorable()
    {
        return monitorable;
    }

    public void setMonitorable(boolean monitorable)
    {
        this.monitorable = monitorable;
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

    /** 卡片是否画门槛条 */
    public boolean hasGauge()
    {
        return gaugeMin != null && gaugeMax != null;
    }

    public BigDecimal getGaugeMin()
    {
        return gaugeMin;
    }

    public void setGaugeMin(BigDecimal gaugeMin)
    {
        this.gaugeMin = gaugeMin;
    }

    public BigDecimal getGaugeMax()
    {
        return gaugeMax;
    }

    public void setGaugeMax(BigDecimal gaugeMax)
    {
        this.gaugeMax = gaugeMax;
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
    public boolean isSingleDayOnly()
    {
        return singleDayOnly;
    }

    public void setSingleDayOnly(boolean singleDayOnly)
    {
        this.singleDayOnly = singleDayOnly;
    }
}
