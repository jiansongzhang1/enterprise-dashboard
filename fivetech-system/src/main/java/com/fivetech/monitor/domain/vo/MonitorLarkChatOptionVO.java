package com.fivetech.monitor.domain.vo;

/**
 * GET /monitor/lark/chats 的一项：机器人所在的 Lark 群。
 *
 * @author fivetech
 */
public class MonitorLarkChatOptionVO
{
    private String chatId;

    private String name;

    /** ok / dead；dead 只在被规则引用时返回，前端显示「失效」且不能新选 */
    private String status;

    /** 引用它的规则数（含暂停的，不含已删除的） */
    private int refCount;

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

    public int getRefCount()
    {
        return refCount;
    }

    public void setRefCount(int refCount)
    {
        this.refCount = refCount;
    }
}
