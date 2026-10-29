package com.fivetech.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fivetech.common.lark.LarkChat;
import com.fivetech.common.lark.LarkClient;
import com.fivetech.common.lark.LarkErrorCategory;
import com.fivetech.common.lark.LarkResult;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.monitor.domain.MonitorLarkChat;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.vo.MonitorLarkChatOptionVO;
import com.fivetech.monitor.domain.vo.MonitorLarkChatsVO;
import com.fivetech.monitor.exception.MonitorErrorCode;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.mapper.MonitorLarkChatMapper;
import com.fivetech.monitor.service.impl.MonitorLarkChatServiceImpl;
import com.fivetech.monitor.service.impl.MonitorLarkChatWriter;

/**
 * GET /monitor/lark/chats 的处理逻辑：用内存里的假 Mapper 模拟 monitor_lark_chat，用子类替换 LarkClient 的网络调用。
 */
class MonitorLarkChatServiceTest
{
    private static final MonitorScope SITE = new MonitorScope("SITE-IN", 1L);

    private FakeMapper mapper;

    private FakeLark lark;

    private MonitorProperties props;

    private MonitorLarkChatServiceImpl service;

    @BeforeEach
    void setUp()
    {
        mapper = new FakeMapper();
        lark = new FakeLark();
        props = new MonitorProperties();
        props.getLark().setAppId("cli_x");
        props.getLark().setAppSecret("secret");
        props.getLark().setChatsCacheSeconds(0);
        service = new MonitorLarkChatServiceImpl(lark, mapper, new MonitorLarkChatWriter(mapper), props, new DashboardProperties());
    }

    @Test
    void successRefreshesCacheAndMarksMissingDead()
    {
        mapper.put("oc_old", "舊群", "ok", LocalDateTime.now().minusDays(1));
        mapper.put("oc_ref", "被引用的舊群", "ok", LocalDateTime.now().minusDays(1));
        mapper.refs.put("oc_ref", 2);
        lark.next = LarkResult.ok(Arrays.asList(new LarkChat("oc_10", "告警 10 群"), new LarkChat("oc_2", "  告警 2 群 "),
                new LarkChat("oc_noname", "")), 200);

        MonitorLarkChatsVO vo = service.listChats(SITE);

        assertTrue(vo.isFetched());
        assertEquals(1, lark.calls);
        assertEquals("ok", mapper.rows.get("oc_10").getStatus());
        assertEquals("告警 2 群", mapper.rows.get("oc_2").getName(), "群名去掉首尾空白");
        assertEquals("oc_noname", mapper.rows.get("oc_noname").getName(), "没有群名用 chat_id");
        assertEquals("dead", mapper.rows.get("oc_old").getStatus());
        assertEquals("dead", mapper.rows.get("oc_ref").getStatus());
        // 不被引用的 dead 群不返回；被引用的返回并带 refCount；按群名自然排序
        assertEquals("oc_noname,oc_2,oc_10,oc_ref", ids(vo));
        MonitorLarkChatOptionVO ref = vo.getChats().get(3);
        assertEquals("dead", ref.getStatus());
        assertEquals(2, ref.getRefCount());
        assertTrue(vo.getFetchedAt().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"), vo.getFetchedAt());
    }

    @Test
    void deadChatComesBackWhenBotIsReAdded()
    {
        mapper.put("oc_a", "A", "dead", LocalDateTime.now().minusDays(1));
        lark.next = LarkResult.ok(Arrays.asList(new LarkChat("oc_a", "A 改名")), 200);
        MonitorLarkChatsVO vo = service.listChats(SITE);
        assertEquals("ok", mapper.rows.get("oc_a").getStatus());
        assertEquals("A 改名", vo.getChats().get(0).getName());
    }

    @Test
    void retryableFailureReturnsLastResult()
    {
        LocalDateTime last = LocalDateTime.of(2026, 10, 20, 13, 5, 30);
        mapper.put("oc_a", "A", "ok", last);
        lark.next = LarkResult.fail(LarkErrorCategory.RETRYABLE, null, "timeout", 0);
        MonitorLarkChatsVO vo = service.listChats(SITE);
        assertFalse(vo.isFetched());
        assertEquals("2026-10-20 13:05", vo.getFetchedAt());
        assertEquals("oc_a", ids(vo));
        assertEquals("ok", mapper.rows.get("oc_a").getStatus(), "拉取失败不能动缓存");
    }

    @Test
    void neverFetchedAndFailingReturnsEmpty()
    {
        lark.next = LarkResult.fail(LarkErrorCategory.RETRYABLE, 99991400, "limit", 400);
        MonitorLarkChatsVO vo = service.listChats(SITE);
        assertFalse(vo.isFetched());
        assertNull(vo.getFetchedAt());
        assertTrue(vo.getChats().isEmpty());
    }

    @Test
    void credentialInvalidIs4802()
    {
        lark.next = LarkResult.tokenFail(LarkErrorCategory.CREDENTIAL_INVALID, 10014, "Lark访问令牌获取失败：app secret invalid", 200);
        MonitorException e = assertThrows(MonitorException.class, () -> service.listChats(SITE));
        assertEquals(MonitorErrorCode.LARK_CREDENTIAL_INVALID, e.getCode());
        assertEquals("Lark 應用憑證無效", e.getMessage());
    }

    @Test
    void repeatedCallWithinCacheSecondsSkipsLark()
    {
        props.getLark().setChatsCacheSeconds(30);
        lark.next = LarkResult.ok(Arrays.asList(new LarkChat("oc_a", "A")), 200);
        assertTrue(service.listChats(SITE).isFetched());
        assertTrue(service.listChats(SITE).isFetched());
        assertEquals(1, lark.calls, "30 秒内第二次不再请求 Lark");
        props.getLark().setChatsCacheSeconds(0);
        service.listChats(SITE);
        assertEquals(2, lark.calls, "0 = 每次实时拉取");
    }

    @Test
    void longNamesAreTruncatedTo100()
    {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 120; i++)
        {
            name.append(i % 2 == 0 ? "群" : "😀");
        }
        lark.next = LarkResult.ok(Arrays.asList(new LarkChat("oc_long", name.toString())), 200);
        service.listChats(SITE);
        String saved = mapper.rows.get("oc_long").getName();
        assertEquals(100, saved.codePointCount(0, saved.length()), "按字符截断，不切开 emoji");
    }

