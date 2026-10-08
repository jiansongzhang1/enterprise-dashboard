package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 提款明细行。一行一笔提款订单。
 * <p>
 * USDT 走链上，没有银行信息，bankName / bankCode / ifsc 为 null；
 * payer / payTime 只有进入资金环节（成功、失败、出款中）才有值。
 *
 * @author fivetech
 */
public class WithdrawRecordVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 订单号 */
    private String orderNo;

    /** 用户ID */
    private String userId;

    /** 账号名称 */
    private String username;

    /** 提款金额（原币种） */
    private BigDecimal amount;

    /** 币种 INR / USD / USDT */
    private String currency;

    /** 状态编码 succ 成功（UDS status = 30）/ fail 失败（其余状态） */
    private String status;

    /** 状态名称 */
    private String statusLabel;

    /** 风控审核人；待审为 null */
    private String auditor;

    /** 资金审批人 */
    private String payer;

    /** 资金操作时间 */
    private String payTime;

    /** 银行名称 */
    private String bankName;

    /** 银行代码（SWIFT） */
    private String bankCode;

    /** 收款银行 IFSC 编码（印度银行分行代码）；USDT 等无银行信息时为 null */
    private String ifsc;

    /** 创建时间 */
    private String createTime;

    /** 完成时间；未到终态为 null */
    private String finishTime;

    /** 耗时（分钟）= 完成时间 − 创建时间，含审核等待；仅终态有值 */
    private BigDecimal costMinutes;

    /** 审核备注（长文本） */
    private String auditNote;

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

    public String getAuditor()
    {
        return auditor;
    }

    public void setAuditor(String auditor)
    {
        this.auditor = auditor;
    }

    public String getPayer()
    {
        return payer;
    }

    public void setPayer(String payer)
    {
        this.payer = payer;
    }

    public String getPayTime()
    {
        return payTime;
    }

    public void setPayTime(String payTime)
    {
        this.payTime = payTime;
    }

    public String getBankName()
    {
        return bankName;
    }

    public void setBankName(String bankName)
    {
        this.bankName = bankName;
    }

    public String getBankCode()
    {
        return bankCode;
    }

    public void setBankCode(String bankCode)
    {
        this.bankCode = bankCode;
    }

    public String getIfsc()
    {
        return ifsc;
    }

    public void setIfsc(String ifsc)
    {
        this.ifsc = ifsc;
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

    public String getAuditNote()
    {
        return auditNote;
    }

    public void setAuditNote(String auditNote)
    {
        this.auditNote = auditNote;
    }
}
