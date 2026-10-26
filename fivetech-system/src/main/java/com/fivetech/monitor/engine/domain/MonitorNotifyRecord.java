package com.fivetech.monitor.engine.domain;

import java.time.LocalDateTime;

/**
 * 写入 monitor_notify_log 的一条待发送记录。
 *
 * @author fivetech
 */
public class MonitorNotifyRecord
{
    private Long id;

    private String siteCode;

    /** alert / test（数据缺失不发通知） */
    private String msgType;

    private Long eventId;

    private LocalDateTime dataHour;

    private String chatId;

    private String content;

    private LocalDateTime now;

    /** 通知类型 ALERT_NEW / ALERT_ESCALATED（同一事件同一类型只发一次） */
    private String notifyType;

    public String getNotifyType()
    {
        return notifyType;
    }

    public void setNotifyType(String notifyType)
    {
        this.notifyType = notifyType;
    }

    /** 生成正文用的模板；内置文案为 null */
    private Long templateId;

    private Integer templateVersion;

    public Long getTemplateId()
    {
        return templateId;
    }

    public void setTemplateId(Long templateId)
    {
        this.templateId = templateId;
    }

    public Integer getTemplateVersion()
    {
        return templateVersion;
    }

    public void setTemplateVersion(Integer templateVersion)
    {
        this.templateVersion = templateVersion;
    }

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

    public LocalDateTime getDataHour()
    {
        return dataHour;
    }

    public void setDataHour(LocalDateTime dataHour)
    {
        this.dataHour = dataHour;
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

    public LocalDateTime getNow()
    {
        return now;
    }

    public void setNow(LocalDateTime now)
    {
        this.now = now;
    }
}
