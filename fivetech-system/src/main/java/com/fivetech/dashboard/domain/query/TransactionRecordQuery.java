package com.fivetech.dashboard.domain.query;

import com.fivetech.dashboard.enums.TransactionType;

/**
 * 交易明细查询入参。存款与提款合并为一张表。
 *
 * @author fivetech
 */
public class TransactionRecordQuery extends BaseRecordQuery
{
    private static final long serialVersionUID = 1L;

    /** 交易方向。null 表示存提都要 */
    private TransactionType type;

    /**
     * 交易状态。存款：succ / fail / pend；
     * 提款：succ / fail / auditing / paying / rejected
     */
    private String status;

    /** 支付通道，如 UPI-Fast / IMPS-B / USDT-TRC20 */
    private String channel;

    /** 审核状态 pass / pending / reject。仅提款有值 */
    private String auditStatus;

    /**
     * 时间字段口径：CREATE 按创建时间，FINISH 按完成时间。
     * 该口径尚未拍板，默认沿用原型的创建时间，见设计方案待确认第 2 条。
     */
    private String timeField = "CREATE";

    public TransactionType getType()
    {
        return type;
    }

    public void setType(TransactionType type)
    {
        this.type = type;
    }

    public String getStatus()
    {
        return status;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public String getChannel()
    {
        return channel;
    }

    public void setChannel(String channel)
    {
        this.channel = channel;
    }

    public String getAuditStatus()
    {
        return auditStatus;
    }

    public void setAuditStatus(String auditStatus)
    {
        this.auditStatus = auditStatus;
    }

    public String getTimeField()
    {
        return timeField;
    }

    public void setTimeField(String timeField)
    {
        this.timeField = timeField;
    }
}
