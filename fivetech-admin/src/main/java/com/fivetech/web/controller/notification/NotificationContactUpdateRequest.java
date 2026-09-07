package com.fivetech.web.controller.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 通知联系人更新请求。 */
public class NotificationContactUpdateRequest
{
    @NotBlank(message = "通知渠道不能为空")
    @Size(max = 30, message = "通知渠道长度不能超过30个字符")
    private String channel;

    @NotBlank(message = "联系人标识不能为空")
    @Size(max = 200, message = "联系人标识长度不能超过200个字符")
    private String value;

    /** Lark 联系人标识类型，例如 open_id 或 chat_id。 */
    @Size(max = 30, message = "联系人标识类型长度不能超过30个字符")
    private String identifierType;

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getIdentifierType() { return identifierType; }
    public void setIdentifierType(String identifierType) { this.identifierType = identifierType; }
}
