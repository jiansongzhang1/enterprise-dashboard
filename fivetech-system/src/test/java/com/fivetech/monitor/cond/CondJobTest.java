package com.fivetech.monitor.cond;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.monitor.domain.MonitorCondEvent;
import com.fivetech.monitor.domain.MonitorCondRow;
import com.fivetech.monitor.domain.MonitorCondState;
import com.fivetech.monitor.domain.MonitorRule;
import com.fivetech.monitor.domain.vo.MonitorEventVO;
import com.fivetech.monitor.engine.domain.MonitorNotifyRecord;
import com.fivetech.monitor.mapper.MonitorCondMapper;
import com.fivetech.monitor.mapper.MonitorEngineMapper;

/** 条件规则每小时检测：就绪分钟、连续次数后开事件并写待发送通知、续期、回到正常结束、不重复处理、静默、非检测点 */
class CondJobTest
{
    static final LocalDateTime DAY = LocalDateTime.of(2026, 11, 16, 0, 0);

    final Map<LocalDateTime, Integer> regs = new HashMap<>();

    final Map<Long, MonitorCondState> states = new HashMap<>();

    final Map<Long, MonitorCondEvent> events = new LinkedHashMap<>();

    final List<MonitorNotifyRecord> notifies = new ArrayList<>();

    MonitorRule rule;

    List<MonitorCondRow> rows;

    CondJob job;

