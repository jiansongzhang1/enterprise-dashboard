package com.fivetech.monitor.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fivetech.monitor.engine.judge.JudgeRule;
import com.fivetech.monitor.engine.judge.PeriodPoint;

/** 回测预览 events：全部返回、不截断（原上限 50 已去掉），与 triggers 计数一致，按开始时间倒序 */
class MonitorPreviewEventsTest
{
    @Test
    @SuppressWarnings("unchecked")
    void returnsAllTriggersNewestFirst()
    {
        // 30 天逐小时：80（低于严重线 90）与 98 交替 → 每两小时触发一次，共 360 次
        JudgeRule judge = new JudgeRule(JudgeRule.LO, null, null, new BigDecimal("90"), null, 1, 1, null, null, false);
        LocalDateTime from = LocalDateTime.of(2026, 9, 28, 0, 0);
        List<PeriodPoint> points = new ArrayList<>();
        for (int i = 0; i < 720; i++)
        {
            LocalDateTime t = from.plusHours(i);
            points.add(new PeriodPoint(t, t.plusHours(1), new BigDecimal(i % 2 == 0 ? "80" : "98"), null, new BigDecimal("100"), false));
        }
        Map<String, Object> pv = MonitorPreviewService.previewOf(judge, "PCT", points, 72);
        Map<String, Object> triggers = (Map<String, Object>) pv.get("triggers");
        List<Map<String, Object>> events = (List<Map<String, Object>>) pv.get("events");
        assertEquals(360, triggers.get("total"));
        assertEquals(360, triggers.get("crit"));
        assertEquals(360, events.size(), "超过 50 次也全部返回");
        assertEquals("2026-10-27 22:00", events.get(0).get("startHour"), "最近的在前");
        assertEquals("2026-09-28 00:00", events.get(359).get("startHour"));
        for (int i = 1; i < events.size(); i++)
        {
            assertTrue(((String) events.get(i - 1).get("startHour")).compareTo((String) events.get(i).get("startHour")) > 0);
        }
        assertFalse((Boolean) events.get(359).get("ongoing"));
        assertEquals("80", ((java.math.BigDecimal) ((Map<String, Object>) events.get(0).get("worst")).get("value")).toPlainString());
    }
}
