package com.fivetech.web.controller.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Telegram 消息发送请求。
 */
public class TelegramSendRequest
{
    @NotBlank(message = "Telegram chatId不能为空")
    @Size(max = 200, message = "Telegram chatId不能超过200个字符")
    private String chatId;

    @NotBlank(message = "Telegram消息不能为空")
    @Size(max = 4096, message = "Telegram消息不能超过4096个字符")
    private String message;

    @Size(max = 20, message = "Telegram消息格式不能超过20个字符")
    private String parseMode;

    public String getChatId()
    {
        return chatId;
    }

    public void setChatId(String chatId)
    {
        this.chatId = chatId;
    }

    public String getMessage()
    {
        return message;
    }

    public void setMessage(String message)
    {
        this.message = message;
    }

    public String getParseMode()
    {
        return parseMode;
    }

    public void setParseMode(String parseMode)
    {
        this.parseMode = parseMode;
    }
}
