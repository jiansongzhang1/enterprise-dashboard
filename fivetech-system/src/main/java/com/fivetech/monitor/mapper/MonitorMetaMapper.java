package com.fivetech.monitor.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.fivetech.monitor.domain.MonitorMetricOption;
import com.fivetech.monitor.domain.MonitorOwner;
import com.fivetech.monitor.domain.MonitorScope;

/**
 * 监控模块引用的外部数据：指标配置（dashboard_metric_card）和用户（sys_user）。
 *
 * <p>这两类数据不带 site_code（指标属性全站通用；用户的站点权限表 sys_user_site 还没有落地），
 * 在 Mapper 扫描测试的白名单里。</p>
 *
 * @author fivetech
 */
public interface MonitorMetaMapper
{
    /** 可建告警规则的指标：monitorable = true 且启用，按 sort_no 排序 */
    List<MonitorMetricOption> selectMonitorableMetrics();

    /** 单个可监控指标；不存在或不可监控返回 null */
    MonitorMetricOption selectMonitorableMetric(@Param("metricCode") String metricCode);

    /** 可选负责人：状态正常、未删除，且是超级管理员或拥有 monitor:rule:list 权限 */
    List<MonitorOwner> selectOwnerCandidates(@Param("scope") MonitorScope scope);

    /** 判断某个用户是否在可选负责人范围内；不在返回 null */
    MonitorOwner selectOwnerCandidate(@Param("scope") MonitorScope scope, @Param("userId") Long userId);
}
