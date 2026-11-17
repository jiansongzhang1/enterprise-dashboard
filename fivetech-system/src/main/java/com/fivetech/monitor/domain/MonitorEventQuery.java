package com.fivetech.monitor.domain;

import java.time.LocalDateTime;

/**
 * GET /monitor/events 的筛选条件。range 由服务层换算成 trigger_hour 的区间 [rangeFrom, rangeTo)，异常中的事件不受 range 影响。
 *
 * @author fivetech
 */
public class MonitorEventQuery
{
    /** pending / done；all 或空 = 不筛 */
    private String handle;

    /** open / resolved / stopped；空 = 不筛 */
    private String status;

    private String metricCode;

    private Long ownerId;

    /** 规则类型多选：METRIC / COND（release_next ㉓）；空 = 不筛 */
    private java.util.List<String> ruleTypes;

    /** 指标多选：选了时只返回指标规则的事件 */
    private java.util.List<String> metricCodes;

    /** 状态多选：open / resolved / stopped */
    private java.util.List<String> statuses;

    /** 负责人多选 */
    private java.util.List<Long> ownerIds;

    /** range 换算后的 trigger_hour 起点（含）；null = 不限 */
    private LocalDateTime rangeFrom;

    /** range 换算后的 trigger_hour 终点（不含）；null = 不限 */
    private LocalDateTime rangeTo;

    public String getHandle() { return handle; }
    public void setHandle(String handle) { this.handle = handle; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public LocalDateTime getRangeFrom() { return rangeFrom; }
    public void setRangeFrom(LocalDateTime rangeFrom) { this.rangeFrom = rangeFrom; }
    public LocalDateTime getRangeTo() { return rangeTo; }
    public void setRangeTo(LocalDateTime rangeTo) { this.rangeTo = rangeTo; }
    public java.util.List<String> getRuleTypes() { return ruleTypes; }
    public void setRuleTypes(java.util.List<String> ruleTypes) { this.ruleTypes = ruleTypes; }
    public java.util.List<String> getMetricCodes() { return metricCodes; }
    public void setMetricCodes(java.util.List<String> metricCodes) { this.metricCodes = metricCodes; }
    public java.util.List<String> getStatuses() { return statuses; }
    public void setStatuses(java.util.List<String> statuses) { this.statuses = statuses; }
    public java.util.List<Long> getOwnerIds() { return ownerIds; }
    public void setOwnerIds(java.util.List<Long> ownerIds) { this.ownerIds = ownerIds; }
}
