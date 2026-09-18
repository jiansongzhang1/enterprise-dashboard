package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 明细查询响应（会员 / 交易 / 投注三张表共用）。
 *
 * @author fivetech
 */
public class RecordResultVO<T> implements Serializable
{
    private static final long serialVersionUID = 1L;

    private QueryContext context;

    /** 本次生效的列定义 */
    private List<ColumnMetaVO> columns = new ArrayList<>();

    private PageResultVO<T> page;

    /**
     * 本次筛选合计。口径由 summaryNote 说明，前端不得自行假设。
     */
    private Map<String, BigDecimal> summary = new LinkedHashMap<>();

    /** 合计口径说明，如「仅计成功单」 */
    private String summaryNote;

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

    public PageResultVO<T> getPage()
    {
        return page;
    }

    public void setPage(PageResultVO<T> page)
    {
        this.page = page;
    }

    public Map<String, BigDecimal> getSummary()
    {
        return summary;
    }

    public void setSummary(Map<String, BigDecimal> summary)
    {
        this.summary = summary;
    }

    public String getSummaryNote()
    {
        return summaryNote;
    }

    public void setSummaryNote(String summaryNote)
    {
        this.summaryNote = summaryNote;
    }
}
