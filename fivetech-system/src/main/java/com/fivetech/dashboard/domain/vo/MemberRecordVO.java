package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 会员明细一行（对齐原型 MVP-V1.0「会员明细」页，27 列）。
 * <p>
 * 后端始终返回全部字段；用户在页面上自选显示哪些列（本机记忆），列定义见 {@code columns}。
 * 无数据的字段为 null，不要当作 0。
 *
 * @author fivetech
 */
public class MemberRecordVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 用户ID */
    private String userId;

    /** 账号名称 */
    private String username;

    /** 用户状态编码（account_status）：1 启用 / 0 禁用 */
    private String status;

    /** 用户状态名称 */
    private String statusLabel;

    /** 用户类型编码 real/trial/test/agent */
    private String userType;

    /** 用户类型名称 */
    private String userTypeLabel;

    /** 用户等级编码（player_level）：0 老铁 / 1 青铜 / 2 白银 / 3 黄金 / 4 铂金1 / 5 铂金2 */
    private String level;

    /** 用户等级名称 */
    private String levelLabel;

    /** 国家代码 */
    private String country;

    /** 国家名称 */
    private String countryLabel;

    /** 注册时间 */
    private String registerTime;

    /** 首存时间；未首存为 null */
    private String firstDepositTime;

    /** 首存金额 */
    private BigDecimal firstDepositAmount;

    /** 首存通道 */
    private String firstDepositChannel;

    /** 注册→首存时长（小时） */
    private Integer regToFtdHours;

    /** 最近存款时间 */
    private String lastDepositTime;

    /** 最近存款金额 */
    private BigDecimal lastDepositAmount;

    /** 历史累计存款金额 */
    private BigDecimal cumulativeDepositAmount;

    /** 历史累计存款笔数 */
    private Long cumulativeDepositCount;

    /** 最近投注时间 */
    private String lastBetTime;

    /** 最近投注金额 */
    private BigDecimal lastBetAmount;

    /** 历史累计投注金额 */
    private BigDecimal cumulativeBetAmount;

    /** 历史累计投注笔数 */
    private Long cumulativeBetCount;

    /** 流水倍数 = 累计投注 ÷ 累计存款 */
    private BigDecimal turnoverMultiple;

    /** 最近提款时间 */
    private String lastWithdrawTime;

    /** 最近提款金额 */
    private BigDecimal lastWithdrawAmount;

    /** 历史累计提款金额 */
    private BigDecimal cumulativeWithdrawAmount;

    /** 历史累计提款笔数 */
    private Long cumulativeWithdrawCount;

    /** 历史累计GGR */
    private BigDecimal cumulativeGgr;

    /** 历史累计NGR */
    private BigDecimal cumulativeNgr;

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

    public String getStatus()
    {
        return status;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public String getStatusLabel()
    {
        return statusLabel;
    }

    public void setStatusLabel(String statusLabel)
    {
        this.statusLabel = statusLabel;
    }

    public String getUserType()
    {
        return userType;
    }

    public void setUserType(String userType)
    {
        this.userType = userType;
    }

    public String getUserTypeLabel()
    {
        return userTypeLabel;
    }

    public void setUserTypeLabel(String userTypeLabel)
    {
        this.userTypeLabel = userTypeLabel;
    }

    public String getLevel()
    {
        return level;
    }

    public void setLevel(String level)
    {
        this.level = level;
    }

    public String getLevelLabel()
    {
        return levelLabel;
    }

    public void setLevelLabel(String levelLabel)
    {
        this.levelLabel = levelLabel;
    }

    public String getCountry()
    {
        return country;
    }

    public void setCountry(String country)
    {
        this.country = country;
    }

    public String getCountryLabel()
    {
        return countryLabel;
    }

    public void setCountryLabel(String countryLabel)
    {
        this.countryLabel = countryLabel;
    }

    public String getRegisterTime()
    {
        return registerTime;
    }

    public void setRegisterTime(String registerTime)
    {
        this.registerTime = registerTime;
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
        this.firstDepositAmount = com.fivetech.dashboard.format.MoneyScale.of(firstDepositAmount);
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

    public String getLastDepositTime()
    {
        return lastDepositTime;
    }

    public void setLastDepositTime(String lastDepositTime)
    {
        this.lastDepositTime = lastDepositTime;
    }

    public BigDecimal getLastDepositAmount()
    {
        return lastDepositAmount;
    }

    public void setLastDepositAmount(BigDecimal lastDepositAmount)
    {
        this.lastDepositAmount = com.fivetech.dashboard.format.MoneyScale.of(lastDepositAmount);
    }

    public BigDecimal getCumulativeDepositAmount()
    {
        return cumulativeDepositAmount;
    }

    public void setCumulativeDepositAmount(BigDecimal cumulativeDepositAmount)
    {
        this.cumulativeDepositAmount = com.fivetech.dashboard.format.MoneyScale.of(cumulativeDepositAmount);
    }

    public Long getCumulativeDepositCount()
    {
        return cumulativeDepositCount;
    }

    public void setCumulativeDepositCount(Long cumulativeDepositCount)
    {
        this.cumulativeDepositCount = cumulativeDepositCount;
    }

    public String getLastBetTime()
    {
        return lastBetTime;
    }

    public void setLastBetTime(String lastBetTime)
    {
        this.lastBetTime = lastBetTime;
    }

    public BigDecimal getLastBetAmount()
    {
        return lastBetAmount;
    }

    public void setLastBetAmount(BigDecimal lastBetAmount)
    {
        this.lastBetAmount = com.fivetech.dashboard.format.MoneyScale.of(lastBetAmount);
    }

    public BigDecimal getCumulativeBetAmount()
    {
        return cumulativeBetAmount;
    }

    public void setCumulativeBetAmount(BigDecimal cumulativeBetAmount)
    {
        this.cumulativeBetAmount = com.fivetech.dashboard.format.MoneyScale.of(cumulativeBetAmount);
    }

    public Long getCumulativeBetCount()
    {
        return cumulativeBetCount;
    }

    public void setCumulativeBetCount(Long cumulativeBetCount)
    {
        this.cumulativeBetCount = cumulativeBetCount;
    }

    public BigDecimal getTurnoverMultiple()
    {
        return turnoverMultiple;
    }

    public void setTurnoverMultiple(BigDecimal turnoverMultiple)
    {
        this.turnoverMultiple = turnoverMultiple;
    }

    public String getLastWithdrawTime()
    {
        return lastWithdrawTime;
    }

    public void setLastWithdrawTime(String lastWithdrawTime)
    {
        this.lastWithdrawTime = lastWithdrawTime;
    }

    public BigDecimal getLastWithdrawAmount()
    {
        return lastWithdrawAmount;
    }

    public void setLastWithdrawAmount(BigDecimal lastWithdrawAmount)
    {
        this.lastWithdrawAmount = com.fivetech.dashboard.format.MoneyScale.of(lastWithdrawAmount);
    }

    public BigDecimal getCumulativeWithdrawAmount()
    {
        return cumulativeWithdrawAmount;
    }

    public void setCumulativeWithdrawAmount(BigDecimal cumulativeWithdrawAmount)
    {
        this.cumulativeWithdrawAmount = com.fivetech.dashboard.format.MoneyScale.of(cumulativeWithdrawAmount);
    }

    public Long getCumulativeWithdrawCount()
    {
        return cumulativeWithdrawCount;
    }

    public void setCumulativeWithdrawCount(Long cumulativeWithdrawCount)
    {
        this.cumulativeWithdrawCount = cumulativeWithdrawCount;
    }

    public BigDecimal getCumulativeGgr()
    {
        return cumulativeGgr;
    }

    public void setCumulativeGgr(BigDecimal cumulativeGgr)
    {
        this.cumulativeGgr = com.fivetech.dashboard.format.MoneyScale.of(cumulativeGgr);
    }

    public BigDecimal getCumulativeNgr()
    {
        return cumulativeNgr;
    }

    public void setCumulativeNgr(BigDecimal cumulativeNgr)
    {
        this.cumulativeNgr = com.fivetech.dashboard.format.MoneyScale.of(cumulativeNgr);
    }
}
