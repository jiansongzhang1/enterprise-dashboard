package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 一张指标卡。
 *
 * <p>三条硬约定：</p>
 * <ol>
 *   <li>{@code value} / {@code prevValue} 无数据是 <b>null 而不是 0</b>；</li>
 *   <li>{@code deltaPct} 与 {@code deltaPt} <b>永不同时有值</b>——
 *       {@code valueFormat=PCT} 填 deltaPt（百分点），其余填 deltaPct（百分比）。
 *       混了的话「首存转化率掉了 1.52 个点」会被读成「掉了 9.6%」；</li>
 *   <li>{@code series} 长度<b>恒等于</b> {@code slot.points}，缺的位置填 null、数组不变短——
 *       前端按下标对齐时间片标签，短一截会让整条趋势线错位而且不报错。</li>
 * </ol>
 *
 * @author fivetech
 */
public class MetricCardVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    private String code;

    private String label;

    private String group;

    private String groupLabel;

    private Integer sortNo;

    /** CORE 是大号卡，其余 NORMAL */
    private String emphasis;

    /** ATOM / DERIVED */
    private String kind;

    /** 派生公式，仅供展示与口径追溯，求值在数据侧完成 */
    private String expression;

    /** INT / MONEY / PCT / MIN / MULTIPLE */
    /** 更新频率 realtime / hour / day，来自配置，前端按它显示更新角标 */
    private String updateFrequency;

    private String valueFormat;

    /** 覆盖默认小数位，null 用 valueFormat 的默认值 */
    private Integer decimals;

    /** up 越高越好 / down 越低越好 / range 区间型 / flat 无方向 */
    private String direction;

    private BigDecimal value;

    private BigDecimal prevValue;

    private BigDecimal delta;

    /** 相对变化率。prevValue 为 0 时为 null，不返回 +100 或 ∞ */
    private BigDecimal deltaPct;

    /** 变化的百分点，仅 PCT 类指标使用 */
    private BigDecimal deltaPt;

    private List<BigDecimal> series = new ArrayList<>();

    private List<BigDecimal> prevSeries = new ArrayList<>();

    /**
     * 今日小时基准线（近 4 周同星期同小时的中位数，UDS *_baseline 列）。
     * 只在区间为「今日」且按小时出序列时返回，长度与 series 相同；其他情况为 null
     */
    private List<BigDecimal> baselineSeries;

    /**
     * 指标抽屉「逐时段明细」的监控列，与 series 等长、按下标对齐。
     * 只在「今日 + 小时粒度」且卡片 alert 为注意或严重（规则启用）时返回，其余为 null（前端不画监控列）。
     */
    private List<MetricMonitorSlotVO> monitorSlots;

    /** 派生指标的计算构成；原子指标为 null */
    private MetricCompositionVO composition;

    private MetricAlertVO alert;

    /** 卡片画什么图（GAUGE / LINE）；旧前端忽略即可，缺失时按旧逻辑显示 */
    private MetricChartVO chart;

    /** 上游指标名。口径有分歧时能直接对上数据平台，省一轮来回 */
    private String udsMetric;

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

    public String getGroup()
    {
        return group;
    }

    public void setGroup(String group)
    {
        this.group = group;
    }

    public String getGroupLabel()
    {
        return groupLabel;
    }

    public void setGroupLabel(String groupLabel)
    {
        this.groupLabel = groupLabel;
    }

    public Integer getSortNo()
    {
        return sortNo;
    }

    public void setSortNo(Integer sortNo)
    {
        this.sortNo = sortNo;
    }

    public String getEmphasis()
    {
        return emphasis;
    }

    public void setEmphasis(String emphasis)
    {
        this.emphasis = emphasis;
    }

    public String getKind()
    {
        return kind;
    }

    public void setKind(String kind)
    {
        this.kind = kind;
    }

    public String getExpression()
    {
        return expression;
    }

    public void setExpression(String expression)
    {
        this.expression = expression;
    }

    public String getUpdateFrequency()
    {
        return updateFrequency;
    }

    public void setUpdateFrequency(String updateFrequency)
    {
        this.updateFrequency = updateFrequency;
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

    public String getDirection()
    {
        return direction;
    }

    public void setDirection(String direction)
    {
        this.direction = direction;
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

    public List<BigDecimal> getSeries()
    {
        return series;
    }

    public void setSeries(List<BigDecimal> series)
    {
        this.series = series;
    }

    public List<BigDecimal> getPrevSeries()
    {
        return prevSeries;
    }

    public void setPrevSeries(List<BigDecimal> prevSeries)
    {
        this.prevSeries = prevSeries;
    }

    public List<BigDecimal> getBaselineSeries()
    {
        return baselineSeries;
    }

    public void setBaselineSeries(List<BigDecimal> baselineSeries)
    {
        this.baselineSeries = baselineSeries;
    }

    public List<MetricMonitorSlotVO> getMonitorSlots()
    {
        return monitorSlots;
    }

    public void setMonitorSlots(List<MetricMonitorSlotVO> monitorSlots)
    {
        this.monitorSlots = monitorSlots;
    }

    public MetricCompositionVO getComposition()
    {
        return composition;
    }

    public void setComposition(MetricCompositionVO composition)
    {
        this.composition = composition;
    }

    public MetricChartVO getChart()
    {
        return chart;
    }

    public void setChart(MetricChartVO chart)
    {
        this.chart = chart;
    }

    public MetricAlertVO getAlert()
    {
        return alert;
    }

    public void setAlert(MetricAlertVO alert)
    {
        this.alert = alert;
    }

    public String getUdsMetric()
    {
        return udsMetric;
    }

    public void setUdsMetric(String udsMetric)
    {
        this.udsMetric = udsMetric;
    }

}