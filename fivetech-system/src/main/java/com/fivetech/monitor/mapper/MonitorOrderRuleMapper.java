package com.fivetech.monitor.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.fivetech.monitor.domain.MonitorOrderRuleFilter;
import com.fivetech.monitor.domain.MonitorRule;
import com.fivetech.monitor.domain.MonitorScope;

/**
 * 订单规则数据层（monitor_rule 里 rule_type = 'ORDER' 的行）。
 *
 * <p>与指标规则同一张表、同一套编号与启停；这里只放订单规则自己的查询和写入。
 * 所有查询都带 {@code scope}（站点），写操作同时带 site_code 与 rule_type 条件。</p>
 *
 * @author fivetech
 */
public interface MonitorOrderRuleMapper
{
    /** 按条件查询（未删除），排序见 filter.orderBy；分页由 Service 里的 PageHelper.startPage 加上 */
    List<MonitorRule> selectOrderRuleList(@Param("scope") MonitorScope scope, @Param("f") MonitorOrderRuleFilter filter);

    /** 单条（未删除）；不存在或不是订单规则返回 null */
    MonitorRule selectOrderRuleById(@Param("scope") MonitorScope scope, @Param("ruleId") Long ruleId);

    /** 按编号取单条（未删除） */
    MonitorRule selectOrderRuleByNo(@Param("scope") MonitorScope scope, @Param("ruleNo") Integer ruleNo);

    /** 新增，回填 ruleId；version 由列默认值取序列 */
    /** 规则的版本号与启停、删除状态（含已删除的行）；发 Kafka 变更事件用 */
    MonitorRule selectOrderRuleStateById(@Param("scope") MonitorScope scope, @Param("ruleId") Long ruleId);

    int insertOrderRule(MonitorRule rule);

    /** 修改内容（不改启停、编号），version 取新值 */
    int updateOrderRule(@Param("scope") MonitorScope scope, @Param("rule") MonitorRule rule);
}
