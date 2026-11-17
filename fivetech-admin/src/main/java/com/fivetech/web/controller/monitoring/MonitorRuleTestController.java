package com.fivetech.web.controller.monitoring;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fivetech.common.annotation.Log;
import com.fivetech.common.core.controller.BaseController;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.common.enums.BusinessType;
import com.fivetech.monitor.engine.MonitorRuleAlertService;
import com.fivetech.monitor.engine.MonitorTestAlertService;
import com.fivetech.monitor.service.MonitorScopeResolver;

/**
 * 【管理后台】监控规则 · 手动触发测试告警（测试用）。
 *
 * <p>取规则最近一个已结束周期的真实数据，按线上模板生成严重告警，加「【測試】」发到规则当前所有正常的群（2026-11-17 起，
 * FY-91 R9；失效的群跳过）。不写事件和发送记录、不改规则状态、不受静默期限制。站点取请求头 X-Site-Code。</p>
 *
 * <p>判定试运行（开发自测）：指定检测周期，按线上引擎重放 30 天并按线上发送规则判断这一周期会不会发；
 * 会发才发到测试群（带「【測試】」）。不写任何表。</p>
 *
 * <p>按规则发告警（外部判定服务用，支持 API Key）：判定命中后按规则 ID 把告警消息发到这条规则配置的 Lark 群，
 * 见 {@link MonitorRuleAlertService}。</p>
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/monitor/rules")
public class MonitorRuleTestController extends BaseController
{
    private final MonitorTestAlertService testAlertService;

    private final MonitorScopeResolver scopeResolver;

    private final MonitorRuleAlertService ruleAlertService;

    public MonitorRuleTestController(MonitorTestAlertService testAlertService, MonitorScopeResolver scopeResolver,
            MonitorRuleAlertService ruleAlertService)
    {
        this.testAlertService = testAlertService;
        this.scopeResolver = scopeResolver;
        this.ruleAlertService = ruleAlertService;
    }

    /**
     * 按规则发告警：把 message 发到规则配置的 Lark 群（开头自动加「【站点名】」）。
     * body：{ "message": "…" }；订单规则须另带订单信息并写订单告警事件：
     * { "message": "…", "orderNo": "W1", "amount": 52000, "cumCount": 3, "cumAmount": 180000 }，
     * 多笔合并用 orders: [ { orderNo, amount, cumCount, cumAmount } ]（最多 100 笔），只记录不发送传 send: false。
     * 支持请求头 X-Api-Key（security.api-key.paths 里的 /monitor/rules/{ruleId}/alert）或登录 token；站点取 X-Site-Code。
     *
     * @return { ruleId, ruleCode, chatId, chatName, messageId, content, chats[] }（规则可选多个群，chats 为每个群的结果）；
     *         订单规则另有 { sent, notifyStatus, notifyReason, events[] }
     */
    @PreAuthorize("@ss.hasPermi('monitor:rule:alert')")
    @Log(title = "按规则发告警", businessType = BusinessType.OTHER)
    @PostMapping("/{ruleId}/alert")
    public AjaxResult alert(@PathVariable Long ruleId, @RequestBody(required = false) Map<String, Object> body)
    {
        return success(ruleAlertService.send(scopeResolver.resolve(), ruleId, body, getUsername()));
    }

    /** 返回 { success, messageId, error, chatId, chats[{chatId, name, status, messageId, error}], ruleCode, periodFrom, periodTo, value, baseline, level, dataError, content … } */
    @PreAuthorize("@ss.hasPermi('monitor:rule:test')")
    @Log(title = "告警规则手动触发测试", businessType = BusinessType.OTHER)
    @PostMapping("/{ruleId}/test-alert")
    public AjaxResult testAlert(@PathVariable Long ruleId)
    {
        return success(testAlertService.trigger(scopeResolver.resolve(), ruleId, getUsername()));
    }

    /**
     * 判定试运行。body：{ "period": "2026-10-19 18:00", "send": true }；period 为周期内任一时间（按规则检测周期对齐），
     * send 缺省 true，传 false 只返回判定结果不发送。
     *
     * @return { level, event, notify{decision, type, reason, lastNotifiedAt}, recentPeriods, sent, success, messageId, content … }
     */
    @PreAuthorize("@ss.hasPermi('monitor:rule:test')")
    @Log(title = "告警规则判定试运行", businessType = BusinessType.OTHER)
    @PostMapping("/{ruleId}/dry-run")
    public AjaxResult dryRun(@PathVariable Long ruleId, @RequestBody(required = false) Map<String, Object> body)
    {
        Object period = body == null ? null : body.get("period");
        Object send = body == null ? null : body.get("send");
        boolean doSend = send == null || (send instanceof Boolean ? (Boolean) send : Boolean.parseBoolean(String.valueOf(send).trim()));
        return success(testAlertService.dryRun(scopeResolver.resolve(), ruleId, period == null ? null : String.valueOf(period),
                doSend, getUsername()));
    }
}
