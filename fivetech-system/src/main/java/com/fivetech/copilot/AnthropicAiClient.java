package com.fivetech.copilot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 直连 Anthropic Claude API（POST /v1/messages）。
 *
 * <ul>
 *   <li>认证头 x-api-key 只放在请求里，日志不打印；</li>
 *   <li>429（限流）、500 / 502 / 503 / 529（服务繁忙）按 retry-after 或 1s、2s… 退避重试；其他错误直接失败；</li>
 *   <li>走 JVM 默认代理设置（-Dhttps.proxyHost 等），与服务器其他出网一致。</li>
 * </ul>
 *
 * @author fivetech
 */
@Component
public class AnthropicAiClient implements AiClient
{
    private static final Logger log = LoggerFactory.getLogger(AnthropicAiClient.class);

    private final CopilotProperties properties;

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient;

    public AnthropicAiClient(CopilotProperties properties, ObjectMapper objectMapper)
    {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getAnthropic().getConnectTimeoutSeconds())))
            .build();
    }

    @Override
    public AiResult complete(String model, String system, String user, int maxTokens)
    {
        return send(model, body(model, system, user, maxTokens));
    }

    @Override
    public AiResult chat(String model, String system, List<Message> messages, int maxTokens)
    {
        List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (Message msg : messages)
        {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("role", "assistant".equals(msg.getRole()) ? "assistant" : "user");
            x.put("content", msg.getContent());
            list.add(x);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", model);
        m.put("max_tokens", Math.max(1, maxTokens));
        if (system != null && !system.isEmpty())
        {
            m.put("system", system);
        }
        m.put("messages", list);
        return send(model, m);
    }

    private AiResult send(String model, Map<String, Object> requestBody)
    {
        CopilotProperties.Anthropic cfg = properties.getAnthropic();
        if (!properties.isConfigured())
        {
            throw new AiException("未配置 ANTHROPIC_API_KEY，AI 功能未啟用", 0, "not_configured");
        }
        String body;
        try
        {
            body = objectMapper.writeValueAsString(requestBody);
        }
        catch (RuntimeException e)
        {
            throw new AiException("AI 請求內容序列化失敗", 0, "client_error");
        }
        String url = trimSlash(cfg.getBaseUrl()) + "/v1/messages";
        int retries = Math.max(0, cfg.getRetries());
        AiException last = null;
        for (int attempt = 0; attempt <= retries; attempt++)
        {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(5, cfg.getTimeoutSeconds())))
                .header("content-type", "application/json")
                .header("anthropic-version", cfg.getVersion())
                .header("x-api-key", cfg.getApiKey().trim())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response;
            long started = System.currentTimeMillis();
            try
            {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            }
            catch (java.net.http.HttpTimeoutException e)
            {
                last = new AiException("AI 回應逾時（" + cfg.getTimeoutSeconds() + " 秒）", 0, "timeout");
                log.warn("[copilot] 调用超时 model={} attempt={}", model, attempt + 1);
                continue;
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new AiException("AI 調用被中斷", 0, "interrupted");
            }
            catch (java.io.IOException e)
            {
                last = new AiException("連不上 AI 服務：" + e.getMessage(), 0, "network");
                log.warn("[copilot] 网络错误 model={} attempt={}：{}", model, attempt + 1, e.getMessage());
                sleep(backoffMillis(attempt, null));
                continue;
            }
            int status = response.statusCode();
            if (status == 200)
            {
                AiResult r = parse(response.body(), model);
                log.info("[copilot] model={} in={} out={} stop={} {}ms", r.getModel(), r.getInputTokens(), r.getOutputTokens(),
                    r.getStopReason(), System.currentTimeMillis() - started);
                return r;
            }
            last = error(status, response.body());
            log.warn("[copilot] 调用失败 model={} status={} type={} attempt={}：{}", model, status, last.getType(), attempt + 1,
                last.getMessage());
            if (!retryable(status))
            {
                throw last;
            }
            sleep(backoffMillis(attempt, response.headers().firstValue("retry-after").orElse(null)));
        }
        throw last != null ? last : new AiException("AI 調用失敗", 0, "unknown");
    }

    static Map<String, Object> body(String model, String system, String user, int maxTokens)
    {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", user);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", model);
        m.put("max_tokens", Math.max(1, maxTokens));
        if (system != null && !system.isEmpty())
        {
            m.put("system", system);
        }
        m.put("messages", List.of(message));
        return m;
    }

    private AiResult parse(String json, String model)
    {
        JsonNode root;
        try
        {
            root = objectMapper.readTree(json);
        }
        catch (RuntimeException e)
        {
            throw new AiException("AI 回應不是合法 JSON", 200, "bad_response");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : root.path("content"))
        {
            if ("text".equals(block.path("type").asString()))
            {
                text.append(block.path("text").asString());
            }
        }
        JsonNode usage = root.path("usage");
        return new AiResult(text.toString().trim(), root.path("model").isMissingNode() ? model : root.path("model").asString(),
            usage.path("input_tokens").asInt(0), usage.path("output_tokens").asInt(0), root.path("stop_reason").asString());
    }

    private AiException error(int status, String json)
    {
        String type = "http_" + status;
        String message = null;
        try
        {
            JsonNode err = objectMapper.readTree(json).path("error");
            if (!err.isMissingNode())
            {
                type = err.path("type").asString();
                message = err.path("message").asString();
            }
        }
        catch (RuntimeException ignore)
        {
            // 不是 JSON（例如代理返回的 HTML），按状态码描述
        }
        return new AiException(describe(status, message), status, type);
    }

    /** 页面上看的说明 */
    static String describe(int status, String message)
    {
        String m = message == null || message.isEmpty() ? "" : "（" + message + "）";
        switch (status)
        {
            case 401:
                return "AI API Key 無效或已被吊銷" + m;
            case 403:
                return "AI API Key 沒有這個模型的權限" + m;
            case 404:
                return "模型不存在，請確認模型名稱" + m;
            case 429:
                return "AI 調用超出限流，稍後重試" + m;
            case 529:
                return "AI 服務繁忙，稍後重試" + m;
            case 400:
                return "AI 請求被拒絕" + m;
            default:
                return "AI 服務錯誤 HTTP " + status + m;
        }
    }

    static boolean retryable(int status)
    {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 529;
    }

    /** retry-after（秒）优先，最多等 10 秒；否则 1s、2s、4s */
    static long backoffMillis(int attempt, String retryAfter)
    {
        if (retryAfter != null)
        {
            try
            {
                return Math.min(10_000L, Math.max(0L, (long) (Double.parseDouble(retryAfter.trim()) * 1000)));
            }
            catch (NumberFormatException ignore)
            {
                // 不是秒数（HTTP 日期），按指数退避
            }
        }
        return Math.min(10_000L, 1000L << Math.min(attempt, 3));
    }

    private static void sleep(long millis)
    {
        try
        {
            Thread.sleep(millis);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    private static String trimSlash(String s)
    {
        String v = s == null ? "" : s.trim();
        while (v.endsWith("/"))
        {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }
}
