package com.fivetech.promo.budget;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * UDS 写入接入之前的占位实现：只记日志，不写任何数据（本系统也不落库）。
 * 接入 UDS 后新增的实现加 {@code @Primary}（或删掉本类）即可替换。
 *
 * @author fivetech
 */
@Component
public class NoopBudgetSpendWriter implements BudgetSpendWriter
{
    private static final Logger log = LoggerFactory.getLogger(NoopBudgetSpendWriter.class);

    @Override
    public boolean write(String siteCode, BudgetKind kind, List<BudgetImportValidator.Record> records, String operator)
    {
        log.info("[promo-budget] UDS 写入未接入，跳过：site={} kind={} rows={} operator={}", siteCode, kind, records.size(), operator);
        return false;
    }

    @Override
    public boolean isEnabled()
    {
        return false;
    }
}
