package com.fivetech.monitor.domain.vo;

/**
 * 规则的通知群。
 *
 * @author fivetech
 */
public class NotifyChatVO
{
    private String chatId;

    private String name;

    /** ok / dead */
    private String status;

    public String getChatId()
    {
        return chatId;
    }

    public void setChatId(String chatId)
    {
        this.chatId = chatId;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public String getStatus()
    {
        return status;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }
}
