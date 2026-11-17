package com.fivetech.monitor.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.monitor.domain.MonitorLarkChat;
import com.fivetech.monitor.domain.MonitorOrderRuleFilter;
import com.fivetech.monitor.domain.MonitorOwner;
import com.fivetech.monitor.domain.MonitorRule;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.OrderRuleOpenQuery;
import com.fivetech.monitor.domain.dto.MonitorOrderRuleBody;
import com.fivetech.monitor.exception.MonitorErrorCode;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.exception.MonitorFieldException;
import com.fivetech.monitor.mapper.MonitorLarkChatMapper;
import com.fivetech.monitor.mapper.MonitorMetaMapper;
import com.fivetech.monitor.mapper.MonitorOrderRuleMapper;
import com.fivetech.monitor.mapper.MonitorSiteMapper;
import com.fivetech.promo.budget.PromoChannelDictionary;
import com.fivetech.promo.budget.PromoChannelDictionary.Channel;
import com.fivetech.promo.budget.PromoChannelDictionary.Promoter;
import tools.jackson.databind.json.JsonMapper;

/** 订单规则服务：新建 / 编辑写库的内容、取号、群与负责人校验、对外查询（不连库，Mapper 用代理） */
class MonitorOrderRuleServiceTest
{
    private static final MonitorScope SCOPE = new MonitorScope("SITE-IN", 1L);

    private final Map<Long, MonitorRule> rules = new HashMap<>();

    private final Map<String, MonitorLarkChat> chats = new HashMap<>();

    private final List<MonitorOrderRuleFilter> filters = new ArrayList<>();

    private int nextRuleNo = 23;

    private long nextVersion = 100;

    private DashboardProperties props;

    private MonitorOrderRuleService service;

    private static MonitorLarkChat chat(String id, String name, String status)
    {
        MonitorLarkChat c = new MonitorLarkChat();
        c.setChatId(id);
        c.setName(name);
        c.setStatus(status);
        return c;
    }

