package com.fivetech.dashboard.domain.query;

import jakarta.validation.constraints.Pattern;

/**
 * 存款明细查询入参（对齐原型 MVP-V1.0「明细查询 · 存款订单」）。
 * <p>
 * 时间范围用父类的 {@code from / to}（按天）或 {@code slotFrom / slotTo}（到小时，左闭右开），
 * 按<b>创建时间</b>筛选。关键字搜索 订单号 / 用户ID / 账号名称。
 * <p>
 * 从「存款总额」指标下钻时前端预置 {@code status=succ}：存款总额只计成功单，
 * 这样下钻出来的笔数与金额才对得上。
 *
 * @author fivetech
 */
public class DepositRecordQuery extends BaseRecordQuery
{
    private static final long serialVersionUID = 1L;

    /**
     * 订单状态：succ 成功（UDS status = 30）/ fail 失败（其余状态一律视为失败）。
     * <p>兼容前端直接传 UDS 原始状态码：30 → succ，其他数字 → fail（见 {@link #setStatus}）。</p>
     */
    @Pattern(regexp = "^(succ|fail)?$", message = "status 取值应为 succ/fail（或 UDS 状态码，30 = 成功）")
    private String status;

    public String getStatus()
    {
        return status;
    }

    public void setStatus(String status)
    {
        this.status = normalizeStatus(status);
    }

    /** 原始状态码转成本系统取值：30 → succ，其他整数 → fail；succ/fail 与空值原样返回 */
    static String normalizeStatus(String status)
    {
        if (status == null)
        {
            return null;
        }
        String s = status.trim();
        if (s.matches("-?\\d+"))
        {
            return "30".equals(s) ? "succ" : "fail";
        }
        return s;
    }
}
