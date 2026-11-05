package com.fivetech.dashboard.subscription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import com.fivetech.common.core.domain.entity.SysUser;
import com.fivetech.common.lark.LarkClient;
import com.fivetech.common.lark.LarkErrorCategory;
import com.fivetech.common.lark.LarkResult;
import com.fivetech.common.utils.SiteContext;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.domain.MetricCardConfig;
import com.fivetech.dashboard.domain.MetricGroupConfig;
import com.fivetech.dashboard.domain.PageMetricConfig;
import com.fivetech.dashboard.domain.query.OverviewQuery;
import com.fivetech.dashboard.domain.vo.MetricCardVO;
import com.fivetech.dashboard.domain.vo.MetricsBlockVO;
import com.fivetech.dashboard.domain.vo.OverviewSlotVO;
import com.fivetech.dashboard.domain.vo.OverviewVO;
import com.fivetech.dashboard.enums.CompareType;
import com.fivetech.dashboard.enums.Granularity;
import com.fivetech.dashboard.mapper.DashboardConfigMapper;
import com.fivetech.dashboard.mapper.DashboardSubscriptionMapper;
import com.fivetech.dashboard.service.DashboardConfigValidator;
import com.fivetech.dashboard.service.IOverviewService;
import com.fivetech.dashboard.service.MetricRegistry;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.exception.MonitorFieldException;
import com.fivetech.system.service.ISysUserService;

/** 订阅：新增 / 修改校验、取数参数（站点、区间、指标顺序）、逐个接收者发送与记录（不连库、不调 UDS / Lark） */
class SubscriptionServiceTest
{
    private final Map<Long, DashboardSubscription> rows = new LinkedHashMap<>();

    private final List<DashboardSubscriptionLog> logs = new ArrayList<>();

    private final List<Object[]> runs = new ArrayList<>();

    private final List<String[]> sent = new ArrayList<>();

    private OverviewQuery lastQuery;

    private String siteInQuery;

    private boolean failQuery;

    private SubscriptionService service;

    private SubscriptionSender sender;

    private static MetricCardConfig cardCfg(String code, String fmt)
    {
        MetricCardConfig c = new MetricCardConfig();
        c.setMetricCode(code);
        c.setMetricName(code + "名");
        c.setGroupCode("eg1");
        c.setValueFormat(fmt);
        c.setShowAsCard(true);
        c.setCalcType("NONE");
        return c;
    }

    private static PageMetricConfig page(String code, int sort, boolean core)
    {
        PageMetricConfig p = new PageMetricConfig();
        p.setPageCode("OVERVIEW");
        p.setMetricCode(code);
        p.setSortNo(sort);
        p.setIsDefault(true);
        p.setEmphasis(core ? "CORE" : "NORMAL");
        return p;
    }

