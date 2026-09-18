package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 投注明细行。一行一笔投注。
 *
 * @author fivetech
 */
public class BetRecordVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 注单号 */
    private String orderNo;

    /** 会员账号（脱敏后） */
    private String account;

    /** 厂商 */
    private String vendor;

    /** 游戏类型编码 */
    private String gameType;

    /** 游戏类型名称 */
    private String gameTypeLabel;

    /** 游戏名称 */
    private String game;

    /** 投注金额 */
    private BigDecimal betAmount;

    /** 派彩金额 */
    private BigDecimal payout;

    /** 输赢（投注 − 派彩），正数代表平台盈利 */
    private BigDecimal winLoss;

    /** 投注时间 */
    private String createTime;

    public String getOrderNo()
    {
        return orderNo;
    }

    public void setOrderNo(String orderNo)
    {
        this.orderNo = orderNo;
    }

    public String getAccount()
    {
        return account;
    }

    public void setAccount(String account)
    {
        this.account = account;
    }

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

    public String getGameTypeLabel()
    {
        return gameTypeLabel;
    }

    public void setGameTypeLabel(String gameTypeLabel)
    {
        this.gameTypeLabel = gameTypeLabel;
    }

    public String getGame()
    {
        return game;
    }

    public void setGame(String game)
    {
        this.game = game;
    }

    public BigDecimal getBetAmount()
    {
        return betAmount;
    }

    public void setBetAmount(BigDecimal betAmount)
    {
        this.betAmount = betAmount;
    }

    public BigDecimal getPayout()
    {
        return payout;
    }

    public void setPayout(BigDecimal payout)
    {
        this.payout = payout;
    }

    public BigDecimal getWinLoss()
    {
        return winLoss;
    }

    public void setWinLoss(BigDecimal winLoss)
    {
        this.winLoss = winLoss;
    }

    public String getCreateTime()
    {
        return createTime;
    }

    public void setCreateTime(String createTime)
    {
        this.createTime = createTime;
    }
}
