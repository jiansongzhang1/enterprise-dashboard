package com.fivetech.dashboard.domain;

/**
 * 系统管理 · 指标管理的一行：dashboard_metric_card 的全部列 + 线上取数配置对照 + 引用情况。
 * <p>只给后台指标管理用，运营总览、指标汇总仍走 {@link MetricCardConfig} / MetricRegistry，互不影响。</p>
 *
 * @author fivetech
 */
public class MetricDictItem
{
    /** 指标编码 */
    private String metricCode;

    /** 指标名称（简体） */
    private String metricName;

    /** 英文名 */
    private String metricNameEn;

    /** 分组编码 */
    private String groupCode;

    /** 分组名称（dashboard_metric_group） */
    private String groupName;

    /** 口径与备注：「口径：…」+「备注：…」 */
    private String remark;

    /** UDS 上游原始表（待 UDS 提供，仅记录） */
    private String sourceTable;

    /** UDS 上游原始字段（仅记录） */
    private String sourceField;

    /** dashboard_overview_hour_v2 字段 */
    private String udsHourField;

    /** dashboard_overview_day_v2 字段 */
    private String udsDayField;

    /** dashboard_overview_amount_v2 字段 */
    private String udsAmountField;

    /** 跌幅基准列 */
    private String udsBaselineField;

    /** 更新频率 realtime / hour / day */
    private String updateFrequency;

    /** 仅支持单日查询 */
    private Boolean singleDayOnly;

    /** LINE / PIE / NUMBER */
    private String chartType;

    /** INT / MONEY / PCT / MIN / MULTIPLE */
    private String valueFormat;

    /** 小数位覆盖 */
    private Integer valueDecimals;

    /** up / down / flat / range */
    private String direction;

    /** 是否上卡 */
    private Boolean showAsCard;

    /** 排序 */
    private Integer sortNo;

    /** NONE / DIVIDE / SUBTRACT */
    private String calcType;

    /** 分子 / 被减数 */
    private String leftCode;

    /** 分母 / 减数 */
    private String rightCode;

    /** 跨时间片聚合 */
    private String aggType;

    /** 加权平均的权重列 */
    private String weightField;

    /** 能否建告警规则 */
    private Boolean monitorable;

    /** lo / hi / band / drop */
    private String judgeType;

    /** 最小样本量看哪个值 */
    private String minSampleKey;

    /** 默认最小样本量 */
    private java.math.BigDecimal minSampleDefault;

    /** 阈值能否为负 */
    private Boolean allowNegative;

    /** 门槛条下限 */
    private java.math.BigDecimal gaugeMin;

    /** 门槛条上限 */
    private java.math.BigDecimal gaugeMax;

    /** 0 已接入（运营总览、指标汇总加载）/ 1 仅登记（不加载） */
    private String status;

    /** 创建人 */
    private String createBy;

    /** 创建时间 */
    private java.util.Date createTime;

    /** 修改人 */
    private String updateBy;

    /** 修改时间 */
    private java.util.Date updateTime;

    /** 线上配置（application.yml）里的实际取数：udsHourField / udsDayField / udsAmountField / udsBaselineField / assemble */
    private java.util.Map<String, String> config;

    /** 表里登记的字段与线上配置不一致的项 */
    private java.util.List<String> configMismatch;

    /** 出现在哪些页面（dashboard_page_metric） */
    private java.util.List<String> pages;

    /** 未删除的告警规则数（全部站点） */
    private Integer ruleCount;

    /** 被哪些指标引用为分子 / 分母 / 最小样本量 */
    private java.util.List<String> usedBy;

    /** 能否删除：仅登记且没有任何引用 */
    private Boolean deletable;

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

    public String getGroupName()
    {
        return groupName;
    }

    public void setGroupName(String groupName)
    {
        this.groupName = groupName;
    }

    public String getRemark()
    {
        return remark;
    }

