package com.fivetech.monitor.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** GET /monitor/rules rows[].unit：金额返回 money，前端按站点币种显示符号；其他接口（详情、可选指标、回测预览）仍是 ₹ */
class MonitorRuleListUnitTest
{
    @Test
    void listMoneyIsMoney()
    {
        assertEquals("money", MonitorRuleServiceImpl.listUnit("MONEY"));
        assertEquals("money", MonitorRuleServiceImpl.listUnit("money"));
        assertEquals("%", MonitorRuleServiceImpl.listUnit("PCT"));
        assertEquals("x", MonitorRuleServiceImpl.listUnit("MULTIPLE"));
        assertEquals("min", MonitorRuleServiceImpl.listUnit("MIN"));
        assertEquals("", MonitorRuleServiceImpl.listUnit("INT"));
        assertEquals("", MonitorRuleServiceImpl.listUnit(null));
    }

    @Test
    void otherInterfacesUnchanged()
    {
        assertEquals("₹", MonitorRuleServiceImpl.unit("MONEY"));
        assertEquals("%", MonitorRuleServiceImpl.unit("PCT"));
        assertEquals("", MonitorRuleServiceImpl.unit(null));
    }
}
