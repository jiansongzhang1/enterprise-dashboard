package com.fivetech.monitor.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.monitor.domain.MonitorEventQuery;
import com.fivetech.monitor.domain.MonitorEventRow;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.exception.MonitorFieldException;
import com.fivetech.monitor.mapper.MonitorEventMapper;

/** 异常事件：筛选换算、标记处理的校验（不连数据库） */
class MonitorEventServiceTest
{
    private static final MonitorScope SITE = new MonitorScope("SITE-IN", 7L);

    private FakeEvents events;

    private MonitorEventServiceImpl service;

    @BeforeEach
    void setUp()
    {
        events = new FakeEvents();
        service = new MonitorEventServiceImpl(events, null, null, null, new DashboardProperties());
    }

    @Test
    void rangeIsNaturalDaysOfTriggerHour()
    {
        LocalDate today = LocalDate.of(2026, 10, 22);
        MonitorEventQuery q = new MonitorEventQuery();
        MonitorEventServiceImpl.applyRange(q, "today", today);
        assertEquals(LocalDateTime.of(2026, 10, 22, 0, 0), q.getRangeFrom());
        assertNull(q.getRangeTo());

        q = new MonitorEventQuery();
        MonitorEventServiceImpl.applyRange(q, "yest", today);
        assertEquals(LocalDateTime.of(2026, 10, 21, 0, 0), q.getRangeFrom());
        assertEquals(LocalDateTime.of(2026, 10, 22, 0, 0), q.getRangeTo());

        q = new MonitorEventQuery();
        MonitorEventServiceImpl.applyRange(q, "d7", today);
        assertEquals(LocalDateTime.of(2026, 10, 16, 0, 0), q.getRangeFrom(), "含今天共 7 个自然日");

        q = new MonitorEventQuery();
        MonitorEventServiceImpl.applyRange(q, "d30", today);
        assertEquals(LocalDateTime.of(2026, 9, 23, 0, 0), q.getRangeFrom());

        q = new MonitorEventQuery();
        MonitorEventServiceImpl.applyRange(q, "all", today);
        assertNull(q.getRangeFrom());
    }

    @Test
    void handleFilterDefaultsToPending()
    {
        assertEquals("pending", MonitorEventServiceImpl.handleFilter(null));
        assertEquals("done", MonitorEventServiceImpl.handleFilter("DONE"));
        assertNull(MonitorEventServiceImpl.handleFilter("all"));
        assertNull(MonitorEventServiceImpl.statusFilter("bogus"));
        assertEquals("stopped", MonitorEventServiceImpl.statusFilter("stopped"));
    }

    @Test
    void handleValidatesNoteAndIds()
    {
        assertThrows(MonitorFieldException.class, () -> service.handle(SITE, List.of(), "x"));
        assertThrows(MonitorFieldException.class, () -> service.handle(SITE, List.of(1L), "   "));
        assertThrows(MonitorFieldException.class, () -> service.handle(SITE, List.of(1L), "字".repeat(501)));
        List<Long> many = new ArrayList<>();
        for (long i = 1; i <= 101; i++)
        {
            many.add(i);
        }
        assertThrows(MonitorFieldException.class, () -> service.handle(SITE, many, "ok"));
    }

    @Test
    void handleIsAllOrNothingAndTrimsNote()
    {
        events.existing = List.of(1L, 2L);
        assertThrows(MonitorException.class, () -> service.handle(SITE, List.of(1L, 3L), "note"), "有一条不属于本站 → 404");
        assertEquals(0, events.handled.size());

        assertEquals(2, service.handle(SITE, Arrays.asList(1L, 2L, 2L, null), "  已切換備用通道 "));
        assertEquals(List.of(1L, 2L), events.handled, "去重、去掉 null");
        assertEquals("已切換備用通道", events.note);
        assertEquals(7L, events.userId);
    }

    @Test
    void unhandleUnknownEventIs404()
    {
        events.existing = List.of(1L);
        service.unhandle(SITE, 1L);
        assertThrows(MonitorException.class, () -> service.unhandle(SITE, 9L));
    }

    static class FakeEvents implements MonitorEventMapper
    {
        List<Long> existing = List.of();
        List<Long> handled = new ArrayList<>();
        String note;
        Long userId;

        @Override
        public List<MonitorEventRow> selectEventList(MonitorScope scope, MonitorEventQuery query)
        {
            return List.of();
        }

        @Override
        public int countEvents(MonitorScope scope, List<Long> eventIds)
        {
            return (int) eventIds.stream().filter(existing::contains).count();
        }

        @Override
        public int markHandled(MonitorScope scope, List<Long> eventIds, String note, Long userId, LocalDateTime now)
        {
            handled.addAll(eventIds);
            this.note = note;
            this.userId = userId;
            return eventIds.size();
        }

        @Override
        public LocalDateTime selectOpenEventStart(MonitorScope scope, String metricCode)
        {
            return null;
        }

        @Override
        public int unhandle(MonitorScope scope, Long eventId, LocalDateTime now)
        {
            return existing.contains(eventId) ? 1 : 0;
        }
    }
}
