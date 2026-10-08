package com.fivetech.dashboard.domain.vo.overview;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 赠金项目构成。
 *
 * @author fivetech
 */
public class BonusBoardVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 标题 */
    private String title = "贈金項目構成";

    /** 赠金总额，与指标卡 bonus 同口径 */
    private BigDecimal total;

    /** 固定 6 项（含「其他」兜底） */
    private List<BonusItemVO> items = new ArrayList<>();

    public String getTitle()
    {
        return title;
    }

    public void setTitle(String title)
    {
        this.title = title;
    }

    public BigDecimal getTotal()
    {
        return total;
    }

    public void setTotal(BigDecimal total)
    {
        this.total = com.fivetech.dashboard.format.MoneyScale.integer(total);
    }

    public List<BonusItemVO> getItems()
    {
        return items;
    }

    public void setItems(List<BonusItemVO> items)
    {
        this.items = items;
    }
}
