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
}
