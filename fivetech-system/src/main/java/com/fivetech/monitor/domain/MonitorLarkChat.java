package com.fivetech.monitor.domain;

import java.time.LocalDateTime;

/**
 * Lark 群列表缓存（monitor_lark_chat）：每次实时拉取机器人所在的群后刷新。
 * status = dead 表示最近一次成功拉取里没有这个群（机器人已不在群里），或发送时 Lark 返回机器人不在群里。
 *
 * @author fivetech
 */
public class MonitorLarkChat
{
    private String siteCode;

    /** oc_ 开头 */
    private String chatId;

    /** Lark 群名 */
    private String name;

    /** ok / dead */
    private String status;

    /** 最近一次拉取中出现的时间 */
    private LocalDateTime lastSeenAt;

    private LocalDateTime updateTime;

    public String getSiteCode()
    {
        return siteCode;
    }

    public void setSiteCode(String siteCode)
    {
        this.siteCode = siteCode;
    }

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

    public LocalDateTime getLastSeenAt()
    {
        return lastSeenAt;
    }

    public void setLastSeenAt(LocalDateTime lastSeenAt)
    {
        this.lastSeenAt = lastSeenAt;
    }

    public LocalDateTime getUpdateTime()
    {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime)
    {
        this.updateTime = updateTime;
    }

    public boolean isDead()
    {
        return "dead".equals(status);
    }

}
