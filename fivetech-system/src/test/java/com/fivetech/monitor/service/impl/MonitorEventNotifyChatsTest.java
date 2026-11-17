package com.fivetech.monitor.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;
import com.fivetech.monitor.domain.vo.MonitorEventVO;
import com.fivetech.monitor.service.MonitorChats;

/** 异常事件「通知送達」按群汇总（规则可选多个群） */
class MonitorEventNotifyChatsTest
{
    private static final List<MonitorChats.Chat> SNAP = List.of(new MonitorChats.Chat("oc_a", "A 群", null),
            new MonitorChats.Chat("oc_b", "B 群", null));

    private static MonitorEventVO.NotifyVO notify(String status)
    {
        MonitorEventVO.NotifyVO n = new MonitorEventVO.NotifyVO();
        n.setStatus(status);
        n.setChatId("oc_a");
        n.setChatName("A 群");
        return n;
    }

    private static String row(String id, String status, String error)
    {
        return "{\"chatId\":\"" + id + "\",\"name\":null,\"chatStatus\":\"ok\",\"status\":\"" + status + "\",\"sentAt\":"
                + ("sent".equals(status) ? "\"2026-11-16T12:13:00\"" : "null") + ",\"error\":" + (error == null ? "null" : "\"" + error + "\"") + "}";
    }

    @Test
    void allSentPartialFailedPending()
    {
        MonitorEventVO.NotifyVO n = notify("sent");
        MonitorEventServiceImpl.applyChats(n, "[" + row("oc_a", "sent", null) + "," + row("oc_b", "sent", null) + "]", SNAP);
        assertEquals("sent", n.getStatus());
        assertEquals(2, n.getTotal());
        assertEquals(2, n.getSentCount());
        assertEquals("B 群", n.getChats().get(1).getName(), "批次里没有群名时取触发时快照");

        n = notify("sent");
        MonitorEventServiceImpl.applyChats(n, "[" + row("oc_a", "sent", null) + "," + row("oc_b", "failed", "機器人已不在群裡") + "]", SNAP);
        assertEquals("partial", n.getStatus(), "部分送達 1/2");
        assertEquals(1, n.getSentCount());
        assertEquals("oc_b", n.getChatId(), "旧字段取第一个失败的群");
        assertEquals("機器人已不在群裡", n.getError());

        n = notify("failed");
        MonitorEventServiceImpl.applyChats(n, "[" + row("oc_a", "failed", null) + "," + row("oc_b", "failed", "x") + "]", SNAP);
        assertEquals("failed", n.getStatus());
        assertEquals("發送失敗", n.getChats().get(0).getError(), "没有原因时写「發送失敗」");

        n = notify("sent");
        MonitorEventServiceImpl.applyChats(n, "[" + row("oc_a", "sent", null) + "," + row("oc_b", "pending", null) + "]", SNAP);
        assertEquals("pending", n.getStatus(), "还有群在重试");
    }

    @Test
    void notSendingStatusesKeepStatusAndListSnapshotChats()
    {
        MonitorEventVO.NotifyVO n = notify("muted");
        MonitorEventServiceImpl.applyChats(n, null, SNAP);
        assertEquals("muted", n.getStatus());
        assertEquals(List.of("oc_a", "oc_b"), n.getChats().stream().map(MonitorEventVO.ChatResultVO::getChatId).toList());
        assertNull(n.getChats().get(0).getStatus());

        n = notify("sent");
        MonitorEventServiceImpl.applyChats(n, null, List.of());
        assertEquals(1, n.getChats().size(), "老事件没有快照：用旧字段的群");
        assertEquals(1, n.getSentCount());
    }
}
