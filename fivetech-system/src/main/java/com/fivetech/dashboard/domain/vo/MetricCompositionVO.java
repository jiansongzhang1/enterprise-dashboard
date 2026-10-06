package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 派生指标的计算构成，供前端展开成一行算式：
 *
 * <pre>
 * 首存人数 47 ↓11.3%  ÷  注册人数 297 ↑2.8%
 * = 首存转化率 15.82% ↓2.51pt
 * </pre>
 *
 * <p><b>为什么由后端给而不是前端自己拼</b>：哪个是分子、口径是什么、两个操作数是否都可查，
 * 全在 {@code dashboard_metric_card} 里配着。前端自己维护一份映射的话，改口径要发两次版，
 * 而且迟早会和后端的算法对不上——那时页面上会出现「构成算出来的数和卡片上的数不一样」。</p>
 *
 * <p>只有 {@code kind = DERIVED} 的卡片有这一块，原子指标为 null。</p>
 *
 * @author fivetech
 */
public class MetricCompositionVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** DIVIDE 相除 / SUBTRACT 相减 */
    private String operator;

    /** 运算符号，直接用于展示：÷ 或 − */
    private String operatorLabel;

    /** 完整算式，如 {@code ftd ÷ reg}，用于 tooltip 或口径说明 */
    private String expression;

    /**
     * 操作数，<b>顺序有意义</b>：DIVIDE 时 [0] 是分子、[1] 是分母；
     * SUBTRACT 时 [0] 是被减数、[1] 是减数。前端按下标渲染，不要重排。
     */
    private List<MetricOperandVO> operands = new ArrayList<>();

    /**
     * 两个操作数的数值是否都拿得到。
     *
     * <p>为 false 时前端仍然可以显示构成的结构（名称与运算符），但算式里会有「—」。
     * 这不是故障，是上游还没把那个中间量注册成可查指标。</p>
     */
    private boolean complete;

    public String getOperator()
    {
        return operator;
    }

    public void setOperator(String operator)
    {
        this.operator = operator;
    }

    public String getOperatorLabel()
    {
        return operatorLabel;
    }

    public void setOperatorLabel(String operatorLabel)
    {
        this.operatorLabel = operatorLabel;
    }

    public String getExpression()
    {
        return expression;
    }

    public void setExpression(String expression)
    {
        this.expression = expression;
    }

    public List<MetricOperandVO> getOperands()
    {
        return operands;
    }

    public void setOperands(List<MetricOperandVO> operands)
    {
        this.operands = operands;
    }

    public boolean isComplete()
    {
        return complete;
    }

    public void setComplete(boolean complete)
    {
        this.complete = complete;
    }
}
