package com.fivetech.dashboard.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.domain.vo.OverviewSlotVO;
import com.fivetech.dashboard.enums.Granularity;

/** 日 / 周粒度：终点截到当前整点时，今天（不完整的一天）也算一格 */
class OverviewDayPointsTest
{
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 11, 5, 0, 0);

    /** 近 7 天，现在 11-11 14:25 → 终点截到 15:00 */
    private static final LocalDateTime TO = LocalDateTime.of(2026, 11, 11, 15, 0);

    @Test
    void lastSevenDaysIncludeToday()
    {
        assertEquals(7, OverviewServiceImpl.countPoints(FROM, TO, Granularity.DAY));
        OverviewSlotVO slot = OverviewServiceImpl.buildSlot(FROM, TO, Granularity.DAY);
        assertEquals(7, slot.getPoints());
        assertEquals("2026-11-05", slot.getLabels().get(0));
        assertEquals("2026-11-11", slot.getLabels().get(6));
    }

    @Test
    void wholeDaysUnchanged()
    {
        assertEquals(7, OverviewServiceImpl.countPoints(FROM, FROM.plusDays(7), Granularity.DAY));
        assertEquals(1, OverviewServiceImpl.countPoints(FROM, FROM.plusDays(7), Granularity.WEEK));
    }

    @Test
    void weekLabelEndsOnToday()
    {
        LocalDateTime from = LocalDateTime.of(2026, 10, 29, 0, 0);
        OverviewSlotVO slot = OverviewServiceImpl.buildSlot(from, TO, Granularity.WEEK);
        assertEquals(2, slot.getPoints());
        assertEquals("11-05 – 11-11", slot.getLabels().get(1));
    }
}