    public void setRemark(String remark)
    {
        this.remark = remark;
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

    public String getUdsHourField()
    {
        return udsHourField;
    }

    public void setUdsHourField(String udsHourField)
    {
        this.udsHourField = udsHourField;
    }

    public String getUdsDayField()
    {
        return udsDayField;
    }

    public void setUdsDayField(String udsDayField)
    {
        this.udsDayField = udsDayField;
    }

    public String getUdsAmountField()
    {
        return udsAmountField;
    }

    public void setUdsAmountField(String udsAmountField)
    {
        this.udsAmountField = udsAmountField;
    }

    public String getUdsBaselineField()
    {
        return udsBaselineField;
    }

    public void setUdsBaselineField(String udsBaselineField)
    {
        this.udsBaselineField = udsBaselineField;
    }

    public String getUpdateFrequency()
    {
        return updateFrequency;
    }

    public void setUpdateFrequency(String updateFrequency)
    {
        this.updateFrequency = updateFrequency;
    }

    public Boolean getSingleDayOnly()
    {
        return singleDayOnly;
    }

    public void setSingleDayOnly(Boolean singleDayOnly)
    {
        this.singleDayOnly = singleDayOnly;
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

    public String getDirection()
    {
        return direction;
    }

    public void setDirection(String direction)
    {
        this.direction = direction;
    }

    public Boolean getShowAsCard()
    {
        return showAsCard;
    }

    public void setShowAsCard(Boolean showAsCard)
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

    public Boolean getMonitorable()
    {
        return monitorable;
    }

    public void setMonitorable(Boolean monitorable)
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

    public java.math.BigDecimal getMinSampleDefault()
    {
        return minSampleDefault;
    }

    public void setMinSampleDefault(java.math.BigDecimal minSampleDefault)
    {
        this.minSampleDefault = minSampleDefault;
    }

    public Boolean getAllowNegative()
    {
        return allowNegative;
    }

    public void setAllowNegative(Boolean allowNegative)
    {
        this.allowNegative = allowNegative;
    }

    public java.math.BigDecimal getGaugeMin()
    {
        return gaugeMin;
    }

    public void setGaugeMin(java.math.BigDecimal gaugeMin)
    {
        this.gaugeMin = gaugeMin;
    }

    public java.math.BigDecimal getGaugeMax()
    {
        return gaugeMax;
    }

    public void setGaugeMax(java.math.BigDecimal gaugeMax)
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

    public String getCreateBy()
    {
        return createBy;
    }

    public void setCreateBy(String createBy)
    {
        this.createBy = createBy;
    }

    public java.util.Date getCreateTime()
    {
        return createTime;
    }

    public void setCreateTime(java.util.Date createTime)
    {
        this.createTime = createTime;
    }

    public String getUpdateBy()
    {
        return updateBy;
    }

    public void setUpdateBy(String updateBy)
    {
        this.updateBy = updateBy;
    }

    public java.util.Date getUpdateTime()
    {
        return updateTime;
    }

    public void setUpdateTime(java.util.Date updateTime)
    {
        this.updateTime = updateTime;
    }

    public java.util.Map<String, String> getConfig()
    {
        return config;
    }

    public void setConfig(java.util.Map<String, String> config)
    {
        this.config = config;
    }

    public java.util.List<String> getConfigMismatch()
    {
        return configMismatch;
    }

    public void setConfigMismatch(java.util.List<String> configMismatch)
    {
        this.configMismatch = configMismatch;
    }

    public java.util.List<String> getPages()
    {
        return pages;
    }

    public void setPages(java.util.List<String> pages)
    {
        this.pages = pages;
    }

    public Integer getRuleCount()
    {
        return ruleCount;
    }

    public void setRuleCount(Integer ruleCount)
    {
        this.ruleCount = ruleCount;
    }

    public java.util.List<String> getUsedBy()
    {
        return usedBy;
    }

    public void setUsedBy(java.util.List<String> usedBy)
    {
        this.usedBy = usedBy;
    }

    public Boolean getDeletable()
    {
        return deletable;
    }

    public void setDeletable(Boolean deletable)
    {
        this.deletable = deletable;
    }
}
