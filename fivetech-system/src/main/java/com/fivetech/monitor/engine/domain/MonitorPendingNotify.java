package com.fivetech.monitor.engine.domain;

/**
 * 发送任务取出的一条到期记录（monitor_notify_log 的 pending 行 + 对应事件的状态）。
 *
 * @author fivetech
 */
public class MonitorPendingNotify
{
    private Long id;

    private String siteCode;

    private String msgType;

    private Long eventId;

    private String chatId;

    private String content;

    /** 已经失败重试过几次（首次发送前为 0） */
    private int retryCount;

    /** 对应事件的规则；没有事件时为 null */
    private Long ruleId;

    /** 对应事件的状态 open / resolved / stopped；没有事件时为 null */
    private String eventStatus;

    public Long getId()
    {
        return id;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public String getSiteCode()
    {
        return siteCode;
    }

    public void setSiteCode(String siteCode)
    {
        this.siteCode = siteCode;
    }

    public String getMsgType()
    {
        return msgType;
    }

    public void setMsgType(String msgType)
    {
        this.msgType = msgType;
    }

    public Long getEventId()
    {
        return eventId;
    }

    public void setEventId(Long eventId)
    {
        this.eventId = eventId;
    }

    public String getChatId()
    {
        return chatId;
    }

    public void setChatId(String chatId)
    {
        this.chatId = chatId;
    }

    public String getContent()
    {
        return content;
    }

    public void setContent(String content)
    {
        this.content = content;
    }

    public int getRetryCount()
    {
        return retryCount;
    }

    public void setRetryCount(int retryCount)
    {
        this.retryCount = retryCount;
    }

    public Long getRuleId()
    {
        return ruleId;
    }

    public void setRuleId(Long ruleId)
    {
        this.ruleId = ruleId;
    }

    public String getEventStatus()
    {
        return eventStatus;
    }

    public void setEventStatus(String eventStatus)
    {
        this.eventStatus = eventStatus;
    }
}
