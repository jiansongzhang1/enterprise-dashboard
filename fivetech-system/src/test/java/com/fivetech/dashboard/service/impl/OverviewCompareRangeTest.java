package com.fivetech.dashboard.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.enums.CompareType;

/**
 * POST /dashboard/metrics/overview 的对比区间：主区间含今天、终点被截到当前整点时，
 * 对比期是已经走完的完整时段，发给 UDS 不截断（调用方传补到今天 24:00 的终点 axisTo）。
 */
class OverviewCompareRangeTest
{
    /** 今天 2026-11-11，现在 17:xx → 主区间截到 18:00，axisTo = 明天 00:00 */
    private static final LocalDateTime TODAY = LocalDateTime.of(2026, 11, 11, 0, 0);

    private static final LocalDateTime TOMORROW = TODAY.plusDays(1);

    private static void assertRange(LocalDateTime[] r, LocalDateTime from, LocalDateTime to)
    {
        assertEquals(from, r[0], "对比期起点");
        assertEquals(to, r[1], "对比期终点");
    }

    @Test
    void todayComparesFullYesterday()
    {
        // 今天 00:00–18:00（axisTo 24:00）→ 昨天 00:00–24:00，不是昨天 00:00–18:00
        assertRange(OverviewServiceImpl.autoCompareRange(CompareType.PREV_PERIOD, TODAY, TOMORROW),
            TODAY.minusDays(1), TODAY);
    }

    @Test
    void lastSevenDaysIncludingTodayComparesFullSevenDays()
    {
        // 11-05 00:00 ~ 11-11 18:00（axisTo 11-12 00:00）→ 10-29 00:00 ~ 11-05 00:00
        LocalDateTime from = TODAY.minusDays(6);
        assertRange(OverviewServiceImpl.autoCompareRange(CompareType.PREV_PERIOD, from, TOMORROW),
            from.minusDays(7), from);
    }

    @Test
    void lastYearUsesFullEnd()
    {
        assertRange(OverviewServiceImpl.autoCompareRange(CompareType.LAST_YEAR, TODAY, TOMORROW),
            TODAY.minusYears(1), TOMORROW.minusYears(1));
    }

    @Test
    void pastRangesUnchanged()
    {
        // 昨天全天 → 前天全天
        assertRange(OverviewServiceImpl.autoCompareRange(CompareType.PREV_PERIOD, TODAY.minusDays(1), TODAY),
            TODAY.minusDays(2), TODAY.minusDays(1));
        // 同一天内的时段 → 前一天同一时段
        assertRange(OverviewServiceImpl.autoCompareRange(CompareType.PREV_PERIOD,
                TODAY.minusDays(1).plusHours(8), TODAY.minusDays(1).plusHours(12)),
            TODAY.minusDays(2).plusHours(8), TODAY.minusDays(2).plusHours(12));
        // 跨多天、已走完 → 等长紧邻
        assertRange(OverviewServiceImpl.autoCompareRange(CompareType.PREV_PERIOD, TODAY.minusDays(7), TODAY),
            TODAY.minusDays(14), TODAY.minusDays(7));
    }
}
