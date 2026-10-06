package com.fivetech.dashboard.domain.query;


/**
 * 留存与 LTV 入参。slotFrom / slotTo 为 yyyy-MM-dd，决定返回哪些分群日。
 *
 * @author fivetech
 */
public class CohortQuery extends OverviewSectionQuery
{
    private static final long serialVersionUID = 1L;

    /** RETENTION / LTV / BOTH，默认 BOTH */
    private String type = "BOTH";

    public String getType()
    {
        return type;
    }

    public void setType(String type)
    {
        this.type = type;
    }
}
