package com.fivetech.web.controller.monitoring;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import com.fivetech.monitor.cond.CondEventService;
import com.fivetech.monitor.domain.MonitorEventQuery;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.dto.MonitorHandleBody;
import com.fivetech.monitor.domain.vo.MonitorEventPageVO;
import com.fivetech.monitor.order.MonitorOrderEventService;
import com.fivetech.monitor.service.IMonitorEventService;
import com.fivetech.monitor.service.MonitorScopeResolver;

/**
 * 【Dashboard】监控管理 · 异常事件：列表、标记已处理、撤销处理。站点取自请求头 X-Site-Code，数据范围只按站点。
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/monitor/events")
public class MonitorEventController extends BaseController
{
    private final IMonitorEventService eventService;

    private final MonitorScopeResolver scopeResolver;

    private final MonitorOrderEventService orderEventService;

    private final CondEventService condEventService;

    public MonitorEventController(IMonitorEventService eventService, MonitorScopeResolver scopeResolver,
            MonitorOrderEventService orderEventService, CondEventService condEventService)
    {
        this.condEventService = condEventService;
        this.eventService = eventService;
        this.scopeResolver = scopeResolver;
        this.orderEventService = orderEventService;
    }

    /**
     * 事件列表：异常中排前，其余按触发时间倒序。指标规则与条件规则的事件同一张表（ruleType 筛选）。
     * range = all / today / yest / d7 / d30 / custom（带 from、to，yyyy-MM-dd，含两端，最多 90 天；默认 all，异常中的事件不受影响）；
     * handle = pending / done / all（默认 pending）；ruleType、metricCode、status、owner 可多选（重复参数或逗号分隔，owner 可为 me）。
     * 返回带 orderPendingTotal（訂單告警页签的未处理数）。
     */
    @PreAuthorize("@ss.hasPermi('monitor:event:list')")
    @Log(title = "异常事件查询", businessType = BusinessType.QUERY, isSaveResponseData = false)
    @GetMapping
    public AjaxResult list(@RequestParam(required = false) String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String handle,
            @RequestParam(required = false) List<String> ruleType,
            @RequestParam(required = false) List<String> metricCode,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) List<String> owner,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize)
    {
        MonitorScope scope = scopeResolver.resolve();
        MonitorEventQuery q = new MonitorEventQuery();
        q.setHandle(handle);
        q.setRuleTypes(ruleType);
        q.setMetricCodes(metricCode);
        q.setStatuses(status);
        List<Long> owners = new ArrayList<>();
        if (ownerId != null)
        {
            owners.add(ownerId);
        }
        if (owner != null)
        {
            for (String o : owner)
            {
                for (String part : o == null ? new String[0] : o.split(","))
                {
                    String t = part.trim();
                    if ("me".equalsIgnoreCase(t))
                    {
                        owners.add(scope.getUserId());
                    }
                    else if (t.matches("\\d{1,18}"))
                    {
                        owners.add(Long.valueOf(t));
                    }
                }
            }
        }
        q.setOwnerIds(owners);
        MonitorEventPageVO vo = eventService.listEvents(scope, q, range, from, to, pageNum, pageSize);
        // 进入页面一次取到两个页签的未处理数
        vo.setOrderPendingTotal(orderEventService.pendingTotal(scope));
        return success(vo);
    }

    /** 事件详情（只给条件规则）：状态、条件明细、走势、触发时的规则快照；指标规则的事件返回 400 */
    @PreAuthorize("@ss.hasPermi('monitor:event:list')")
    @GetMapping("/{eventId}")
    public AjaxResult detail(@PathVariable Long eventId)
    {
        return success(condEventService.detail(scopeResolver.resolve(), eventId));
    }

    /** 标记已处理（单条与批量共用）：{ "eventIds": [...], "note": "..." }，返回 { "updated": n } */
    @PreAuthorize("@ss.hasPermi('monitor:event:edit')")
    @Log(title = "异常事件处理", businessType = BusinessType.UPDATE)
    @PostMapping("/handle")
    public AjaxResult handle(@RequestBody MonitorHandleBody body)
    {
        int updated = eventService.handle(scopeResolver.resolve(), body == null ? null : body.getEventIds(),
            body == null ? null : body.getNote());
        return success(Map.of("updated", updated));
    }

    /** 撤销处理：无请求体 */
    @PreAuthorize("@ss.hasPermi('monitor:event:edit')")
    @Log(title = "异常事件撤销处理", businessType = BusinessType.UPDATE)
    @PostMapping("/{eventId}/unhandle")
    public AjaxResult unhandle(@PathVariable Long eventId)
    {
        eventService.unhandle(scopeResolver.resolve(), eventId);
        return success();
    }
}
