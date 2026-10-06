package com.fivetech.dashboard.domain.vo.overview;

import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

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

    /** 留存率列头：D+1…D+30（9 列）；type=LTV 时为空 */
    @JsonProperty("retention_columns")
    private List<String> retentionColumns = new ArrayList<>();

    /** LTV 列头：Pre D+0、D+1…D+30（10 列）；type=RETENTION 时为空 */
    @JsonProperty("ltv_columns")
    private List<String> ltvColumns = new ArrayList<>();

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

    public List<String> getRetentionColumns()
    {
        return retentionColumns;
    }

    public void setRetentionColumns(List<String> retentionColumns)
    {
        this.retentionColumns = retentionColumns;
    }

    public List<String> getLtvColumns()
    {
        return ltvColumns;
    }

    public void setLtvColumns(List<String> ltvColumns)
    {
        this.ltvColumns = ltvColumns;
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
