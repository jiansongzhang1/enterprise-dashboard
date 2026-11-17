package com.fivetech.monitor.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.monitor.domain.MonitorRule;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.exception.MonitorErrorCode;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.mapper.MonitorRuleMapper;

/**
 * 指标规则接口碰到订单规则：详情 / 修改按不存在处理，删除拒绝，启停只改状态
 * （不占启用上限、不动运行状态和事件）。Mapper 用代理，调到别的方法直接失败。
 */
class MonitorRuleTypeGuardTest
{
    private static final MonitorScope SCOPE = new MonitorScope("SITE-IN", 1L);

    private final List<String> calls = new ArrayList<>();

    private MonitorRule order;

    private MonitorRuleServiceImpl service;

    @BeforeEach
    void setUp()
    {
        order = new MonitorRule();
        order.setRuleId(9L);
        order.setRuleNo(9);
        order.setSiteCode("SITE-IN");
        order.setRuleType("ORDER");
        order.setEnabled(true);
        MonitorRuleMapper mapper = (MonitorRuleMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { MonitorRuleMapper.class }, (p, m, a) -> {
                    calls.add(m.getName());
                    switch (m.getName())
                    {
                        case "selectRuleById":
                            return Long.valueOf(9L).equals(a[1]) ? order : null;
                        case "updateEnabled":
                        case "deleteRuleLogically":
                            return 1;
                        default:
                            throw new UnsupportedOperationException(m.getName());
                    }
                });
        service = new MonitorRuleServiceImpl(mapper, null, null, null, null, null, new DashboardProperties(), null);
    }

    private static int code(Runnable r)
    {
        try
        {
            r.run();
        }
        catch (MonitorException e)
        {
            return e.getCode();
        }
        return 0;
    }

    @Test
    void orderRulesAreNotMetricRules()
    {
        assertEquals(MonitorErrorCode.NOT_FOUND, code(() -> service.getRule(SCOPE, 9L)), "指标规则详情不返回订单规则");
        assertEquals(MonitorErrorCode.NOT_FOUND, code(() -> service.updateRule(SCOPE, 9L, null, "u")));
    }

    @Test
    void deletingAnOrderRuleOnlyMarksItDeleted()
    {
        calls.clear();
        service.deleteRule(SCOPE, 9L, "u");
        assertEquals(List.of("selectRuleById", "deleteRuleLogically"), calls, "订单规则可删（FY-95 R7）：只逻辑删除，不停事件、不删运行状态");
    }

    @Test
    void pausingAnOrderRuleOnlyFlipsTheFlag()
    {
        calls.clear();
        service.setEnabled(SCOPE, 9L, false, "u");
        assertEquals(List.of("selectRuleById", "updateEnabled"), calls, "不停事件、不删运行状态");
        order.setEnabled(false);
        calls.clear();
        service.setEnabled(SCOPE, 9L, true, "u");
        assertEquals(List.of("selectRuleById", "updateEnabled"), calls, "启用不查启用上限、不重置运行状态");
        assertEquals(true, MonitorRuleServiceImpl.isMetric(new MonitorRule()), "旧数据 rule_type 为空按指标规则");
    }
}
