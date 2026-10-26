package com.fivetech.monitor.service;

import java.util.List;
import com.fivetech.dashboard.domain.vo.PageResultVO;
import com.fivetech.monitor.domain.MonitorRuleQuery;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.dto.MonitorRuleBody;
import com.fivetech.monitor.domain.vo.MonitorMetricVO;
import com.fivetech.monitor.domain.vo.MonitorOwnerVO;
import com.fivetech.monitor.domain.vo.MonitorRuleCreatedVO;
import com.fivetech.monitor.domain.vo.MonitorRuleDetailVO;
import com.fivetech.monitor.domain.vo.MonitorRuleVO;

/**
 * 告警规则服务。所有方法都按 {@link MonitorScope}（站点）过滤：取不到的规则一律 404。
 *
 * @author fivetech
 */
public interface IMonitorRuleService
{
    /** 规则列表，按 ruleNo 升序分页 */
    PageResultVO<MonitorRuleVO> listRules(MonitorScope scope, MonitorRuleQuery query);

    MonitorRuleDetailVO getRule(MonitorScope scope, Long ruleId);

    /** 本站全部可监控指标，并标出是否已有规则 */
    List<MonitorMetricVO> listMetrics(MonitorScope scope);

    /** 可选负责人 */
    List<MonitorOwnerVO> listOwners(MonitorScope scope);

    MonitorRuleCreatedVO createRule(MonitorScope scope, MonitorRuleBody body, String operator);

    void updateRule(MonitorScope scope, Long ruleId, MonitorRuleBody body, String operator);

    /** 启用 / 暂停；重复设置同一状态直接返回 */
    void setEnabled(MonitorScope scope, Long ruleId, boolean enabled, String operator);

    /** 逻辑删除；历史事件保留，规则号不复用 */
    void deleteRule(MonitorScope scope, Long ruleId, String operator);
}
