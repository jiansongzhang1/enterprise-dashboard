package com.fivetech.monitor.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.fivetech.monitor.domain.MonitorCondEvent;
import com.fivetech.monitor.domain.MonitorCondRow;
import com.fivetech.monitor.domain.MonitorCondState;
import com.fivetech.monitor.domain.MonitorRule;
import com.fivetech.monitor.domain.MonitorScope;

/**
 * 条件规则（monitor_rule.rule_type = 'COND' + monitor_rule_condition）的数据层：规则读写、每小时检测的状态与事件。
 * 查询都带 scope（站点），写操作带 site_code 条件。
 *
 * @author fivetech
 */
public interface MonitorCondMapper
{
    MonitorRule selectCondRule(@Param("scope") MonitorScope scope, @Param("ruleId") Long ruleId);

    /** 本站启用中的条件规则（检测用），按规则号 */
    List<MonitorRule> selectEnabledCondRules(@Param("scope") MonitorScope scope);

    List<MonitorCondRow> selectConditions(@Param("scope") MonitorScope scope, @Param("ruleIds") List<Long> ruleIds);

    int insertCondRule(MonitorRule rule);

    int updateCondRule(@Param("scope") MonitorScope scope, @Param("rule") MonitorRule rule);

    int deleteConditions(@Param("scope") MonitorScope scope, @Param("ruleId") Long ruleId);

    int insertCondition(@Param("scope") MonitorScope scope, @Param("row") MonitorCondRow row);

    // ------------------------------------------------------------------ 检测

    /** 有启用中条件规则的站点（检测任务入口，MonitorMapperScopeTest 白名单） */
    List<String> selectCondSites();

    /** 规则的检测状态 + 进行中的事件 */
    List<MonitorCondState> selectStates(@Param("scope") MonitorScope scope, @Param("ruleIds") List<Long> ruleIds);

    int upsertState(@Param("scope") MonitorScope scope, @Param("st") MonitorCondState state);

    /** 新建 / 启用时：之前的小时不判定 */
    int resetState(@Param("scope") MonitorScope scope, @Param("ruleId") Long ruleId, @Param("lastDataHour") LocalDateTime lastDataHour);

    int insertEvent(@Param("scope") MonitorScope scope, @Param("e") MonitorCondEvent event, @Param("now") LocalDateTime now);

    /** 事件继续：更新最近一期（detail.latest）与 last_period_end */
    int updateEventLatest(@Param("scope") MonitorScope scope, @Param("eventId") Long eventId, @Param("detail") String detail,
            @Param("lastPeriodEnd") LocalDateTime lastPeriodEnd, @Param("now") LocalDateTime now);

    /** 事件结束：回到正常 */
    int resolveEvent(@Param("scope") MonitorScope scope, @Param("eventId") Long eventId, @Param("endHour") LocalDateTime endHour,
            @Param("now") LocalDateTime now);

    int updateEventNotify(@Param("scope") MonitorScope scope, @Param("eventId") Long eventId, @Param("status") String status,
            @Param("now") LocalDateTime now);

    /** 事件详情（只认条件规则的事件由 Service 判断） */
    MonitorCondEvent selectEvent(@Param("scope") MonitorScope scope, @Param("eventId") Long eventId);

    /** 规则最近一次触发（判定时间），给规则列表的「最近觸發」 */
    LocalDateTime selectLastTrigger(@Param("scope") MonitorScope scope, @Param("ruleId") Long ruleId);
}
