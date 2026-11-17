package com.fivetech.risk.catalog;

import java.util.Map;
import com.fivetech.dashboard.service.MetricExt;

/**
 * 风控排行榜 / 游戏分析的一列（指标字典里 page_scope = RISK_RANK / RISK_GAME 的一行），属性见 {@link MetricExt}。
 *
 * @author fivetech
 */
public final class RiskColumn
{
    /** 接口编码（ext.apiCode） */
    private final String code;

    /** 指标字典的编码 */
    private final String metricCode;

    private final String name;

    private final String group;

    private final String source;

    private final String kind;

    private final String agg;

    /** UDS 字段：排行榜为 uds_day_field，游戏分析为 uds_day_field（游戏数据集） */
    private final String udsField;

    private final String valueFormat;

    private final Integer decimals;

    private final String follow;

    private final String argOf;

    private final boolean positive;

    /** ARGMAX 并列时再比哪一列（如有效投注） */
    private final String tieBy;

    private final String tieRatioOf;

    private final String tieTimeOf;

    private final String calcOp;

    private final String calcLeft;

    private final String calcRight;

    private final boolean rankable;

    private final String riskDir;

    private final boolean column;

    private final String condition;

    private final boolean common;

    private final boolean standard;

    private final int sortNo;

    private final String remark;

    @SuppressWarnings("unchecked")
    RiskColumn(String metricCode, String name, String valueFormat, Integer decimals, String direction, String udsField,
        int sortNo, String remark, Map<String, Object> ext)
    {
        this.metricCode = metricCode;
        this.name = name;
        this.valueFormat = valueFormat == null ? "INT" : valueFormat.toUpperCase(java.util.Locale.ROOT);
        this.decimals = decimals;
        this.udsField = udsField == null ? null : udsField.trim();
        this.sortNo = sortNo;
        this.remark = remark;
        this.code = MetricExt.str(ext.get("apiCode"));
        this.group = MetricExt.str(ext.get("group"));
        String src = MetricExt.str(ext.get("source"));
        this.source = src == null ? "USER_DAY" : src;
        String k = MetricExt.str(ext.get("kind"));
        this.kind = k == null ? "NUMBER" : k;
        String a = MetricExt.str(ext.get("agg"));
        this.agg = a == null ? ("NUMBER".equals(this.kind) ? "SUM" : "LAST") : a;
        this.follow = MetricExt.str(ext.get("follow"));
        this.argOf = MetricExt.str(ext.get("argOf"));
        this.positive = Boolean.TRUE.equals(ext.get("positive"));
        this.tieBy = MetricExt.str(ext.get("tieBy"));
        Map<String, Object> tie = ext.get("tieBreak") instanceof Map<?, ?> t ? (Map<String, Object>) t : Map.of();
        this.tieRatioOf = MetricExt.str(tie.get("ratioOf"));
        this.tieTimeOf = MetricExt.str(tie.get("timeOf"));
        Map<String, Object> calc = ext.get("calc") instanceof Map<?, ?> c ? (Map<String, Object>) c : Map.of();
        String op = MetricExt.str(calc.get("op"));
        this.calcOp = op == null ? null : op.toUpperCase(java.util.Locale.ROOT);
        this.calcLeft = MetricExt.str(calc.get("left"));
        this.calcRight = MetricExt.str(calc.get("right"));
        this.rankable = ext.get("rankable") instanceof Boolean b ? b : "NUMBER".equals(this.kind);
        String dir = MetricExt.str(ext.get("riskDir"));
        this.riskDir = dir != null ? dir : ("down".equalsIgnoreCase(direction) ? "asc" : "desc");
        this.column = !(ext.get("column") instanceof Boolean b2) || b2;
        this.condition = MetricExt.str(ext.get("condition"));
        this.common = Boolean.TRUE.equals(ext.get("common"));
        this.standard = Boolean.TRUE.equals(ext.get("standard"));
    }

    public boolean isNumber()
    {
        return "NUMBER".equals(kind);
    }

    public boolean isCalc()
    {
        return calcOp != null;
    }

    public String getCode() { return code; }
    public String getMetricCode() { return metricCode; }
    public String getName() { return name; }
    public String getGroup() { return group; }
    public String getSource() { return source; }
    public String getKind() { return kind; }
    public String getAgg() { return agg; }
    public String getUdsField() { return udsField; }
    public String getValueFormat() { return valueFormat; }
    public Integer getDecimals() { return decimals; }
    public String getFollow() { return follow; }
    public String getArgOf() { return argOf; }
    public boolean isPositive() { return positive; }
    public String getTieBy() { return tieBy; }
    public String getTieRatioOf() { return tieRatioOf; }
    public String getTieTimeOf() { return tieTimeOf; }
    public String getCalcOp() { return calcOp; }
    public String getCalcLeft() { return calcLeft; }
    public String getCalcRight() { return calcRight; }
    public boolean isRankable() { return rankable; }
    public String getRiskDir() { return riskDir; }
    public boolean isColumn() { return column; }
    public String getCondition() { return condition; }
    public boolean isCommon() { return common; }
    public boolean isStandard() { return standard; }
    public int getSortNo() { return sortNo; }
    public String getRemark() { return remark; }
}
