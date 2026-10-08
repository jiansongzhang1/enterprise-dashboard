package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 存款明细行。一行一笔存款订单。
 * <p>
 * 金额是订单<b>原币种</b>金额（INR / USD / USDT），不做折算，币种看同一行的 currency。
 * 无值一律 null，前端显示「—」。
 *
 * @author fivetech
 */
public class DepositRecordVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 订单号 */
    private String orderNo;

    /** 用户ID */
    private String userId;

    /** 账号名称 */
    private String username;

    /** 存款金额（原币种） */
    private BigDecimal amount;

    /** 币种 INR / USD / USDT */
    private String currency;

    /** 状态编码 succ / fail / pend */
    private String status;

    /** 状态名称 */
    private String statusLabel;

    /** 创建时间 yyyy-MM-dd HH:mm */
    private String createTime;

    /** 完成时间；处理中为 null */
    private String finishTime;

    /** 耗时（分钟，一位小数）= 完成时间 − 创建时间；仅终态有值 */
    private BigDecimal costMinutes;

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

    public BigDecimal getAmount()
    {
        return amount;
    }

    public void setAmount(BigDecimal amount)
    {
        this.amount = com.fivetech.dashboard.format.MoneyScale.of(amount);
    }

    public String getCurrency()
    {
        return currency;
    }

    public void setCurrency(String currency)
    {
        this.currency = currency;
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

    public String getCreateTime()
    {
        return createTime;
    }

    public void setCreateTime(String createTime)
    {
        this.createTime = createTime;
    }

    public String getFinishTime()
    {
        return finishTime;
    }

    public void setFinishTime(String finishTime)
    {
        this.finishTime = finishTime;
    }

    public BigDecimal getCostMinutes()
    {
        return costMinutes;
    }

    public void setCostMinutes(BigDecimal costMinutes)
    {
        this.costMinutes = costMinutes == null ? null : costMinutes.setScale(1, java.math.RoundingMode.HALF_UP);
    }
}