    @BeforeEach
    void setUp()
    {
        rule = new MonitorRule();
        rule.setRuleId(9L);
        rule.setRuleNo(31);
        rule.setRuleType("COND");
        rule.setRuleName("疑似批量註冊");
        rule.setLevel("CRIT");
        rule.setEnabled(true);
        rule.setNotifyEnabled(true);
        rule.setNotifyChats("[{\"chatId\":\"oc_1\",\"name\":\"風控群\"}]");
        rule.setMuteHours(2);
        rule.setConfig("{\"matchMode\":\"ALL\",\"window\":\"1h\",\"durationCount\":2}");
        MonitorCondRow r = new MonitorCondRow();
        r.setRuleId(9L);
        r.setSeq(1);
        r.setFieldCode("reg");
        r.setScope("{}");
        r.setHow("CUR");
        r.setOp("GT");
        r.setThresholdLow(BigDecimal.valueOf(100));
        rows = List.of(r);
        MonitorCondState st = new MonitorCondState();
        st.setRuleId(9L);
        st.setLastDataHour(DAY.plusHours(10));
        states.put(9L, st);

        CondProperties p = new CondProperties();
        CondDataSource data = new CondDataSource()
        {
            @Override
            public boolean available()
            {
                return true;
            }

            @Override
            public Map<String, CondSeries> fetch(List<Request> requests, LocalDateTime from, LocalDateTime to)
            {
                CondSeries s = new CondSeries(from);
                regs.forEach((h, v) -> {
                    if (!h.isBefore(from) && h.isBefore(to))
                    {
                        s.put(h, CondFixtures.vals("reg_cnt", v));
                    }
                });
                Map<String, CondSeries> out = new HashMap<>();
                requests.forEach(rq -> out.put(rq.key(), s));
                return out;
            }
        };
        DashboardProperties d = new DashboardProperties();
        d.setTimezone("Asia/Kolkata");
        CondSupport support = new CondSupport(CondFixtures.catalog(), data, p, d);
        MonitorCondMapper mapper = condMapper();
        MonitorEngineMapper engine = (MonitorEngineMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] { MonitorEngineMapper.class }, (px, m, a) -> {
                if ("insertNotifyLog".equals(m.getName()))
                {
                    notifies.add((MonitorNotifyRecord) a[0]);
                    return 1;
                }
                throw new UnsupportedOperationException(m.getName());
            });
        MonitorProperties mp = new MonitorProperties();
        CondEventWriter writer = new CondEventWriter(mapper, engine, support, mp);
        job = new CondJob(mapper, support, writer, new CondDims(null, null, null, null), mp, null);
    }

    MonitorCondMapper condMapper()
    {
        return (MonitorCondMapper) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { MonitorCondMapper.class },
            (px, m, a) -> {
                switch (m.getName())
                {
                    case "selectEnabledCondRules":
                        return List.of(rule);
                    case "selectConditions":
                        return rows;
                    case "selectStates":
                    {
                        List<MonitorCondState> out = new ArrayList<>();
                        for (MonitorCondState s : states.values())
                        {
                            MonitorCondState c = copy(s);
                            MonitorCondEvent open = events.values().stream().filter(e -> "open".equals(e.getStatus())).findFirst().orElse(null);
                            c.setEventId(open == null ? null : open.getEventId());
                            c.setEventDetail(open == null ? null : open.getDetail());
                            out.add(c);
                        }
                        return out;
                    }
                    case "upsertState":
                        states.put(((MonitorCondState) a[1]).getRuleId(), copy((MonitorCondState) a[1]));
                        return 1;
                    case "insertEvent":
                    {
                        MonitorCondEvent e = (MonitorCondEvent) a[1];
                        e.setEventId(100L + events.size());
                        e.setStatus("open");
                        events.put(e.getEventId(), e);
                        return 1;
                    }
                    case "updateEventLatest":
                        events.get((Long) a[1]).setDetail((String) a[2]);
                        return 1;
                    case "resolveEvent":
                        events.get((Long) a[1]).setStatus("resolved");
                        events.get((Long) a[1]).setEndHour((LocalDateTime) a[2]);
                        return 1;
                    default:
                        throw new UnsupportedOperationException(m.getName());
                }
            });
    }

    static MonitorCondState copy(MonitorCondState s)
    {
        MonitorCondState c = new MonitorCondState();
        c.setRuleId(s.getRuleId());
        c.setRunStartHour(s.getRunStartHour());
        c.setRecentPeriods(s.getRecentPeriods());
        c.setLastPeriodEnd(s.getLastPeriodEnd());
        c.setLastDataHour(s.getLastDataHour());
        c.setDetail(s.getDetail());
        return c;
    }

    @Test
    void opensAfterConsecutiveHitsNotifiesAndResolves()
    {
        regs.put(DAY.plusHours(11), 200);
        assertEquals(0, job.run("SITE-IN", DAY.plusHours(12).plusMinutes(3)), "整点后 5 分钟前不处理");
        assertEquals(1, job.run("SITE-IN", DAY.plusHours(12).plusMinutes(10)));
        assertTrue(events.isEmpty(), "命中 1 次，还没连续 2 次");
        assertEquals(DAY.plusHours(11), states.get(9L).getLastDataHour());
        assertEquals(0, job.run("SITE-IN", DAY.plusHours(12).plusMinutes(20)), "同一小时不重复处理");

        regs.put(DAY.plusHours(12), 300);
        job.run("SITE-IN", DAY.plusHours(13).plusMinutes(10));
        assertEquals(1, events.size());
        MonitorCondEvent e = events.values().iterator().next();
        assertEquals(DAY.plusHours(11), e.getStartHour(), "事件从第一次命中的时段算起");
        assertEquals(DAY.plusHours(12), e.getTriggerHour());
        assertEquals("pending", e.getNotifyStatus());
        assertEquals(1, notifies.size());
        assertEquals("oc_1", notifies.get(0).getChatId());
        assertTrue(notifies.get(0).getContent().contains("Yata 條件告警 · 嚴重"), notifies.get(0).getContent());
        assertTrue(notifies.get(0).getContent().contains("註冊人數 大於 100：300"), notifies.get(0).getContent());

        regs.put(DAY.plusHours(13), 500);
        job.run("SITE-IN", DAY.plusHours(14).plusMinutes(10));
        assertEquals(1, events.size(), "仍在异常中，不开新事件");
        assertTrue(e.getDetail().contains("\"end\":\"2026-11-16 14:00\""), "最近一次更新到 14:00 的检测");

        regs.put(DAY.plusHours(14), 50);
        job.run("SITE-IN", DAY.plusHours(15).plusMinutes(10));
        assertEquals("resolved", e.getStatus());
        assertEquals(DAY.plusHours(14), e.getEndHour());
        assertEquals("[]", states.get(9L).getRecentPeriods());

        // 列表行：满足 / 判定基准 / 判定说明
        MonitorEventVO vo = new MonitorEventVO();
        CondEventView.applyListRow(vo, "疑似批量註冊", e.getRuleSnapshot(), e.getDetail(), CondFixtures.catalog().fields(), "₹");
        assertEquals("500", vo.getCurrentText());
        assertTrue(vo.isCanDetail());
        assertTrue(vo.getJudgeTip().contains("這次檢測統計 11-16 12:00"), vo.getJudgeTip());
    }

    @Test
    void mutedWithinMuteHoursAndNonCheckpointOnlyAdvances()
    {
        rule.setLastNotifiedAt(DAY.plusHours(12));
        rule.setConfig("{\"matchMode\":\"ALL\",\"window\":\"1h\",\"durationCount\":1}");
        regs.put(DAY.plusHours(12), 300);
        job.run("SITE-IN", DAY.plusHours(13).plusMinutes(10));
        MonitorCondEvent e = events.values().iterator().next();
        assertEquals("muted", e.getNotifyStatus(), "距上次发送不足 2 小时");
        assertTrue(notifies.isEmpty());

        events.clear();
        states.get(9L).setRecentPeriods("[]");
        rule.setConfig("{\"matchMode\":\"ALL\",\"window\":\"4h\",\"durationCount\":1}");
        regs.put(DAY.plusHours(13), 300);
        job.run("SITE-IN", DAY.plusHours(14).plusMinutes(10));
        assertTrue(events.isEmpty(), "14:00 不是 4h 的检测点");
        assertEquals(DAY.plusHours(13), states.get(9L).getLastDataHour());
        assertNull(states.get(9L).getEventId());
        assertNotNull(states.get(9L));
    }

    @Test
    void noBaseHintForShortHistory()
    {
        CondSpec spec = new CondSpec();
        CondCondition c = new CondCondition();
        c.setIndex(1);
        c.setField("reg");
        c.setHow("BASE");
        c.setOp("UP");
        c.setUnit("PCT");
        c.setLow(BigDecimal.TEN);
        spec.setConditions(List.of(c));
        CondSeries s = CondFixtures.flat(DAY.minusDays(3), 24 * 3, "reg_cnt", 10);
        CondEvaluator.Point pt = CondEvaluator.point(spec, CondFixtures.catalog().fields(), Map.of(1, s), DAY, 4);
        Map<String, Object> hint = CondJob.noBase(spec, pt, Map.of(1, s), 4);
        assertEquals(List.of(1), hint.get("noBase"));
        assertEquals("2026-12-11", hint.get("from"), "最早数据 11-13 + 4 周");
    }
}
