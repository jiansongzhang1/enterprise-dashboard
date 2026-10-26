package com.fivetech.monitor.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fivetech.common.lark.LarkClient;
import com.fivetech.common.lark.LarkErrorCategory;
import com.fivetech.common.lark.LarkResult;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.gateway.HourlyFetch;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.engine.domain.MonitorEngineRule;
import com.fivetech.monitor.engine.judge.PeriodPoint;
import com.fivetech.monitor.exception.MonitorErrorCode;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.mapper.MonitorEngineMapper;
import com.fivetech.monitor.mapper.MonitorNotifyTemplateMapper;
import com.fivetech.monitor.service.MonitorNotifyTemplateService;

/**
 * 管理后台「手动触发测试告警」：发到 MONITOR_TEST_CHAT_ID 固定群、开头带【測試】、不写库；
 * 取最近一个已结束周期的数据；数据缺失时照样发并返回 dataError。
 */
class MonitorTestAlertServiceTest
{
    private static final MonitorScope SITE = new MonitorScope("SITE-IN", 1L);

    private MonitorProperties props;

    private MonitorEngineRule rule;

    private List<String> writes;

    private FakeLark lark;

    private BigDecimal value;

    private boolean fetchFails;

    private MonitorTestAlertService service;

    @BeforeEach
    void setUp()
    {
        props = new MonitorProperties();
        props.getLark().setAppId("cli_x");
        props.getLark().setAppSecret("secret");
        props.getLark().setTestChatId(" oc_test ");
        rule = new MonitorEngineRule();
        rule.setRuleId(9L);
        rule.setSiteCode("SITE-IN");
        rule.setRuleNo(9);
        rule.setMetricCode("ftdr");
        rule.setMetricName("首存轉化率");
        rule.setValueFormat("PCT");
        rule.setJudgeType("lo");
        rule.setCritLow(new BigDecimal("1"));
        rule.setPeriodHours(1);
        rule.setDurationCount(1);
        rule.setMinSampleKey("reg");
        rule.setMinSampleValue(BigDecimal.ZERO);
        rule.setNotifyChatId("oc_real_group");
        writes = new ArrayList<>();
        value = new BigDecimal("0.00");
        lark = new FakeLark();
        DashboardProperties dashboard = new DashboardProperties();

        MonitorEngineMapper mapper = (MonitorEngineMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { MonitorEngineMapper.class }, (p, m, a) -> {
                    if ("selectEngineRule".equals(m.getName()))
                    {
                        assertEquals("SITE-IN", ((MonitorScope) a[0]).getSiteCode());
                        return Long.valueOf(9L).equals(a[1]) ? rule : null;
                    }
                    writes.add(m.getName());
                    return m.getReturnType() == int.class ? 0 : null;
                });
        MonitorNotifyTemplateMapper tplMapper = (MonitorNotifyTemplateMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { MonitorNotifyTemplateMapper.class }, (p, m, a) -> {
                    Class<?> t = m.getReturnType();
                    return List.class.isAssignableFrom(t) ? Collections.emptyList() : t == int.class ? 0 : null;
                });
        MonitorDataFetcher fetcher = new MonitorDataFetcher(null, null, props)
        {
            @Override
            public HourlyFetch fetch(List<MonitorEngineRule> rules, LocalDateTime from, LocalDateTime to)
            {
                if (fetchFails)
                {
                    throw new IllegalStateException("UDS 503");
                }
                return new HourlyFetch(from, 1);
            }
        };
        MonitorPeriodBuilder builder = new MonitorPeriodBuilder(null, null)
        {
            @Override
            public PeriodPoint build(MonitorEngineRule r, HourlyFetch data, LocalDateTime from, LocalDateTime to)
            {
                return new PeriodPoint(from, to, value, null, new BigDecimal("40"), value == null);
            }
        };
        service = new MonitorTestAlertService(mapper, fetcher, builder, new MonitorMessageBuilder(dashboard, props),
                new MonitorNotifyTemplateService(tplMapper, dashboard, props, lark), lark, props, dashboard);
    }

