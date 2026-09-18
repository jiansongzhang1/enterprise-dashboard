package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 分页结果。
 *
 * @author fivetech
 */
public class PageResultVO<T> implements Serializable
{
    private static final long serialVersionUID = 1L;

    private List<T> rows = new ArrayList<>();

    private long total;

    private int pageNum;

    private int pageSize;

    /**
     * 是否因为超过行数上限而被截断。
     * 为 true 时前端提示「行数超过上限」，引导用户走异步导出。
     */
    private boolean truncated;

    /** 行数上限 */
    private Integer rowLimit;

    public static <T> PageResultVO<T> of(List<T> rows, long total, int pageNum, int pageSize)
    {
        PageResultVO<T> vo = new PageResultVO<>();
        vo.rows = rows == null ? new ArrayList<>() : rows;
        vo.total = total;
        vo.pageNum = pageNum;
        vo.pageSize = pageSize;
        return vo;
    }

    public List<T> getRows()
    {
        return rows;
    }

    public void setRows(List<T> rows)
    {
        this.rows = rows;
    }

    public long getTotal()
    {
        return total;
    }

    public void setTotal(long total)
    {
        this.total = total;
    }

    public int getPageNum()
    {
        return pageNum;
    }

    public void setPageNum(int pageNum)
    {
        this.pageNum = pageNum;
    }

    public int getPageSize()
    {
        return pageSize;
    }

    public void setPageSize(int pageSize)
    {
        this.pageSize = pageSize;
    }

    public boolean isTruncated()
    {
        return truncated;
    }

    public void setTruncated(boolean truncated)
    {
        this.truncated = truncated;
    }

    public Integer getRowLimit()
    {
        return rowLimit;
    }

    public void setRowLimit(Integer rowLimit)
    {
        this.rowLimit = rowLimit;
    }
}
