package com.fivetech.dashboard.subscription;

import java.io.Serializable;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 订阅接收者：Lark 群（type = chat，id = chat_id）或系统用户（type = user，id = user_id，按其邮箱发 Lark 个人消息）。
 * name 只用于列表显示和发送记录（群名 / 邮箱），发送时群用 chat_id、个人用用户当前的邮箱。
 *
 * @author fivetech
 */
public class SubscriptionReceiver implements Serializable
{
    private static final long serialVersionUID = 1L;

    public static final String CHAT = "chat";

    public static final String USER = "user";

    private String type;

    private String id;

    private String name;

    public SubscriptionReceiver()
    {
    }

    public SubscriptionReceiver(String type, String id, String name)
    {
        this.type = type;
        this.id = id;
        this.name = name;
    }

    public String getType() { return type; }

    public void setType(String type) { this.type = type; }

    public String getId() { return id; }

    public void setId(String id) { this.id = id; }

    public String getName() { return name; }

    public void setName(String name) { this.name = name; }

    @JsonIgnore
    public boolean isChat() { return CHAT.equals(type); }

    @JsonIgnore
    public boolean isUser() { return USER.equals(type); }
}
