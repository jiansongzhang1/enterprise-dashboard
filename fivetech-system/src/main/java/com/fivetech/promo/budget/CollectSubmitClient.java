package com.fivetech.promo.budget;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link CollectSubmitApi} 的 HTTP 实现（java.net.http）。token 只放 Authorization 头，不进日志、不进给用户的提示。
 *
 * @author fivetech
 */
@Component
public class CollectSubmitClient implements CollectSubmitApi
{
    private static final Logger log = LoggerFactory.getLogger(CollectSubmitClient.class);

    private final BudgetImportProperties props;

    private final ObjectMapper objectMapper;

    private volatile HttpClient http;

    public CollectSubmitClient(BudgetImportProperties props, ObjectMapper objectMapper)
    {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    public Response submit(String site, BudgetKind kind, String idempotencyKey, byte[] body)
    {
        String base = props.getCollectBaseUrl() == null ? "" : props.getCollectBaseUrl().trim();
        while (base.endsWith("/"))
        {
            base = base.substring(0, base.length() - 1);
        }
        String path = props.submitPath(kind);
        String token = props.tokenOf(site);
        if (base.isEmpty() || token.isEmpty())
        {
            return new Response(401, null, null, null, base.isEmpty() ? "未配置采集地址" : "未配置站点 " + site + " 的采集令牌");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(Math.max(1, props.getRequestTimeoutSeconds())))
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", idempotencyKey)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        long start = System.currentTimeMillis();
        try
        {
            HttpResponse<String> resp = http().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode json = null;
            String text = resp.body();
            if (text != null && !text.isBlank())
            {
                try
                {
                    json = objectMapper.readTree(text);
                }
                catch (RuntimeException e)
                {
                    json = null;
                }
            }
            String requestId = resp.headers().firstValue("X-Request-Id").orElse(json == null || json.get("request_id") == null
                ? null : json.get("request_id").asString());
            Integer retryAfter = retryAfter(resp.headers().firstValue("Retry-After").orElse(null));
            log.info("[promo-budget] 提交 {} site={} key={} → {} status={} code={} requestId={} 耗时 {}ms", path, site, idempotencyKey,
                resp.statusCode(), json == null ? null : json.path("status").asString(null),
                json == null ? null : json.path("code").asString(null), requestId, System.currentTimeMillis() - start);
            return new Response(resp.statusCode(), json, retryAfter, requestId, null);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return new Response(0, null, null, null, "interrupted");
        }
        catch (Exception e)
        {
            log.warn("[promo-budget] 提交 {} site={} key={} 没有响应 耗时 {}ms : {} {}", path, site, idempotencyKey,
                System.currentTimeMillis() - start, e.getClass().getSimpleName(), e.getMessage());
            return new Response(0, null, null, null, e.getClass().getSimpleName());
        }
    }

    private HttpClient http()
    {
        HttpClient c = http;
        if (c == null)
        {
            synchronized (this)
            {
                if (http == null)
                {
                    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                }
                c = http;
            }
        }
        return c;
    }

    /** Retry-After：秒数（HTTP 日期格式按没有） */
    static Integer retryAfter(String v)
    {
        if (v == null)
        {
            return null;
        }
        try
        {
            int s = Integer.parseInt(v.trim());
            return s < 0 ? null : s;
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }
}
