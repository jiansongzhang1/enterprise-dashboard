package com.fivetech.copilot;

import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fivetech.copilot.domain.CopilotUsage;
import com.fivetech.copilot.mapper.CopilotUsageMapper;

/**
 * 业务调用模型的统一关口：开关 / API Key 检查 → 每日次数上限 → 调用 → 业务校验 → 记一行 copilot_usage_log。
 *
 * <ul>
 *   <li>不抛异常：任何失败都返回 {@link Outcome#ok} = false 和可以给页面看的原因，业务照常进行（例如简报不带摘要）；</li>
 *   <li>没开或没配 Key、达到每日上限时不调用模型，也不记录；真正调用了的（成功、失败、被校验拒绝）都记一行；</li>
 *   <li>记录写失败只打日志，不影响业务结果；日志里不打印提示词和模型输出。</li>
 * </ul>
 *
 * @author fivetech
 */
@Component
public class CopilotGateway
{
    private static final Logger log = LoggerFactory.getLogger(CopilotGateway.class);

    /** copilot_usage_log.error 的长度上限 */
    private static final int ERROR_MAX = 500;

    private final AiClient client;

    private final CopilotProperties properties;

    private final CopilotUsageMapper usageMapper;

    public CopilotGateway(AiClient client, CopilotProperties properties, CopilotUsageMapper usageMapper)
    {
        this.client = client;
        this.properties = properties;
        this.usageMapper = usageMapper;
    }

    /** 一次调用的参数 */
    public static final class Request
    {
        /** 功能编码，如 BRIEF；每日上限按它统计 */
        public String feature;

        public String siteCode;

        /** 业务 ID，如订阅 ID */
        public String refId;

        public String model;

        public String system;

        public String user;

        public int maxTokens;

        /** 该功能每天最多调用几次；&lt;= 0 表示不限 */
        public int dailyLimit;

        public String operator;

        /** 多轮对话：不为 null 时按 messages 调用（user 不用） */
        public java.util.List<AiClient.Message> messages;

        /** true：每日上限按 operator 分别统计（聊天按人限次） */
        public boolean limitPerOperator;

        /** 业务校验：返回 null 表示通过，否则返回不采用的原因（会写进记录和页面提示） */
        public Function<String, String> validator;
    }

    /** 调用结果 */
    public static final class Outcome
    {
        public final boolean ok;

        /** ok 时为模型输出 */
        public final String text;

        /** 不 ok 时的原因（可以给页面看） */
        public final String reason;

        private Outcome(boolean ok, String text, String reason)
        {
            this.ok = ok;
            this.text = text;
            this.reason = reason;
        }

        static Outcome ok(String text)
        {
            return new Outcome(true, text, null);
        }

        static Outcome fail(String reason)
        {
            return new Outcome(false, null, reason);
        }
    }

    public Outcome complete(Request r)
    {
        if (!properties.isEnabled())
        {
            return Outcome.fail("AI 功能已關閉");
        }
        if (!properties.isConfigured())
        {
            return Outcome.fail("未配置 ANTHROPIC_API_KEY，AI 功能未啟用");
        }
        if (r.dailyLimit > 0)
        {
            int used;
            try
            {
                used = r.limitPerOperator ? usageMapper.countTodayByOperator(r.feature, r.operator)
                    : usageMapper.countToday(r.feature);
            }
            catch (RuntimeException e)
            {
                log.warn("copilot usage count failed, feature={}: {}", r.feature, e.toString());
                return Outcome.fail("無法確認今日 AI 調用次數，本次不調用");
            }
            if (used >= r.dailyLimit)
            {
                return Outcome.fail((r.limitPerOperator ? "你今日的 AI 對話已達上限（" : "今日 AI 調用已達上限（") + r.dailyLimit + " 次）");
            }
        }

        long start = System.nanoTime();
        CopilotUsage u = new CopilotUsage();
        u.setFeature(r.feature);
        u.setSiteCode(r.siteCode);
        u.setRefId(r.refId);
        u.setModel(r.model);
        u.setOperator(r.operator);
        Outcome outcome;
        try
        {
            AiClient.AiResult res = r.messages != null ? client.chat(r.model, r.system, r.messages, r.maxTokens)
                : client.complete(r.model, r.system, r.user, r.maxTokens);
            u.setInputTokens(res.getInputTokens());
            u.setOutputTokens(res.getOutputTokens());
            if (res.getModel() != null)
            {
                u.setModel(res.getModel());
            }
            String text = res.getText();
            String rejected;
            if (text == null || text.trim().isEmpty())
            {
                rejected = "模型沒有返回內容";
            }
            else
            {
                rejected = r.validator == null ? null : r.validator.apply(text);
            }
            if (rejected == null)
            {
                u.setStatus(CopilotUsage.SUCCESS);
                outcome = Outcome.ok(text);
            }
            else
            {
                u.setStatus(CopilotUsage.REJECTED);
                u.setError(cut(rejected));
                outcome = Outcome.fail(rejected);
            }
        }
        catch (AiClient.AiException e)
        {
            u.setStatus(CopilotUsage.FAILED);
            u.setError(cut(e.getMessage()));
            outcome = Outcome.fail(e.getMessage());
        }
        catch (RuntimeException e)
        {
            log.warn("copilot call failed, feature={} ref={}: {}", r.feature, r.refId, e.toString());
            u.setStatus(CopilotUsage.FAILED);
            u.setError(cut(e.getClass().getSimpleName()));
            outcome = Outcome.fail("AI 調用失敗");
        }
        u.setLatencyMs((int) ((System.nanoTime() - start) / 1_000_000L));
        record(u);
        return outcome;
    }

    private void record(CopilotUsage u)
    {
        try
        {
            usageMapper.insert(u);
        }
        catch (RuntimeException e)
        {
            log.warn("copilot usage insert failed, feature={} status={}: {}", u.getFeature(), u.getStatus(), e.toString());
        }
    }

    private static String cut(String s)
    {
        if (s == null)
        {
            return null;
        }
        return s.length() <= ERROR_MAX ? s : s.substring(0, ERROR_MAX);
    }
}
