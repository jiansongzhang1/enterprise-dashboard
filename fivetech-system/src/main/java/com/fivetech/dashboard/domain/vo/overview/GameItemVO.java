package com.fivetech.dashboard.domain.vo.overview;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 热销游戏一项。
 *
 * @author fivetech
 */
public class GameItemVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 排名 */
    private Integer rank;

    /** 游戏名称 */
    private String name;

    /** 游戏平台 Code */
    private String platformCode;

    /** 厂商名（游戏榜数据集的 provider_name，如 JILI / SPRIBE / EVOLUTION） */
    private String vendorName;

    /** 游戏ID */
    private String gameId;

    /** 游戏类型名称 */
    private String gameType;

    /** 游戏类型编码 slots / live / mini */
    private String gameTypeCode;

    /** 投注金额（INR） */
    private BigDecimal betAmount;

    /** 盈利率 = GGR ÷ 投注金额，如 15.4% */
    private String profitRate;

    /** 占全站投注额比例，如 12% */
    private String rateForBetAmount;

    /** 投注人数 */
    private Long betUsers;

    /** 投注笔数 */
    private Long betCount;

    public Integer getRank()
    {
        return rank;
    }

    public void setRank(Integer rank)
    {
        this.rank = rank;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public String getPlatformCode()
    {
        return platformCode;
    }

    public void setPlatformCode(String platformCode)
    {
        this.platformCode = platformCode;
    }

    public String getVendorName()
    {
        return vendorName;
    }

    public void setVendorName(String vendorName)
    {
        this.vendorName = vendorName;
    }

    public String getGameId()
    {
        return gameId;
    }

    public void setGameId(String gameId)
    {
        this.gameId = gameId;
    }

    public String getGameType()
    {
        return gameType;
    }

    public void setGameType(String gameType)
    {
        this.gameType = gameType;
    }

    public String getGameTypeCode()
    {
        return gameTypeCode;
    }

    public void setGameTypeCode(String gameTypeCode)
    {
        this.gameTypeCode = gameTypeCode;
    }

    public BigDecimal getBetAmount()
    {
        return betAmount;
    }

    public void setBetAmount(BigDecimal betAmount)
    {
        this.betAmount = com.fivetech.dashboard.format.MoneyScale.integer(betAmount);
    }

    public String getProfitRate()
    {
        return profitRate;
    }

    public void setProfitRate(String profitRate)
    {
        this.profitRate = profitRate;
    }

    public String getRateForBetAmount()
    {
        return rateForBetAmount;
    }

    public void setRateForBetAmount(String rateForBetAmount)
    {
        this.rateForBetAmount = rateForBetAmount;
    }

    public Long getBetUsers()
    {
        return betUsers;
    }

    public void setBetUsers(Long betUsers)
    {
        this.betUsers = betUsers;
    }

    public Long getBetCount()
    {
        return betCount;
    }

    public void setBetCount(Long betCount)
    {
        this.betCount = betCount;
    }
}
