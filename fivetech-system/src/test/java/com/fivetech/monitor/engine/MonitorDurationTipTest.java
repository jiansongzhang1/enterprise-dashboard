package com.fivetech.monitor.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** 异常事件「持續」列悬停：已停止判定的三种原因（MON-28 / MON-29：修改判定条件） */
class MonitorDurationTipTest
{
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 27, 2, 0);

    private static final LocalDateTime STOPPED = LocalDateTime.of(2026, 10, 27, 8, 40);

    @Test
    void stoppedByUpdate()
    {
        assertEquals("10-27 02:00 起；08:40 規則修改，之後按新規則重新判定",
                MonitorMessageBuilder.durationTip("stopped", false, "update", START, null, STOPPED));
    }

    @Test
    void stoppedByPauseOrDelete()
    {
        assertEquals("10-27 02:00 起；08:40 規則暫停，之後不再判定",
                MonitorMessageBuilder.durationTip("stopped", false, "pause", START, null, STOPPED));
        assertEquals("10-27 02:00 起；08:40 規則刪除，之後不再判定",
                MonitorMessageBuilder.durationTip("stopped", false, "delete", START, null, STOPPED));
        assertEquals("10-27 02:00 起；08:40 規則刪除，之後不再判定",
                MonitorMessageBuilder.durationTip("stopped", true, null, START, null, STOPPED), "老数据按 ruleDeleted");
        assertEquals("10-27 02:00 起；08:40 規則暫停，之後不再判定",
                MonitorMessageBuilder.durationTip("stopped", false, START, null, STOPPED), "旧签名不变");
    }
}
