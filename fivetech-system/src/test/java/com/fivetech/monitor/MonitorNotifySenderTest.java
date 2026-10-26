package com.fivetech.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import com.fivetech.common.lark.LarkClient;
import com.fivetech.common.lark.LarkErrorCategory;
import com.fivetech.common.lark.LarkResult;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.vo.MonitorLarkChatsVO;
import com.fivetech.monitor.engine.MonitorNotifySender;
import com.fivetech.monitor.engine.MonitorNotifyWriter;
import com.fivetech.monitor.engine.MonitorOpsAlerter;
import com.fivetech.monitor.engine.domain.MonitorPendingNotify;
import com.fivetech.monitor.mapper.MonitorNotifyMapper;
import com.fivetech.monitor.service.IMonitorLarkChatService;

/**
 * 发送任务：用内存里的假 Mapper 模拟 monitor_notify_log / monitor_event / monitor_rule，用子类替换 LarkClient 的网络调用。
 */
class MonitorNotifySenderTest
{
    private static final String SITE = "SITE-IN";

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 11, 0, 30);

    private FakeMapper mapper;

    private FakeLark lark;

    private FakeChats chats;

    private List<String> opsAlerts;

    private MonitorNotifySender sender;

    @BeforeEach
    void setUp()
    {
        mapper = new FakeMapper();
        lark = new FakeLark();
        chats = new FakeChats();
        opsAlerts = new ArrayList<>();
        MonitorProperties props = new MonitorProperties();
        props.getLark().setAppId("cli_x");
        props.getLark().setAppSecret("s3cr3t-value");
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ops", (MonitorOpsAlerter) (title, detail) -> opsAlerts.add(title + "|" + detail));
        sender = new MonitorNotifySender(mapper, new MonitorNotifyWriter(mapper), lark, chats, props,
                beans.getBeanProvider(MonitorOpsAlerter.class));
    }

    private long noDeadline()
    {
        return Long.MAX_VALUE;
    }

    @Test
    void successUpdatesLogEventAndRule()
    {
        mapper.add(1L, 100L, 7L, "open", "oc_a", 0);
        lark.results.add(LarkResult.ok("om_1", 200));

        assertEquals(1, sender.sendSite(SITE, NOW, noDeadline()));

        Row r = mapper.rows.get(1L);
        assertEquals("sent", r.status);
        assertEquals(NOW, r.sentAt);
        assertTrue(r.success);
        assertEquals("sent", mapper.eventStatus.get(100L));
        assertEquals(1, mapper.eventCount.get(100L));
        assertEquals(NOW, mapper.ruleNotified.get(7L), "静默期从发送成功的时间算");
        assertEquals("oc_a", lark.chatIds.get(0));
        assertEquals("chat_id", lark.receiveIdType);
    }

    @Test
    void retryableFailureRetriesAfter1_5_30MinutesThenFails()
    {
        mapper.add(1L, 100L, 7L, "open", "oc_a", 0);
        LarkResult<String> timeout = LarkResult.fail(LarkErrorCategory.RETRYABLE, null, "網絡錯誤：HttpTimeoutException", 0);

        int[] delays = { 1, 5, 30 };
        LocalDateTime now = NOW;
        for (int i = 0; i < delays.length; i++)
        {
            lark.results.add(timeout);
            sender.sendSite(SITE, now, noDeadline());
            Row r = mapper.rows.get(1L);
            assertEquals("pending", r.status, "第 " + (i + 1) + " 次失败后仍待发送");
            assertEquals(i + 1, r.retryCount);
            assertEquals(now.plusMinutes(delays[i]), r.nextRetryAt);
            assertFalse(mapper.eventStatus.containsKey(100L), "重试期间不动事件");
            // 没到下次重试时间不会再取出来
            assertEquals(0, sender.sendSite(SITE, now.plusSeconds(1), noDeadline()));
            now = r.nextRetryAt;
        }
        lark.results.add(timeout);
        sender.sendSite(SITE, now, noDeadline());
        Row r = mapper.rows.get(1L);
        assertEquals("failed", r.status, "重试 3 次后放弃");
        assertTrue(r.error.startsWith("網絡超時或 Lark 限流，已重試 3 次"), r.error);
        assertEquals("failed", mapper.eventStatus.get(100L));
        assertNull(mapper.ruleNotified.get(7L));
        assertEquals(4, lark.chatIds.size());
    }

    @Test
    void targetInvalidFailsImmediatelyAndMarksChatDead()
    {
        mapper.add(1L, 100L, 7L, "open", "oc_gone", 0);
        lark.results.add(LarkResult.fail(LarkErrorCategory.TARGET_INVALID, 230002, "Bot/User can NOT be out of the chat.", 400));

        sender.sendSite(SITE, NOW, noDeadline());

        assertEquals("failed", mapper.rows.get(1L).status);
        assertEquals("機器人已不在群裡或群已解散（code=230002 http=400）", mapper.rows.get(1L).error);
        assertEquals("failed", mapper.eventStatus.get(100L));
        assertEquals(List.of("oc_gone"), chats.markedDead);
    }

    @Test
    void credentialInvalidFailsWholeBatchWithoutCallingLarkAgainAndAlertsOps()
    {
        mapper.add(1L, 100L, 7L, "open", "oc_a", 0);
        mapper.add(2L, 101L, 8L, "open", "oc_b", 0);
        mapper.add(3L, 102L, 9L, "open", "oc_c", 2);
        lark.results.add(LarkResult.tokenFail(LarkErrorCategory.CREDENTIAL_INVALID, 10014, "app secret invalid", 400));

        assertEquals(3, sender.sendSite(SITE, NOW, noDeadline()));

        assertEquals(1, lark.chatIds.size(), "凭证无效后不再请求 Lark");
        for (long id = 1; id <= 3; id++)
        {
            assertEquals("failed", mapper.rows.get(id).status, "发送记录关掉，不再重试");
        }
        assertEquals("paused", mapper.eventStatus.get(100L), "暂停发送，不算发送失败（僅記錄）");
        assertEquals("paused", mapper.eventStatus.get(102L));
        assertTrue(mapper.rows.get(1L).error.startsWith("Lark 機器人憑證失效，未發送"), mapper.rows.get(1L).error);
        assertEquals(1, opsAlerts.size());
        assertTrue(opsAlerts.get(0).contains("憑證"));
        assertFalse(opsAlerts.get(0).contains("s3cr3t-value"), "值班告警不带凭证");
    }

    @Test
    void stoppedEventIsNotSentAndMarkedFailedWithReason()
    {
        mapper.add(1L, 100L, 7L, "stopped", "oc_a", 0);

        sender.sendSite(SITE, NOW, noDeadline());

        assertEquals("failed", mapper.rows.get(1L).status);
        assertTrue(mapper.rows.get(1L).error.contains("取消發送"));
        assertTrue(lark.chatIds.isEmpty());
        assertEquals("failed", mapper.eventStatus.get(100L), "不能一直停在待发送");
    }

    @Test
    void noCredentialsMeansNotConfigured()
    {
        MonitorProperties props = new MonitorProperties();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        MonitorNotifySender noBot = new MonitorNotifySender(mapper, new MonitorNotifyWriter(mapper), lark, chats, props,
                beans.getBeanProvider(MonitorOpsAlerter.class));
        mapper.add(1L, 100L, 7L, "open", "oc_a", 0);

        noBot.sendSite(SITE, NOW, noDeadline());

        assertTrue(lark.chatIds.isEmpty(), "没配凭证不调 Lark");
        assertEquals("unset", mapper.eventStatus.get(100L), "事件「未配置」");
        assertEquals("failed", mapper.rows.get(1L).status);
    }

    @Test
    void stopsWhenBudgetExceeded()
    {
        mapper.add(1L, 100L, 7L, "open", "oc_a", 0);
        mapper.add(2L, 101L, 8L, "open", "oc_b", 0);

        assertEquals(0, sender.sendSite(SITE, NOW, System.currentTimeMillis() - 1));

        assertEquals("pending", mapper.rows.get(1L).status);
        assertTrue(lark.chatIds.isEmpty());
    }

    @Test
    void alreadyHandledRowIsNotWrittenTwice()
    {
        mapper.add(1L, 100L, 7L, "open", "oc_a", 0);
        lark.results.add(LarkResult.ok("om_1", 200));
        sender.sendSite(SITE, NOW, noDeadline());
        // 另一个实例拿着旧的行再回写一次：记录已不是 pending，事件和规则不应重复计数
        MonitorPendingNotify stale = mapper.toPending(mapper.rows.get(1L));
        new MonitorNotifyWriter(mapper).sent(new MonitorScope(SITE, null), stale, NOW.plusMinutes(1));
        assertEquals(1, mapper.eventCount.get(100L));
        assertEquals(NOW, mapper.ruleNotified.get(7L));
    }

    // ------------------------------------------------------------------ 假实现

    static class Row
    {
        Long id;
        Long eventId;
        Long ruleId;
        String eventStatusAtSelect;
        String chatId;
        String status = "pending";
        int retryCount;
        LocalDateTime nextRetryAt;
        LocalDateTime sentAt;
        Boolean success;
        String error;
    }

    static class FakeMapper implements MonitorNotifyMapper
    {
        final Map<Long, Row> rows = new LinkedHashMap<>();
        final Map<Long, String> eventStatus = new LinkedHashMap<>();
        final Map<Long, Integer> eventCount = new LinkedHashMap<>();
        final Map<Long, LocalDateTime> ruleNotified = new LinkedHashMap<>();

        void add(Long id, Long eventId, Long ruleId, String eventState, String chatId, int retryCount)
        {
            Row r = new Row();
            r.id = id;
            r.eventId = eventId;
            r.ruleId = ruleId;
            r.eventStatusAtSelect = eventState;
            r.chatId = chatId;
            r.retryCount = retryCount;
            rows.put(id, r);
        }

        MonitorPendingNotify toPending(Row r)
        {
            MonitorPendingNotify p = new MonitorPendingNotify();
            p.setId(r.id);
            p.setSiteCode(SITE);
            p.setMsgType("alert");
            p.setEventId(r.eventId);
            p.setRuleId(r.ruleId);
            p.setEventStatus(r.eventStatusAtSelect);
            p.setChatId(r.chatId);
            p.setContent("【嚴重】測試告警");
            p.setRetryCount(r.retryCount);
            return p;
        }

        private boolean due(Row r, LocalDateTime now)
        {
            return "pending".equals(r.status) && (r.nextRetryAt == null || !r.nextRetryAt.isAfter(now));
        }

        @Override
        public List<String> selectNotifySites(LocalDateTime now)
        {
            return rows.values().stream().anyMatch(r -> due(r, now)) ? List.of(SITE) : List.of();
        }

        @Override
        public List<MonitorPendingNotify> selectDueNotify(MonitorScope scope, LocalDateTime now, int limit)
        {
            assertEquals(SITE, scope.getSiteCode());
            List<MonitorPendingNotify> list = new ArrayList<>();
            for (Row r : rows.values())
            {
                if (due(r, now) && list.size() < limit)
                {
                    list.add(toPending(r));
                }
            }
            return list;
        }

        @Override
        public int markSent(MonitorScope scope, Long id, LocalDateTime now)
        {
            Row r = rows.get(id);
            if (!"pending".equals(r.status))
            {
                return 0;
            }
            r.status = "sent";
            r.success = true;
            r.sentAt = now;
            r.nextRetryAt = null;
            return 1;
        }

        @Override
        public int markRetry(MonitorScope scope, Long id, int retryCount, LocalDateTime nextRetryAt, String error)
        {
            Row r = rows.get(id);
            r.retryCount = retryCount;
            r.nextRetryAt = nextRetryAt;
            r.success = false;
            r.error = error;
            return 1;
        }

        @Override
        public int markFailed(MonitorScope scope, Long id, LocalDateTime now, String error)
        {
            Row r = rows.get(id);
            if (!"pending".equals(r.status))
            {
                return 0;
            }
            r.status = "failed";
            r.success = false;
            r.sentAt = now;
            r.error = error;
            return 1;
        }

        @Override
        public int updateEventSent(MonitorScope scope, Long eventId, LocalDateTime now)
        {
            eventStatus.put(eventId, "sent");
            eventCount.merge(eventId, 1, Integer::sum);
            return 1;
        }

        @Override
        public int updateEventNotifyStatus(MonitorScope scope, Long eventId, String status, LocalDateTime now)
        {
            eventStatus.put(eventId, status);
            return 1;
        }

        @Override
        public int updateRuleNotified(MonitorScope scope, Long ruleId, LocalDateTime now)
        {
            ruleNotified.put(ruleId, now);
            return 1;
        }
    }

    static class FakeLark extends LarkClient
    {
        final List<LarkResult<String>> results = new ArrayList<>();
        final List<String> chatIds = new ArrayList<>();
        String receiveIdType;

        FakeLark()
        {
            super(null, "http://127.0.0.1:1", 1);
        }

        @Override
        public LarkResult<String> sendPost(String appId, String appSecret, String receiveIdType, String receiveId,
                String title, List<List<Map<String, String>>> paragraphs)
        {
            assertEquals("cli_x", appId);
            assertEquals("s3cr3t-value", appSecret);
            this.receiveIdType = receiveIdType;
            chatIds.add(receiveId);
            return results.remove(0);
        }
    }

    static class FakeChats implements IMonitorLarkChatService
    {
        final List<String> markedDead = new ArrayList<>();

        @Override
        public MonitorLarkChatsVO listChats(MonitorScope scope)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean markDeadIfChatGone(MonitorScope scope, String chatId, LarkResult<?> sendResult)
        {
            if (sendResult.isChatGone())
            {
                markedDead.add(chatId);
                return true;
            }
            return false;
        }

        @Override
        public java.util.Map<String, Object> testChat(MonitorScope scope, String chatId, String sender)
        {
            throw new UnsupportedOperationException();
        }
    }
}
