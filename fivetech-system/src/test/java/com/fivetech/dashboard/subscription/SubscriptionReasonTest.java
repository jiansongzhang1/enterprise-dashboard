package com.fivetech.dashboard.subscription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.fivetech.common.lark.LarkErrorCategory;
import com.fivetech.common.lark.LarkResult;

/** 发送历史的原因代码：Lark 失败 → 原因、是否重试、汇总结果 */
class SubscriptionReasonTest
{
    @Test
    void fromLarkResult()
    {
        assertNull(SubscriptionReason.of(LarkResult.ok("om_1", 200)));
        assertEquals(SubscriptionReason.CRED, SubscriptionReason.of(LarkResult.fail(LarkErrorCategory.CREDENTIAL_INVALID, 99991663, "x", 400)));
        assertEquals(SubscriptionReason.GONE, SubscriptionReason.of(LarkResult.fail(LarkErrorCategory.TARGET_INVALID, 230002, "x", 400)));
        assertEquals(SubscriptionReason.LIMIT, SubscriptionReason.of(LarkResult.fail(LarkErrorCategory.RETRYABLE, null, "x", 429)));
        assertEquals(SubscriptionReason.LIMIT, SubscriptionReason.of(LarkResult.fail(LarkErrorCategory.RETRYABLE, 230020, "x", 200)));
        assertEquals(SubscriptionReason.SERVER, SubscriptionReason.of(LarkResult.fail(LarkErrorCategory.RETRYABLE, null, "x", 502)));
        assertEquals(SubscriptionReason.TIMEOUT, SubscriptionReason.of(LarkResult.fail(LarkErrorCategory.RETRYABLE, null, "x", 0)));
        assertEquals(SubscriptionReason.REJECTED, SubscriptionReason.of(LarkResult.fail(LarkErrorCategory.RETRYABLE, 99992361, "x", 400)));
    }

    @Test
    void onlyTransientFailuresRetry()
    {
        assertTrue(SubscriptionReason.TIMEOUT.retryable());
        assertTrue(SubscriptionReason.LIMIT.retryable());
        assertTrue(SubscriptionReason.SERVER.retryable());
        assertFalse(SubscriptionReason.GONE.retryable());
        assertFalse(SubscriptionReason.REJECTED.retryable());
        assertFalse(SubscriptionReason.CRED.retryable());
    }

    @Test
    void resultsAndTexts()
    {
        assertEquals("OK", SubscriptionReason.aggregate(List.of("OK", "OK")));
        assertEquals("PART", SubscriptionReason.aggregate(List.of("OK", "FAIL")));
        assertEquals("SKIP", SubscriptionReason.aggregate(List.of("SKIP", "SKIP")));
        assertEquals("FAIL", SubscriptionReason.aggregate(List.of()));
        assertEquals("SKIPPED", SubscriptionReason.lastStatus("SKIP"));
        assertEquals("PARTIAL", SubscriptionReason.lastStatus("PART"));
        assertEquals("部分失敗", SubscriptionReason.resultText("PART"));
        assertEquals("已跳過", SubscriptionReason.CRED.getResult().equals("SKIP") ? "已跳過" : "");
        assertEquals(SubscriptionReason.SHOT, SubscriptionReason.of("SHOT"));
        assertEquals("頁面截圖沒有生成，本次所有群都未發送", SubscriptionReason.SHOT.getNote());
        assertNull(SubscriptionReason.of("nope"));
    }
}
