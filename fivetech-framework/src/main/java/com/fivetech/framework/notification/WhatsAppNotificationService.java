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

/**
 * WhatsApp Business Platform Cloud API 通知服务。
 */
@Service
public class WhatsAppNotificationService
{
    private final WhatsAppProperties properties;

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient;

    public WhatsAppNotificationService(WhatsAppProperties properties, ObjectMapper objectMapper)
    {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                .build();
    }

    /**
     * 发送 WhatsApp 文本消息。to 使用 E.164 国际号码格式，不带加号和空格。
     */
    public void sendText(String to, String message)
    {
        if (!properties.isEnabled())
        {
            throw new IllegalStateException("WhatsApp通知未启用，请配置 notification.whatsapp.enabled=true");
        }
        if (!StringUtils.hasText(properties.getAccessToken()))
        {
            throw new IllegalStateException("WhatsApp Access Token未配置，请配置 notification.whatsapp.access-token");
        }
        if (!StringUtils.hasText(properties.getPhoneNumberId()))
        {
            throw new IllegalStateException("WhatsApp Phone Number ID未配置，请配置 notification.whatsapp.phone-number-id");
        }

        try
        {
            Map<String, Object> text = new HashMap<>();
            text.put("preview_url", false);
            text.put("body", message);

            Map<String, Object> payload = new HashMap<>();
            payload.put("messaging_product", "whatsapp");
            payload.put("recipient_type", "individual");
            payload.put("to", to);
            payload.put("type", "text");
            payload.put("text", text);

            String version = StringUtils.hasText(properties.getGraphApiVersion())
                    ? properties.getGraphApiVersion() : "v23.0";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://graph.facebook.com/" + version + "/"
                            + properties.getPhoneNumberId() + "/messages"))
                    .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                    .header("Authorization", "Bearer " + properties.getAccessToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode result = objectMapper.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300 || result.has("error"))
            {
                String detail = result.path("error").path("message").asText(response.body());
                throw new IllegalStateException("WhatsApp消息发送失败：" + detail);
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("WhatsApp消息发送被中断", e);
        }
        catch (Exception e)
        {
            if (e instanceof IllegalStateException)
            {
                throw (IllegalStateException) e;
            }
            throw new IllegalStateException("WhatsApp消息发送失败", e);
        }
    }
}
