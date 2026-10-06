package com.fivetech.dashboard.gateway;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 向外部数据平台请求明细分页的参数。
 *
 * @author fivetech
 */
public class RecordPageRequest implements Serializable
{
    private static final long serialVersionUID = 1L;

    private String siteCode;

    /** 时间字段编码，由各表的服务层按下钻来源决定 */
    private String timeField;

    private LocalDateTime from;

    private LocalDateTime to;

    /** 关键字 */
    private String keyword;

    /**
     * 业务筛选条件。key 为<b>列编码</b>（白名单内），不是数据库列名。
     */
    private Map<String, Object> filters = new LinkedHashMap<>();

    /**
     * 区间筛选条件，可以有多个且互相叠加（会员表的注册 / 首存 / 最近投注时间、累计存款金额）。
     * field 同样是<b>列编码</b>，由网关映射成上游字段名。
     */
    private java.util.List<Range> ranges = new java.util.ArrayList<>();

    /** 排序列编码，已通过白名单校验 */
    private String sortColumn;

    /** asc / desc */
    private String sortDirection;

    private int pageNum = 1;

    private int pageSize = 20;

    /** 行数上限，超过时网关应返回 truncated 标记而不是把全量拉回来 */
    private int rowLimit;

    public String getSiteCode()
    {
        return siteCode;
    }

    public void setSiteCode(String siteCode)
    {
        this.siteCode = siteCode;
    }

    public String getTimeField()
    {
        return timeField;
    }

    public void setTimeField(String timeField)
    {
        this.timeField = timeField;
    }

    public LocalDateTime getFrom()
    {
        return from;
    }

    public void setFrom(LocalDateTime from)
    {
        this.from = from;
    }

    public LocalDateTime getTo()
    {
        return to;
    }

    public void setTo(LocalDateTime to)
    {
        this.to = to;
    }

    public String getKeyword()
    {
        return keyword;
    }

    public void setKeyword(String keyword)
    {
        this.keyword = keyword;
    }

    public Map<String, Object> getFilters()
    {
        return filters;
    }

    public void setFilters(Map<String, Object> filters)
    {
        this.filters = filters;
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

    public int getRowLimit()
    {
        return rowLimit;
    }

    public void setRowLimit(int rowLimit)
    {
        this.rowLimit = rowLimit;
    }

    public java.util.List<Range> getRanges()
    {
        return ranges;
    }

    public void setRanges(java.util.List<Range> ranges)
    {
        this.ranges = ranges;
    }

    /** 加一个区间条件；两端都为空时忽略 */
    public void addRange(String field, Object from, Object to, boolean toInclusive)
    {
        if (from == null && to == null)
        {
            return;
        }
        ranges.add(new Range(field, from, to, toInclusive));
    }

    /**
     * 区间条件。from 恒为闭区间；to 是否包含由 toInclusive 决定——
     * 时间按全站约定左闭右开（false），金额上下限都包含（true）。
     */
    public static class Range implements Serializable
    {
        private static final long serialVersionUID = 1L;

        private final String field;

        private final Object from;

        private final Object to;

        private final boolean toInclusive;

        public Range(String field, Object from, Object to, boolean toInclusive)
        {
            this.field = field;
            this.from = from;
            this.to = to;
            this.toInclusive = toInclusive;
        }

        public String getField()
        {
            return field;
        }

        public Object getFrom()
        {
            return from;
        }

        public Object getTo()
        {
            return to;
        }

        public boolean isToInclusive()
        {
            return toInclusive;
        }
    }
}
