package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;

/**
 * {@code blocks.METRICS.groups[]}：指标墙顶部的分组按钮。
 * <p>
 * 顺序即 {@code dashboard_metric_group.sort_no}，前端直接按数组顺序渲染。
 * {@code count} 是该分组在指标墙上配置的卡片数，<b>不随本次请求的筛选变化</b>——
 * 选中某个分组时其余按钮的数字不能跟着变成 0。
 *
 * @author fivetech
 */
public class MetricGroupVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    private String code;

    private String label;

    private String labelEn;

    private int count;

    public MetricGroupVO()
    {
    }

    public MetricGroupVO(String code, String label, String labelEn, int count)
    {
        this.code = code;
        this.label = label;
        this.labelEn = labelEn;
        this.count = count;
    }

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

    public String getLabelEn()
    {
        return labelEn;
    }

    public void setLabelEn(String labelEn)
    {
        this.labelEn = labelEn;
    }

    public int getCount()
    {
        return count;
    }

    public void setCount(int count)
    {
        this.count = count;
    }
}
