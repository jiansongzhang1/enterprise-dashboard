package com.fivetech.dashboard.domain.query;

import jakarta.validation.constraints.Pattern;

/**
 * 提款明细查询入参（对齐原型 MVP-V1.0「明细查询 · 提款订单」）。
 * <p>
 * 时间范围按<b>创建时间</b>筛选，规则同存款明细。
 * 从「提款总额」指标下钻时前端预置 {@code status=succ}。
 *
 * @author fivetech
 */
public class WithdrawRecordQuery extends BaseRecordQuery
{
    private static final long serialVersionUID = 1L;

    /** 订单状态：succ 成功 / fail 失败 / auditing 待审核 / paying 出款中 / rejected 已驳回 */
    @Pattern(regexp = "^(succ|fail|auditing|paying|rejected)?$",
        message = "status 取值应为 succ/fail/auditing/paying/rejected")
    private String status;

    /** 风控审核状态：pass 已通过 / pending 待审 / reject 驳回 */
    @Pattern(regexp = "^(pass|pending|reject)?$", message = "auditStatus 取值应为 pass/pending/reject")
    private String auditStatus;

    public String getStatus()
    {
        return status;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public String getAuditStatus()
    {
        return auditStatus;
    }

    public void setAuditStatus(String auditStatus)
    {
        this.auditStatus = auditStatus;
    }
}