    @Test
    void sendResultBotRemovedOrChatDissolvedMarksChatDead()
    {
        mapper.put("oc_a", "A", "ok", LocalDateTime.now());
        mapper.put("oc_b", "B", "ok", LocalDateTime.now());
        assertTrue(service.markDeadIfChatGone(SITE, "oc_a",
                LarkResult.fail(LarkErrorCategory.TARGET_INVALID, 230002, "The bot can not be outside the group.", 400)), "机器人被踢出");
        assertEquals("dead", mapper.rows.get("oc_a").getStatus());
        assertTrue(service.markDeadIfChatGone(SITE, "oc_b",
                LarkResult.fail(LarkErrorCategory.TARGET_INVALID, 232009, "chat has already been dissolved", 400)), "群已解散");
        assertEquals("dead", mapper.rows.get("oc_b").getStatus());
        assertFalse(service.markDeadIfChatGone(SITE, "oc_a",
                LarkResult.fail(LarkErrorCategory.TARGET_INVALID, 230002, "x", 400)), "已经是失效：不重复改");
    }

    @Test
    void otherSendResultsDoNotTouchChat()
    {
        mapper.put("oc_a", "A", "ok", LocalDateTime.now());
        assertFalse(service.markDeadIfChatGone(SITE, "oc_a", LarkResult.ok("om_1", 200)));
        assertFalse(service.markDeadIfChatGone(SITE, "oc_a",
                LarkResult.fail(LarkErrorCategory.TARGET_INVALID, 230035, "Send Message Permission deny.", 400)), "被禁言：群还在");
        assertFalse(service.markDeadIfChatGone(SITE, "oc_a", LarkResult.fail(LarkErrorCategory.RETRYABLE, null, "timeout", 0)));
        assertFalse(service.markDeadIfChatGone(SITE, "oc_a",
                LarkResult.tokenFail(LarkErrorCategory.CREDENTIAL_INVALID, 10014, "app secret invalid", 200)));
        assertEquals("ok", mapper.rows.get("oc_a").getStatus());
        assertEquals(0, lark.calls, "不调 Lark");
    }

