package com.fivetech.monitor.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * GET /monitor/order-events 的库内筛选（订单状态在 UDS，由服务层过滤）。时间已换成统计时区，区间 [rangeFrom, rangeTo)。
 *
 * @author fivetech
 */
public class MonitorOrderEventQuery
{
    /** pending / done；空 = 不筛 */
    private String handle;

    private LocalDateTime rangeFrom;

    private LocalDateTime rangeTo;

    private List<Long> ruleIds;

    private List<Long> ownerIds;

    public String getHandle() { return handle; }
    public void setHandle(String handle) { this.handle = handle; }
    public LocalDateTime getRangeFrom() { return rangeFrom; }
    public void setRangeFrom(LocalDateTime rangeFrom) { this.rangeFrom = rangeFrom; }
    public LocalDateTime getRangeTo() { return rangeTo; }
    public void setRangeTo(LocalDateTime rangeTo) { this.rangeTo = rangeTo; }
    public List<Long> getRuleIds() { return ruleIds; }
    public void setRuleIds(List<Long> ruleIds) { this.ruleIds = ruleIds; }
    public List<Long> getOwnerIds() { return ownerIds; }
    public void setOwnerIds(List<Long> ownerIds) { this.ownerIds = ownerIds; }
}
