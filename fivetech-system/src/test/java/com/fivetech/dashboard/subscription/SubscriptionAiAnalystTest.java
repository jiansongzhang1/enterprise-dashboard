package com.fivetech.dashboard.subscription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fivetech.copilot.AiClient;
import com.fivetech.copilot.CopilotGateway;
import com.fivetech.copilot.CopilotProperties;
import com.fivetech.copilot.domain.CopilotUsage;
import com.fivetech.copilot.mapper.CopilotUsageMapper;

/** 订阅简报 AI 摘要：数据表、数字校验、整理格式、卡片与预览（假模型，不连网不连库） */
class SubscriptionAiAnalystTest
{
    /** 返回固定文本的假模型，记下收到的请求 */
    static final class FakeClient implements AiClient
    {
        String reply;
        String model;
        String system;
        String user;

        FakeClient(String reply)
        {
            this.reply = reply;
        }

        @Override
        public AiResult complete(String model, String system, String user, int maxTokens)
        {
            this.model = model;
            this.system = system;
            this.user = user;
            return new AiResult(reply, model, 1200, 150, "end_turn");
        }
    }

    /** 内存版调用记录 */
    static final class FakeUsage implements CopilotUsageMapper
    {
        final List<CopilotUsage> rows = new ArrayList<>();
        int today;

        @Override
        public int insert(CopilotUsage usage)
        {
            rows.add(usage);
            return 1;
        }

        @Override
        public int countToday(String feature)
        {
            return today;
        }
    }

    private static CopilotProperties props()
    {
        CopilotProperties p = new CopilotProperties();
        p.setEnabled(true);
        p.getAnthropic().setApiKey("test-key");
        return p;
    }

    private static SubscriptionAiAnalyst analyst(FakeClient client, FakeUsage usage, CopilotProperties p)
    {
        return new SubscriptionAiAnalyst(new CopilotGateway(client, p, usage), p);
    }

    @Test
    void factsTextListsEveryMetricWithDirectionAndAlert()
    {
        SubscriptionBrief b = SubscriptionAiSamples.brief();
        String facts = SubscriptionAiAnalyst.factsText(b, SubscriptionAiSamples.subscription());
        System.out.println(facts);
        assertEquals(8, b.getFacts().size());
        assertTrue(facts.contains("站點：uWin"));
        assertTrue(facts.contains("統計週期："));
        assertTrue(facts.contains("越高越好"));
        assertTrue(facts.contains("無方向"));
        assertTrue(facts.contains("嚴重"), "存款成功率的严重告警要写进数据表");
        assertTrue(facts.contains("777"));
        assertTrue(facts.contains("702"));
    }

    @Test
    void unknownNumbersFlagsOnlyNumbersMissingFromFacts()
    {
        String facts = "註冊人數｜777｜702｜+10.7%\n存款總額｜₹36,386.99｜₹41,210.50｜-11.7%";
        assertTrue(SubscriptionAiAnalyst.unknownNumbers("- 註冊 777 人，較上期 702 上升 10.7%", facts).isEmpty());
        assertTrue(SubscriptionAiAnalyst.unknownNumbers("- 存款 ₹36,386.99（-11.7%）", facts).isEmpty(), "千分位要能对上");
        assertTrue(SubscriptionAiAnalyst.unknownNumbers("- 3 個指標下降", facts).isEmpty(), "10 以下的计数不检查");
        assertEquals(List.of("75"), SubscriptionAiAnalyst.unknownNumbers("- 註冊增加 75 人", facts), "模型自己算的差值要拦下");
        assertEquals(List.of("10.5"), SubscriptionAiAnalyst.unknownNumbers("- 上升 10.5%", facts));
        assertEquals(List.of("77"), SubscriptionAiAnalyst.unknownNumbers("- 77", facts), "不能匹配到 777 的一部分");
        assertEquals(List.of("36.3"), SubscriptionAiAnalyst.unknownNumbers("- 36.3", "值 36.38"), "不能匹配到更长小数的前缀");
    }

    @Test
    void tidyKeepsBulletLinesOnly()
    {
        String raw = "1. **存款成功率** 嚴重\n\n• 註冊上升\n* GGR 上升\n- a\n- b\n- c\n- d";
        String t = SubscriptionAiAnalyst.tidy(raw);
        String[] lines = t.split("\n");
        assertEquals(SubscriptionAiAnalyst.MAX_LINES, lines.length);
        assertEquals("- 存款成功率 嚴重", lines[0]);
        assertEquals("- 註冊上升", lines[1]);
        assertEquals("- GGR 上升", lines[2]);
    }

    @Test
    void applyPutsSummaryIntoCardAndPreview()
    {
        FakeClient client = new FakeClient("- 存款成功率 81.33%，低於嚴重線 85.00%\n- 註冊人數 777，+10.7%");
        FakeUsage usage = new FakeUsage();
        SubscriptionBrief b = SubscriptionAiSamples.brief();
        analyst(client, usage, props()).apply(SubscriptionAiSamples.subscription(), b, "admin");

        assertEquals("claude-haiku-5-5", client.model, "订阅没选模型时用默认模型");
        assertNotNull(b.getAiSummary());
        String card = b.getCard().toString();
        assertTrue(card.contains("AI 摘要"));
        assertTrue(card.contains("僅供參考"));
        @SuppressWarnings("unchecked")
        Map<String, Object> ai = (Map<String, Object>) b.toPreview().get("ai");
        assertEquals("claude-haiku-5-5", ai.get("model"));
        assertNull(ai.get("note"));
        assertEquals(1, usage.rows.size());
        assertEquals(CopilotUsage.SUCCESS, usage.rows.get(0).getStatus());
    }

    @Test
    void inventedNumbersAreRejectedAndCardIsSentWithoutSummary()
    {
        FakeClient client = new FakeClient("- 註冊人數增加 75 人");
        FakeUsage usage = new FakeUsage();
        SubscriptionBrief b = SubscriptionAiSamples.brief();
        analyst(client, usage, props()).apply(SubscriptionAiSamples.subscription(), b, "admin");

        assertNull(b.getAiSummary());
        assertFalse(b.getCard().toString().contains("AI 摘要"));
        @SuppressWarnings("unchecked")
        Map<String, Object> ai = (Map<String, Object>) b.toPreview().get("ai");
        assertTrue(String.valueOf(ai.get("note")).contains("75"));
        assertEquals(CopilotUsage.REJECTED, usage.rows.get(0).getStatus());
    }

    @Test
    void dailyLimitAndMissingKeySkipTheCall()
    {
        FakeClient client = new FakeClient("- x");
        FakeUsage usage = new FakeUsage();
        usage.today = 20;
        SubscriptionBrief b = SubscriptionAiSamples.brief();
        analyst(client, usage, props()).apply(SubscriptionAiSamples.subscription(), b, "system");
        assertNull(client.user, "达到上限不调用模型");
        assertNull(b.getAiSummary());

        CopilotProperties noKey = props();
        noKey.getAnthropic().setApiKey("");
        SubscriptionBrief b2 = SubscriptionAiSamples.brief();
        analyst(client, new FakeUsage(), noKey).apply(SubscriptionAiSamples.subscription(), b2, "system");
        assertNull(client.user);
        @SuppressWarnings("unchecked")
        Map<String, Object> ai = (Map<String, Object>) b2.toPreview().get("ai");
        assertTrue(String.valueOf(ai.get("note")).contains("ANTHROPIC_API_KEY"));
    }
}
