package com.fivetech.dashboard.subscription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 发送频率与排程（V0.4 原型：每日、每週、每 N 小時）：发送时刻、到期、下次、文字、校验、组合提示 */
class SubscriptionScheduleTest
{
    private static DashboardSubscription freq(String type, int hour, Integer dow, Integer every, Integer to)
    {
        DashboardSubscription s = new DashboardSubscription();
        s.setFreqType(type);
        s.setSendHour(hour);
        s.setFreqDow(dow);
        s.setFreqEvery(every);
        s.setFreqTo(to);
        return s;
    }

    /** 2026-11-02 是週一 */
    private static final LocalDate MON = LocalDate.of(2026, 11, 2);

    @Test
    void hoursOnEachFrequency()
    {
        assertEquals(List.of(9), SubscriptionSchedule.hoursOn(freq("DAY", 9, null, null, null), MON));
        assertEquals(List.of(9), SubscriptionSchedule.hoursOn(freq(null, 9, null, null, null), MON));   // 旧数据没有频率 = 每日
        assertEquals(List.of(10), SubscriptionSchedule.hoursOn(freq("WEEK", 10, 1, null, null), MON));
        assertEquals(List.of(), SubscriptionSchedule.hoursOn(freq("WEEK", 10, 1, null, null), MON.plusDays(1)));
        assertEquals(List.of(10), SubscriptionSchedule.hoursOn(freq("WEEK", 10, 7, null, null), MON.minusDays(1)));   // 週日 = 7
        // 每 4 小時 10:00–22:00：10、14、18、22（含「到」）
        assertEquals(List.of(10, 14, 18, 22), SubscriptionSchedule.hoursOn(freq("HOURS", 10, null, 4, 22), MON));
        // 最后一次不晚于「到」：每 5 小時 10:00–22:00 → 10、15、20
        assertEquals(List.of(10, 15, 20), SubscriptionSchedule.hoursOn(freq("HOURS", 10, null, 5, 22), MON));
        // 從 = 到：每天一次
        assertEquals(List.of(9), SubscriptionSchedule.hoursOn(freq("HOURS", 9, null, 4, 9), MON));
        // 不合法时不排程
        assertEquals(List.of(), SubscriptionSchedule.hoursOn(freq("HOURS", 10, null, 13, 22), MON));
        assertEquals(List.of(), SubscriptionSchedule.hoursOn(freq("HOURS", 10, null, 4, 8), MON));
    }

    @Test
    void dueTakesLatestPassedSlotOnly()
    {
        DashboardSubscription s = freq("HOURS", 10, null, 4, 22);
        // 12:05：10:00 已过等待分钟、在补发期内 → 10:00
        assertEquals(MON.atTime(10, 0), SubscriptionSchedule.due(s, MON.atTime(12, 5), 10, 3, null));
        // 14:05：14:00 还在等待分钟内，10:00 已过补发期 → 不发（不补更早的）
        assertNull(SubscriptionSchedule.due(s, MON.atTime(14, 5), 10, 3, null));
        // 14:10 → 14:00；10:00 已发过的不补
        assertEquals(MON.atTime(14, 0), SubscriptionSchedule.due(s, MON.atTime(14, 10), 10, 3, "2026-11-02 10:00"));
        // 14:00 已发 → 不再发
        assertNull(SubscriptionSchedule.due(s, MON.atTime(15, 0), 10, 3, "2026-11-02 14:00"));
        // 过了补发期（17:00 起 14:00 不补，18:00 还没到）→ 不发
        assertNull(SubscriptionSchedule.due(s, MON.atTime(17, 30), 10, 3, "2026-11-02 10:00"));
        // 跨零点：01:00 时补发前一天 22:00
        assertEquals(MON.atTime(22, 0), SubscriptionSchedule.due(s, MON.plusDays(1).atTime(0, 30), 10, 3, "2026-11-02 18:00"));
        // 每週：週一 10:00
        DashboardSubscription w = freq("WEEK", 10, 1, null, null);
        assertEquals(MON.atTime(10, 0), SubscriptionSchedule.due(w, MON.atTime(10, 15), 10, 3, null));
        assertNull(SubscriptionSchedule.due(w, MON.plusDays(1).atTime(10, 15), 10, 3, null));
    }

