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
}
