package com.fivetech.dashboard.subscription;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import com.fivetech.copilot.AiClient;
import com.fivetech.copilot.AnthropicAiClient;
import com.fivetech.copilot.CopilotProperties;

/**
 * 真实调用模型看摘要效果（只在设置了环境变量 ANTHROPIC_API_KEY 时运行，否则跳过）：
 * <pre>
 * export ANTHROPIC_API_KEY=...   # 不要写进代码或配置文件
 * mvn -pl fivetech-system -am test -Dtest=SubscriptionAiLiveTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * 可用 -Dcopilot.model=xxx 换模型。
 */
class SubscriptionAiLiveTest
{
    @Test
    void printSummaryOfSampleBrief()
    {
        String key = System.getenv("ANTHROPIC_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "未设置 ANTHROPIC_API_KEY，跳过");
        CopilotProperties p = new CopilotProperties();
        p.setEnabled(true);
        p.getAnthropic().setApiKey(key);
        String model = System.getProperty("copilot.model", p.getDefaultModel());

        SubscriptionBrief b = SubscriptionAiSamples.brief();
        String facts = SubscriptionAiAnalyst.factsText(b, SubscriptionAiSamples.subscription());
        AiClient.AiResult r = new AnthropicAiClient(p, JsonMapper.builder().build())
            .complete(model, SubscriptionAiAnalyst.systemPrompt(), facts, p.getBrief().getMaxTokens());

        System.out.println("===== 数据 =====\n" + facts);
        System.out.println("===== " + r.getModel() + "（in " + r.getInputTokens() + " / out " + r.getOutputTokens()
            + " tokens，" + r.getStopReason() + "）=====\n" + r.getText());
        System.out.println("===== 校验 =====\n数据里没有的数字：" + SubscriptionAiAnalyst.unknownNumbers(r.getText(), facts));
        String tidy = SubscriptionAiAnalyst.tidy(r.getText());
        b.applyAi(tidy, model, p.label(model), null);
        Map<String, Object> preview = b.toPreview();
        System.out.println("===== 预览 ai =====\n" + preview.get("ai"));
        assertNotNull(r.getText());
    }
}