    @Test
    void nextSlot()
    {
        DashboardSubscription s = freq("HOURS", 10, null, 4, 22);
        assertEquals(MON.atTime(14, 0), SubscriptionSchedule.next(s, MON.atTime(13, 0), 3, "2026-11-02 10:00"));
        // 23:00、22:00 已发 → 明天 10:00
        assertEquals(MON.plusDays(1).atTime(10, 0), SubscriptionSchedule.next(s, MON.atTime(23, 0), 3, "2026-11-02 22:00"));
        // 每週一 → 下週一
        DashboardSubscription w = freq("WEEK", 10, 1, null, null);
        assertEquals(MON.plusDays(7).atTime(10, 0), SubscriptionSchedule.next(w, MON.atTime(11, 0), 3, "2026-11-02 10:00"));
    }

    @Test
    void text()
    {
        assertEquals("每日 09:00", SubscriptionSchedule.text(freq("DAY", 9, null, null, null)));
        assertEquals("每週一 10:00", SubscriptionSchedule.text(freq("WEEK", 10, 1, null, null)));
        assertEquals("每 4 小時（10:00–22:00）", SubscriptionSchedule.text(freq("HOURS", 10, null, 4, 22)));
    }

    @Test
    void validate()
    {
        Map<String, String> e = new LinkedHashMap<>();
        SubscriptionSchedule.validate(freq("HOURS", 10, null, 13, 8), SubscriptionRange.TODAY, e);
        assertEquals("請輸入 1–12 的整數", e.get("freqEvery"));
        assertEquals("「到」不能早於「從」", e.get("freqTo"));
        e.clear();
        SubscriptionSchedule.validate(freq("WEEK", 9, 8, null, null), SubscriptionRange.YESTERDAY, e);
        assertTrue(e.containsKey("freqDow"));
        e.clear();
        SubscriptionSchedule.validate(freq("MONTH", 9, null, null, null), SubscriptionRange.YESTERDAY, e);
        assertTrue(e.containsKey("freqType"));
        e.clear();
        DashboardSubscription day = freq("day", 0, 3, 4, 22);
        SubscriptionSchedule.validate(day, SubscriptionRange.TODAY, e);
        assertTrue(e.get("sendHour").contains("00:00"));
        // 不相干的字段清掉
        assertEquals("DAY", day.getFreqType());
        assertNull(day.getFreqDow());
        assertNull(day.getFreqEvery());
    }

    @Test
    void warnings()
    {
        assertEquals(List.of("每天只會發送 1 次，建議改為每日。"),
            SubscriptionSchedule.warnings(freq("HOURS", 10, null, 12, 20), SubscriptionRange.TODAY, MON));
        assertTrue(SubscriptionSchedule.warnings(freq("HOURS", 10, null, 4, 22), SubscriptionRange.LAST_7D, MON).get(0)
            .contains("相鄰兩次的資料大部分相同"));
        assertEquals(List.of("每週只發一次，但只統計發送前一天。"),
            SubscriptionSchedule.warnings(freq("WEEK", 10, 1, null, null), SubscriptionRange.YESTERDAY, MON));
        assertEquals(List.of("每天 09:00 發送時，當天只有 9 小時的資料。"),
            SubscriptionSchedule.warnings(freq("DAY", 9, null, null, null), SubscriptionRange.TODAY, MON));
        assertEquals(List.of("固定日期的資料不會再變，建議改為每週發送。"),
            SubscriptionSchedule.warnings(freq("DAY", 9, null, null, null), SubscriptionRange.CUSTOM, MON));
        assertEquals(List.of(), SubscriptionSchedule.warnings(freq("WEEK", 10, 1, null, null), SubscriptionRange.CUSTOM, MON));
    }

    @Test
    void legacyDailyDueStillWorks()
    {
        LocalDateTime nine = MON.atTime(9, 0);
        assertEquals(nine, SubscriptionScheduler.due(nine.plusMinutes(10), 9, 10, 3, "2026-11-01 09:00"));
        assertNull(SubscriptionScheduler.due(nine.plusMinutes(10), 9, 10, 3, "2026-11-02 09:00"));
    }
}
