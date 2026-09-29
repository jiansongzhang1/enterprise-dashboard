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

    /**
     * 是否可被接口选为列。
     * <p>仅供派生指标引用的隐藏原子量（depU / ftdAmt / pay / dOk / dTry / wOk / wTry）为 false：
     * 它们要留在注册表里让公式解析得通，但不能被前端选出来当一列。</p>
     */
    private boolean selectable = true;

    /** 是否出现在运营总览的指标墙。login 为 false：只进汇总表，不上墙 */
    private boolean onWall = true;

    /** 覆盖默认小数位，null 表示按 format 的默认值；来自 dashboard_metric_card.value_decimals */
    private Integer decimals;

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

    /** 链式设置可见性，供注册表初始化时使用 */
    public MetricDefinition visibility(boolean selectable, boolean onWall)
    {
        this.selectable = selectable;
        this.onWall = onWall;
        return this;
    }

    public Integer getDecimals()
    {
        return decimals;
    }

    public void setDecimals(Integer decimals)
    {
        this.decimals = decimals;
    }

    public boolean isSelectable()
    {
        return selectable;
    }

    public void setSelectable(boolean selectable)
    {
        this.selectable = selectable;
    }

    public boolean isOnWall()
    {
        return onWall;
    }

    public void setOnWall(boolean onWall)
    {
        this.onWall = onWall;
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
