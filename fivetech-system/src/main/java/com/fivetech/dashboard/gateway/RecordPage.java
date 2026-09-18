package com.fivetech.dashboard.gateway;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部数据平台返回的明细分页结果。
 *
 * @author fivetech
 */
public class RecordPage<T> implements Serializable
{
    private static final long serialVersionUID = 1L;

    private List<T> rows = new ArrayList<>();

    private long total;

    /** 是否因超过行数上限而截断 */
    private boolean truncated;

    /** 本次筛选合计，key 为列编码 */
    private Map<String, BigDecimal> summary = new LinkedHashMap<>();

    public static <T> RecordPage<T> empty()
    {
        return new RecordPage<>();
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

    public boolean isTruncated()
    {
        return truncated;
    }

    public void setTruncated(boolean truncated)
    {
        this.truncated = truncated;
    }

    public Map<String, BigDecimal> getSummary()
    {
        return summary;
    }

    public void setSummary(Map<String, BigDecimal> summary)
    {
        this.summary = summary;
    }
}
