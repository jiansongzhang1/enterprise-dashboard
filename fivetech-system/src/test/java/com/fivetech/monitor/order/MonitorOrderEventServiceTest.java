package com.fivetech.monitor.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.monitor.domain.MonitorOrderEvent;
import com.fivetech.monitor.domain.MonitorOrderEventQuery;
import com.fivetech.monitor.domain.MonitorOrderEventRow;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.exception.MonitorFieldException;
import com.fivetech.monitor.mapper.MonitorOrderEventMapper;
import tools.jackson.databind.json.JsonMapper;

/** 訂單告警列表：筛选换算（站点时区 → 统计时区）、按订单状态过滤与内存分页、UDS 不可用、行组装、处理校验（不连数据库） */
class MonitorOrderEventServiceTest
{
    private static final MonitorScope SITE = new MonitorScope("SITE-CN", 7L);

    private FakeEvents events;

    private FakeLookup lookup;

    private OrderLookupProperties props;

    private MonitorOrderEventService service;

    @BeforeEach
    void setUp()
    {
        DashboardProperties d = new DashboardProperties();
        d.setTimezone("Asia/Kolkata");
        DashboardProperties.Site cn = new DashboardProperties.Site();
        cn.setCode("SITE-CN");
        cn.setTimezone("Asia/Shanghai");
        cn.setCurrency("CNY");
        cn.setCurrencySymbol("¥");
        d.setSites(List.of(cn));
        events = new FakeEvents();
        lookup = new FakeLookup();
        props = new OrderLookupProperties();
        service = new MonitorOrderEventService(events, lookup, props, d, JsonMapper.builder().build());
    }

    @Test
    void rangeUsesSiteDaysConvertedToStatZone()
    {
        ZoneId site = ZoneId.of("Asia/Shanghai");
        ZoneId stat = ZoneId.of("Asia/Kolkata");
        LocalDate today = LocalDate.of(2026, 11, 16);
        Map<String, String> errors = new LinkedHashMap<>();
        MonitorOrderEventQuery q = new MonitorOrderEventQuery();
        MonitorOrderEventService.applyRange(q, "today", null, null, today, site, stat, errors);
        assertEquals(LocalDateTime.of(2026, 11, 15, 21, 30), q.getRangeFrom(), "北京 0 点 = 印度前一天 21:30");
        assertNull(q.getRangeTo());

        q = new MonitorOrderEventQuery();
        MonitorOrderEventService.applyRange(q, "custom", "2026-11-01", "2026-11-03", today, site, stat, errors);
        assertEquals(LocalDateTime.of(2026, 10, 31, 21, 30), q.getRangeFrom());
        assertEquals(LocalDateTime.of(2026, 11, 3, 21, 30), q.getRangeTo(), "含结束日");
        assertTrue(errors.isEmpty());

        MonitorOrderEventService.applyRange(new MonitorOrderEventQuery(), "custom", "2026-01-01", "2026-11-01", today, site, stat, errors);
        assertTrue(errors.get("to").contains("90"), "最多 90 天");
        errors.clear();
        MonitorOrderEventService.applyRange(new MonitorOrderEventQuery(), "custom", "2026-11-05", "2026-11-01", today, site, stat, errors);
        assertTrue(errors.containsKey("to"));
        errors.clear();
        MonitorOrderEventService.applyRange(new MonitorOrderEventQuery(), "custom", "11/01", null, today, site, stat, errors);
        assertTrue(errors.containsKey("from") && errors.containsKey("to"));
        errors.clear();
        MonitorOrderEventService.applyRange(new MonitorOrderEventQuery(), "week", null, null, today, site, stat, errors);
        assertTrue(errors.containsKey("range"));
    }

