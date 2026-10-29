package com.fivetech.monitor.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.fivetech.monitor.domain.MonitorNotifyTemplate;
import com.fivetech.monitor.engine.judge.JudgeRule;
import com.fivetech.monitor.engine.judge.PeriodPoint;
import com.fivetech.monitor.engine.judge.StepResult;
import com.fivetech.monitor.exception.MonitorErrorCode;
import com.fivetech.monitor.exception.MonitorException;

/**
 * 判定试运行的纯逻辑：周期对齐、period 解析、按线上引擎 + 发送规则重放（新触发严重 / 注意升级 / 越过严重线 /
 * 严重持续 / 同类型只发一次 / 静默期 / 未连续达标 / 不判定 / 恢复）。
 */
class MonitorTestAlertDryRunTest
{
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 19, 0, 0);

    /** lo 类：注意线 5、严重线 1；每 1 小时一周期 */
    private static JudgeRule rule(int durationCount, Integer escalateHours)
    {
        return new JudgeRule(JudgeRule.LO, new BigDecimal("5"), null, new BigDecimal("1"), null, 1, durationCount,
                escalateHours, null, false);
    }

    /** null = 该周期数据缺失 */
    private static List<PeriodPoint> points(Integer... values)
    {
        List<PeriodPoint> out = new ArrayList<>();
        for (int i = 0; i < values.length; i++)
        {
            LocalDateTime f = BASE.plusHours(i);
            BigDecimal v = values[i] == null ? null : new BigDecimal(values[i]);
            out.add(new PeriodPoint(f, f.plusHours(1), v, null, new BigDecimal("40"), v == null));
        }
        return out;
    }

    private static MonitorTestAlertService.DryRun replay(JudgeRule r, Integer mute, Integer... values)
    {
        return MonitorTestAlertService.replay(r, mute, points(values), 72);
    }

    @Test
    void alignPeriodFromMidnight()
    {
        LocalDateTime t = LocalDateTime.of(2026, 10, 19, 18, 30);
        LocalDateTime[] p1 = MonitorTestAlertService.alignPeriod(t, 1);
        assertEquals(LocalDateTime.of(2026, 10, 19, 18, 0), p1[0]);
        assertEquals(LocalDateTime.of(2026, 10, 19, 19, 0), p1[1]);
        LocalDateTime[] p4 = MonitorTestAlertService.alignPeriod(t, 4);
        assertEquals(LocalDateTime.of(2026, 10, 19, 16, 0), p4[0], "18:30 落在 16:00–20:00");
        assertEquals(LocalDateTime.of(2026, 10, 19, 20, 0), p4[1]);
        LocalDateTime[] p6 = MonitorTestAlertService.alignPeriod(LocalDateTime.of(2026, 10, 19, 18, 0), 6);
        assertEquals(LocalDateTime.of(2026, 10, 19, 18, 0), p6[0]);
        assertEquals(LocalDateTime.of(2026, 10, 20, 0, 0), p6[1]);
        LocalDateTime[] p24 = MonitorTestAlertService.alignPeriod(t, 24);
        assertEquals(LocalDateTime.of(2026, 10, 19, 0, 0), p24[0]);
        assertEquals(LocalDateTime.of(2026, 10, 20, 0, 0), p24[1]);
    }

    @Test
    void parsePeriodFormats()
    {
        LocalDateTime want = LocalDateTime.of(2026, 10, 19, 18, 0);
        assertEquals(want, MonitorTestAlertService.parsePeriod("2026-10-19 18:00"));
        assertEquals(want, MonitorTestAlertService.parsePeriod(" 2026-10-19 18:00:00 "));
        assertEquals(want, MonitorTestAlertService.parsePeriod("2026-10-19T18:00"));
        assertEquals(want, MonitorTestAlertService.parsePeriod("2026-10-19 18"));
        for (String bad : new String[] { null, "", "2026/10/19 18:00", "18:00" })
        {
            MonitorException e = assertThrows(MonitorException.class, () -> MonitorTestAlertService.parsePeriod(bad));
            assertEquals(MonitorErrorCode.BAD_REQUEST, e.getCode());
            assertTrue(e.getMessage().contains("2026-10-19 18:00"));
        }
    }

    @Test
    void newCritSendsAlertNew()
    {
        MonitorTestAlertService.DryRun r = replay(rule(2, null), null, 9, 9, 0, 0);
        assertEquals("CRIT", r.level);
        assertEquals(StepResult.Action.OPEN, r.action);
        assertEquals("CRIT", r.eventLevel);
        assertEquals("SEND", r.decision);
        assertEquals(MonitorNotifyTemplate.ALERT_NEW, r.type);
        assertEquals("新觸發嚴重", r.reason);
        assertEquals(BASE.plusHours(2), r.startHour, "事件从连续越线的第一段算起");
        assertEquals(BASE.plusHours(3), r.triggerHour);
        assertEquals(List.of("OK", "OK", "CRIT", "CRIT"), r.levels);
    }

    @Test
    void notEnoughConsecutiveDoesNotSend()
    {
        MonitorTestAlertService.DryRun r = replay(rule(2, null), null, 9, 9, 0);
        assertEquals("CRIT", r.level);
        assertNull(r.eventLevel);
        assertEquals("KEEP", r.decision);
        assertNull(r.type);
        assertEquals("越線但未連續達到 2 次（目前連續 1 次）", r.reason);
    }

    @Test
    void critPersistingDoesNotRepeat()
    {
        MonitorTestAlertService.DryRun r = replay(rule(2, null), null, 0, 0, 0);
        assertEquals(StepResult.Action.UPDATE, r.action);
        assertEquals("KEEP", r.decision);
        assertEquals("嚴重持續中：未恢復期間不重複發送", r.reason);
        assertEquals(BASE.plusHours(2), r.lastNotifiedAt, "上次（模拟）发送在第 2 段结束时");
    }

    @Test
    void warnThenEscalateSendsAlertEscalated()
    {
        JudgeRule j = rule(1, 2);
        MonitorTestAlertService.DryRun open = replay(j, null, 3);
        assertEquals("WARN", open.eventLevel);
        assertEquals("KEEP", open.decision);
        assertEquals("新觸發注意：注意不發送", open.reason);

        MonitorTestAlertService.DryRun still = replay(j, null, 3, 3);
        assertEquals("WARN", still.eventLevel);
        assertEquals("注意持續中：注意不發送", still.reason);

        MonitorTestAlertService.DryRun esc = replay(j, null, 3, 3, 3);
        assertEquals("WARN", esc.level, "本周期仍是注意，事件因持续 2 小时升为严重");
        assertEquals("CRIT", esc.eventLevel);
        assertTrue(esc.escalated);
        assertEquals("SEND", esc.decision);
        assertEquals(MonitorNotifyTemplate.ALERT_ESCALATED, esc.type);
        assertEquals("注意持續，升級為嚴重", esc.reason);
    }

    @Test
    void warnCrossingCritSendsAlertNew()
    {
        MonitorTestAlertService.DryRun r = replay(rule(1, null), null, 3, 0);
        assertEquals("CRIT", r.eventLevel);
        assertFalse(r.escalated);
        assertEquals("SEND", r.decision);
        assertEquals(MonitorNotifyTemplate.ALERT_NEW, r.type);
        assertEquals("越過嚴重線，由注意變為嚴重", r.reason);
    }

    @Test
    void sameEventSameTypeOnlyOnce()
    {
        // 严重（发）→ 回落注意 → 再严重：事件仍为严重（只升不降），再次越过严重线 → 再发一次 ALERT_NEW（2026-10-27 产品确认）
        MonitorTestAlertService.DryRun r = replay(rule(1, null), null, 0, 3, 0);
        assertEquals("CRIT", r.eventLevel);
        assertFalse(r.escalated);
        assertEquals("SEND", r.decision);
        assertEquals(MonitorNotifyTemplate.ALERT_NEW, r.type);
        assertEquals("回落注意後再次越過嚴重線", r.reason);
    }

    @Test
    void reCrossWithinMuteIsMuted()
    {
        // 严重（发，第 1 段结束）→ 注意 → 再严重：距上次发送不足 24 小时 → 静默期内不发
        MonitorTestAlertService.DryRun r = replay(rule(1, null), 24, 0, 3, 0);
        assertEquals("MUTED", r.decision);
        assertTrue(r.reason.startsWith("靜默期內"), r.reason);
    }

    @Test
    void falloutStepItselfDoesNotSend()
    {
        // 严重 → 回落注意：这一周期不发
        MonitorTestAlertService.DryRun r = replay(rule(1, null), null, 0, 3);
        assertEquals("CRIT", r.eventLevel, "事件等级只升不降");
        assertEquals("KEEP", r.decision);
    }

    @Test
    void muteAcrossEvents()
    {
        // 第 1 段严重并发送 → 恢复 → 新事件严重，距上次发送不足 24 小时
        MonitorTestAlertService.DryRun r = replay(rule(1, null), 24, 0, 9, 0);
        assertEquals(StepResult.Action.OPEN, r.action);
        assertEquals("MUTED", r.decision);
        assertTrue(r.reason.startsWith("靜默期內"), r.reason);
        assertEquals(BASE.plusHours(1), r.lastNotifiedAt);

        MonitorTestAlertService.DryRun noMute = replay(rule(1, null), 1, 0, 9, 0);
        assertEquals("SEND", noMute.decision, "静默 1 小时已过");
        assertNull(noMute.lastNotifiedAt);
    }

    @Test
    void resolveAndSkip()
    {
        MonitorTestAlertService.DryRun resolved = replay(rule(1, null), null, 0, 9);
        assertEquals(StepResult.Action.RESOLVE, resolved.action);
        assertNull(resolved.eventLevel);
        assertEquals("事件恢復：恢復不發送", resolved.reason);

        MonitorTestAlertService.DryRun skip = replay(rule(1, null), null, 9, null);
        assertEquals("SKIP", skip.level);
        assertEquals("KEEP", skip.decision);
        assertTrue(skip.reason.startsWith("本週期不判定"), skip.reason);

        MonitorTestAlertService.DryRun skipInEvent = replay(rule(1, null), null, 0, null);
        assertEquals("CRIT", skipInEvent.eventLevel, "有事件时缺数据，事件保持");
        assertTrue(skipInEvent.reason.contains("事件保持不變"), skipInEvent.reason);

        MonitorTestAlertService.DryRun ok = replay(rule(1, null), null, 9);
        assertEquals("OK", ok.level);
        assertEquals("未越線", ok.reason);
    }

}
