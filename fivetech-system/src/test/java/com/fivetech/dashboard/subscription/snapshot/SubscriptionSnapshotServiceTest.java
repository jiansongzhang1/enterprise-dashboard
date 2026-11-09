package com.fivetech.dashboard.subscription.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.subscription.SubscriptionRange;

/** 截图页地址：按统计范围生成、用户填的查询条件、占位符 */
class SubscriptionSnapshotServiceTest
{
    static final String BASE = "https://d3ajrxvlugsarb.cloudfront.net/zh-TW/overview";

    static final LocalDateTime D1 = LocalDateTime.of(2026, 11, 2, 0, 0);

    static final LocalDateTime D9 = LocalDateTime.of(2026, 11, 9, 0, 0);

    @Test
    void defaultQueryFollowsSubscriptionRange()
    {
        assertEquals(BASE + "?range=today&gran=hour&cmp=previous-period",
            SubscriptionSnapshotService.pageUrl(BASE, null, SubscriptionRange.TODAY, "SITE-IN", D9, D9.plusHours(9)));
        assertEquals(BASE + "?range=yesterday&gran=hour&cmp=previous-period",
            SubscriptionSnapshotService.pageUrl(BASE, "", SubscriptionRange.YESTERDAY, "SITE-IN", D9.minusDays(1), D9));
        // 近 7 日：不含发送当天，to 为最后一天（与卡片同一区间）
        assertEquals(BASE + "?range=custom&gran=day&from=2026-11-02&to=2026-11-08&cmp=previous-period",
            SubscriptionSnapshotService.pageUrl(BASE, null, SubscriptionRange.LAST_7D, "SITE-IN", D1, D9));
    }

    @Test
    void customQueryAndPlaceholders()
    {
        assertEquals(BASE + "?range=last-7-days&gran=day&cmp=previous-period",
            SubscriptionSnapshotService.pageUrl(BASE, "range=last-7-days&gran=day&cmp=previous-period", SubscriptionRange.TODAY, "SITE-IN", D1, D9));
        assertEquals(BASE + "?site=SITE-IN&from=2026-11-02&to=2026-11-08",
            SubscriptionSnapshotService.pageUrl(BASE, "site={site}&from={from}&to={to}", SubscriptionRange.LAST_7D, "SITE-IN", D1, D9));
        assertEquals("https://x/SITE-IN/overview?a=1&range=today&gran=hour&cmp=previous-period",
            SubscriptionSnapshotService.pageUrl("https://x/{site}/overview?a=1", null, SubscriptionRange.TODAY, "SITE-IN", D9, D9));
        assertThrows(IllegalArgumentException.class,
            () -> SubscriptionSnapshotService.pageUrl("", null, SubscriptionRange.TODAY, "SITE-IN", D9, D9));
    }

    @Test
    void normalizeAcceptsWholeUrlOrQueryAndRejectsOddCharacters()
    {
        assertEquals("range=custom&gran=day&from=2026-10-09&to=2026-10-23&cmp=previous-period",
            SubscriptionSnapshotService.normalizeQuery(
                "https://d3ajrxvlugsarb.cloudfront.net/zh-TW/overview?range=custom&gran=day&from=2026-10-09&to=2026-10-23&cmp=previous-period"));
        assertEquals("range=today&gran=hour", SubscriptionSnapshotService.normalizeQuery("?range=today&gran=hour#top"));
        assertEquals("", SubscriptionSnapshotService.normalizeQuery("  "));
        assertEquals("", SubscriptionSnapshotService.normalizeQuery("https://x/zh-TW/overview"));
        assertNull(SubscriptionSnapshotService.normalizeQuery("range=today\"><script>"));
        assertNull(SubscriptionSnapshotService.normalizeQuery("a=" + "x".repeat(600)));
    }
}