    @Test
    void handleAndStatusFilters()
    {
        Map<String, String> errors = new LinkedHashMap<>();
        assertEquals("pending", MonitorOrderEventService.handleFilter(null, errors));
        assertEquals("done", MonitorOrderEventService.handleFilter("DONE", errors));
        assertNull(MonitorOrderEventService.handleFilter("all", errors));
        assertTrue(errors.isEmpty());
        MonitorOrderEventService.handleFilter("x", errors);
        assertTrue(errors.containsKey("handle"));
        errors.clear();
        assertEquals(List.of("PENDING", "PAID"),
            new ArrayList<>(MonitorOrderEventService.statusFilter(List.of("pending,PAID", "PENDING"), errors)), "逗号分隔、去重");
        MonitorOrderEventService.statusFilter(List.of("DONE"), errors);
        assertTrue(errors.containsKey("orderStatus"));
    }

    @Test
    void filterByOrderStatusPagesInMemoryAndFillsUserInfo()
    {
        props.setOrderDataset("ds_withdraw");
        for (int i = 1; i <= 5; i++)
        {
            events.rows.add(row(100L + i, "W" + i, LocalDateTime.of(2026, 11, 16, 8, i)));
        }
        lookup.orders.put("W1", new OrderLookup.OrderInfo("PENDING", "u1"));
        lookup.orders.put("W2", new OrderLookup.OrderInfo("PAID", "u2"));
        lookup.orders.put("W3", new OrderLookup.OrderInfo("PENDING", "u3"));
        lookup.orders.put("W5", new OrderLookup.OrderInfo("PENDING", "u5"));
        lookup.users.put("u3", new OrderLookup.UserInfo("player3", LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(3).atTime(12, 0)));

        MonitorOrderEventService.ListParams p = new MonitorOrderEventService.ListParams();
        p.orderStatus = List.of("PENDING");
        p.pageNum = 2;
        p.pageSize = 2;
        Map<String, Object> vo = service.list(SITE, p);
        assertEquals(3L, vo.get("total"), "W1、W3、W5 是審核中");
        assertEquals(true, vo.get("udsOk"));
        assertEquals(4, vo.get("pendingTotal"));
        List<Map<String, Object>> rows = rows(vo);
        assertEquals(1, rows.size(), "第 2 页只剩 1 条");
        assertEquals("W5", rows.get(0).get("orderNo"));
        assertEquals("pending", events.lastQuery.getHandle(), "默认只看未处理");
        assertEquals(List.of("u5"), lookup.userCalls.get(0), "只查当前页的用户");

        p.pageNum = 1;
        rows = rows(service.list(SITE, p));
        Map<String, Object> user = sub(rows.get(1), "user");
        assertEquals("u3", user.get("uid"));
        assertEquals("player3", user.get("account"));
        assertEquals(3L, user.get("registerDays"));
        assertEquals("審核中", sub(rows.get(1), "order").get("statusText"));
    }

    @Test
    void filterByOrderStatusRefusesTooManyOrNoUds()
    {
        MonitorOrderEventService.ListParams p = new MonitorOrderEventService.ListParams();
        p.orderStatus = List.of("PAID");
        lookup.on = false;
        MonitorException ex = assertThrows(MonitorException.class, () -> service.list(SITE, p), "UDS 未接入不能按状态筛");
        assertTrue(ex.getMessage().contains("訂單狀態"));

        lookup.on = true;
        props.setFilterMax(2);
        for (int i = 1; i <= 3; i++)
        {
            events.rows.add(row(100L + i, "W" + i, LocalDateTime.of(2026, 11, 16, 8, i)));
        }
        ex = assertThrows(MonitorException.class, () -> service.list(SITE, p));
        assertTrue(ex.getMessage().contains("太多"));

        props.setFilterMax(10);
        lookup.fail = true;
        assertThrows(MonitorException.class, () -> service.list(SITE, p), "按状态筛选时 UDS 失败直接报错");

        MonitorOrderEventService.ListParams bad = new MonitorOrderEventService.ListParams();
        bad.pageSize = 101;
        assertThrows(MonitorFieldException.class, () -> service.list(SITE, bad));
    }

