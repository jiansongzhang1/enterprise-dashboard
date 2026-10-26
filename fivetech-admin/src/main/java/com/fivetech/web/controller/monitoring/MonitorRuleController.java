package com.fivetech.web.controller.monitoring;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.fivetech.common.annotation.Log;
import com.fivetech.common.core.controller.BaseController;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.common.enums.BusinessType;
import com.fivetech.monitor.domain.MonitorRuleQuery;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.dto.MonitorEnabledBody;
import com.fivetech.monitor.domain.dto.MonitorRuleBody;
import com.fivetech.monitor.exception.MonitorFieldException;
import com.fivetech.monitor.service.IMonitorRuleService;
import com.fivetech.monitor.service.MonitorScopeResolver;
import com.fivetech.monitor.service.impl.MonitorPreviewService;

/**
 * 【Dashboard】监控管理 · 告警规则。
 *
 * <p>放在 monitoring 包而不是 monitor 包：后者是 RuoYi 自带的系统监控（登录日志、在线用户等）。</p>
 *
 * <p>只用 GET 和 POST：查询用 GET；新增、修改、启停、删除一律 POST，动作写在路径末尾。</p>
 *
 * <p>一期不做功能级权限：有页面（菜单）权限就能编辑和保存。数据范围由 {@link MonitorScope} 控制，
 * 只按站点隔离（2026-10-20 按原型调整）；取不到的规则（其他站点、已删除）一律 404。</p>
 *
 * <p>站点取自请求头 {@code X-Site-Code}（不传为默认站点）。</p>
 *
 * <p>回测预览（POST /monitor/rules/preview）：近 30 天平时水平与按当前设置会触发的越线段（开始、等级、持续、最差值），
 * 复用判定引擎的判定纯函数，接口定义见设计文档。</p>
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/monitor")
public class MonitorRuleController extends BaseController
{
    private final IMonitorRuleService ruleService;

    private final MonitorPreviewService previewService;

    private final MonitorScopeResolver scopeResolver;

    public MonitorRuleController(IMonitorRuleService ruleService, MonitorPreviewService previewService,
            MonitorScopeResolver scopeResolver)
    {
        this.ruleService = ruleService;
        this.previewService = previewService;
        this.scopeResolver = scopeResolver;
    }

    /**
     * 规则列表：按 ruleNo 升序分页（pageSize 20 / 30 / 50 / 100）。
     * ownerId / owner=me 只是筛选，不是数据范围：有页面权限的人看得到本站全部规则。
     */
    @PreAuthorize("@ss.hasPermi('monitor:rule:list')")
    @GetMapping("/rules")
    public AjaxResult list(@RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String chatId,
            @RequestParam(required = false) String metricCode,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) String owner,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize)
    {
        MonitorScope scope = scopeResolver.resolve();
        MonitorRuleQuery query = new MonitorRuleQuery();
        query.setEnabled(enabled);
        query.setChatId(chatId);
        query.setMetricCode(metricCode);
        query.setOwnerId("me".equalsIgnoreCase(owner) ? scope.getUserId() : ownerId);
        query.setPageNum(pageNum);
        query.setPageSize(pageSize);
        return success(ruleService.listRules(scope, query));
    }

    /** 可新建的指标：本站全部可监控指标，并标出是否已有规则（不返回别人的阈值） */
    @PreAuthorize("@ss.hasPermi('monitor:rule:add')")
    @GetMapping("/rules/metrics")
    public AjaxResult metrics()
    {
        return success(ruleService.listMetrics(scopeResolver.resolve()));
    }

    /**
     * 平时水平与触发预览：请求体同新增规则，去掉 notifyChatId、ownerUserId、enabled；阈值可以没填完。不写库。
     */
    @PreAuthorize("@ss.hasPermi('monitor:rule:query')")
    @PostMapping("/rules/preview")
    public AjaxResult preview(@RequestBody MonitorRuleBody body)
    {
        return success(previewService.preview(scopeResolver.resolve(), body));
    }

    /** 规则详情；不在数据范围内或已删除 → 404 */
    @PreAuthorize("@ss.hasPermi('monitor:rule:query')")
    @GetMapping("/rules/{ruleId}")
    public AjaxResult detail(@PathVariable Long ruleId)
    {
        return success(ruleService.getRule(scopeResolver.resolve(), ruleId));
    }

    /** 可选负责人 */
    @PreAuthorize("@ss.hasPermi('monitor:rule:add')")
    @GetMapping("/owners")
    public AjaxResult owners()
    {
        return success(ruleService.listOwners(scopeResolver.resolve()));
    }

    /** 新增规则：返回 { ruleId, ruleNo, ruleCode } */
    @PreAuthorize("@ss.hasPermi('monitor:rule:add')")
    @Log(title = "告警规则", businessType = BusinessType.INSERT)
    @PostMapping("/rules")
    public AjaxResult create(@RequestBody MonitorRuleBody body)
    {
        return success(ruleService.createRule(scopeResolver.resolve(), body, getUsername()));
    }

    /** 修改规则：不能改 metricCode；只对之后的小时生效 */
    @PreAuthorize("@ss.hasPermi('monitor:rule:edit')")
    @Log(title = "告警规则", businessType = BusinessType.UPDATE)
    @PostMapping("/rules/{ruleId}/update")
    public AjaxResult update(@PathVariable Long ruleId, @RequestBody MonitorRuleBody body)
    {
        ruleService.updateRule(scopeResolver.resolve(), ruleId, body, getUsername());
        return success();
    }

    /** 启用 / 暂停：{ "enabled": false }；重复设置同一状态直接返回成功 */
    @PreAuthorize("@ss.hasPermi('monitor:rule:edit')")
    @Log(title = "告警规则启停", businessType = BusinessType.UPDATE)
    @PostMapping("/rules/{ruleId}/enabled")
    public AjaxResult enabled(@PathVariable Long ruleId, @RequestBody MonitorEnabledBody body)
    {
        if (body == null || body.getEnabled() == null)
        {
            throw MonitorFieldException.of("enabled", "請指定啟用或暫停");
        }
        ruleService.setEnabled(scopeResolver.resolve(), ruleId, body.getEnabled(), getUsername());
        return success();
    }

    /** 删除规则：逻辑删除，历史事件保留，规则号不复用，该指标之后可重新建规则 */
    @PreAuthorize("@ss.hasPermi('monitor:rule:remove')")
    @Log(title = "告警规则", businessType = BusinessType.DELETE)
    @PostMapping("/rules/{ruleId}/delete")
    public AjaxResult delete(@PathVariable Long ruleId)
    {
        ruleService.deleteRule(scopeResolver.resolve(), ruleId, getUsername());
        return success();
    }
}
