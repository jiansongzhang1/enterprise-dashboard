package com.fivetech.web.controller.monitoring;

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
import com.fivetech.monitor.domain.dto.MonitorHandleBody;
import com.fivetech.monitor.order.MonitorOrderEventService;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.service.MonitorScopeResolver;

/**
 * 【Dashboard】监控管理 · 訂單告警：列表、标记已处理、撤销。站点取自请求头 X-Site-Code。
 * 订单状态、用户信息实时查 UDS，取不到时 udsOk = false、对应字段为空。
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/monitor/order-events")
public class MonitorOrderEventController extends BaseController
{
    private final MonitorOrderEventService service;

    private final MonitorScopeResolver scopeResolver;

    public MonitorOrderEventController(MonitorOrderEventService service, MonitorScopeResolver scopeResolver)
    {
        this.service = service;
        this.scopeResolver = scopeResolver;
    }

    /**
     * 列表：按触发时间倒序。handle = pending（默认）/ done / all；range = all（默认）/ today / yest / d7 / d30 / custom（带 from、to）；
     * ruleId、orderStatus、owner 可多选（重复参数或逗号分隔）；pageSize 默认 20，最多 100。
     */
    @PreAuthorize("@ss.hasPermi('monitor:event:list')")
    @Log(title = "訂單告警查询", businessType = BusinessType.QUERY, isSaveResponseData = false)
    @GetMapping
    public AjaxResult list(@RequestParam(required = false) String handle,
            @RequestParam(required = false) String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) List<Long> ruleId,
            @RequestParam(required = false) List<String> orderStatus,
            @RequestParam(required = false) List<String> owner,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize)
    {
        MonitorScope scope = scopeResolver.resolve();
        MonitorOrderEventService.ListParams p = new MonitorOrderEventService.ListParams();
        p.handle = handle;
        p.range = range;
        p.from = from;
        p.to = to;
        p.ruleId = ruleId;
        p.orderStatus = orderStatus;
        p.owner = owners(owner, scope.getUserId());
        p.pageNum = pageNum;
        p.pageSize = pageSize;
        return success(service.list(scope, p));
    }

    /** 批量标记已处理：{ "eventIds": [...], "note": "..." }，返回 { "updated": n }；已处理的再标记时覆盖说明 */
    @PreAuthorize("@ss.hasPermi('monitor:event:edit')")
    @Log(title = "訂單告警处理", businessType = BusinessType.UPDATE)
    @PostMapping("/handle")
    public AjaxResult handle(@RequestBody MonitorHandleBody body)
    {
        int updated = service.handle(scopeResolver.resolve(), body == null ? null : body.getEventIds(),
            body == null ? null : body.getNote());
        return success(Map.of("updated", updated));
    }

    /** 撤销已处理，清空说明：无请求体 */
    @PreAuthorize("@ss.hasPermi('monitor:event:edit')")
    @Log(title = "訂單告警撤销处理", businessType = BusinessType.UPDATE)
    @PostMapping("/{eventId}/unhandle")
    public AjaxResult unhandle(@PathVariable Long eventId)
    {
        service.unhandle(scopeResolver.resolve(), eventId);
        return success();
    }

    /** 负责人多选：用户 ID 或 me，逗号拼接、重复传参都行（同 GET /monitor/events 的 owner）；认不出的值忽略 */
    static List<Long> owners(List<String> owner, Long me)
    {
        List<Long> out = new java.util.ArrayList<>();
        if (owner == null)
        {
            return out;
        }
        for (String o : owner)
        {
            for (String part : o == null ? new String[0] : o.split(","))
            {
                String t = part.trim();
                if ("me".equalsIgnoreCase(t) && me != null)
                {
                    out.add(me);
                }
                else if (t.matches("\\d{1,18}"))
                {
                    out.add(Long.valueOf(t));
                }
            }
        }
        return out;
    }
}
