package com.fivetech.promo.budget;

import java.util.List;

/**
 * 预算与消耗的写入（FY-56 R9：有效行一次写入，同一「日期 × 推广渠道」已有值直接覆盖）。
 *
 * <p>数据存放在 UDS 侧，本系统不落库、不存上传的文件。UDS 写入接口还没有，目前只有
 * {@link NoopBudgetSpendWriter}（不写，返回 false）；接好后提供一个新的实现替换它即可，校验与结果页不变。</p>
 *
 * @author fivetech
 */
public interface BudgetSpendWriter
{
    /**
     * @param siteCode 站点编码
     * @param kind     消耗 / 预算
     * @param records  通过校验的有效行（不为空）
     * @param operator 操作人
     * @return true = 已写入；false = 没有写入（未接入）
     */
    boolean write(String siteCode, BudgetKind kind, List<BudgetImportValidator.Record> records, String operator);

    /** 是否已接入写入（前端据此提示「本次未寫入」） */
    boolean isEnabled();
}
