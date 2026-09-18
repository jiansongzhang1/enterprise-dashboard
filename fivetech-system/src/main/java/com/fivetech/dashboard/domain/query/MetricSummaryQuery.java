package com.fivetech.dashboard.domain.query;

import java.util.ArrayList;
import java.util.List;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import com.fivetech.dashboard.enums.CompareType;
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

    /** 对比期类型 */
    private CompareType compareType = CompareType.PREV_PERIOD;

    /** 自定义对比区间开始日期，compareType=CUSTOM 时必填 */
    private String compareFrom;

    /** 自定义对比区间结束日期，compareType=CUSTOM 时必填 */
    private String compareTo;

    /** 需要的指标编码列表。为空时返回核心指标集 */
    private List<String> metricCodes = new ArrayList<>();

    /** 排序列：时间列传 "time"，否则传指标编码 */
    private String sortColumn = "time";

    /** 排序方向 asc / desc */
    private String sortDirection = "desc";

    @Min(1)
    private Integer pageNum = 1;

    @Min(1)
    @Max(200)
    private Integer pageSize = 20;

    public Granularity getGranularity()
    {
        return granularity;
    }

    public void setGranularity(Granularity granularity)
    {
        this.granularity = granularity;
    }

    public CompareType getCompareType()
    {
        return compareType;
    }

    public void setCompareType(CompareType compareType)
    {
        this.compareType = compareType;
    }

    public String getCompareFrom()
    {
        return compareFrom;
    }

    public void setCompareFrom(String compareFrom)
    {
        this.compareFrom = compareFrom;
    }

    public String getCompareTo()
    {
        return compareTo;
    }

    public void setCompareTo(String compareTo)
    {
        this.compareTo = compareTo;
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
