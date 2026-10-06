package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code blocks.METRICS}：指标墙。
 *
 * @author fivetech
 */
public class MetricsBlockVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    private int total;

    /** 大号卡数量，正常是 8 */
    private int coreCount;

    /** 分组按钮，按 dashboard_metric_group.sort_no 排序；只含指标墙上至少有一张卡的分组 */
    private List<MetricGroupVO> groups = new ArrayList<>();

    /** 按页面配置的 sortNo 排序，前端不要重排 */
    private List<MetricCardVO> items = new ArrayList<>();

    public int getTotal()
    {
        return total;
    }

    public void setTotal(int total)
    {
        this.total = total;
    }

    public int getCoreCount()
    {
        return coreCount;
    }

    public void setCoreCount(int coreCount)
    {
        this.coreCount = coreCount;
    }

    public List<MetricGroupVO> getGroups()
    {
        return groups;
    }

    public void setGroups(List<MetricGroupVO> groups)
    {
        this.groups = groups;
    }

    public List<MetricCardVO> getItems()
    {
        return items;
    }

    public void setItems(List<MetricCardVO> items)
    {
        this.items = items;
    }

}