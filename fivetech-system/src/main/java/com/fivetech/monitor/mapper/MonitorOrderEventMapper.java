package com.fivetech.monitor.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.fivetech.monitor.domain.MonitorOrderEvent;
import com.fivetech.monitor.domain.MonitorOrderEventQuery;
import com.fivetech.monitor.domain.MonitorOrderEventRow;
import com.fivetech.monitor.domain.MonitorScope;

/**
 * 订单告警事件数据层（monitor_order_event）。查询都带 {@code scope}（站点），写操作带 site_code 条件。
 *
 * @author fivetech
 */
public interface MonitorOrderEventMapper
{
    /** 这条规则下已有的这些订单号（判断重复上报、取 eventId） */
    List<MonitorOrderEvent> selectByOrderNos(@Param("scope") MonitorScope scope, @Param("ruleId") Long ruleId,
            @Param("orderNos") List<String> orderNos);

    /** 新增；(site_code, rule_id, order_no) 已存在时什么都不做（并发重复上报），返回 0 */
    int insertEvent(MonitorOrderEvent event);

    /** 写一条事件的发送结果：汇总状态与每个群的结果（JSON）；at 为空时不改 notified_at */
    int updateNotify(@Param("scope") MonitorScope scope, @Param("eventId") Long eventId, @Param("status") String status,
            @Param("chats") String chats, @Param("at") LocalDateTime at);

    /** 「訂單告警」列表：按触发时间倒序，相同时 eventId 倒序（分页由 PageHelper 做） */
    List<MonitorOrderEventRow> selectOrderEventList(@Param("scope") MonitorScope scope, @Param("q") MonitorOrderEventQuery q);

    /** 未处理数（页签上的数字） */
    int countPending(@Param("scope") MonitorScope scope);

    /** 这些事件里属于本站的条数（标记处理前校验） */
    int countByIds(@Param("scope") MonitorScope scope, @Param("ids") List<Long> ids);

    /** 标记已处理；已处理的再标记时覆盖说明 */
    int markHandled(@Param("scope") MonitorScope scope, @Param("ids") List<Long> ids, @Param("note") String note,
            @Param("by") String by, @Param("now") LocalDateTime now);

    /** 撤销已处理，清空说明 */
    int unhandle(@Param("scope") MonitorScope scope, @Param("eventId") Long eventId);

    /** 每条订单规则最新一条订单告警的触发时间：[{ruleId, lastAt}]（告警规则列表的「最近觸發」） */
    List<java.util.Map<String, Object>> selectLastTriggers(@Param("scope") MonitorScope scope, @Param("ruleIds") List<Long> ruleIds);
}
