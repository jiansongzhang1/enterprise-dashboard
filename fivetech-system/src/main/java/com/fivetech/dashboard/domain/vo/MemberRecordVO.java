package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 会员明细行。一行一名会员。
 * <p>
 * 累计类字段（首存、存款、投注、NGR）对未首存会员应为 <b>null 而非 0</b>，
 * 前端显示「—」。
 *
 * @author fivetech
 */
public class MemberRecordVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 会员账号（脱敏后） */
    private String account;

    /** 会员唯一标识，供下钻与导出使用 */
    private String memberId;

    /** 注册时间 */
    private String registerTime;

    /** 注册渠道 */
    private String registerChannel;

    /** 注册设备 */
    private String device;

    /** 是否已首存 */
    private Boolean hasFirstDeposit;

    /** 首存时间 */
    private String firstDepositTime;

    /** 首存金额 */
    private BigDecimal firstDepositAmount;

    /** 首存通道 */
    private String firstDepositChannel;

    /** 注册到首存的小时数 */
    private Integer regToFtdHours;

    /** 累计存款 */
    private BigDecimal cumulativeDeposit;

    /** 累计提款 */
    private BigDecimal cumulativeWithdraw;

    /** 累计投注 */
    private BigDecimal cumulativeBet;

    /** 累计 NGR */
    private BigDecimal cumulativeNgr;

    /** 流水倍数 */
    private BigDecimal turnoverMultiple;

    /** 价值层级编码 */
    private String tier;

    /** 价值层级名称 */
    private String tierLabel;

    /** 生命周期阶段编码 */
    private String stage;

    /** 生命周期阶段名称 */
    private String stageLabel;

    /** 最近活跃时间 */
    private String lastActiveTime;

    /** 最近投注距今，如 "< 24h"、"9d" */
    private String lastBetGap;

    public String getAccount()
    {
        return account;
    }

    public void setAccount(String account)
    {
        this.account = account;
    }

    public String getMemberId()
    {
        return memberId;
    }

    public void setMemberId(String memberId)
    {
        this.memberId = memberId;
    }

    public String getRegisterTime()
    {
        return registerTime;
    }

    public void setRegisterTime(String registerTime)
    {
        this.registerTime = registerTime;
    }

    public String getRegisterChannel()
    {
        return registerChannel;
    }

    public void setRegisterChannel(String registerChannel)
    {
        this.registerChannel = registerChannel;
    }

    public String getDevice()
    {
        return device;
    }

    public void setDevice(String device)
    {
        this.device = device;
    }

    public Boolean getHasFirstDeposit()
    {
        return hasFirstDeposit;
    }

    public void setHasFirstDeposit(Boolean hasFirstDeposit)
    {
        this.hasFirstDeposit = hasFirstDeposit;
    }

    public String getFirstDepositTime()
    {
        return firstDepositTime;
    }

    public void setFirstDepositTime(String firstDepositTime)
    {
        this.firstDepositTime = firstDepositTime;
    }

    public BigDecimal getFirstDepositAmount()
    {
        return firstDepositAmount;
    }

    public void setFirstDepositAmount(BigDecimal firstDepositAmount)
    {
        this.firstDepositAmount = firstDepositAmount;
    }

    public String getFirstDepositChannel()
    {
        return firstDepositChannel;
    }

    public void setFirstDepositChannel(String firstDepositChannel)
    {
        this.firstDepositChannel = firstDepositChannel;
    }

    public Integer getRegToFtdHours()
    {
        return regToFtdHours;
    }

    public void setRegToFtdHours(Integer regToFtdHours)
    {
        this.regToFtdHours = regToFtdHours;
    }

    public BigDecimal getCumulativeDeposit()
    {
        return cumulativeDeposit;
    }

    public void setCumulativeDeposit(BigDecimal cumulativeDeposit)
    {
        this.cumulativeDeposit = cumulativeDeposit;
    }

    public BigDecimal getCumulativeWithdraw()
    {
        return cumulativeWithdraw;
    }

    public void setCumulativeWithdraw(BigDecimal cumulativeWithdraw)
    {
        this.cumulativeWithdraw = cumulativeWithdraw;
    }

    public BigDecimal getCumulativeBet()
    {
        return cumulativeBet;
    }

    public void setCumulativeBet(BigDecimal cumulativeBet)
    {
        this.cumulativeBet = cumulativeBet;
    }

    public BigDecimal getCumulativeNgr()
    {
        return cumulativeNgr;
    }

    public void setCumulativeNgr(BigDecimal cumulativeNgr)
    {
        this.cumulativeNgr = cumulativeNgr;
    }

    public BigDecimal getTurnoverMultiple()
    {
        return turnoverMultiple;
    }

    public void setTurnoverMultiple(BigDecimal turnoverMultiple)
    {
        this.turnoverMultiple = turnoverMultiple;
    }

    public String getTier()
    {
        return tier;
    }

    public void setTier(String tier)
    {
        this.tier = tier;
    }

    public String getTierLabel()
    {
        return tierLabel;
    }

    public void setTierLabel(String tierLabel)
    {
        this.tierLabel = tierLabel;
    }

    public String getStage()
    {
        return stage;
    }

    public void setStage(String stage)
    {
        this.stage = stage;
    }

    public String getStageLabel()
    {
        return stageLabel;
    }

    public void setStageLabel(String stageLabel)
    {
        this.stageLabel = stageLabel;
    }

    public String getLastActiveTime()
    {
        return lastActiveTime;
    }

    public void setLastActiveTime(String lastActiveTime)
    {
        this.lastActiveTime = lastActiveTime;
    }

    public String getLastBetGap()
    {
        return lastBetGap;
    }

    public void setLastBetGap(String lastBetGap)
    {
        this.lastBetGap = lastBetGap;
    }
}
