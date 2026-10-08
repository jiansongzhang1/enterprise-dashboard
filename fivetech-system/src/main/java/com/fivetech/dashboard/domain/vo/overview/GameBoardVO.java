package com.fivetech.dashboard.domain.vo.overview;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 热销游戏 Top N。
 *
 * @author fivetech
 */
public class GameBoardVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 标题 */
    private String title = "熱銷遊戲";

    /** 实际取的 N */
    private Integer topN;

    /** 全站投注总额（不是 TopN 之和），与指标卡 bet 同口径 */
    private BigDecimal totalBetAmount;

    /** TopN 投注额占全站比例，如 90% */
    private String totalBetAmountPct;

    /** 按投注金额降序 */
    private List<GameItemVO> items = new ArrayList<>();

    public String getTitle()
    {
        return title;
    }

    public void setTitle(String title)
    {
        this.title = title;
    }

    public Integer getTopN()
    {
        return topN;
    }

    public void setTopN(Integer topN)
    {
        this.topN = topN;
    }

    public BigDecimal getTotalBetAmount()
    {
        return totalBetAmount;
    }

    public void setTotalBetAmount(BigDecimal totalBetAmount)
    {
        this.totalBetAmount = com.fivetech.dashboard.format.MoneyScale.integer(totalBetAmount);
    }

    public String getTotalBetAmountPct()
    {
        return totalBetAmountPct;
    }

    public void setTotalBetAmountPct(String totalBetAmountPct)
    {
        this.totalBetAmountPct = totalBetAmountPct;
    }

    public List<GameItemVO> getItems()
    {
        return items;
    }

    public void setItems(List<GameItemVO> items)
    {
        this.items = items;
    }
}
