package com.fivetech.dashboard.domain.vo.overview;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 一张队列矩阵（留存率或 LTV）。
 *
 * @author fivetech
 */
public class CohortTableVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 标题 */
    private String title;

    /** 分群依据 FIRST_BET_DATE / FIRST_DEPOSIT_DATE */
    private String cohortBy;

    /** 分群依据名称 */
    private String cohortLabel;

    /** 基数名称 */
    private String baseLabel;

    /** PCT / MONEY */
    private String valueFormat;

    /** 按分群日升序 */
    private List<CohortRowVO> rows = new ArrayList<>();

    public String getTitle()
    {
        return title;
    }

    public void setTitle(String title)
    {
        this.title = title;
    }

    public String getCohortBy()
    {
        return cohortBy;
    }

    public void setCohortBy(String cohortBy)
    {
        this.cohortBy = cohortBy;
    }

    public String getCohortLabel()
    {
        return cohortLabel;
    }

    public void setCohortLabel(String cohortLabel)
    {
        this.cohortLabel = cohortLabel;
    }

    public String getBaseLabel()
    {
        return baseLabel;
    }

    public void setBaseLabel(String baseLabel)
    {
        this.baseLabel = baseLabel;
    }

    public String getValueFormat()
    {
        return valueFormat;
    }

    public void setValueFormat(String valueFormat)
    {
        this.valueFormat = valueFormat;
    }

    public List<CohortRowVO> getRows()
    {
        return rows;
    }

    public void setRows(List<CohortRowVO> rows)
    {
        this.rows = rows;
    }
}
