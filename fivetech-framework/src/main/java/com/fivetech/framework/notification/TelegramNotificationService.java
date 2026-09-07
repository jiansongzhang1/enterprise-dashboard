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
 * Telegram Bot API 通知服务。
 */
@Service
public class TelegramNotificationService
{
    private final TelegramProperties properties;

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient;

    public TelegramNotificationService(TelegramProperties properties, ObjectMapper objectMapper)
    {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                .build();
    }

    /**
     * 通过 Telegram Bot 发送文本消息。
     *
     * @param chatId Telegram chat_id 或允许使用的 @channelusername
     * @param message 消息正文
     * @param parseMode 可选：Markdown、MarkdownV2 或 HTML
     */
    public void send(String chatId, String message, String parseMode)
    {
        if (!properties.isEnabled())
        {
            throw new IllegalStateException("Telegram通知未启用，请配置 notification.telegram.enabled=true");
        }
        if (!StringUtils.hasText(properties.getBotToken()))
        {
            throw new IllegalStateException("Telegram Bot Token未配置，请配置 notification.telegram.bot-token");
        }

        try
        {
            Map<String, Object> payload = new HashMap<>();
            payload.put("chat_id", chatId);
            payload.put("text", message);
            if (StringUtils.hasText(parseMode))
            {
                payload.put("parse_mode", parseMode);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot" + properties.getBotToken() + "/sendMessage"))
                    .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode result = objectMapper.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300 || !result.path("ok").asBoolean(false))
            {
                throw new IllegalStateException("Telegram消息发送失败：" + result.path("description").asText(response.body()));
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Telegram消息发送被中断", e);
        }
        catch (Exception e)
        {
            if (e instanceof IllegalStateException)
            {
                throw (IllegalStateException) e;
            }
            throw new IllegalStateException("Telegram消息发送失败", e);
        }
    }
}
