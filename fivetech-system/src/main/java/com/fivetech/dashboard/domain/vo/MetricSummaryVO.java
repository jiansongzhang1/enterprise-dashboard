package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标汇总表响应。
 *
 * @author fivetech
 */
public class MetricSummaryVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    private QueryContext context;

    /** 列定义，顺序即展示顺序 */
    private List<ColumnMetaVO> columns = new ArrayList<>();

    /** 分页后的时间片行 */
    private PageResultVO<MetricSummaryRowVO> page;

    /**
     * 区间合计行。
     * <p>
     * 注意：合计是按<b>整个区间</b>重新聚合的结果，不是分页行的加总。
     * 派生指标必须先聚合分子分母再套公式；去重人数类指标跨时间片不可相加。
     */
    private Map<String, BigDecimal> totalRow = new LinkedHashMap<>();

    /** 对比区间的合计 */
    private Map<String, BigDecimal> compareTotalRow = new LinkedHashMap<>();

    public QueryContext getContext()
    {
        return context;
    }

    public void setContext(QueryContext context)
    {
        this.context = context;
    }

    public List<ColumnMetaVO> getColumns()
    {
        return columns;
    }

    public void setColumns(List<ColumnMetaVO> columns)
    {
        this.columns = columns;
    }

    public PageResultVO<MetricSummaryRowVO> getPage()
    {
        return page;
    }

    public void setPage(PageResultVO<MetricSummaryRowVO> page)
    {
        this.page = page;
    }

    public Map<String, BigDecimal> getTotalRow()
    {
        return totalRow;
    }

    public void setTotalRow(Map<String, BigDecimal> totalRow)
    {
        this.totalRow = totalRow;
    }

    public Map<String, BigDecimal> getCompareTotalRow()
    {
        return compareTotalRow;
    }

    public void setCompareTotalRow(Map<String, BigDecimal> compareTotalRow)
    {
        this.compareTotalRow = compareTotalRow;
    }
}
