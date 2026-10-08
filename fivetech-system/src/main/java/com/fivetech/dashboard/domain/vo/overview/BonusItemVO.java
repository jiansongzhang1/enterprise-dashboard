package com.fivetech.dashboard.domain.vo.overview;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 赠金项目一项。
 *
 * @author fivetech
 */
public class BonusItemVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 排名 */
    private Integer rank;

    /** 项目编码 */
    private String code;

    /** 项目名称 */
    private String name;

    /** 金额（INR） */
    private BigDecimal amount;

    /** 占比 %，分母为 bonus.total */
    private BigDecimal share;

    public Integer getRank()
    {
        return rank;
    }

    public void setRank(Integer rank)
    {
        this.rank = rank;
    }

    public String getCode()
    {
        return code;
    }

    public void setCode(String code)
    {
        this.code = code;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public BigDecimal getAmount()
    {
        return amount;
    }

    public void setAmount(BigDecimal amount)
    {
        this.amount = com.fivetech.dashboard.format.MoneyScale.integer(amount);
    }

    public BigDecimal getShare()
    {
        return share;
    }

    public void setShare(BigDecimal share)
    {
        this.share = share;
    }
}
