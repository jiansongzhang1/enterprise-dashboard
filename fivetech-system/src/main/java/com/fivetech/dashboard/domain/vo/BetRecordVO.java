package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 投注明细行。一行一笔注单。
 * <p>
 * 金额统一为站点币种（INR）。未结算注单的派彩、输赢为 null，不是 0。
 *
 * @author fivetech
 */
public class BetRecordVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 注单号 */
    private String orderNo;

    /** 用户ID */
    private String userId;

    /** 账号名称 */
    private String username;

    /** 游戏平台 Code */
    private String vendorCode;

    /** 平台厂商名 */
    private String vendorName;

    /** 游戏类型编码（game_catalog）1 真人 / 2 电游 / 3 体育 / 4 捕鱼 / 5 彩票 / 6 棋牌 / 7 电竞 */
    private String gameType;

    /** 游戏类型名称 */
    private String gameTypeLabel;

    /** 游戏ID */
    private String gameId;

    /** 游戏名称 */
    private String gameName;

    /** 投注金额 */
    private BigDecimal betAmount;

    /** 派彩；未结算为 null */
    private BigDecimal payout;

    /** 输赢 = 投注 − 派彩，平台视角：正数平台赢；未结算为 null */
    private BigDecimal winLoss;

    /** 结算状态编码 done / open */
    private String settleStatus;

    /** 结算状态名称 */
    private String settleStatusLabel;

    /** 投注时间 */
    private String betTime;

    /** 结算时间；未结算为 null */
    private String settleTime;

    public String getOrderNo()
    {
        return orderNo;
    }

    public void setOrderNo(String orderNo)
    {
        this.orderNo = orderNo;
    }

    public String getUserId()
    {
        return userId;
    }

    public void setUserId(String userId)
    {
        this.userId = userId;
    }

    public String getUsername()
    {
        return username;
    }

    public void setUsername(String username)
    {
        this.username = username;
    }

    public String getVendorCode()
    {
        return vendorCode;
    }

    public void setVendorCode(String vendorCode)
    {
        this.vendorCode = vendorCode;
    }

    public String getVendorName()
    {
        return vendorName;
    }

    public void setVendorName(String vendorName)
    {
        this.vendorName = vendorName;
    }

    public String getGameType()
    {
        return gameType;
    }

    public void setGameType(String gameType)
    {
        this.gameType = gameType;
    }

    public String getGameTypeLabel()
    {
        return gameTypeLabel;
    }

    public void setGameTypeLabel(String gameTypeLabel)
    {
        this.gameTypeLabel = gameTypeLabel;
    }

    public String getGameId()
    {
        return gameId;
    }

    public void setGameId(String gameId)
    {
        this.gameId = gameId;
    }

    public String getGameName()
    {
        return gameName;
    }

    public void setGameName(String gameName)
    {
        this.gameName = gameName;
    }

    public BigDecimal getBetAmount()
    {
        return betAmount;
    }

    public void setBetAmount(BigDecimal betAmount)
    {
        this.betAmount = com.fivetech.dashboard.format.MoneyScale.of(betAmount);
    }

    public BigDecimal getPayout()
    {
        return payout;
    }

    public void setPayout(BigDecimal payout)
    {
        this.payout = com.fivetech.dashboard.format.MoneyScale.of(payout);
    }

    public BigDecimal getWinLoss()
    {
        return winLoss;
    }

    public void setWinLoss(BigDecimal winLoss)
    {
        this.winLoss = com.fivetech.dashboard.format.MoneyScale.of(winLoss);
    }

    public String getSettleStatus()
    {
        return settleStatus;
    }

    public void setSettleStatus(String settleStatus)
    {
        this.settleStatus = settleStatus;
    }

    public String getSettleStatusLabel()
    {
        return settleStatusLabel;
    }

    public void setSettleStatusLabel(String settleStatusLabel)
    {
        this.settleStatusLabel = settleStatusLabel;
    }

    public String getBetTime()
    {
        return betTime;
    }

    public void setBetTime(String betTime)
    {
        this.betTime = betTime;
    }

    public String getSettleTime()
    {
        return settleTime;
    }

    public void setSettleTime(String settleTime)
    {
        this.settleTime = settleTime;
    }
}