    @Test
    void chatTestSendsTextAndReturnsName()
    {
        mapper.put("oc_a", "運營群", "ok", LocalDateTime.now());
        lark.sendNext = LarkResult.ok("om_1", 200);
        Map<String, Object> out = service.testChat(SITE, " oc_a ", "ops@yata.demo");
        assertEquals("運營群", out.get("name"));
        assertEquals("om_1", out.get("messageId"));
        assertEquals("oc_a", lark.sentTo);
        String json = lark.sentCard.toString();
        assertTrue(json.contains("template=grey"), json);
        assertTrue(json.contains("content=【測試】Yata 群連接測試"), json);
        assertTrue(json.contains("**機器人**：Yata 告警 · 一組"), json);
        assertTrue(json.contains("**群**：運營群"), json);
        assertTrue(json.contains("**發送人**：ops@yata.demo"), json);
        assertFalse(json.contains("（IST）"), "時間不写时区：" + json);
        assertTrue(json.matches("(?s).*\\*\\*時間\\*\\*：\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}[^（\\d].*"), json);
        assertFalse(json.contains("button"), "群连接测试没有按钮");
    }

    @Test
    void chatTestFallsBackToAppIdWhenBotNameUnavailable()
    {
        mapper.put("oc_a", "A_*x*", "ok", LocalDateTime.now());
        lark.botNext = LarkResult.fail(LarkErrorCategory.RETRYABLE, null, "timeout", 0);
        lark.sendNext = LarkResult.ok("om_1", 200);
        service.testChat(SITE, "oc_a", "ops@yata.demo");
        String json = lark.sentCard.toString();
        assertTrue(json.contains("**機器人**：cli&#95;x"), json);
        assertTrue(json.contains("**群**：A&#95;&#42;x&#42;"), "群名里的 markdown 字符转义：" + json);
    }

    @Test
    void chatTestBotInfoCredentialInvalidIs4802()
    {
        mapper.put("oc_a", "A", "ok", LocalDateTime.now());
        lark.botNext = LarkResult.tokenFail(LarkErrorCategory.CREDENTIAL_INVALID, 10014, "app secret invalid", 200);
        assertEquals(MonitorErrorCode.LARK_CREDENTIAL_INVALID,
                assertThrows(MonitorException.class, () -> service.testChat(SITE, "oc_a", "ops@yata.demo")).getCode());
        assertNull(lark.sentTo);
    }

    @Test
    void chatTestRejectsUnknownOrDeadChatWithoutCallingLark()
    {
        mapper.put("oc_dead", "D", "dead", LocalDateTime.now());
        assertEquals(MonitorErrorCode.NOT_FOUND,
                assertThrows(MonitorException.class, () -> service.testChat(SITE, "oc_x", "ops@yata.demo")).getCode());
        assertEquals(MonitorErrorCode.CHAT_DEAD,
                assertThrows(MonitorException.class, () -> service.testChat(SITE, "oc_dead", "ops@yata.demo")).getCode());
        assertNull(lark.sentTo);
    }

    @Test
    void chatTestFailures()
    {
        mapper.put("oc_a", "A", "ok", LocalDateTime.now());
        lark.sendNext = LarkResult.tokenFail(LarkErrorCategory.CREDENTIAL_INVALID, 10014, "app secret invalid", 200);
        assertEquals(MonitorErrorCode.LARK_CREDENTIAL_INVALID,
                assertThrows(MonitorException.class, () -> service.testChat(SITE, "oc_a", "ops@yata.demo")).getCode());
        lark.sendNext = LarkResult.fail(LarkErrorCategory.RETRYABLE, null, "timeout", 0);
        MonitorException e = assertThrows(MonitorException.class, () -> service.testChat(SITE, "oc_a", "ops@yata.demo"));
        assertEquals(MonitorErrorCode.LARK_SEND_FAILED, e.getCode());
        assertTrue(e.getMessage().startsWith("測試訊息發送失敗："));
        lark.sendNext = LarkResult.fail(LarkErrorCategory.TARGET_INVALID, 230002, "The bot can not be outside the group.", 400);
        assertEquals(MonitorErrorCode.CHAT_DEAD,
                assertThrows(MonitorException.class, () -> service.testChat(SITE, "oc_a", "ops@yata.demo")).getCode());
        assertEquals("dead", mapper.rows.get("oc_a").getStatus(), "机器人不在群里：群置为失效");
    }

    @Test
    void chatTestWithoutCredentialsIs4802()
    {
        mapper.put("oc_a", "A", "ok", LocalDateTime.now());
        props.getLark().setAppSecret("");
        assertEquals(MonitorErrorCode.LARK_CREDENTIAL_INVALID,
                assertThrows(MonitorException.class, () -> service.testChat(SITE, "oc_a", "ops@yata.demo")).getCode());
        assertNull(lark.sentTo);
    }