    @Test
    void toRowConvertsTimesAndBuildsRuleNotifyHandle()
    {
        MonitorOrderEventRow e = row(50231L, "W20261116103512001", LocalDateTime.of(2026, 11, 16, 8, 5, 12));
        e.setTriggerPoint("PAID");
        e.setAmountMode("DAILY_TOTAL");
        e.setAmount(new BigDecimal("52000.0000"));
        e.setCumAmount(new BigDecimal("80000.5"));
        e.setCumCount(3);
        e.setRuleNo(23);
        e.setRuleName("大額提款提醒");
        e.setRuleConfig("{\"orderType\":\"WITHDRAW\",\"triggerAt\":\"PAID\",\"amountMode\":\"DAILY_TOTAL\",\"amountThreshold\":50000}");
        e.setOwnerName("Amy");
        e.setNotifyStatus("PARTIAL");
        e.setNotifyChats("[{\"chatId\":\"oc_1\",\"name\":\"風控群\",\"status\":\"SENT\"},"
            + "{\"chatId\":\"oc_2\",\"name\":\"財務群\",\"status\":\"FAILED\",\"error\":\"機器人不在群裡\"}]");
        e.setNotifiedAt(LocalDateTime.of(2026, 11, 16, 8, 6));
        e.setHandleStatus("done");
        e.setHandleNote("已核實");
        e.setHandleByName("ops@x.com");
        e.setHandleTime(LocalDateTime.of(2026, 11, 16, 9, 0));
        Map<String, Object> m = service.toRow(e, "SITE-CN", "¥", LocalDate.of(2026, 11, 16), null, new HashMap<>());
        assertEquals("2026-11-16 10:35:12", m.get("triggerTime"), "统计时区 → 站点时区（+2.5h）");
        assertEquals("出款時間", m.get("triggerLabel"));
        Map<String, Object> rule = sub(m, "rule");
        assertEquals("R-023", rule.get("ruleCode"));
        assertEquals("帳號當日累計提款 ≥ ¥50,000（出款成功時）", rule.get("summary"));
        assertEquals(new BigDecimal("52000.00"), m.get("amount"));
        assertEquals(new BigDecimal("80000.50"), m.get("cumAmount"));
        assertNull(sub(m, "order").get("status"), "UDS 没取到");
        assertNull(sub(m, "user").get("uid"));
        Map<String, Object> notify = sub(m, "notify");
        assertEquals("風控群", notify.get("chatName"));
        assertEquals("機器人不在群裡", notify.get("error"));
        assertEquals("2026-11-16 10:36", notify.get("sentAt"));
        assertEquals(2, ((List<?>) notify.get("chats")).size());
        Map<String, Object> handle = sub(m, "handle");
        assertEquals("ops@x.com", handle.get("by"));
        assertEquals("2026-11-16 11:30", handle.get("at"));
    }

    @Test
    void handleValidatesAndChecksSite()
    {
        assertThrows(MonitorFieldException.class, () -> service.handle(SITE, List.of(), "x"));
        assertThrows(MonitorFieldException.class, () -> service.handle(SITE, List.of(1L), "  "));
        assertThrows(MonitorFieldException.class, () -> service.handle(SITE, List.of(1L), "字".repeat(501)));
        List<Long> many = new ArrayList<>();
        for (long i = 0; i < 101; i++)
        {
            many.add(i);
        }
        assertThrows(MonitorFieldException.class, () -> service.handle(SITE, many, "x"));
        events.rows.add(row(1L, "W1", LocalDateTime.now()));
        assertThrows(MonitorException.class, () -> service.handle(SITE, List.of(1L, 2L), "x"), "有一条不属于本站整体 404");
        assertEquals(1, service.handle(SITE, List.of(1L, 1L), " 已聯繫 "));
        assertEquals("已聯繫", events.lastNote);
        assertEquals("7", events.lastBy, "处理人存 user_id");
        assertThrows(MonitorException.class, () -> service.unhandle(SITE, 99L));
        service.unhandle(SITE, 1L);
    }