    private static Map<String, Object> chat(String id, String name)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "chat");
        m.put("id", id);
        m.put("name", name);
        return m;
    }

    private static Map<String, Object> user(String id)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "user");
        m.put("id", id);
        return m;
    }

    private Map<String, Object> body()
    {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("siteCode", "jito");
        b.put("name", "經營日報");
        b.put("description", "晨會用");
        b.put("rangeCode", "YESTERDAY");
        b.put("metrics", List.of("dep", "reg"));
        b.put("sendHour", 9);
        b.put("receivers", List.of(chat("oc_ok", "運營群"), user("7")));
        return b;
    }

    @BeforeEach
    void setUp()
    {
        List<MetricCardConfig> cards = List.of(cardCfg("reg", "INT"), cardCfg("dep", "MONEY"), cardCfg("dT", "MIN"));
        List<PageMetricConfig> pages = List.of(page("reg", 10, true), page("dep", 20, false), page("dT", 30, false));
        DashboardConfigMapper cfg = (DashboardConfigMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] { DashboardConfigMapper.class }, (p, m, a) -> {
                switch (m.getName())
                {
                    case "selectAllMetricCards": return cards;
                    case "selectAllPageMetrics": return pages;
                    case "selectEnabledGroups":
                        MetricGroupConfig g = new MetricGroupConfig();
                        g.setGroupCode("eg1");
                        g.setGroupName("获客与转化");
                        return List.of(g);
                    default: return null;
                }
            });
        MetricRegistry registry = new MetricRegistry(cfg, new DashboardConfigValidator()
        {
            @Override
            public void validateOrFail(List<MetricCardConfig> c, List<PageMetricConfig> p, List<String> g)
            {
            }
        });
        registry.reload();

        DashboardProperties dashboard = new DashboardProperties();
        DashboardProperties.Site jito = new DashboardProperties.Site();
        jito.setCode("JITO");
        jito.setName("JITO 站");
        dashboard.applySites("SITE-IN", "uWin", "SITE-IN", List.of(jito));

        DashboardSubscriptionMapper mapper = (DashboardSubscriptionMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] { DashboardSubscriptionMapper.class }, (p, m, a) -> {
                switch (m.getName())
                {
                    case "insert":
                        DashboardSubscription s = (DashboardSubscription) a[0];
                        s.setId((long) rows.size() + 1);
                        rows.put(s.getId(), s);
                        return 1;
                    case "update":
                        DashboardSubscription u = (DashboardSubscription) a[0];
                        rows.put(u.getId(), u);
                        return 1;
                    case "selectById": return rows.get((Long) a[0]);
                    case "countName":
                        return (int) rows.values().stream().filter(r -> r.getSiteCode().equalsIgnoreCase((String) a[0])
                            && r.getName().equalsIgnoreCase((String) a[1]) && !r.getId().equals(a[2])).count();
                    case "markRun": runs.add(a); return 1;
                    case "insertLog": logs.add((DashboardSubscriptionLog) a[0]); return 1;
                    case "selectList": return new ArrayList<>(rows.values());
                    default: return 0;
                }
            });
        IOverviewService overview = q -> {
            lastQuery = q;
            siteInQuery = SiteContext.code();
            if (failQuery)
            {
                throw new IllegalStateException("UDS 逾時");
            }
            OverviewVO vo = new OverviewVO();
            OverviewSlotVO slot = new OverviewSlotVO();
            slot.setFrom(q.getSlotFrom());
            slot.setTo(q.getSlotTo());
            slot.setGranularity(q.getGranularity());
            vo.setSlot(slot);
            MetricsBlockVO block = new MetricsBlockVO();
            List<MetricCardVO> items = new ArrayList<>();
            for (String code : q.getMetrics())
            {
                MetricCardVO c = new MetricCardVO();
                c.setCode(code);
                c.setLabel(code + "名");
                c.setValueFormat("dep".equals(code) ? "MONEY" : "INT");
                c.setValue(new BigDecimal("100"));
                items.add(c);
            }
            block.setItems(items);
            vo.getBlocks().put("METRICS", block);
            return vo;
        };
        MonitorProperties mp = new MonitorProperties();
        mp.getLark().setAppId("cli_test");
        mp.getLark().setAppSecret("<SECRET>");
        LarkClient lark = new LarkClient(new ObjectMapper(), "http://127.0.0.1:1", 1)
        {
            @Override
            public LarkResult<String> sendCard(String appId, String appSecret, String type, String id, Map<String, Object> card)
            {
                sent.add(new String[] { type, id });
                if ("oc_gone".equals(id))
                {
                    return LarkResult.fail(LarkErrorCategory.TARGET_INVALID, 230002, "bot not in chat", 400);
                }
                if ("nobody@fivetech.co.jp".equals(id))
                {
                    return LarkResult.fail(LarkErrorCategory.RETRYABLE, 99992361, "open_id cross app", 400);
                }
                if ("oc_cred".equals(id))
                {
                    return LarkResult.fail(LarkErrorCategory.CREDENTIAL_INVALID, 99991663, "invalid", 400);
                }
                return LarkResult.ok("om_" + sent.size(), 200);
            }
        };
        ISysUserService users = (ISysUserService) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] { ISysUserService.class }, (p, m, a) -> {
                if (!"selectUserById".equals(m.getName()))
                {
                    return null;
                }
                long id = (Long) a[0];
                if (id == 7L || id == 9L)
                {
                    SysUser u = new SysUser();
                    u.setUserId(id);
                    u.setEmail(id == 7L ? "ops@fivetech.co.jp" : "");
                    u.setStatus("0");
                    u.setDelFlag("0");
                    return u;
                }
                return null;
            });
        SubscriptionProperties props = new SubscriptionProperties();
        props.setOverviewUrl("https://yata.example/overview");
        sender = new SubscriptionSender(mapper, overview, registry, dashboard, props, mp, lark, users, new ObjectMapper());
        service = new SubscriptionService(mapper, sender, registry, dashboard, props, null, null, new ObjectMapper());
    }

    // ---------------- 校验 ----------------

    @Test
    void createValidatesAllFieldsAtOnce()
    {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("siteCode", "NOPE");
        b.put("name", " ");
        b.put("rangeCode", "WEEK");
        b.put("sendHour", 24);
        b.put("metrics", List.of());
        b.put("receivers", List.of(chat("bad", "x"), user("abc")));
        MonitorFieldException e = assertThrows(MonitorFieldException.class, () -> service.create(b, "admin"));
        for (String f : List.of("siteCode", "name", "rangeCode", "sendHour", "metrics", "receivers"))
        {
            assertTrue(e.getFields().containsKey(f), f);
        }
        assertTrue(rows.isEmpty());
    }

    @Test
    void todayCannotSendAtMidnightAndMetricsMustBeOnSite()
    {
        Map<String, Object> b = body();
        b.put("rangeCode", "TODAY");
        b.put("sendHour", 0);
        b.put("metrics", List.of("reg", "ghost"));
        MonitorFieldException e = assertThrows(MonitorFieldException.class, () -> service.create(b, "admin"));
        assertTrue(e.getFields().get("sendHour").contains("00:00"));
        assertTrue(e.getFields().get("metrics").contains("ghost"));
    }

    @Test
    void createStoresSiteOrderAndReceivers()
    {
        Map<String, Object> v = service.create(body(), "admin");
        DashboardSubscription s = rows.get(1L);
        assertEquals("JITO", s.getSiteCode());                     // 站点规范写法
        assertEquals("reg,dep", s.getMetrics());                   // 按站点顺序存
        assertTrue(s.isOn());                                      // 默认启用
        assertTrue(s.getReceivers().contains("\"oc_ok\""));
        assertFalse(s.getReceivers().contains("\"chat\":"));         // isChat() 不进 JSON
        List<?> back = (List<?>) v.get("receivers");                   // 读回来能解析
        assertEquals(2, back.size());
        assertEquals("運營群", ((SubscriptionReceiver) back.get(0)).getName());
        assertEquals("7", ((SubscriptionReceiver) back.get(1)).getName());   // 用户没传 name 时先用 ID
        assertEquals("每日 09:00", v.get("sendTimeText"));
        assertEquals("昨日", v.get("rangeLabel"));
        assertEquals(List.of("reg名", "dep名"), v.get("metricLabels"));
        // 同站同名
        MonitorFieldException e = assertThrows(MonitorFieldException.class, () -> service.create(body(), "admin"));
        assertTrue(e.getFields().containsKey("name"));
    }

    @Test
    void updateCannotChangeSite()
    {
        service.create(body(), "admin");
        Map<String, Object> b = body();
        b.put("siteCode", "SITE-IN");
        MonitorFieldException e = assertThrows(MonitorFieldException.class, () -> service.update(1L, b, "admin"));
        assertTrue(e.getFields().containsKey("siteCode"));
        b.put("siteCode", "JITO");
        b.put("name", "經營日報");                                  // 改自己不算重名
        b.put("sendHour", 10);
        assertEquals(10, service.update(1L, b, "admin").get("sendHour"));
        assertThrows(MonitorException.class, () -> service.update(99L, b, "admin"));
    }

    @Test
    void nextRunTodayOrTomorrow()
    {
        DashboardSubscription s = new DashboardSubscription();
        s.setSendHour(9);
        assertEquals("2026-11-01 09:00", service.nextRun(LocalDateTime.of(2026, 11, 1, 8, 0), s));
        s.setLastSlot("2026-11-01 09:00");
        assertEquals("2026-11-02 09:00", service.nextRun(LocalDateTime.of(2026, 11, 1, 9, 20), s));
    }

    // ---------------- 取数 ----------------

    @Test
    void prepareQueriesOverviewUnderSite()
    {
        service.create(body(), "admin");
        DashboardSubscription s = rows.get(1L);
        SubscriptionBrief b = sender.prepare(s, LocalDateTime.of(2026, 11, 1, 9, 0), false);
        assertEquals("JITO", siteInQuery);
        assertNull(SiteContext.code());                            // 用完清掉
        assertEquals(Granularity.HOUR, lastQuery.getGranularity());
        assertEquals("2026-10-31 00:00", lastQuery.getSlotFrom());
        assertEquals("2026-11-01 00:00", lastQuery.getSlotTo());
        assertEquals(CompareType.PREV_PERIOD, lastQuery.getCompareType());
        assertEquals(List.of("reg", "dep"), lastQuery.getMetrics());
        assertFalse(lastQuery.isIncludeSeries());
        assertEquals("JITO 站 · 經營日報", b.getTitle());

        s.setRangeCode("LAST_7D");
        sender.prepare(s, LocalDateTime.of(2026, 11, 1, 9, 0), false);
        assertEquals(Granularity.DAY, lastQuery.getGranularity());
        assertEquals("2026-10-25 00:00", lastQuery.getSlotFrom());
    }

    @Test
    void prepareFailures()
    {
        service.create(body(), "admin");
        DashboardSubscription s = rows.get(1L);
        s.setRangeCode("TODAY");
        assertThrows(SubscriptionSender.PrepareException.class, () -> sender.prepare(s, LocalDateTime.of(2026, 11, 1, 0, 0), false));
        s.setRangeCode("YESTERDAY");
        s.setMetrics("ghost");
        assertThrows(SubscriptionSender.PrepareException.class, () -> sender.prepare(s, LocalDateTime.of(2026, 11, 1, 9, 0), false));
        s.setMetrics("reg");
        failQuery = true;
        SubscriptionSender.PrepareException e = assertThrows(SubscriptionSender.PrepareException.class,
            () -> sender.prepare(s, LocalDateTime.of(2026, 11, 1, 9, 0), false));
        assertTrue(e.getMessage().contains("UDS 逾時"));
    }

    // ---------------- 发送 ----------------

    @Test
    void deliverEachReceiverAndRecord()
    {
        service.create(body(), "admin");
        DashboardSubscription s = rows.get(1L);
        SubscriptionBrief b = sender.prepare(s, LocalDateTime.of(2026, 11, 1, 9, 0), false);
        List<SubscriptionReceiver> rs = List.of(new SubscriptionReceiver("chat", "oc_ok", "運營群"),
            new SubscriptionReceiver("user", "7", "舊郵箱"), new SubscriptionReceiver("chat", "oc_gone", "舊群"),
            new SubscriptionReceiver("user", "9", "沒郵箱"));
        Map<String, Object> r = sender.deliver(s, b, rs, SubscriptionSender.SCHEDULE, "system", "2026-11-01 09:00");
        assertEquals("PARTIAL", r.get("status"));
        assertEquals(2, r.get("sent"));
        // 群用 chat_id，个人用当前邮箱
        assertEquals("chat_id", sent.get(0)[0]);
        assertEquals("email", sent.get(1)[0]);
        assertEquals("ops@fivetech.co.jp", sent.get(1)[1]);
        assertEquals(3, sent.size());                              // 没邮箱的不调 Lark
        assertEquals(4, logs.size());
        assertEquals("ops@fivetech.co.jp", logs.get(1).getReceiverName());
        assertTrue(logs.get(2).getError().contains("機器人已不在群裡"));
        assertTrue(logs.get(3).getError().contains("郵箱"));
        assertEquals("SCHEDULE", logs.get(0).getTriggerType());
        assertEquals("10-31 00:00 – 11-01 00:00", logs.get(0).getPeriod());
        // 最近状态：部分成功，写 last_slot
        Object[] run = runs.get(0);
        assertEquals("2026-11-01 09:00", run[1]);
        assertEquals("PARTIAL", run[3]);
        assertTrue(((String) run[4]).startsWith("舊群："));
    }

    @Test
    void credentialInvalidStopsCallingLark()
    {
        service.create(body(), "admin");
        DashboardSubscription s = rows.get(1L);
        SubscriptionBrief b = sender.prepare(s, LocalDateTime.of(2026, 11, 1, 9, 0), false);
        Map<String, Object> r = sender.deliver(s, b, List.of(new SubscriptionReceiver("chat", "oc_cred", "A"),
            new SubscriptionReceiver("chat", "oc_ok", "B")), SubscriptionSender.MANUAL, "admin", null);
        assertEquals("FAILED", r.get("status"));
        assertEquals(1, sent.size());
        assertTrue(logs.get(1).getError().contains("憑證失效"));
        assertNull(runs.get(0)[1]);                                // 立即发送不改 last_slot
        // 未识别的 4xx 不写成「超时」
        assertTrue(SubscriptionSender.reason(LarkResult.fail(LarkErrorCategory.RETRYABLE, 99992361, "x", 400), false)
            .startsWith("Lark 拒絕發送（確認郵箱"));
        assertTrue(SubscriptionSender.reason(LarkResult.fail(LarkErrorCategory.RETRYABLE, null, "x", 0), true)
            .startsWith("網絡超時"));
    }

    @Test
    void testSendGoesToMeOnlyAndDoesNotTouchStatus()
    {
        service.create(body(), "admin");
        Map<String, Object> r = service.test(1L, 7L, "ops@fivetech.co.jp", "admin");
        // 测试发送：时间按现在的整点取数，卡片标【測試】，只发给自己
        assertEquals("SUCCESS", r.get("status"));
        assertEquals(1, sent.size());
        assertEquals("ops@fivetech.co.jp", sent.get(0)[1]);
        assertEquals("TEST", logs.get(0).getTriggerType());
        assertTrue(runs.isEmpty());
        assertThrows(MonitorException.class, () -> service.test(1L, 7L, " ", "admin"));
    }
}
