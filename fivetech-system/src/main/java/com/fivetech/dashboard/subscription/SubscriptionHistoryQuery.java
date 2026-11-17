package com.fivetech.dashboard.subscription;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 发送历史的查询条件（mapper 参数）。时间是站点时区的墙上时间，左闭右开；为空不限。
 *
 * @author fivetech
 */
public class SubscriptionHistoryQuery
{
    private String siteCode;

    private LocalDateTime from;

    private LocalDateTime to;

    private List<Long> subscriptionIds = new ArrayList<>();

    private List<String> results = new ArrayList<>();

    private List<String> chatIds = new ArrayList<>();

    private int offset;

    private int limit = 20;

    public String getSiteCode() { return siteCode; }

    public void setSiteCode(String siteCode) { this.siteCode = siteCode; }

    public LocalDateTime getFrom() { return from; }

    public void setFrom(LocalDateTime from) { this.from = from; }

    public LocalDateTime getTo() { return to; }

    public void setTo(LocalDateTime to) { this.to = to; }

    public List<Long> getSubscriptionIds() { return subscriptionIds; }

    public void setSubscriptionIds(List<Long> subscriptionIds) { this.subscriptionIds = subscriptionIds; }

    public List<String> getResults() { return results; }

    public void setResults(List<String> results) { this.results = results; }

    public List<String> getChatIds() { return chatIds; }

    public void setChatIds(List<String> chatIds) { this.chatIds = chatIds; }

    public int getOffset() { return offset; }

    public void setOffset(int offset) { this.offset = offset; }

    public int getLimit() { return limit; }

    public void setLimit(int limit) { this.limit = limit; }
}
