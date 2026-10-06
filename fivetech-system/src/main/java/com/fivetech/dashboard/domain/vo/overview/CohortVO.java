package com.fivetech.dashboard.domain.vo.overview;

import java.util.ArrayList;
import java.util.List;

/**
 * 留存与 LTV 响应。
 *
 * @author fivetech
 */
public class CohortVO extends OverviewSectionVO
{
    private static final long serialVersionUID = 1L;

    /** 队列是 T-1 日快照，每天更新一次 */
    public CohortVO()
    {
        setUpdateFrequency("day");
    }

    /** SNAPSHOT_T1 */
    private String asOfKind = "SNAPSHOT_T1";

    /** 固定 10 列：Pre D+0、D+1…D+30 */
    private List<String> columns = new ArrayList<>();

    /** 留存率；type=LTV 时为 null */
    private CohortTableVO retention;

    /** LTV；type=RETENTION 时为 null */
    private CohortTableVO ltv;

    public String getAsOfKind()
    {
        return asOfKind;
    }

    public void setAsOfKind(String asOfKind)
    {
        this.asOfKind = asOfKind;
    }

    public List<String> getColumns()
    {
        return columns;
    }

    public void setColumns(List<String> columns)
    {
        this.columns = columns;
    }

    public CohortTableVO getRetention()
    {
        return retention;
    }

    public void setRetention(CohortTableVO retention)
    {
        this.retention = retention;
    }

    public CohortTableVO getLtv()
    {
        return ltv;
    }

    public void setLtv(CohortTableVO ltv)
    {
        this.ltv = ltv;
    }
}
