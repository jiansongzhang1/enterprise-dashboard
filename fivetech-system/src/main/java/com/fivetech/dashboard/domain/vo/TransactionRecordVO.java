package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 交易明细行。一行一笔资金流水，存提合并，由 type 区分方向。
 * <p>
 * 存款没有审核环节，审核相关字段为 null，前端显示「—」而不是隐藏列。
 *
 * @author fivetech
 */
public class TransactionRecordVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 订单号 */
    private String orderNo;

    /** 方向 DEPOSIT / WITHDRAW */
    private String type;

    /** 会员账号（脱敏后） */
    private String account;

    /** 金额 */
    private BigDecimal amount;

    /** 支付通道 */
    private String channel;

    /** 状态编码 */
    private String status;

    /** 状态名称 */
    private String statusLabel;

    /** 审核状态编码，仅提款有值 */
    private String auditStatus;

    /** 审核状态名称 */
    private String auditStatusLabel;

    /** 审核人 */
    private String auditor;

    /** 创建时间 */
    private String createTime;

    /** 完成时间。时间口径未拍板前，两个都返回，由前端按配置展示 */
    private String finishTime;

    /** 耗时（分钟）。失败与驳回单为 null */
    private BigDecimal costMinutes;

    public String getOrderNo()
    {
        return orderNo;
    }

    public void setOrderNo(String orderNo)
    {
        this.orderNo = orderNo;
    }

    public String getType()
    {
        return type;
    }

    public void setType(String type)
    {
        this.type = type;
    }

    public String getAccount()
    {
        return account;
    }

    public void setAccount(String account)
    {
        this.account = account;
    }

    public BigDecimal getAmount()
    {
        return amount;
    }

    public void setAmount(BigDecimal amount)
    {
        this.amount = amount;
    }

    public String getChannel()
    {
        return channel;
    }

    public void setChannel(String channel)
    {
        this.channel = channel;
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
}
