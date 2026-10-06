package com.fivetech.dashboard.domain.query;

import java.util.ArrayList;
import java.util.List;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import com.fivetech.dashboard.enums.Granularity;

/**
 * 指标汇总表查询入参。
 * <p>
 * 一行是一个时间片，一列是一个指标；行数由区间与粒度决定，列由 metricCodes 决定。
 *
 * @author fivetech
 */
public class MetricSummaryQuery extends BaseDashboardQuery
{
    private static final long serialVersionUID = 1L;

    /** 时间粒度。为空时由服务端按区间长度给出默认值 */
    private Granularity granularity;

    /** 需要的指标编码列表。为空时返回全部指标集 */
    private List<String> metricCodes = new ArrayList<>();

    /**
     * 只查核心指标。可为空，等同 false。
     * <p>为 true 时只向数据平台查询核心指标（columns[].core = true 的那一套）：
     * 同时传了 metricCodes 时取二者交集，交集为空则返回全部核心指标。</p>
     */
    private Boolean onlyCore;

    /** 排序列：时间列传 "time"，否则传指标编码 */
    private String sortColumn = "time";

    /** 排序方向 asc / desc */
    private String sortDirection = "desc";

    @Min(1)
    private Integer pageNum = 1;

    @Min(1)
    @Max(200)
    private Integer pageSize = 20;

    public Boolean getOnlyCore()
    {
        return onlyCore;
    }

    public void setOnlyCore(Boolean onlyCore)
    {
        this.onlyCore = onlyCore;
    }

    public Granularity getGranularity()
    {
        return granularity;
    }

    public void setGranularity(Granularity granularity)
    {
        this.granularity = granularity;
    }

    public List<String> getMetricCodes()
    {
        return metricCodes;
    }

    public void setMetricCodes(List<String> metricCodes)
    {
        this.metricCodes = metricCodes;
    }

    public String getSortColumn()
    {
        return sortColumn;
    }

    public void setSortColumn(String sortColumn)
    {
        this.sortColumn = sortColumn;
    }

    public String getSortDirection()
    {
        return sortDirection;
    }

    public void setSortDirection(String sortDirection)
    {
        this.sortDirection = sortDirection;
    }

    public Integer getPageNum()
    {
        return pageNum;
    }

    public void setPageNum(Integer pageNum)
    {
        this.pageNum = pageNum;
    }

    public Integer getPageSize()
    {
        return pageSize;
    }

    public void setPageSize(Integer pageSize)
    {
        this.pageSize = pageSize;
    }
}
