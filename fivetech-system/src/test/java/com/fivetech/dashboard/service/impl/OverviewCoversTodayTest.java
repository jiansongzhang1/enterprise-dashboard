package com.fivetech.dashboard.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.enums.Granularity;

/** anomalies.inScope / baselineSeries 用的「区间恰好是今天」 */
class OverviewCoversTodayTest
{
    /** 现在 2026-10-22 11:25 → 当前整点 11:00 */
    private static final LocalDateTime NOW_HOUR = LocalDateTime.of(2026, 10, 22, 11, 0);

    private static final LocalDateTime TODAY = LocalDateTime.of(2026, 10, 22, 0, 0);

    private static boolean hour(LocalDateTime from, LocalDateTime to)
    {
        LocalDateTime effective = to.isAfter(NOW_HOUR) ? NOW_HOUR : to;
        return OverviewServiceImpl.coversToday(from, to, effective, Granularity.HOUR, NOW_HOUR);
    }

    @Test
    void hourlyTodayEndingAtCurrentHourIsToday()
    {
        // 前端传当前整点 11:00（问题请求）
        assertTrue(hour(TODAY, NOW_HOUR));
    }

    @Test
    void hourlyTodayEndingTomorrowOrEarlierHourIsToday()
    {
        assertTrue(hour(TODAY, TODAY.plusDays(1)));
        assertTrue(hour(TODAY, TODAY.plusHours(6)), "小时粒度截止今天时终点按当前整点处理");
    }

    @Test
    void otherRangesAreNotToday()
    {
        assertFalse(hour(TODAY.minusDays(1), NOW_HOUR), "跨昨天");
        assertFalse(hour(TODAY.plusHours(1), NOW_HOUR), "起点不是 00:00");
        assertFalse(hour(TODAY.minusDays(1), TODAY), "昨天");
        assertFalse(hour(TODAY, TODAY.plusDays(1).plusHours(1)), "超过明天 00:00");
    }

    @Test
    void dayGranularity()
    {
        assertTrue(OverviewServiceImpl.coversToday(TODAY, TODAY.plusDays(1), NOW_HOUR, Granularity.DAY, NOW_HOUR));
        assertTrue(OverviewServiceImpl.coversToday(TODAY, NOW_HOUR, NOW_HOUR, Granularity.DAY, NOW_HOUR));
        assertFalse(OverviewServiceImpl.coversToday(TODAY, TODAY.plusHours(6), TODAY.plusHours(6), Granularity.DAY, NOW_HOUR),
                "日粒度只到 06:00，不是整个今天");
    }
}
