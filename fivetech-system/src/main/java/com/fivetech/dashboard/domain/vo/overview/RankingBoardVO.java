package com.fivetech.dashboard.domain.vo.overview;


/**
 * 排行榜响应。
 *
 * @author fivetech
 */
public class RankingBoardVO extends OverviewSectionVO
{
    private static final long serialVersionUID = 1L;

    /** 赠金项目构成 */
    private BonusBoardVO bonus;

    /** 热销游戏 */
    private GameBoardVO games;

    public BonusBoardVO getBonus()
    {
        return bonus;
    }

    public void setBonus(BonusBoardVO bonus)
    {
        this.bonus = bonus;
    }

    public GameBoardVO getGames()
    {
        return games;
    }

    public void setGames(GameBoardVO games)
    {
        this.games = games;
    }
}
