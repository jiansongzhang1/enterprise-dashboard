package com.fivetech.web.controller.monitoring;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fivetech.common.annotation.Log;
import com.fivetech.common.core.controller.BaseController;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.common.enums.BusinessType;
import com.fivetech.monitor.domain.dto.MonitorOrderRuleBody;
import com.fivetech.monitor.service.MonitorScopeResolver;
import com.fivetech.monitor.service.impl.MonitorOrderRuleService;

/**
 * 【Dashboard】监控管理 · 订单规则（后台新建、编辑、详情）。
 *
 * <p>订单规则逐笔判断提款订单；Yata 只存规则，判定与通知由判定服务做（设计文档 5.1、5.2）。
 * 启用 / 暂停复用 POST /monitor/rules/{ruleId}/enabled；订单规则不提供删除。
 * 站点取自请求头 X-Site-Code，权限沿用告警规则的 monitor:rule:*。</p>
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/monitor/order-rules")
public class MonitorOrderRuleController extends BaseController
{
    private final MonitorOrderRuleService service;

    private final MonitorScopeResolver scopeResolver;

    private final com.fivetech.monitor.service.IMonitorRuleService ruleService;

    public MonitorOrderRuleController(MonitorOrderRuleService service, MonitorScopeResolver scopeResolver,
            com.fivetech.monitor.service.IMonitorRuleService ruleService)
    {
        this.ruleService = ruleService;
        this.service = service;
        this.scopeResolver = scopeResolver;
    }

    /** 详情：结构同 GET /open/monitor/order-rules 的一条 */
    @PreAuthorize("@ss.hasPermi('monitor:rule:query')")
    @Log(title = "订单规则详情", businessType = BusinessType.QUERY, isSaveResponseData = false)
    @GetMapping("/{ruleId}")
    public AjaxResult detail(@PathVariable Long ruleId)
    {
        return success(service.detail(scopeResolver.resolve(), ruleId));
    }

    /** 新建：返回 { ruleId, ruleNo, ruleCode } */
    @PreAuthorize("@ss.hasPermi('monitor:rule:add')")
    @Log(title = "訂單規則", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult create(@RequestBody MonitorOrderRuleBody body)
    {
        return success(service.create(scopeResolver.resolve(), body, getUsername()));
    }

    /** 编辑：整条提交，不改启停 */
    @PreAuthorize("@ss.hasPermi('monitor:rule:edit')")
    @Log(title = "訂單規則", businessType = BusinessType.UPDATE)
    @PostMapping("/{ruleId}/update")
    public AjaxResult update(@PathVariable Long ruleId, @RequestBody MonitorOrderRuleBody body)
    {
        service.update(scopeResolver.resolve(), ruleId, body, getUsername());
        return success();
    }

    /** 测试告警：示例订单的卡片发到规则当前所有正常的群（失效的群跳过），不写订单告警事件 */
    @PreAuthorize("@ss.hasPermi('monitor:rule:test')")
    @Log(title = "訂單規則測試告警", businessType = BusinessType.OTHER)
    @PostMapping("/{ruleId}/test-alert")
    public AjaxResult testAlert(@PathVariable Long ruleId)
    {
        return success(service.testAlert(scopeResolver.resolve(), ruleId, getUsername()));
    }

    /**
     * 删除（FY-95 R7，二次确认由前端做）：逻辑删除，规则号不复用；已记录的订单告警保留（列表标「已刪除」）。
     * 判定服务按 sinceVersion 增量拉取时拿到 deleted = true；之后对这条规则写回告警返回 404。
     */
    @PreAuthorize("@ss.hasPermi('monitor:rule:remove')")
    @Log(title = "訂單規則", businessType = BusinessType.DELETE)
    @PostMapping("/{ruleId}/delete")
    public AjaxResult delete(@PathVariable Long ruleId)
    {
        com.fivetech.monitor.domain.MonitorScope scope = scopeResolver.resolve();
        // 只认订单规则：不存在、已删除、不是订单规则 → 404
        // （ruleService.deleteRule 自己的校验不限类型，少了这句就能用订单规则的接口删掉指标规则）
        service.requireOrderRule(scope, ruleId);
        ruleService.deleteRule(scope, ruleId, getUsername());
        return success();
    }
}
