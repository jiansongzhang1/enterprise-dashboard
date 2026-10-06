package com.fivetech.dashboard.domain;

import java.io.Serializable;

/**
 * 指标定义（注册表的一行）。
 * <p>
 * 一期先以内存注册表落地，字段结构与后续的 {@code metric_definition} 表一致，
 * 迁移到库表时只需换数据来源，调用方不变。
 *
 * @author fivetech
 */
public class MetricDefinition implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 指标编码，接口入参只认这个值 */
    private final String code;

    /** 指标名称 */
    private final String label;

    /** 分组编码 */
    private final String group;

    /** ATOM 原子指标 / DERIVED 派生指标 */
    private final String kind;

    /** 展示格式 INT / MONEY / MONEY1 / PCT / MIN / X */
    private final String format;

    /**
     * 跨时间片的聚合方式：
     * SUM 可加 / WEIGHTED_AVG 按笔数加权 / DISTINCT 去重不可加 / FORMULA 先聚合再套公式
     */
    private final String aggregation;

    /** 派生指标的公式，仅作展示与文档用途，求值在数据侧完成 */
    private final String expression;



    /** 覆盖默认小数位，null 表示按 format 的默认值；来自 dashboard_metric_card.value_decimals */
    private Integer decimals;

    /**
     * 更新频率 realtime / hour / day，来自 dashboard_metric_card.update_frequency。
     * <p>描述的是「这个数多久变一次」，不是查询用的时间字段——前端据此显示角标，
     * 也用来解释「为什么刷新了数字还没动」。</p>
     */
    private String updateFrequency;

    /**
     * 仅支持单日查询，来自 dashboard_metric_card.single_day_only。
     * <p>上游只按自然日给数（如活跃人数的日去重、ARPPU 的日口径），跨天区间的值没有意义，
     * 运营总览在区间跨天时直接不查、不出卡，而不是给一个错的数。</p>
     */
    private boolean singleDayOnly;

    /**
     * 涨跌好坏方向，来自 dashboard_metric_card.direction：
     * up 越高越好 / down 越低越好 / flat 中性不着色 / range 落在正常区间内为好
     */
    private String direction;

    /**
     * 指标的全局顺序，来自 dashboard_metric_card.sort_no，只用于注册表与导出。
     * <b>不决定任何页面的排列</b>——页面顺序见 {@code MetricRegistry#pageSortNo}
     */
    private Integer sortNo;

    /** NONE / DIVIDE / SUBTRACT，来自 dashboard_metric_card.calc_type */
    private String calcType;

    /** 派生指标的左操作数：DIVIDE 的分子、SUBTRACT 的被减数 */
    private String leftCode;

    /** 派生指标的右操作数：DIVIDE 的分母、SUBTRACT 的减数 */
    private String rightCode;

    public MetricDefinition(String code, String label, String group, String kind,
            String format, String aggregation, String expression)
    {
        this.code = code;
        this.label = label;
        this.group = group;
        this.kind = kind;
        this.format = format;
        this.aggregation = aggregation;
        this.expression = expression;
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

    public String getUpdateFrequency()
    {
        return updateFrequency;
    }

    public void setUpdateFrequency(String updateFrequency)
    {
        this.updateFrequency = updateFrequency;
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

    public String getCode()
    {
        return code;
    }

    public String getLabel()
    {
        return label;
    }

    public String getGroup()
    {
        return group;
    }

    public String getKind()
    {
        return kind;
    }

    public String getFormat()
    {
        return format;
    }

    public String getAggregation()
    {
        return aggregation;
    }

    public String getExpression()
    {
        return expression;
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
