package com.fivetech.monitor.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fivetech.common.lark.LarkClient;
import com.fivetech.common.lark.LarkErrorCategory;
import com.fivetech.common.lark.LarkResult;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.monitor.domain.MonitorOrderEvent;
import com.fivetech.monitor.domain.MonitorRule;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.exception.MonitorErrorCode;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.mapper.MonitorOrderEventMapper;
import com.fivetech.monitor.mapper.MonitorRuleMapper;
import com.fivetech.monitor.service.IMonitorLarkChatService;
import tools.jackson.databind.json.JsonMapper;

/** 按规则发告警：群取规则上配置的、站点前缀、各种拒绝与 Lark 失败的错误码；订单规则写订单告警事件（不连库、不连 Lark） */
class MonitorRuleAlertServiceTest
{
    private static final MonitorScope SCOPE = new MonitorScope("SITE-IN", 1L);

    private MonitorRule rule;

    private MonitorProperties props;

    private FakeLark lark;

    private final List<String> markedDead = new ArrayList<>();

    /** 内存里的 monitor_order_event */
    private final List<MonitorOrderEvent> events = new ArrayList<>();

    private MonitorRuleAlertService service;

    @BeforeEach
    void setUp()
    {
        rule = new MonitorRule();
        rule.setRuleId(23L);
        rule.setSiteCode("SITE-IN");
        rule.setRuleNo(23);
        rule.setEnabled(true);
        rule.setNotifyChatId(" oc_risk ");
        rule.setNotifyChatName("風控告警群");
        rule.setChatStatus("normal");
        props = new MonitorProperties();
        props.getLark().setAppId("cli_x");
        props.getLark().setAppSecret("secret");
        lark = new FakeLark();
        MonitorRuleMapper mapper = (MonitorRuleMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { MonitorRuleMapper.class }, (p, m, a) -> {
                    if ("selectRuleById".equals(m.getName()))
                    {
                        MonitorScope s = (MonitorScope) a[0];
                        return "SITE-IN".equals(s.getSiteCode()) && Long.valueOf(23L).equals(a[1]) ? rule : null;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        IMonitorLarkChatService chats = (IMonitorLarkChatService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { IMonitorLarkChatService.class }, (p, m, a) -> {
                    if ("markDeadIfChatGone".equals(m.getName()))
                    {
                        markedDead.add((String) a[1]);
                        return true;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        MonitorOrderEventMapper eventMapper = (MonitorOrderEventMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { MonitorOrderEventMapper.class }, (p, m, a) -> {
                    switch (m.getName())
                    {
                        case "selectByOrderNos":
                        {
                            @SuppressWarnings("unchecked")
                            List<String> nos = (List<String>) a[2];
                            String site = ((MonitorScope) a[0]).getSiteCode();
                            return events.stream().filter(e -> e.getSiteCode().equals(site) && e.getRuleId().equals(a[1])
                                    && nos.contains(e.getOrderNo())).toList();
                        }
                        case "insertEvent":
                        {
                            MonitorOrderEvent e = (MonitorOrderEvent) a[0];
                            e.setEventId(100L + events.size());
                            events.add(e);
                            return 1;
                        }
                        case "updateNotify":
                        {
                            for (MonitorOrderEvent e : events)
                            {
                                if (e.getEventId().equals(a[1]))
                                {
                                    e.setNotifyStatus((String) a[2]);
                                    e.setNotifyReason(null);
                                    e.setNotifyChats((String) a[3]);
                                }
                            }
                            return 1;
                        }
                        default:
                            throw new UnsupportedOperationException(m.getName());
                    }
                });
        service = new MonitorRuleAlertService(mapper, eventMapper, chats, lark, props, new DashboardProperties(),
                JsonMapper.builder().build());
    }

    private static int code(Runnable r)
    {
        try
        {
            r.run();
        }
        catch (MonitorException e)
        {
            return e.getCode();
        }
        return 0;
    }

    @Test
    void sendsToTheRuleChatWithSitePrefix()
    {
        Map<String, Object> out = service.send(SCOPE, 23L, "  R-023 大額提款提醒\n訂單 W1 ₹80,000  ", "alert_bot");
        assertEquals("oc_risk", lark.chatId, "群取规则上配置的，去掉首尾空白");
        assertEquals("【uWin】R-023 大額提款提醒\n訂單 W1 ₹80,000\n", lark.text);
        assertEquals("R-023", out.get("ruleCode"));
        assertEquals("oc_risk", out.get("chatId"));
        assertEquals("風控告警群", out.get("chatName"));
        assertEquals("om_1", out.get("messageId"));
        assertEquals("【uWin】R-023 大額提款提醒\n訂單 W1 ₹80,000", out.get("content"));
    }

    @Test
    void sitePrefixIsNotDoubled()
    {
        assertEquals("【uWin】x", MonitorRuleAlertService.withSitePrefix("uWin", "【uWin】x"));
        assertEquals("【uWin】x", MonitorRuleAlertService.withSitePrefix("uWin", "x"));
        assertEquals("x", MonitorRuleAlertService.withSitePrefix("", "x"));
        assertEquals("R-007", MonitorRuleAlertService.ruleCode(7));
    }

    @Test
    void rejectsBeforeCallingLark()
    {
        assertEquals(MonitorErrorCode.BAD_REQUEST, code(() -> service.send(SCOPE, 23L, "  ", "u")), "message 必填");
        assertEquals(MonitorErrorCode.BAD_REQUEST, code(() -> service.send(SCOPE, 23L, "x".repeat(4001), "u")), "超长");
        assertEquals(MonitorErrorCode.NOT_FOUND, code(() -> service.send(SCOPE, 99L, "x", "u")), "规则不存在");
        assertEquals(MonitorErrorCode.NOT_FOUND, code(() -> service.send(new MonitorScope("SITE-JITO", 1L), 23L, "x", "u")), "别的站点");
        rule.setEnabled(false);
        assertEquals(MonitorErrorCode.BAD_REQUEST, code(() -> service.send(SCOPE, 23L, "x", "u")), "已暂停");
        rule.setEnabled(true);
        rule.setNotifyEnabled(false);
        assertEquals(MonitorErrorCode.BAD_REQUEST, code(() -> service.send(SCOPE, 23L, "x", "u")), "非订单规则关闭了通知");
        rule.setNotifyEnabled(null);
        rule.setNotifyChatId(" ");
        assertEquals(MonitorErrorCode.BAD_REQUEST, code(() -> service.send(SCOPE, 23L, "x", "u")), "没配群");
        rule.setNotifyChatId("oc_risk");
        rule.setChatStatus("dead");
        assertEquals(MonitorErrorCode.CHAT_DEAD, code(() -> service.send(SCOPE, 23L, "x", "u")), "群已失效");
        rule.setChatStatus("normal");
        props.getLark().setAppSecret("");
        assertEquals(MonitorErrorCode.LARK_CREDENTIAL_INVALID, code(() -> service.send(SCOPE, 23L, "x", "u")), "没配凭证");
        assertNull(lark.chatId, "以上都没有调 Lark");
        props.getLark().setAppSecret("secret");
        assertEquals(0, code(() -> service.send(SCOPE, 23L, "x".repeat(4000), "u")), "4000 字以内可以发");
    }

    @Test
    void larkFailuresMapToErrorCodes()
    {
        lark.next = LarkResult.fail(LarkErrorCategory.TARGET_INVALID, 230002, "bot not in chat", 400);
        assertEquals(MonitorErrorCode.CHAT_DEAD, code(() -> service.send(SCOPE, 23L, "x", "u")));
        assertEquals(List.of("oc_risk"), markedDead, "机器人不在群时把群标成失效");

        lark.next = LarkResult.fail(LarkErrorCategory.CREDENTIAL_INVALID, 99991663, "invalid token", 400);
        assertEquals(MonitorErrorCode.LARK_CREDENTIAL_INVALID, code(() -> service.send(SCOPE, 23L, "x", "u")));

        lark.next = LarkResult.fail(LarkErrorCategory.RETRYABLE, 99991400, "too many", 429);
        assertEquals(MonitorErrorCode.LARK_SEND_FAILED, code(() -> service.send(SCOPE, 23L, "x", "u")));
        assertTrue(markedDead.size() == 1);
    }

    private static Map<String, Object> body(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2)
        {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private void orderRule(String amountMode)
    {
        rule.setRuleType("ORDER");
        rule.setLevel("CRIT");
        rule.setCurrency("INR");
        rule.setNotifyEnabled(true);
        rule.setConfig("{\"orderType\":\"WITHDRAW\",\"triggerAt\":\"SUBMIT\",\"amountMode\":\"" + amountMode
                + "\",\"amountThreshold\":50000,\"channelScope\":null,\"dailyCap\":50}");
    }

    @Test
    void orderRuleRequiresOrderInfoAndOtherRulesRejectIt()
    {
        assertEquals(MonitorErrorCode.BAD_REQUEST, code(() -> service.send(SCOPE, 23L, body("message", "x", "orderNo", "W1", "amount", 1), "u")),
                "指标规则不能带订单信息");
        assertEquals(MonitorErrorCode.BAD_REQUEST, code(() -> service.send(SCOPE, 23L, body("send", false), "u")), "send=false 只给订单规则");
        orderRule("SINGLE");
        assertEquals(MonitorErrorCode.BAD_REQUEST, code(() -> service.send(SCOPE, 23L, "x", "u")), "订单规则没带订单信息");
        orderRule("DAILY_TOTAL");
        assertEquals(MonitorErrorCode.BAD_REQUEST, code(() -> service.send(SCOPE, 23L, body("message", "x", "orderNo", "W1", "amount", 1), "u")),
                "当日累计规则要 cumCount");
        assertTrue(events.isEmpty(), "校验不过不写事件");
        assertNull(lark.chatId);
    }

    @Test
    void orderRuleRecordsEventThenSends()
    {
        orderRule("DAILY_TOTAL");
        Map<String, Object> out = service.send(SCOPE, 23L,
                body("message", "大額提款", "orderNo", "W20261116001", "amount", "52000", "cumCount", 3, "cumAmount", 180000), "judge_bot");
        assertEquals(true, out.get("sent"));
        assertEquals("SENT", out.get("notifyStatus"));
        assertEquals("om_1", out.get("messageId"));
        assertEquals("【uWin】大額提款\n", lark.text);
        assertEquals(1, events.size());
        MonitorOrderEvent e = events.get(0);
        assertEquals("SITE-IN", e.getSiteCode());
        assertEquals(23L, e.getRuleId());
        assertEquals("W20261116001", e.getOrderNo());
        assertEquals(new BigDecimal("52000"), e.getAmount());
        assertEquals(3, e.getCumCount());
        assertEquals(new BigDecimal("180000"), e.getCumAmount());
        assertEquals("CRIT", e.getLevel());
        assertEquals("SUBMIT", e.getTriggerPoint());
        assertEquals("DAILY_TOTAL", e.getAmountMode());
        assertEquals("INR", e.getCurrency());
        assertEquals("judge_bot", e.getReportBy());
        assertEquals("SENT", e.getNotifyStatus());
        assertEquals("oc_risk", chat(e, 0).get("chatId"));
        assertEquals("om_1", chat(e, 0).get("messageId"));
        assertEquals("SENT", chat(e, 0).get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("events");
        assertEquals(100L, rows.get(0).get("eventId"));
        assertEquals(false, rows.get(0).get("duplicate"));
    }

    @Test
    void singleRuleDropsCumulativeFieldsAndMergesOrders()
    {
        orderRule("SINGLE");
        service.send(SCOPE, 23L, body("message", "3 筆", "orders", List.of(body("orderNo", "W1", "amount", 60000, "cumCount", 2),
                body("orderNo", "W2", "amount", 70000), body("orderNo", "W3", "amount", 80000))), "u");
        assertEquals(3, events.size(), "一笔一行");
        assertTrue(events.stream().allMatch(e -> e.getCumCount() == null && e.getCumAmount() == null), "单笔规则不存累计");
        assertTrue(events.stream().allMatch(e -> "SENT".equals(e.getNotifyStatus()) && "om_1".equals(chat(e, 0).get("messageId"))),
                "合并发一条，三笔共用一个消息");
    }

    @Test
    void duplicateOrdersAreNotResent()
    {
        orderRule("SINGLE");
        service.send(SCOPE, 23L, body("message", "x", "orderNo", "W1", "amount", 60000), "u");
        lark.chatId = null;
        Map<String, Object> again = service.send(SCOPE, 23L, body("message", "x", "orderNo", "W1", "amount", 99999), "u");
        assertEquals(false, again.get("sent"));
        assertEquals("SKIPPED", again.get("notifyStatus"));
        assertEquals("DUPLICATE", again.get("notifyReason"));
        assertNull(lark.chatId, "已发送过的订单不重发");
        assertEquals(1, events.size());
        assertEquals(new BigDecimal("60000"), events.get(0).getAmount(), "重复上报不改金额");

        Map<String, Object> mixed = service.send(SCOPE, 23L, body("message", "x", "orders", List.of(body("orderNo", "W1", "amount", 1),
                body("orderNo", "W2", "amount", 2))), "u");
        assertEquals(true, mixed.get("sent"), "有新订单时照常发");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) mixed.get("events");
        assertEquals(true, rows.get(0).get("duplicate"));
        assertEquals(false, rows.get(1).get("duplicate"));
    }

    @Test
    void failedSendIsRecordedAndCanBeRetried()
    {
        orderRule("SINGLE");
        lark.next = LarkResult.fail(LarkErrorCategory.RETRYABLE, 99991400, "too many", 429);
        assertEquals(MonitorErrorCode.LARK_SEND_FAILED, code(() -> service.send(SCOPE, 23L, body("message", "x", "orderNo", "W1", "amount", 1), "u")));
        assertEquals(1, events.size(), "发送失败也留下事件");
        assertEquals("FAILED", events.get(0).getNotifyStatus());
        assertTrue(chat(events.get(0), 0).get("error") != null, "失败原因记在群结果上");

        lark.next = LarkResult.ok("om_2", 200);
        Map<String, Object> retry = service.send(SCOPE, 23L, body("message", "x", "orderNo", "W1", "amount", 1), "u");
        assertEquals(true, retry.get("sent"), "原样重试会补发");
        assertEquals(1, events.size());
        assertEquals("SENT", events.get(0).getNotifyStatus());
        assertEquals("om_2", chat(events.get(0), 0).get("messageId"));

        rule.setChatStatus("dead");
        assertEquals(MonitorErrorCode.CHAT_DEAD, code(() -> service.send(SCOPE, 23L, body("message", "x", "orderNo", "W9", "amount", 1), "u")));
        assertEquals("FAILED", events.get(1).getNotifyStatus(), "群失效也记成发送失败");
    }

    @Test
    void logOnlyWhenCappedOrNotifyOff()
    {
        orderRule("SINGLE");
        Map<String, Object> capped = service.send(SCOPE, 23L, body("send", false, "orderNo", "W1", "amount", 1), "u");
        assertEquals(false, capped.get("sent"));
        assertEquals("LOG_ONLY", capped.get("notifyStatus"));
        assertEquals("CAPPED", capped.get("notifyReason"));
        assertEquals("CAPPED", events.get(0).getNotifyReason());
        assertNull(lark.chatId, "send=false 不调 Lark，也不用带 message");

        rule.setNotifyEnabled(false);
        Map<String, Object> off = service.send(SCOPE, 23L, body("message", "x", "orderNo", "W2", "amount", 1), "u");
        assertEquals("LOG_ONLY", off.get("notifyStatus"));
        assertEquals("NOTIFY_OFF", off.get("notifyReason"));
        assertEquals("LOG_ONLY", events.get(1).getNotifyStatus());
        assertNull(lark.chatId);

        rule.setNotifyEnabled(true);
        Map<String, Object> later = service.send(SCOPE, 23L, body("message", "x", "orderNo", "W1", "amount", 1), "u");
        assertFalse((Boolean) later.get("sent"), "只记录过的订单之后不补发");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> chat(MonitorOrderEvent e, int i)
    {
        return ((List<Map<String, Object>>) JsonMapper.builder().build().readValue(e.getNotifyChats(), List.class)).get(i);
    }

    private void twoChats(String deadOne)
    {
        rule.setNotifyChats("[{\"chatId\":\"oc_risk\",\"name\":\"風控告警群\",\"status\":\"ok\"},"
                + "{\"chatId\":\"oc_fund\",\"name\":\"金流告警群\",\"status\":\"" + (deadOne == null ? "ok" : deadOne) + "\"}]");
    }

    @Test
    @SuppressWarnings("unchecked")
    void metricRuleSendsToEveryChat()
    {
        twoChats(null);
        Map<String, Object> out = service.send(SCOPE, 23L, "x", "u");
        assertEquals(List.of("oc_risk", "oc_fund"), lark.chats, "同一条消息分别发到每个群，按选择顺序");
        List<Map<String, Object>> chats = (List<Map<String, Object>>) out.get("chats");
        assertEquals(2, chats.size());
        assertEquals(true, chats.get(1).get("success"));
        assertEquals("oc_risk", out.get("chatId"), "旧字段为第一个送达的群");
    }

    @Test
    @SuppressWarnings("unchecked")
    void partialFailureReturnsOkWithPerChatResults()
    {
        twoChats("dead");
        Map<String, Object> out = service.send(SCOPE, 23L, "x", "u");
        assertEquals(List.of("oc_risk"), lark.chats, "已失效的群不调 Lark");
        List<Map<String, Object>> chats = (List<Map<String, Object>>) out.get("chats");
        assertEquals(false, chats.get(1).get("success"));
        assertTrue(String.valueOf(chats.get(1).get("error")).contains("失效"));

        twoChats(null);
        lark.chats.clear();
        lark.failFor = "oc_risk";
        lark.next = LarkResult.fail(LarkErrorCategory.RETRYABLE, 99991400, "too many", 429);
        out = service.send(SCOPE, 23L, "x", "u");
        assertEquals("oc_fund", out.get("chatId"), "第一个群失败、第二个送达：返回 200");
    }

    @Test
    void allChatsDeadOrCredentialFailureUsesOldErrorCodes()
    {
        rule.setNotifyChats("[{\"chatId\":\"oc_a\",\"status\":\"dead\"},{\"chatId\":\"oc_b\",\"status\":\"dead\"}]");
        assertEquals(MonitorErrorCode.CHAT_DEAD, code(() -> service.send(SCOPE, 23L, "x", "u")), "全部群已失效");
        twoChats(null);
        lark.next = LarkResult.fail(LarkErrorCategory.CREDENTIAL_INVALID, 99991663, "invalid token", 400);
        assertEquals(MonitorErrorCode.LARK_CREDENTIAL_INVALID, code(() -> service.send(SCOPE, 23L, "x", "u")));
        assertEquals(List.of("oc_risk"), lark.chats, "凭证失效后其余群不再请求 Lark");
    }

    @Test
    void orderRuleRetriesOnlyChatsNotYetSent()
    {
        orderRule("SINGLE");
        twoChats(null);
        lark.failFor = "oc_fund";
        lark.next = LarkResult.fail(LarkErrorCategory.RETRYABLE, 99991400, "too many", 429);
        Map<String, Object> first = service.send(SCOPE, 23L, body("message", "x", "orderNo", "W1", "amount", 60000), "u");
        assertEquals("PARTIAL", first.get("notifyStatus"));
        assertEquals("PARTIAL", events.get(0).getNotifyStatus());
        assertEquals("SENT", chat(events.get(0), 0).get("status"));
        assertEquals("FAILED", chat(events.get(0), 1).get("status"));

        lark.chats.clear();
        lark.failFor = null;
        lark.next = LarkResult.ok("om_9", 200);
        Map<String, Object> retry = service.send(SCOPE, 23L, body("message", "x", "orderNo", "W1", "amount", 60000), "u");
        assertEquals(List.of("oc_fund"), lark.chats, "重试只补发没送达的群");
        assertEquals("SENT", retry.get("notifyStatus"));
        assertEquals("SENT", events.get(0).getNotifyStatus());
        assertEquals("om_1", chat(events.get(0), 0).get("messageId"), "已送达的群结果不被覆盖");
        assertEquals("om_9", chat(events.get(0), 1).get("messageId"));
    }

    static class FakeLark extends LarkClient
    {
        String chatId;

        String text;

        /** 发过的群（按顺序） */
        final List<String> chats = new ArrayList<>();

        /** 不为空时只有这个群返回 next，其余群成功 */
        String failFor;

        LarkResult<String> next = LarkResult.ok("om_1", 200);

        FakeLark()
        {
            super(null, "http://127.0.0.1:1", 1);
        }

        @Override
        public LarkResult<String> sendPost(String appId, String appSecret, String receiveIdType, String receiveId, String title,
                List<List<Map<String, String>>> paragraphs)
        {
            chatId = receiveId;
            StringBuilder sb = new StringBuilder();
            for (List<Map<String, String>> line : paragraphs)
            {
                for (Map<String, String> el : line)
                {
                    sb.append(el.getOrDefault("text", ""));
                }
                sb.append('\n');
            }
            text = sb.toString();
            chats.add(receiveId);
            if (failFor != null)
            {
                return failFor.equals(receiveId) ? next : LarkResult.ok("om_1", 200);
            }
            return next;
        }
    }
}
