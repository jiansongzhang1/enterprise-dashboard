package com.fivetech.monitor.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 回测预览的百分位与取整 */
class MonitorPreviewMathTest
{
    private static List<BigDecimal> range(int n)
    {
        List<BigDecimal> list = new ArrayList<>();
        for (int i = n; i >= 1; i--)
        {
            list.add(BigDecimal.valueOf(i));
        }
        return list;
    }

    @Test
    void nearestRankDoesNotInterpolate()
    {
        List<BigDecimal> v = range(101);   // 1..101，乱序传入
        assertEquals(0, BigDecimal.valueOf(51).compareTo(MonitorPreviewService.nearestRank(v, 0.50)));
        assertEquals(0, BigDecimal.valueOf(6).compareTo(MonitorPreviewService.nearestRank(v, 0.05)));
        assertEquals(0, BigDecimal.valueOf(96).compareTo(MonitorPreviewService.nearestRank(v, 0.95)));
        // n = 10：floor(0.95 × 9) = 8 → 第 9 小的值，不插值
        assertEquals(0, BigDecimal.valueOf(9).compareTo(MonitorPreviewService.nearestRank(range(10), 0.95)));
        assertEquals(0, BigDecimal.ONE.compareTo(MonitorPreviewService.nearestRank(range(10), 0.05)));
        assertEquals(0, BigDecimal.ONE.compareTo(MonitorPreviewService.nearestRank(range(2), 0.50)));
        assertNull(MonitorPreviewService.nearestRank(List.of(), 0.5));
    }

    @Test
    void roundByFormat()
    {
        assertEquals("97.42", MonitorPreviewService.round("PCT", new BigDecimal("97.4166")).toPlainString());
        assertEquals("5.3", MonitorPreviewService.round("MIN", new BigDecimal("5.26")).toPlainString());
        assertEquals("40000", MonitorPreviewService.round("MONEY", new BigDecimal("40000.0000")).toPlainString());
    }
}
