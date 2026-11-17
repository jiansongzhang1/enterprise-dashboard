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
import com.fivetech.monitor.cond.CondRuleBody;
import com.fivetech.monitor.service.MonitorScopeResolver;
import com.fivetech.monitor.service.impl.MonitorCondRuleService;

/**
 * 【Dashboard】监控管理 · 条件规则：字段目录、详情、新建、编辑、删除、触发预览、条件参考信息、测试告警。站点取自请求头 X-Site-Code。
 * 启停沿用 POST /monitor/rules/{ruleId}/enabled；列表在 GET /monitor/rules（ruleType = COND）。
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/monitor/cond-rules")
public class MonitorCondRuleController extends BaseController
{
    private final MonitorCondRuleService service;

    private final MonitorScopeResolver scopeResolver;

    public MonitorCondRuleController(MonitorCondRuleService service, MonitorScopeResolver scopeResolver)
    {
        this.service = service;
        this.scopeResolver = scopeResolver;
    }

    /** 抽屉的字段目录与选项（字段在指标管理 MONITOR_COND 维护，维度选项在配置中心） */
    @PreAuthorize("@ss.hasPermi('monitor:rule:list')")
    @GetMapping("/fields")
    public AjaxResult fields()
    {
        return success(service.fields(scopeResolver.resolve()));
    }

    @PreAuthorize("@ss.hasPermi('monitor:rule:query')")
    @GetMapping("/{ruleId}")
    public AjaxResult detail(@PathVariable Long ruleId)
    {
        return success(service.detail(scopeResolver.resolve(), ruleId));
    }

    @PreAuthorize("@ss.hasPermi('monitor:rule:add')")
    @Log(title = "条件规则", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult create(@RequestBody CondRuleBody body)
    {
        return success(service.create(scopeResolver.resolve(), body == null ? new CondRuleBody() : body, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('monitor:rule:edit')")
    @Log(title = "条件规则", businessType = BusinessType.UPDATE)
    @PostMapping("/{ruleId}/update")
    public AjaxResult update(@PathVariable Long ruleId, @RequestBody CondRuleBody body)
    {
        service.update(scopeResolver.resolve(), ruleId, body == null ? new CondRuleBody() : body, getUsername());
        return success();
    }

    @PreAuthorize("@ss.hasPermi('monitor:rule:remove')")
    @Log(title = "条件规则", businessType = BusinessType.DELETE)
    @PostMapping("/{ruleId}/delete")
    public AjaxResult delete(@PathVariable Long ruleId)
    {
        service.delete(scopeResolver.resolve(), ruleId, getUsername());
        return success();
    }

    /** 触发预览：当前表单（可未保存）回测近 30 天；按表单签名缓存 5 分钟 */
    @PreAuthorize("@ss.hasPermi('monitor:rule:query')")
    @PostMapping("/preview")
    public AjaxResult preview(@RequestBody CondRuleBody body)
    {
        return success(service.preview(scopeResolver.resolve(), body == null ? new CondRuleBody() : body));
    }

    /** 条件的参考信息：最小样本量参考值（近 30 天每个时段中位数）、历史天数、基准从哪天起可判定（FY-93 R4 / R13） */
    @PreAuthorize("@ss.hasPermi('monitor:rule:query')")
    @PostMapping("/condition-info")
    public AjaxResult conditionInfo(@RequestBody(required = false) java.util.Map<String, Object> body)
    {
        return success(service.conditionInfo(scopeResolver.resolve(), body));
    }

    @PreAuthorize("@ss.hasPermi('monitor:rule:test')")
    @Log(title = "条件规则测试告警", businessType = BusinessType.OTHER)
    @PostMapping("/{ruleId}/test-alert")
    public AjaxResult testAlert(@PathVariable Long ruleId)
    {
        return success(service.testAlert(scopeResolver.resolve(), ruleId, getUsername()));
    }
}
