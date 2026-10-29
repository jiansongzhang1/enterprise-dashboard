package com.fivetech.monitor.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** GET /monitor/events rows[].unit：金额返回 money，前端按站点币种显示符号；其余不变 */
class MonitorEventUnitTest
{
    @Test
    void moneyIsMoney()
    {
        assertEquals("money", MonitorEventServiceImpl.eventUnit("MONEY"));
        assertEquals("money", MonitorEventServiceImpl.eventUnit("money"));
        assertEquals("%", MonitorEventServiceImpl.eventUnit("PCT"));
        assertEquals("x", MonitorEventServiceImpl.eventUnit("MULTIPLE"));
        assertEquals("min", MonitorEventServiceImpl.eventUnit("MIN"));
        assertEquals("", MonitorEventServiceImpl.eventUnit("INT"));
        assertEquals("", MonitorEventServiceImpl.eventUnit(null));
    }
}
