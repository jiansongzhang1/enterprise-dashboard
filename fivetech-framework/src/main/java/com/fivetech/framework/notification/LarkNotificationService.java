package com.fivetech.framework.notification;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Lark 文本消息通知服务。 */
@Service
public class LarkNotificationService
{
    private final LarkProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private volatile String accessToken;
    private volatile long accessTokenExpireAt;

    public LarkNotificationService(LarkProperties properties, ObjectMapper objectMapper)
    {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
            .build();
    }

    /**
     * 向 Lark 用户或群聊发送文本消息。
     *
     * @param receiveId 用户 open_id 或群聊 chat_id
     * @param receiveIdType open_id 或 chat_id
     * @param message 文本消息
     */
    public void sendText(String receiveId, String receiveIdType, String message)
    {
        validateConfiguration();
        try
        {
            String token = getAccessToken();
            HttpResponse<String> response = sendMessage(token, receiveId, receiveIdType, message);
            JsonNode result = objectMapper.readTree(response.body());
            if (isFailed(response, result))
            {
                // 凭证可能刚好失效，刷新一次后重试，避免要求用户重复操作。
                invalidateToken(token);
                response = sendMessage(getAccessToken(), receiveId, receiveIdType, message);
                result = objectMapper.readTree(response.body());
            }
            if (isFailed(response, result))
            {
                throw new IllegalStateException("Lark消息发送失败：" + errorMessage(result, response.body()));
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Lark消息发送被中断", e);
        }
        catch (Exception e)
        {
            if (e instanceof IllegalStateException)
            {
                throw (IllegalStateException) e;
            }
            throw new IllegalStateException("Lark消息发送失败", e);
        }
    }

    private HttpResponse<String> sendMessage(String token, String receiveId, String receiveIdType, String message)
        throws Exception
    {
        Map<String, Object> text = new HashMap<>();
        text.put("text", message);
        Map<String, Object> payload = new HashMap<>();
        payload.put("receive_id", receiveId);
        payload.put("msg_type", "text");
        payload.put("content", objectMapper.writeValueAsString(text));

        String baseUrl = properties.getApiBaseUrl().replaceAll("/+$", "");
        URI uri = URI.create(baseUrl + "/open-apis/im/v1/messages?receive_id_type=" + receiveIdType);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String getAccessToken() throws Exception
    {
        long now = System.currentTimeMillis();
        if (StringUtils.hasText(accessToken) && now < accessTokenExpireAt)
        {
            return accessToken;
        }
        synchronized (this)
        {
            now = System.currentTimeMillis();
            if (StringUtils.hasText(accessToken) && now < accessTokenExpireAt)
            {
                return accessToken;
            }
            Map<String, String> payload = Map.of("app_id", properties.getAppId(), "app_secret", properties.getAppSecret());
            String baseUrl = properties.getApiBaseUrl().replaceAll("/+$", "");
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/open-apis/auth/v3/tenant_access_token/internal"))
                .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode result = objectMapper.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                || result.path("code").asInt(-1) != 0 || !StringUtils.hasText(result.path("tenant_access_token").asText()))
            {
                throw new IllegalStateException("Lark访问令牌获取失败：" + errorMessage(result, response.body()));
            }
            accessToken = result.path("tenant_access_token").asText();
            long expiresIn = Math.max(60, result.path("expire").asLong(7200));
            accessTokenExpireAt = System.currentTimeMillis() + Math.max(30, expiresIn - 300) * 1000;
            return accessToken;
        }
    }

    private void validateConfiguration()
    {
        if (!properties.isEnabled())
        {
            throw new IllegalStateException("Lark通知未启用，请配置 notification.lark.enabled=true");
        }
        if (!StringUtils.hasText(properties.getAppId()))
        {
            throw new IllegalStateException("Lark App ID未配置，请配置 notification.lark.app-id");
        }
        if (!StringUtils.hasText(properties.getAppSecret()))
        {
            throw new IllegalStateException("Lark App Secret未配置，请配置 notification.lark.app-secret");
        }
    }

    private boolean isFailed(HttpResponse<String> response, JsonNode result)
    {
        return response.statusCode() < 200 || response.statusCode() >= 300 || result.path("code").asInt(-1) != 0;
    }

    private String errorMessage(JsonNode result, String fallback)
    {
        String message = result.path("msg").asText();
        return StringUtils.hasText(message) ? message : fallback;
    }

    private void invalidateToken(String token)
    {
        if (token != null && token.equals(accessToken))
        {
            accessToken = null;
            accessTokenExpireAt = 0;
        }
    }
}
