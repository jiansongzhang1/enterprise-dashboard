package com.fivetech.promo.budget;

import java.util.List;

/**
 * 把校验通过的消耗 / 预算同步写到数据平台（导入请求里写完再返回结果）。实现见 {@link CollectBudgetSpendWriter}。
 *
 * @author fivetech
 */
public interface BudgetSpendWriter
{
    /** 是否已开启写入（前端据此提示「本次未寫入」） */
    boolean isEnabled();

    /**
     * 提交一次上传。
     *
     * @param siteCode 站点编码
     * @param kind     消耗 / 预算
     * @param records  通过校验的有效行（不为空）
     * @param operator 操作人
     */
    BudgetWriteOutcome write(String siteCode, BudgetKind kind, List<BudgetImportValidator.Record> records, String operator);

    /**
     * 接着上一次没完成的提交：CONFIRM_REQUIRED 时 confirm = true 确认覆盖、false 取消；PROCESSING 时用同一 key、同一请求体再发查结果。
     *
     * @param token    上一次结果里的 token
     * @param confirm  是否确认覆盖
     * @param siteCode 当前站点（须与上次一致）
     * @param operator 操作人
     */
    BudgetWriteOutcome resume(String token, boolean confirm, String siteCode, String operator);
}
