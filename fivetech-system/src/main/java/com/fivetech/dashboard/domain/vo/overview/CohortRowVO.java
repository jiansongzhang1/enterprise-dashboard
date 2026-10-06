package com.fivetech.dashboard.domain.vo.overview;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 队列矩阵一行。values 与 columns 一一对应，未到观察期为 null（不是 0）。
 *
 * @author fivetech
 */
public class CohortRowVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 分群日 */
    private String cohortDate;

    /** 分群人数 */
    private Long base;

    /** D+N 的值 */
    private List<BigDecimal> values = new ArrayList<>();

    public String getCohortDate()
    {
        return cohortDate;
    }

    public void setCohortDate(String cohortDate)
    {
        this.cohortDate = cohortDate;
    }

    public Long getBase()
    {
        return base;
    }

    public void setBase(Long base)
    {
        this.base = base;
    }

    public List<BigDecimal> getValues()
    {
        return values;
    }

    public void setValues(List<BigDecimal> values)
    {
        this.values = values;
    }
}
