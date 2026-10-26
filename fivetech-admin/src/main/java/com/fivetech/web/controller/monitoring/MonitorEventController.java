package com.fivetech.web.controller.monitoring;

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
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.dto.MonitorHandleBody;
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

    public MonitorEventController(IMonitorEventService eventService, MonitorScopeResolver scopeResolver)
    {
        this.eventService = eventService;
        this.scopeResolver = scopeResolver;
    }

    /**
     * 事件列表：异常中排前（严重在前、持续从长到短），其余按触发时间倒序。
     * range = all / today / yest / d7 / d30（默认 all，异常中的事件不受影响）；handle = pending / done / all（默认 pending）。
     */
    @PreAuthorize("@ss.hasPermi('monitor:event:list')")
    @GetMapping
    public AjaxResult list(@RequestParam(required = false) String range,
            @RequestParam(required = false) String handle,
            @RequestParam(required = false) String metricCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) String owner,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize)
    {
        MonitorScope scope = scopeResolver.resolve();
        Long owned = "me".equalsIgnoreCase(owner) ? scope.getUserId() : ownerId;
        return success(eventService.listEvents(scope, range, handle, metricCode, status, owned, pageNum, pageSize));
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
