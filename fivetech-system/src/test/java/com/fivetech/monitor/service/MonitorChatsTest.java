package com.fivetech.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 规则的多个通知群：解析、旧数据回退、写库格式、请求里的群 */
class MonitorChatsTest
{
    @Test
    void parseKeepsOrderDropsDuplicatesAndFallsBack()
    {
        List<MonitorChats.Chat> c = MonitorChats.parse(
                "[{\"chatId\":\"oc_b\",\"name\":\"B\",\"status\":\"ok\"},{\"chatId\":\"oc_a\",\"name\":\"A\"},{\"chatId\":\"oc_b\"},{\"name\":\"no id\"}]",
                "oc_x", "X", "ok");
        assertEquals(List.of("oc_b", "oc_a"), c.stream().map(MonitorChats.Chat::chatId).toList(), "按选择顺序，去重、去掉没有 id 的");
        assertFalse(c.get(0).dead());
        assertTrue(c.get(1).dead(), "缓存里没有状态按失效");

        assertEquals(List.of(new MonitorChats.Chat("oc_x", "X", "dead")), MonitorChats.parse("[]", " oc_x ", "X", "dead"),
                "notify_chats 为空（㉑ 回填前）退回 notify_chat_id");
        assertEquals(List.of(), MonitorChats.parse(null, null, null, null));
        assertEquals(List.of(), MonitorChats.parse("not json"));
    }

    @Test
    void toJsonWritesIdAndNameOnly()
    {
        assertEquals("[{\"chatId\":\"oc_a\",\"name\":\"A\"}]", MonitorChats.toJson(List.of(new MonitorChats.Chat("oc_a", "A", "ok"))));
        assertEquals("[]", MonitorChats.toJson(List.of()));
    }

    @Test
    void requestedPrefersArrayAndNormalizes()
    {
        assertEquals(List.of("oc_a", "oc_b"), MonitorChats.requested(Arrays.asList(" oc_a ", null, "", "oc_b", "oc_a"), "oc_z"));
        assertEquals(List.of("oc_z"), MonitorChats.requested(null, " oc_z "), "没传数组时用旧字段");
        assertEquals(List.of(), MonitorChats.requested(List.of(), "oc_z"), "传了空数组就是没选");
        assertEquals(List.of(), MonitorChats.requested(null, " "));
    }

    @Test
    void viewDefaultsNameAndStatus()
    {
        Map<String, Object> v = MonitorChats.view(List.of(new MonitorChats.Chat("oc_a", null, null))).get(0);
        assertEquals("oc_a", v.get("name"));
        assertEquals("dead", v.get("status"));
    }
}
