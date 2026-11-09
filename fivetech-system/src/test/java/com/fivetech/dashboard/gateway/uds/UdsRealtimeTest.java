package com.fivetech.dashboard.gateway.uds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** UDS 实时数据：含今日时 uds_hh 的 to 按当前时间向右取整 */
class UdsRealtimeTest
{
    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 16, 50);
    static final LocalDateTime TODAY = LocalDateTime.of(2026, 11, 6, 0, 0);

    @Test
    void ceilHour()
    {
        assertEquals(LocalDateTime.of(2026, 11, 6, 17, 0), UdsRealtime.ceilHour(NOW));
        assertEquals(LocalDateTime.of(2026, 11, 6, 17, 0), UdsRealtime.ceilHour(LocalDateTime.of(2026, 11, 6, 17, 0)));
        assertEquals(LocalDateTime.of(2026, 11, 7, 0, 0), UdsRealtime.ceilHour(LocalDateTime.of(2026, 11, 6, 23, 30)));
    }

    @Test
    void todayOrRangesReachingNowUseCeil()
    {
        LocalDateTime ceil = LocalDateTime.of(2026, 11, 6, 17, 0);
        // 今日（终点已截到 17:00）、今日到明天 00:00、近 7 天含今天：都发 17:00
        assertEquals(ceil, UdsRealtime.hourTo(ceil, ceil.minusHours(1), NOW));
        assertEquals(ceil, UdsRealtime.hourTo(TODAY.plusDays(1), TODAY.plusDays(1).minusHours(1), NOW));
        assertEquals(ceil, UdsRealtime.hourTo(TODAY.plusHours(16).plusMinutes(1), TODAY.plusHours(16), NOW));
    }

    @Test
    void pastRangesKeepLastIncludedHour()
    {
        // 昨天、今天到当前整点之前：照旧（最后一个包含的整点）
        assertEquals(TODAY.minusHours(1), UdsRealtime.hourTo(TODAY, TODAY.minusHours(1), NOW));
        LocalDateTime h16 = TODAY.plusHours(16);
        assertEquals(h16.minusHours(1), UdsRealtime.hourTo(h16, h16.minusHours(1), NOW));
        assertEquals(TODAY.minusHours(1), UdsRealtime.hourTo(TODAY, TODAY.minusHours(1), null));
    }
}
