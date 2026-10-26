package com.fivetech.monitor.domain.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * GET /monitor/lark/chats 的返回。
 *
 * @author fivetech
 */
public class MonitorLarkChatsVO
{
    /** true = 本次（或 chats-cache-seconds 内）从 Lark 拉取成功；false = 拉取失败，chats 是上次的结果 */
    private boolean fetched;

    /** 最近一次成功拉取的时间（站点时区，yyyy-MM-dd HH:mm）；从没拉成功过为 null */
    private String fetchedAt;

    private List<MonitorLarkChatOptionVO> chats = new ArrayList<>();

    public boolean isFetched()
    {
        return fetched;
    }

    public void setFetched(boolean fetched)
    {
        this.fetched = fetched;
    }

    public String getFetchedAt()
    {
        return fetchedAt;
    }

    public void setFetchedAt(String fetchedAt)
    {
        this.fetchedAt = fetchedAt;
    }

    public List<MonitorLarkChatOptionVO> getChats()
    {
        return chats;
    }

    public void setChats(List<MonitorLarkChatOptionVO> chats)
    {
        this.chats = chats == null ? new ArrayList<>() : chats;
    }
}