    @BeforeEach
    void setUp()
    {
        chats.put("oc_risk", chat("oc_risk", "風控告警群", "ok"));
        chats.put("oc_dead", chat("oc_dead", "舊群", "dead"));
        MonitorOrderRuleMapper mapper = (MonitorOrderRuleMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { MonitorOrderRuleMapper.class }, (p, m, a) -> {
                    switch (m.getName())
                    {
                        case "insertOrderRule":
                        {
                            MonitorRule r = (MonitorRule) a[0];
                            r.setRuleId(1000L + r.getRuleNo());
                            r.setVersion(++nextVersion);
                            rules.put(r.getRuleId(), r);
                            return 1;
                        }
                        case "updateOrderRule":
                        {
                            MonitorRule r = (MonitorRule) a[1];
                            MonitorRule old = rules.get(r.getRuleId());
                            r.setSiteCode(old.getSiteCode());
                            r.setRuleNo(old.getRuleNo());
                            r.setEnabled(old.getEnabled());
                            r.setVersion(++nextVersion);
                            rules.put(r.getRuleId(), r);
                            return 1;
                        }
                        case "selectOrderRuleById":
                        {
                            MonitorRule r = rules.get((Long) a[1]);
                            return r != null && r.getSiteCode().equals(((MonitorScope) a[0]).getSiteCode()) ? r : null;
                        }
                        case "selectOrderRuleByNo":
                            return rules.values().stream().filter(r -> r.getRuleNo().equals(a[1])).findFirst().orElse(null);
                        case "selectOrderRuleList":
                            filters.add((MonitorOrderRuleFilter) a[1]);
                            return new ArrayList<>(rules.values());
                        default:
                            throw new UnsupportedOperationException(m.getName());
                    }
                });
        MonitorMetaMapper meta = (MonitorMetaMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { MonitorMetaMapper.class }, (p, m, a) -> {
                    if ("selectOwnerCandidate".equals(m.getName()))
                    {
                        if (!Long.valueOf(7L).equals(a[1]))
                        {
                            return null;
                        }
                        MonitorOwner o = new MonitorOwner();
                        o.setUserId(7L);
                        o.setEmail("owner@example.com");
                        o.setUserType("01");
                        return o;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        MonitorLarkChatMapper chatMapper = (MonitorLarkChatMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { MonitorLarkChatMapper.class }, (p, m, a) -> {
                    if ("selectChat".equals(m.getName()))
                    {
                        return chats.get((String) a[1]);
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        MonitorSiteMapper siteMapper = (MonitorSiteMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { MonitorSiteMapper.class }, (p, m, a) -> {
                    switch (m.getName())
                    {
                        case "ensureSiteSetting":
                            return 0;
                        case "lockNextRuleNo":
                            return nextRuleNo;
                        case "increaseNextRuleNo":
                            nextRuleNo++;
                            return 1;
                        default:
                            throw new UnsupportedOperationException(m.getName());
                    }
                });
        PromoChannelDictionary dict = new PromoChannelDictionary(null, null)
        {
            @Override
            public List<Promoter> promotersOf(String siteCode)
            {
                return List.of(new Promoter("1001", "ROI 推廣", List.of(new Channel("tg-roi", "Telegram ROI"))));
            }
        };
        props = new DashboardProperties();
        props.setDefaultSite("SITE-IN");
        service = new MonitorOrderRuleService(mapper, meta, chatMapper, siteMapper, dict, props, JsonMapper.builder().build());
    }

    private static MonitorOrderRuleBody body()
    {
        MonitorOrderRuleBody b = new MonitorOrderRuleBody();
        b.setName("大額提款提醒");
        b.setOwnerUserId(7L);
        b.setTriggerAt("SUBMIT");
        b.setAmountMode("DAILY_TOTAL");
        b.setAmountThreshold(new BigDecimal("200000"));
        b.setChannelScope("g:1001");
        b.setLevel("WARN");
        b.setNotifyChatId("oc_risk");
        b.setDailyCap(20);
        return b;
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
    @SuppressWarnings("unchecked")
    void createStoresAnOrderRuleWithItsOwnNumber()
    {
        Map<String, Object> out = service.create(SCOPE, body(), "ops");
        assertEquals(1023L, out.get("ruleId"));
        assertEquals("R-023", out.get("ruleCode"));
        assertEquals(24, nextRuleNo, "与指标规则共用编号，取号后加 1");

        MonitorRule r = rules.get(1023L);
        assertEquals("ORDER", r.getRuleType());
        assertEquals("WARN", r.getLevel());
        assertEquals(true, r.getEnabled(), "不传 = 启用");
        assertEquals("風控告警群", r.getNotifyChatName(), "群名存保存时的快照");
        assertEquals(props.siteCurrency("SITE-IN"), r.getCurrency(), "记下保存时的站点币种");
        assertTrue(r.getConfig().contains("\"amountMode\":\"DAILY_TOTAL\""), r.getConfig());
        assertTrue(r.getConfig().contains("\"promoterName\":\"ROI 推廣\""), r.getConfig());

        r.setOwnerEmail("owner@example.com");
        Map<String, Object> v = service.detail(SCOPE, 1023L);
        assertEquals("R-023", v.get("ruleNo"));
        Map<String, Object> notify = (Map<String, Object>) v.get("notify");
        assertEquals(20, notify.get("dailyCap"));
        Map<String, Object> trigger = (Map<String, Object>) v.get("trigger");
        assertEquals("PROMOTER", ((Map<String, Object>) trigger.get("channelScope")).get("type"));
        assertTrue(trigger.containsKey("dailyTotalRule"));
    }

    @Test
    void createRejectsBadOwnerChatOrFields()
    {
        MonitorOrderRuleBody b = body();
        b.setOwnerUserId(99L);
        b.setNotifyChatId("oc_none");
        try
        {
            service.create(SCOPE, b, "ops");
            throw new AssertionError("负责人、群不对应拒绝");
        }
        catch (MonitorFieldException e)
        {
            assertEquals("負責人不在可選範圍內", e.getFields().get("ownerUserId"));
            assertEquals("通知群不存在，請重新選擇", e.getFields().get("notifyChatId"));
        }
        MonitorOrderRuleBody dead = body();
        dead.setNotifyChatId("oc_dead");
        assertEquals(MonitorErrorCode.CHAT_DEAD, code(() -> service.create(SCOPE, dead, "ops")));
        assertTrue(rules.isEmpty(), "都没有写库");
        assertEquals(23, nextRuleNo, "校验失败不取号");
    }

    @Test
    void updateKeepsTheOriginalDeadChatButNotANewOne()
    {
        MonitorOrderRuleBody b = body();
        b.setNotifyChatId("oc_dead");
        chats.get("oc_dead").setStatus("ok");
        service.create(SCOPE, b, "ops");
        chats.get("oc_dead").setStatus("dead");
        long before = rules.get(1023L).getVersion();

        b.setAmountThreshold(new BigDecimal("300000"));
        service.update(SCOPE, 1023L, b, "ops2");
        MonitorRule r = rules.get(1023L);
        assertTrue(r.getConfig().contains("300000"), r.getConfig());
        assertEquals("ops2", r.getUpdateBy());
        assertTrue(r.getVersion() > before, "每次保存取新版本号");

        chats.put("oc_dead2", chat("oc_dead2", "另一個舊群", "dead"));
        b.setNotifyChatId("oc_dead2");
        assertEquals(MonitorErrorCode.CHAT_DEAD, code(() -> service.update(SCOPE, 1023L, b, "ops2")), "改成别的失效群不行");
        assertEquals(MonitorErrorCode.NOT_FOUND, code(() -> service.update(SCOPE, 9L, b, "ops2")));
        assertEquals(MonitorErrorCode.NOT_FOUND, code(() -> service.update(new MonitorScope("JITO", 1L), 1023L, b, "ops2")),
                "别的站点");
    }

    @Test
    @SuppressWarnings("unchecked")
    void openQueryListAndDetail()
    {
        service.create(SCOPE, body(), "ops");
        service.create(SCOPE, body(), "ops");
        OrderRuleOpenQuery q = new OrderRuleOpenQuery();
        q.setSinceVersion("100");
        q.setMetric("WITHDRAW_AMOUNT_DAILY_TOTAL");
        Map<String, Object> out = service.list(SCOPE, q);
        assertEquals(2, ((List<Object>) out.get("rows")).size());
        assertEquals(102L, out.get("maxVersion"), "本页最大的 version");
        assertEquals(List.of("DAILY_TOTAL"), filters.get(0).amountModes);
        assertEquals(1, out.get("pageNum"));
        assertEquals(100, out.get("pageSize"));

        rules.clear();
        assertEquals(100L, service.list(SCOPE, q).get("maxVersion"), "没有新变化时原样返回 sinceVersion");

        service.create(SCOPE, body(), "ops");
        assertEquals("R-025", service.get(SCOPE, "R-025").get("ruleNo"));
        assertEquals("R-025", service.get(SCOPE, "1025").get("ruleNo"));
        assertEquals(MonitorErrorCode.NOT_FOUND, code(() -> service.get(SCOPE, "R-099")));
        assertEquals(MonitorErrorCode.BAD_REQUEST, code(() -> service.get(SCOPE, "abc")));

        OrderRuleOpenQuery bad = new OrderRuleOpenQuery();
        bad.setPageSize("1000");
        assertEquals(MonitorErrorCode.BAD_REQUEST, code(() -> service.list(SCOPE, bad)));
        assertFalse(filters.isEmpty());
        assertNull(filters.get(0).channelMode);
    }
}
