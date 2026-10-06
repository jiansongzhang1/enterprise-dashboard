package com.fivetech.dashboard.domain.query;


/**
 * 排行榜入参：赠金项目构成 + 热销游戏 Top N。
 *
 * @author fivetech
 */
public class RankingBoardQuery extends OverviewSectionQuery
{
    private static final long serialVersionUID = 1L;

    /** 游戏榜取前 N，1–50，默认 10 */
    /** 已忽略：游戏榜固定 Top 20。保留字段只为兼容旧前端，传了会在 notices 里提示 */
    private Integer topN;

    public Integer getTopN()
    {
        return topN;
    }

    public void setTopN(Integer topN)
    {
        this.topN = topN;
    }
}
