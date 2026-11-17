package com.fivetech.monitor.cond;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 事件详情 series[] 的比较方式：前端直接读 op / unit 画阈值线，不再从 howText 推断 */
class CondSeriesCompareTest
{
    private static CondCondition cond(String how, String op, String unit, String low, String high)
    {
        CondCondition c = new CondCondition();
        c.setHow(how);
        c.setOp(op);
        c.setUnit(unit);
        c.setLow(low == null ? null : new BigDecimal(low));
        c.setHigh(high == null ? null : new BigDecimal(high));
        return c;
    }

    @Test
    void changeConditionCarriesOpAndUnit()
    {
        Map<String, Object> x = new LinkedHashMap<>();
        CondEventService.putCompare(x, cond("BASE", "UP", "PCT", "50", null));
        assertEquals("BASE", x.get("how"));
        assertEquals("UP", x.get("op"));
        assertEquals("PCT", x.get("unit"));
        assertEquals(new BigDecimal("50"), x.get("threshold"));
    }

    @Test
    void currentValueHasNoUnit()
    {
        Map<String, Object> x = new LinkedHashMap<>();
        CondEventService.putCompare(x, cond("CUR", "LT", "PCT", "5", null));
        assertEquals("LT", x.get("op"));
        assertTrue(x.containsKey("unit"), "字段始终在，当前值为 null");
        assertNull(x.get("unit"));
    }

    @Test
    void outsideRangeThresholdIsLowHighPair()
    {
        Map<String, Object> x = new LinkedHashMap<>();
        CondEventService.putCompare(x, cond("CUR", "OUT", null, "10", "30"));
        assertEquals("OUT", x.get("op"));
        assertEquals(List.of(new BigDecimal("10"), new BigDecimal("30")), x.get("threshold"));
    }
}
