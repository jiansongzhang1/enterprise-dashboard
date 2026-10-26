package com.fivetech.monitor.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.fivetech.monitor.domain.MonitorEventQuery;
import com.fivetech.monitor.domain.MonitorEventRow;
import com.fivetech.monitor.domain.MonitorScope;

/**
 * 异常事件列表与处理（GET /monitor/events、POST /monitor/events/handle、/unhandle）。全部按站点限定，只看告警事件（event_type = alert）。
 *
 * @author fivetech
 */
public interface MonitorEventMapper
{
    /** 事件列表：异常中排前（严重在前、起点早的在前），其余按触发时间倒序；调用方用 PageHelper 分页 */
    List<MonitorEventRow> selectEventList(@Param("scope") MonitorScope scope, @Param("q") MonitorEventQuery query);

    /** 这些事件里属于本站的告警事件数（批量处理前校验，不做部分成功） */
    int countEvents(@Param("scope") MonitorScope scope, @Param("ids") List<Long> eventIds);

    /** 标记已处理（已处理的再标记 = 修改，直接覆盖）；handled_level = 事件当前等级，清掉 handle_reset */
    int markHandled(@Param("scope") MonitorScope scope, @Param("ids") List<Long> eventIds, @Param("note") String note,
            @Param("userId") Long userId, @Param("now") LocalDateTime now);

    /** 本站该指标异常中事件的起点（回测预览 extreme.inOpenEvent 用）；没有为 null */
    LocalDateTime selectOpenEventStart(@Param("scope") MonitorScope scope, @Param("metricCode") String metricCode);

    /** 撤销处理：回到 pending，清空处理说明、处理人、时间、处理时等级 */
    int unhandle(@Param("scope") MonitorScope scope, @Param("eventId") Long eventId, @Param("now") LocalDateTime now);
}
