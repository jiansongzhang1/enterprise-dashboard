package com.fivetech.web.controller.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * WhatsApp 文本消息请求。
 */
public class WhatsAppSendRequest
{
    @NotBlank(message = "WhatsApp手机号不能为空")
    @Pattern(regexp = "^[1-9][0-9]{6,14}$", message = "WhatsApp手机号必须是E.164国际格式，例如819012345678")
    private String to;

    @NotBlank(message = "消息内容不能为空")
    @Size(max = 4096, message = "消息内容不能超过4096个字符")
    private String message;

    public String getTo()
    {
        return to;
    }

    public void setTo(String to)
    {
        this.to = to;
    }

    public String getMessage()
    {
        return message;
    }

    public void setMessage(String message)
    {
        this.message = message;
    }
}
