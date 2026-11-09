package com.fivetech.dashboard.subscription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fivetech.copilot.CopilotGateway;
import com.fivetech.copilot.CopilotProperties;
import com.fivetech.monitor.domain.vo.MonitorEventVO;

/** 订阅简报 · 数据异动分析：触发条件、给模型的数据、100 字与数字校验、卡片与预览（假模型） */
class SubscriptionAnomalyAnalystTest
{
    private static CopilotProperties props()
    {
        CopilotProperties p = new CopilotProperties();
        p.setEnabled(true);
        p.getAnthropic().setApiKey("test-key");
        return p;
    }

    private static MonitorEventVO event()
    {
        MonitorEventVO e = new MonitorEventVO();
        e.setMetricName("存款成功率");
        e.setLevel("CRIT");
        e.setStatus("open");
        e.setTriggerHour("2026-10-20 14:00");
        e.setCurrentText("81.33%");
        e.setJudgeBasis(List.of("嚴重線 < 85.00%"));
        return e;
    }

    private static final String GOOD = "- 存款成功率嚴重告警且仍異常中，觸發值81.33%，較上期-11.77pt；首存人數同步下降-21.1%，可能是支付通道問題拖累首存。GGR上升27.2%。";

    @Test
    void moversUsePctOrPt()
    {
        SubscriptionBrief b = SubscriptionAiSamples.brief();
        List<SubscriptionBrief.Change> m = SubscriptionAnomalyAnalyst.movers(b.getChanges(), new BigDecimal("20"), new BigDecimal("5"));
        assertEquals(List.of("ftd", "dOkR", "ggr"), m.stream().map(c -> c.code).toList());
        assertEquals(LocalDateTimes.of("2026-10-20 00:00"), b.getWindowFrom());
        assertEquals(LocalDateTimes.of("2026-10-21 00:00"), b.getWindowTo());
    }

    @Test
    void analysisWithEventsGoesIntoCardAndPreview()
    {
        SubscriptionAiAnalystTest.FakeClient client = new SubscriptionAiAnalystTest.FakeClient(GOOD);
        SubscriptionAiAnalystTest.FakeUsage usage = new SubscriptionAiAnalystTest.FakeUsage();
        CopilotProperties p = props();
        SubscriptionBrief b = SubscriptionAiSamples.brief();
        new SubscriptionAnomalyAnalyst(new CopilotGateway(client, p, usage), p)
            .apply(SubscriptionAiSamples.subscription(), b, List.of(event()), "tester");
        assertTrue(client.user.contains("2026-10-20 14:00｜存款成功率｜嚴重｜異常中｜81.33%｜嚴重線 < 85.00%"), client.user);
        assertTrue(client.user.contains("明顯波動的指標") && client.user.contains("- 首存人數 ") && client.user.contains("- GGR +27.2%"));
        assertTrue(client.system.contains("不超過 90 個字"));
        assertEquals("BRIEF_ANOMALY", usage.rows.get(0).getFeature());
        assertTrue(b.getAnomaly().startsWith("存款成功率嚴重告警"));
        assertTrue(!b.getAnomaly().startsWith("-"));
        assertTrue(String.valueOf(b.getCard()).contains("數據異動分析"));
        @SuppressWarnings("unchecked")
        Map<String, Object> an = (Map<String, Object>) b.toPreview().get("anomaly");
        assertEquals(b.getAnomaly(), an.get("analysis"));
        assertNull(an.get("note"));
    }

    @Test
    void quietPeriodDoesNotCallModel()
    {
        SubscriptionAiAnalystTest.FakeClient client = new SubscriptionAiAnalystTest.FakeClient(GOOD);
        SubscriptionAiAnalystTest.FakeUsage usage = new SubscriptionAiAnalystTest.FakeUsage();
        CopilotProperties p = props();
        p.getAnomaly().setChangePct(new BigDecimal("50"));
        p.getAnomaly().setChangePt(new BigDecimal("50"));
        SubscriptionBrief b = SubscriptionAiSamples.brief();
        new SubscriptionAnomalyAnalyst(new CopilotGateway(client, p, usage), p).apply(SubscriptionAiSamples.subscription(), b, List.of(), "t");
        assertNull(client.user);
        assertTrue(usage.rows.isEmpty());
        assertNull(b.getAnomaly());
        assertTrue(!String.valueOf(b.getCard()).contains("數據異動分析"));
        @SuppressWarnings("unchecked")
        Map<String, Object> an = (Map<String, Object>) b.toPreview().get("anomaly");
        assertTrue(String.valueOf(an.get("note")).contains("沒有告警事件"));
    }

    @Test
    void tooLongOrUnknownNumberRejected()
    {
        CopilotProperties p = props();
        String longText = "存款成功率異常中".repeat(15);
        SubscriptionAiAnalystTest.FakeUsage usage = new SubscriptionAiAnalystTest.FakeUsage();
        SubscriptionBrief b = SubscriptionAiSamples.brief();
        new SubscriptionAnomalyAnalyst(new CopilotGateway(new SubscriptionAiAnalystTest.FakeClient(longText), p, usage), p)
            .apply(SubscriptionAiSamples.subscription(), b, List.of(event()), "t");
        assertNull(b.getAnomaly());
        assertTrue(String.valueOf(((Map<?, ?>) b.toPreview().get("anomaly")).get("note")).contains("超過 100 字"));

        SubscriptionBrief b2 = SubscriptionAiSamples.brief();
        new SubscriptionAnomalyAnalyst(new CopilotGateway(new SubscriptionAiAnalystTest.FakeClient("存款成功率下降到 60.5%，可能是通道問題。"), p,
            new SubscriptionAiAnalystTest.FakeUsage()), p).apply(SubscriptionAiSamples.subscription(), b2, List.of(event()), "t");
        assertNull(b2.getAnomaly());
        assertTrue(String.valueOf(((Map<?, ?>) b2.toPreview().get("anomaly")).get("note")).contains("60.5"));
    }

    @Test
    void lengthIgnoresWhitespace()
    {
        assertEquals(6, SubscriptionAnomalyAnalyst.length("存款 成功\n率。"));
        assertEquals("ab", SubscriptionAnomalyAnalyst.tidy("- a\n\n**b**"));
    }

    /** yyyy-MM-dd HH:mm */
    static final class LocalDateTimes
    {
        static java.time.LocalDateTime of(String s)
        {
            return java.time.LocalDateTime.parse(s, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }
    }
}