    @Test
    void sendsTestAlertToConfiguredChatWithoutWritingAnything()
    {
        Map<String, Object> out = service.trigger(SITE, 9L, "admin");
        assertEquals(Boolean.TRUE, out.get("success"));
        assertEquals("om_1", out.get("messageId"));
        assertEquals("oc_test", lark.chatId, "发到 MONITOR_TEST_CHAT_ID，不是规则自己的通知群");
        assertTrue(lark.text.startsWith("【測試】"), lark.text);
        assertTrue(lark.text.contains("首存轉化率"), lark.text);
        assertEquals("CRIT", out.get("level"), "0% < 1% → 按真实数据判定为严重");
        assertNull(out.get("dataError"));
        assertEquals("R-009", out.get("ruleCode"));
        assertTrue(writes.isEmpty(), "不写事件、发送记录和运行状态：" + writes);
    }

    @Test
    void notCrossingStillSendsAndReportsLevel()
    {
        value = new BigDecimal("8.50");
        Map<String, Object> out = service.trigger(SITE, 9L, "admin");
        assertEquals(Boolean.TRUE, out.get("success"));
        assertEquals("OK", out.get("level"), "没越线也发，level 供对照");
    }

    @Test
    void fetchFailureStillSendsWithDataError()
    {
        fetchFails = true;
        Map<String, Object> out = service.trigger(SITE, 9L, "admin");
        assertEquals(Boolean.TRUE, out.get("success"));
        assertTrue(String.valueOf(out.get("dataError")).contains("UDS 503"));
        assertEquals("SKIP", out.get("level"));
    }

    @Test
    void larkFailureIsReturnedNotThrown()
    {
        lark.next = LarkResult.fail(LarkErrorCategory.TARGET_INVALID, 230002, "The bot can not be outside the group.", 400);
        Map<String, Object> out = service.trigger(SITE, 9L, "admin");
        assertFalse((Boolean) out.get("success"));
        assertTrue(String.valueOf(out.get("error")).length() > 0);
    }

    @Test
    void missingTestChatOrCredentialOrRule()
    {
        props.getLark().setTestChatId("");
        MonitorException e = assertThrows(MonitorException.class, () -> service.trigger(SITE, 9L, "admin"));
        assertEquals(MonitorErrorCode.BAD_REQUEST, e.getCode());
        assertTrue(e.getMessage().contains("MONITOR_TEST_CHAT_ID"));

        props.getLark().setTestChatId("oc_test");
        props.getLark().setAppSecret(null);
        assertEquals(MonitorErrorCode.LARK_CREDENTIAL_INVALID,
                assertThrows(MonitorException.class, () -> service.trigger(SITE, 9L, "admin")).getCode());

        props.getLark().setAppSecret("secret");
        assertEquals(MonitorErrorCode.NOT_FOUND,
                assertThrows(MonitorException.class, () -> service.trigger(SITE, 404L, "admin")).getCode());
        assertNull(lark.chatId, "以上情况都不调 Lark");
    }

    @Test
    void latestPeriodIsLastFinishedPeriod()
    {
        LocalDateTime now = LocalDateTime.of(2026, 10, 23, 13, 25);
        LocalDateTime[] p1 = MonitorTestAlertService.latestPeriod(now, 1);
        assertEquals(LocalDateTime.of(2026, 10, 23, 12, 0), p1[0]);
        assertEquals(LocalDateTime.of(2026, 10, 23, 13, 0), p1[1]);
        LocalDateTime[] p4 = MonitorTestAlertService.latestPeriod(now, 4);
        assertEquals(LocalDateTime.of(2026, 10, 23, 8, 0), p4[0], "08:00–12:00 是最近结束的 4 小时时段");
        assertEquals(LocalDateTime.of(2026, 10, 23, 12, 0), p4[1]);
        LocalDateTime[] p24 = MonitorTestAlertService.latestPeriod(now, 24);
        assertEquals(LocalDateTime.of(2026, 10, 22, 0, 0), p24[0]);
        assertEquals(LocalDateTime.of(2026, 10, 23, 0, 0), p24[1]);
        LocalDateTime[] edge = MonitorTestAlertService.latestPeriod(LocalDateTime.of(2026, 10, 23, 12, 0), 4);
        assertEquals(LocalDateTime.of(2026, 10, 23, 8, 0), edge[0], "整点 12:00 时 08:00–12:00 已结束");
    }

    static class FakeLark extends LarkClient
    {
        String chatId;

        String text;

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
            return next;
        }
    }
}
