package com.fivetech.dashboard.domain.query;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 明细查询的公共入参。三张记录表共用。
 *
 * @author fivetech
 */
public class BaseRecordQuery extends BaseDashboardQuery
{
    private static final long serialVersionUID = 1L;

    /** 关键字搜索。会员表搜账号，交易/投注表搜订单号或账号 */
    @Size(max = 64)
    private String keyword;

    /** 排序列编码，必须在该表的列白名单内 */
    private String sortColumn;

    /** 排序方向 asc / desc */
    private String sortDirection = "desc";

    @Min(1)
    private Integer pageNum = 1;

    @Min(1)
    @Max(200)
    private Integer pageSize = 20;

    public String getKeyword()
    {
        return keyword;
    }

    public void setKeyword(String keyword)
    {
        this.keyword = keyword;
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
