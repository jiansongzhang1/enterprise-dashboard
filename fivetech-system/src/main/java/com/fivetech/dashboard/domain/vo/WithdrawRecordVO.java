package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 提款明细行。一行一笔提款订单。
 * <p>
 * USDT 走链上，没有银行信息，bankName / bankCode / bankCountry 为 null；
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

    /** 状态编码 succ / fail / auditing / paying / rejected */
    private String status;

    /** 状态名称 */
    private String statusLabel;

    /** 风控审核编码 pass / pending / reject */
    private String auditStatus;

    /** 审核状态名称 */
    private String auditStatusLabel;

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

    /** 银行所在国家代码 */
    private String bankCountry;

    /** 银行所在国家名称 */
    private String bankCountryLabel;

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
        this.amount = amount;
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

    public String getAuditStatus()
    {
        return auditStatus;
    }

    public void setAuditStatus(String auditStatus)
    {
        this.auditStatus = auditStatus;
    }

    public String getAuditStatusLabel()
    {
        return auditStatusLabel;
    }

    public void setAuditStatusLabel(String auditStatusLabel)
    {
        this.auditStatusLabel = auditStatusLabel;
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

    public String getBankCountry()
    {
        return bankCountry;
    }

    public void setBankCountry(String bankCountry)
    {
        this.bankCountry = bankCountry;
    }

    public String getBankCountryLabel()
    {
        return bankCountryLabel;
    }

    public void setBankCountryLabel(String bankCountryLabel)
    {
        this.bankCountryLabel = bankCountryLabel;
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
        this.costMinutes = costMinutes;
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