    @Test
    void lookupFailureOnPlainListKeepsRowsAndFlagsUds()
    {
        props.setOrderDataset("ds_withdraw");
        lookup.fail = true;
        events.rows.add(row(1L, "W1", LocalDateTime.of(2026, 11, 16, 8, 0)));
        Map<String, Object> vo = service.list(SITE, new MonitorOrderEventService.ListParams());
        assertFalse((Boolean) vo.get("udsOk"));
        assertEquals(1, rows(vo).size());
    }

    // ------------------------------------------------------------------ 工具

    static MonitorOrderEventRow row(Long id, String orderNo, LocalDateTime t)
    {
        MonitorOrderEventRow r = new MonitorOrderEventRow();
        r.setEventId(id);
        r.setSiteCode("SITE-CN");
        r.setRuleId(1023L);
        r.setOrderNo(orderNo);
        r.setTriggerTime(t);
        r.setTriggerPoint("SUBMIT");
        r.setAmount(new BigDecimal("1000"));
        r.setLevel("CRIT");
        r.setNotifyStatus("SENT");
        r.setHandleStatus("pending");
        return r;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Map<String, Object> vo)
    {
        return (List<Map<String, Object>>) vo.get("rows");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> sub(Map<String, Object> m, String key)
    {
        return (Map<String, Object>) m.get(key);
    }

    static final class FakeLookup implements OrderLookup
    {
        final Map<String, OrderInfo> orders = new HashMap<>();

        final Map<String, UserInfo> users = new HashMap<>();

        final List<List<String>> userCalls = new ArrayList<>();

        boolean fail;

        boolean on = true;

        @Override
        public boolean available()
        {
            return on;
        }

        @Override
        public Map<String, OrderInfo> orders(String siteCode, Collection<String> orderNos, LocalDateTime since)
        {
            if (fail)
            {
                throw new IllegalStateException("UDS down");
            }
            Map<String, OrderInfo> out = new HashMap<>();
            orderNos.forEach(n -> {
                if (orders.containsKey(n))
                {
                    out.put(n, orders.get(n));
                }
            });
            return out;
        }

        @Override
        public Map<String, UserInfo> users(String siteCode, Collection<String> userIds)
        {
            userCalls.add(new ArrayList<>(userIds));
            Map<String, UserInfo> out = new HashMap<>();
            userIds.forEach(u -> {
                if (users.containsKey(u))
                {
                    out.put(u, users.get(u));
                }
            });
            return out;
        }
    }

    static final class FakeEvents implements MonitorOrderEventMapper
    {
        final List<MonitorOrderEventRow> rows = new ArrayList<>();

        MonitorOrderEventQuery lastQuery;

        String lastNote;

        String lastBy;

        @Override
        public List<MonitorOrderEvent> selectByOrderNos(MonitorScope scope, Long ruleId, List<String> orderNos)
        {
            return List.of();
        }

        @Override
        public int insertEvent(MonitorOrderEvent event)
        {
            return 0;
        }

        @Override
        public int updateNotify(MonitorScope scope, Long eventId, String status, String chats, LocalDateTime at)
        {
            return 0;
        }

        @Override
        public List<MonitorOrderEventRow> selectOrderEventList(MonitorScope scope, MonitorOrderEventQuery q)
        {
            lastQuery = q;
            return new ArrayList<>(rows);
        }

        @Override
        public int countPending(MonitorScope scope)
        {
            return 4;
        }

        @Override
        public int countByIds(MonitorScope scope, List<Long> ids)
        {
            return (int) rows.stream().filter(r -> ids.contains(r.getEventId())).count();
        }

        @Override
        public int markHandled(MonitorScope scope, List<Long> ids, String note, String by, LocalDateTime now)
        {
            lastNote = note;
            lastBy = by;
            return ids.size();
        }

        @Override
        public int unhandle(MonitorScope scope, Long eventId)
        {
            return countByIds(scope, List.of(eventId));
        }

        @Override
        public List<Map<String, Object>> selectLastTriggers(MonitorScope scope, List<Long> ruleIds)
        {
            return List.of();
        }
    }
}
