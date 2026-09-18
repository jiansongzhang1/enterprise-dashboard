package com.fivetech.dashboard.domain.query;

/**
 * 投注明细查询入参。
 *
 * @author fivetech
 */
public class BetRecordQuery extends BaseRecordQuery
{
    private static final long serialVersionUID = 1L;

    /** 游戏厂商，如 JILI / EVOLUTION / SPRIBE */
    private String vendor;

    /** 游戏类型编码，如 slots / live / mini */
    private String gameType;

    /** 游戏名称 */
    private String game;

    public String getVendor()
    {
        return vendor;
    }

    public void setVendor(String vendor)
    {
        this.vendor = vendor;
    }

    public String getGameType()
    {
        return gameType;
    }

    public void setGameType(String gameType)
    {
        this.gameType = gameType;
    }

    public String getGame()
    {
        return game;
    }

    public void setGame(String game)
    {
        this.game = game;
    }
}
