package com.fivetech.monitor.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.gateway.HourlyFetch;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.engine.domain.MonitorEngineRule;
import com.fivetech.monitor.engine.domain.MonitorRuleStateRecord;
import com.fivetech.monitor.mapper.MonitorEngineMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 数据缺失（2026-10-22 与产品确认）：:50 时指标值仍为空 → 本周期不判定（SKIP），
 * 不产生告警、不写 monitor_event、不写 monitor_notify_log；连续计数中断；异常中的事件保持不变。
 */
class MonitorEventWriterMissingDataTest
{
    private static final MonitorScope SITE = new MonitorScope("SITE-IN", null);

    private static final LocalDateTime HOUR = LocalDateTime.of(2026, 10, 22, 9, 0);

    private static final LocalDateTime NOW = HOUR.plusHours(1).plusMinutes(50);

    private List<String> calls;

    private List<MonitorRuleStateRecord> states;

    private MonitorEventWriter writer;

    @BeforeEach
    void setUp()
    {
        calls = new ArrayList<>();
        states = new ArrayList<>();
        MonitorEngineMapper mapper = (MonitorEngineMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { MonitorEngineMapper.class }, (proxy, method, args) -> {
                    calls.add(method.getName());
                    if ("upsertRuleState".equals(method.getName()))
                    {
                        states.add((MonitorRuleStateRecord) args[1]);
                    }
                    return defaultValue(method);
                });
        writer = new MonitorEventWriter(mapper, null, null, null, new MonitorProperties(), JsonMapper.builder().build(), null);
    }

    private static Object defaultValue(Method m)
    {
        Class<?> t = m.getReturnType();
        if (t == int.class || t == Integer.class)
        {
            return 0;
        }
        if (t == boolean.class)
        {
            return false;
        }
        return List.class.isAssignableFrom(t) ? Collections.emptyList() : null;
    }

    private static MonitorEngineRule rule()
    {
        MonitorEngineRule r = new MonitorEngineRule();
        r.setRuleId(11L);
        r.setSiteCode("SITE-IN");
        r.setRuleNo(1);
        r.setMetricCode("dOkR");
        r.setJudgeType("lo");
        r.setCritLow(new BigDecimal("90"));
        r.setPeriodHours(1);
        r.setDurationCount(2);
        r.setMuteHours(1);
        r.setNotifyChatId("oc_a");
        return r;
    }

    /** 本小时取到了别的指标，但这个指标值为空 */
    private static HourlyFetch dataWithoutValue()
    {
        HourlyFetch data = new HourlyFetch(HOUR, 1);
        data.put("dOkR", Arrays.asList((BigDecimal) null));
        return data;
    }

    @Test
    void missingAtFinalCheckWritesNoEventNoNotifyAndBreaksRun()
    {
        MonitorEngineRule r = rule();
        // 上一个周期已越线 1 次（连续 2 次才触发）
        r.setStateRuleId(11L);
        r.setRecentLevels("[\"CRIT\"]");
        r.setRunStartHour(HOUR.minusHours(1));
        int processed = writer.applyHour(SITE, HOUR, NOW, List.of(r), dataWithoutValue(), true, true);

        assertEquals(1, processed);
        assertFalse(calls.contains("insertEvent"), "数据缺失不触发告警");
        assertFalse(calls.contains("updateEvent"));
        assertFalse(calls.contains("insertNotifyLog"), "数据缺失不发通知");
        assertEquals(1, states.size());
        MonitorRuleStateRecord st = states.get(0);
        assertEquals(HOUR, st.getLastDataHour(), "这个小时已处理，不会重复处理");
        assertNull(st.getRunStartHour(), "连续计数中断");
        assertTrue(calls.contains("finishHourCheck"));
    }

    @Test
    void missingBeforeFinalCheckWaitsForNextRound()
    {
        int processed = writer.applyHour(SITE, HOUR, HOUR.plusHours(1).plusMinutes(10), List.of(rule()), dataWithoutValue(),
                true, false);
        assertEquals(0, processed);
        assertTrue(states.isEmpty(), "还没到 :50，下一轮再取");
        assertFalse(calls.contains("finishHourCheck"));
    }

    @Test
    void missingWithOpenEventKeepsEventUnchanged()
    {
        MonitorEngineRule r = rule();
        r.setStateRuleId(11L);
        r.setRecentLevels("[\"CRIT\",\"CRIT\"]");
        r.setRunStartHour(HOUR.minusHours(3));
        r.setEventId(500L);
        r.setEventLevel("CRIT");
        r.setEventPeakLevel("CRIT");
        r.setEventStartHour(HOUR.minusHours(3));
        r.setEventTriggerHour(HOUR.minusHours(2));
        r.setEventNotifyStatus("sent");
        writer.applyHour(SITE, HOUR, NOW, List.of(r), null, false, true);

        assertFalse(calls.contains("insertEvent"));
        assertFalse(calls.contains("updateEvent"), "不恢复、不改等级");
        assertFalse(calls.contains("insertNotifyLog"));
        assertEquals(Long.valueOf(500L), states.get(0).getOpenEventId(), "事件仍异常中");
    }
}
