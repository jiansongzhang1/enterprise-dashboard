package com.fivetech.dashboard.subscription.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.subscription.SubscriptionRange;

/** 截图页地址与 localStorage overview-filters：按统计范围生成、用户填的筛选条件、占位符、校验 */
class SubscriptionSnapshotServiceTest
{
    static final LocalDateTime D1 = LocalDateTime.of(2026, 11, 2, 0, 0);

    static final LocalDateTime D9 = LocalDateTime.of(2026, 11, 9, 0, 0);

    @Test
    void pageUrlIsBaseWithSite()
    {
        assertEquals("https://x.cloudfront.net/zh-TW/overview",
            SubscriptionSnapshotService.pageUrl(" https://x.cloudfront.net/zh-TW/overview ", "SITE-IN"));
        assertEquals("https://x/SITE-IN/overview", SubscriptionSnapshotService.pageUrl("https://x/{site}/overview", "SITE-IN"));
        assertThrows(IllegalArgumentException.class, () -> SubscriptionSnapshotService.pageUrl("", "SITE-IN"));
    }

    @Test
    void defaultFiltersFollowSubscriptionRange()
    {
        assertEquals("{\"range\":\"today\",\"gran\":\"hour\",\"cmp\":\"previous-period\"}",
            SubscriptionSnapshotService.filters(null, SubscriptionRange.TODAY, D9, D9.plusHours(9)));
        assertEquals("{\"range\":\"yesterday\",\"gran\":\"hour\",\"cmp\":\"previous-period\"}",
            SubscriptionSnapshotService.filters("", SubscriptionRange.YESTERDAY, D9.minusDays(1), D9));
        // 近 7 日：不含发送当天，to 为最后一天（与卡片同一区间）
        assertEquals("{\"range\":\"custom\",\"gran\":\"day\",\"from\":\"2026-11-02\",\"to\":\"2026-11-08\",\"cmp\":\"previous-period\"}",
            SubscriptionSnapshotService.filters(null, SubscriptionRange.LAST_7D, D1, D9));
    }

    @Test
    void storedFiltersWithPlaceholders()
    {
        assertEquals("{\"range\":\"custom\",\"gran\":\"day\",\"from\":\"2026-11-02\",\"to\":\"2026-11-08\"}",
            SubscriptionSnapshotService.filters("{\"range\":\"custom\",\"gran\":\"day\",\"from\":\"{from}\",\"to\":\"{to}\"}",
                SubscriptionRange.LAST_7D, D1, D9));
        assertEquals("{\"range\":\"last-7-days\",\"gran\":\"day\",\"cmp\":\"previous-period\"}",
            SubscriptionSnapshotService.filters("{\"range\":\"last-7-days\",\"gran\":\"day\",\"cmp\":\"previous-period\"}",
                SubscriptionRange.TODAY, D9, D9));
    }

    @Test
    void normalizeAcceptsObjectJsonOrUrlAndRejectsBadInput()
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("range", "custom");
        m.put("gran", "day");
        m.put("from", "2026-10-08");
        m.put("to", "2026-10-16");
        m.put("cmp", "previous-period");
        assertEquals("{\"range\":\"custom\",\"gran\":\"day\",\"from\":\"2026-10-08\",\"to\":\"2026-10-16\",\"cmp\":\"previous-period\"}",
            SubscriptionSnapshotService.normalizeFilters(m));
        assertEquals("{\"range\":\"yesterday\",\"gran\":\"day\",\"cmp\":\"previous-period\"}",
            SubscriptionSnapshotService.normalizeFilters(" { \"range\": \"yesterday\", \"gran\": \"day\", \"cmp\": \"previous-period\" } "));
        assertEquals("{\"range\":\"last-7-days\",\"gran\":\"day\",\"cmp\":\"previous-period\"}",
            SubscriptionSnapshotService.normalizeFilters(
                "https://d3ajrxvlugsarb.cloudfront.net/zh-TW/overview?range=last-7-days&gran=day&cmp=previous-period"));
        assertEquals("{\"range\":\"custom\",\"from\":\"{from}\",\"to\":\"{to}\"}",
            SubscriptionSnapshotService.normalizeFilters("{\"range\":\"custom\",\"from\":\"{from}\",\"to\":\"{to}\"}"));
        assertEquals("", SubscriptionSnapshotService.normalizeFilters("  "));
        assertEquals("", SubscriptionSnapshotService.normalizeFilters(null));
        // 没有 range、自定义缺日期、值里有奇怪字符、嵌套对象、不是 JSON
        assertNull(SubscriptionSnapshotService.normalizeFilters("{\"gran\":\"day\"}"));
        assertNull(SubscriptionSnapshotService.normalizeFilters("{\"range\":\"custom\",\"from\":\"2026-10-08\"}"));
        assertNull(SubscriptionSnapshotService.normalizeFilters("{\"range\":\"custom\",\"from\":\"10/08\",\"to\":\"2026-10-16\"}"));
        assertNull(SubscriptionSnapshotService.normalizeFilters("{\"range\":\"today<script>\"}"));
        assertNull(SubscriptionSnapshotService.normalizeFilters("{\"range\":\"today\",\"x\":{\"a\":1}}"));
        assertNull(SubscriptionSnapshotService.normalizeFilters("{range:today"));
    }
}
