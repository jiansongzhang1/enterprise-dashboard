package com.fivetech.monitor.domain.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * GET /monitor/events 的 data：分页 + asOf + pendingTotal。
 *
 * @author fivetech
 */
public class MonitorEventPageVO
{
    /** 本站最近一个已判定小时的结束时刻；还没有判定过为 null */
    private String asOf;

    /** 本站未处理的告警事件总数，不受任何筛选影响 */
    private int pendingTotal;

    private long total;

    private int pageNum;

    private int pageSize;

    private List<MonitorEventVO> rows = new ArrayList<>();

    public String getAsOf() { return asOf; }
    public void setAsOf(String asOf) { this.asOf = asOf; }
    public int getPendingTotal() { return pendingTotal; }
    public void setPendingTotal(int pendingTotal) { this.pendingTotal = pendingTotal; }
    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }
    public int getPageNum() { return pageNum; }
    public void setPageNum(int pageNum) { this.pageNum = pageNum; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public List<MonitorEventVO> getRows() { return rows; }
    public void setRows(List<MonitorEventVO> rows) { this.rows = rows; }
}
