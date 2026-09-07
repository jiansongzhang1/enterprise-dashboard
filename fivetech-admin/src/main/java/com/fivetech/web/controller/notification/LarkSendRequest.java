package com.fivetech.web.controller.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Lark 文本消息发送请求。 */
public class LarkSendRequest
{
    @NotBlank(message = "Lark接收人不能为空")
    @Size(max = 200, message = "Lark接收人不能超过200个字符")
    private String receiveId;

    @NotBlank(message = "Lark接收人类型不能为空")
    @Pattern(regexp = "^(open_id|chat_id)$", message = "Lark接收人类型只支持 open_id 或 chat_id")
    private String receiveIdType;

    @NotBlank(message = "Lark消息不能为空")
    @Size(max = 4096, message = "Lark消息不能超过4096个字符")
    private String message;

    public String getReceiveId() { return receiveId; }
    public void setReceiveId(String receiveId) { this.receiveId = receiveId; }
    public String getReceiveIdType() { return receiveIdType; }
    public void setReceiveIdType(String receiveIdType) { this.receiveIdType = receiveIdType; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
