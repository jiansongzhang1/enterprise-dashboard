package com.fivetech.monitor.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.fivetech.monitor.domain.MonitorOccupiedMetric;
import com.fivetech.monitor.domain.MonitorRule;
import com.fivetech.monitor.domain.MonitorRuleQuery;
import com.fivetech.monitor.domain.MonitorScope;

/**
 * 告警规则数据层。
 *
 * <p>所有查询都带 {@code scope}（站点 + 负责人范围）；写操作只接收已经用 scope 查出来的 ruleId，
 * 并同时带 site_code 条件，避免跨站点误改。</p>
 *
 * @author fivetech
 */
public interface MonitorRuleMapper
{
    /** 规则列表（未删除），按 rule_no 升序；带出负责人、通知对象、最近触发小时和指标名称 */
    List<MonitorRule> selectRuleList(@Param("scope") MonitorScope scope, @Param("q") MonitorRuleQuery query);

    /** 单条规则（未删除）；不在数据范围内返回 null */
    MonitorRule selectRuleById(@Param("scope") MonitorScope scope, @Param("ruleId") Long ruleId);

    /**
     * 本站已被未删除规则占用的指标（不分负责人）。
     * <p>普通用户看不到别人的规则，但必须知道「该指标已有规则」，所以这里只按站点过滤，
     * 且只返回规则号和负责人名称，不返回阈值。</p>
     */
    List<MonitorOccupiedMetric> selectOccupiedMetrics(@Param("scope") MonitorScope scope);

    /** 新增，回填 ruleId */
    int insertRule(MonitorRule rule);

    /** 修改阈值、周期、负责人、通知对象等（不改 metric_code、judge_type、min_sample_key、rule_no） */
    int updateRule(@Param("scope") MonitorScope scope, @Param("rule") MonitorRule rule);

    /** 启用 / 暂停；暂停时记 paused_at */
    int updateEnabled(@Param("scope") MonitorScope scope, @Param("ruleId") Long ruleId,
            @Param("enabled") boolean enabled, @Param("pausedAt") LocalDateTime pausedAt,
            @Param("updateBy") String updateBy, @Param("updateTime") LocalDateTime updateTime);

    /** 逻辑删除（del_flag = '2'），并暂停判定 */
    int deleteRuleLogically(@Param("scope") MonitorScope scope, @Param("ruleId") Long ruleId,
            @Param("updateBy") String updateBy, @Param("updateTime") LocalDateTime updateTime);

    /** 规则暂停 / 删除时，未恢复事件改为 stopped，end_hour = 最近一个已判定小时 */
    int stopOpenEvents(@Param("scope") MonitorScope scope, @Param("ruleId") Long ruleId,
            @Param("endHour") LocalDateTime endHour, @Param("now") LocalDateTime now);

    /** 清掉规则运行状态：连续计数从 0 重新累计（判定任务读不到时按新规则起算） */
    int deleteRuleState(@Param("scope") MonitorScope scope, @Param("ruleId") Long ruleId);

    /** 本站启用中的规则数（一期上限 monitor.max-enabled-rules-per-site） */
    int countEnabledRules(@Param("scope") MonitorScope scope);

    /**
     * 运行状态重置（没有则插入）：连续计数清零，last_data_hour 置为给定小时。
     * 新建 / 启用 / 改了检测周期或持续次数时调用；异常中的事件不受影响（引擎从 monitor_event 读）。
     */
    int resetRuleState(@Param("scope") MonitorScope scope, @Param("ruleId") Long ruleId,
            @Param("lastDataHour") LocalDateTime lastDataHour, @Param("now") LocalDateTime now);
}
