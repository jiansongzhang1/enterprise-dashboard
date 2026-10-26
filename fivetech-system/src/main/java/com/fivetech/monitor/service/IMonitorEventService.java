package com.fivetech.monitor.service;

import java.util.List;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.vo.MonitorEventPageVO;

/**
 * 监控管理 · 异常事件。
 *
 * @author fivetech
 */
public interface IMonitorEventService
{
    /**
     * 事件列表。
     *
     * @param range  all / today / yest / d7 / d30（按 trigger_hour 的 IST 自然日，异常中的事件不受影响），默认 all
     * @param handle pending / done / all，默认 pending
     * @param status open / resolved / stopped，空 = 全部
     */
    MonitorEventPageVO listEvents(MonitorScope scope, String range, String handle, String metricCode, String status,
            Long ownerId, Integer pageNum, Integer pageSize);

    /** 标记已处理（单条与批量共用）：1～100 条，全部属于本站，否则 404；note 去掉首尾空白后 1～500 字 */
    int handle(MonitorScope scope, List<Long> eventIds, String note);

    /** 撤销处理；事件不属于本站 → 404；本来就是未处理也返回成功 */
    void unhandle(MonitorScope scope, Long eventId);
}