    private static String ids(MonitorLarkChatsVO vo)
    {
        return vo.getChats().stream().map(MonitorLarkChatOptionVO::getChatId).collect(Collectors.joining(","));
    }

    /** 只替换 listChats，不发网络请求 */
    static class FakeLark extends LarkClient
    {
        LarkResult<List<LarkChat>> next;

        int calls;

        FakeLark()
        {
            super(null, "http://127.0.0.1:1", 1);
        }

        @Override
        public LarkResult<List<LarkChat>> listChats(String appId, String appSecret)
        {
            assertEquals("cli_x", appId);
            assertEquals("secret", appSecret);
            calls++;
            return next;
        }

        LarkResult<String> sendNext;

        LarkResult<String> botNext = LarkResult.ok("Yata 告警 · 一組", 200);

        String sentTo;

        Map<String, Object> sentCard;

        @Override
        public LarkResult<String> getBotName(String appId, String appSecret)
        {
            return botNext;
        }

        @Override
        public LarkResult<String> sendCard(String appId, String appSecret, String receiveIdType, String receiveId,
                Map<String, Object> card)
        {
            assertEquals("chat_id", receiveIdType);
            sentTo = receiveId;
            sentCard = card;
            return sendNext;
        }
    }

    /** 内存版 monitor_lark_chat，语义与 MonitorLarkChatMapper.xml 一致 */
    static class FakeMapper implements MonitorLarkChatMapper
    {
        final Map<String, MonitorLarkChat> rows = new LinkedHashMap<>();

        final Map<String, Integer> refs = new LinkedHashMap<>();

        void put(String chatId, String name, String status, LocalDateTime lastSeen)
        {
            MonitorLarkChat c = new MonitorLarkChat();
            c.setSiteCode(SITE.getSiteCode());
            c.setChatId(chatId);
            c.setName(name);
            c.setStatus(status);
            c.setLastSeenAt(lastSeen);
            rows.put(chatId, c);
        }

        @Override
        public MonitorLarkChat selectChat(MonitorScope scope, String chatId)
        {
            return rows.get(chatId);
        }

        @Override
        public LocalDateTime selectLastSeenAt(MonitorScope scope)
        {
            return rows.values().stream().map(MonitorLarkChat::getLastSeenAt).filter(t -> t != null)
                    .max(LocalDateTime::compareTo).orElse(null);
        }

        @Override
        public int upsertSeenChats(MonitorScope scope, List<MonitorLarkChat> chats, LocalDateTime now)
        {
            for (MonitorLarkChat c : chats)
            {
                MonitorLarkChat old = rows.get(c.getChatId());
                if (old == null || old.getLastSeenAt() == null || !old.getLastSeenAt().isAfter(now))
                {
                    put(c.getChatId(), c.getName(), "ok", now);
                }
            }
            return chats.size();
        }

        @Override
        public int markUnseenDead(MonitorScope scope, LocalDateTime now)
        {
            int n = 0;
            for (MonitorLarkChat c : rows.values())
            {
                if ("ok".equals(c.getStatus()) && (c.getLastSeenAt() == null || c.getLastSeenAt().isBefore(now)))
                {
                    c.setStatus("dead");
                    n++;
                }
            }
            return n;
        }

        @Override
        public int markChatDead(MonitorScope scope, String chatId, LocalDateTime now)
        {
            MonitorLarkChat c = rows.get(chatId);
            if (c == null || !"ok".equals(c.getStatus()))
            {
                return 0;
            }
            c.setStatus("dead");
            return 1;
        }

        @Override
        public List<MonitorLarkChatOptionVO> selectChatOptions(MonitorScope scope)
        {
            List<MonitorLarkChatOptionVO> out = new ArrayList<>();
            for (MonitorLarkChat c : rows.values())
            {
                int ref = refs.getOrDefault(c.getChatId(), 0);
                if ("ok".equals(c.getStatus()) || ref > 0)
                {
                    MonitorLarkChatOptionVO vo = new MonitorLarkChatOptionVO();
                    vo.setChatId(c.getChatId());
                    vo.setName(c.getName());
                    vo.setStatus(c.getStatus());
                    vo.setRefCount(ref);
                    out.add(vo);
                }
            }
            return out;
        }
    }
}
