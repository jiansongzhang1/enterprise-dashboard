package com.fivetech.web.controller.system;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.fivetech.common.annotation.Log;
import com.fivetech.common.core.controller.BaseController;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.common.core.domain.model.LoginUser;
import com.fivetech.common.enums.BusinessType;
import com.fivetech.common.utils.SecurityUtils;
import com.fivetech.dashboard.subscription.SubscriptionService;

/**
 * 【管理后台】系统管理 · 订阅简报：每天定时把运营总览某个范围的指标整理成卡片，发到 Lark 群或个人。
 *
 * <p>权限：查看 {@code system:subscription:list}；新增 {@code :add}；修改、启停、立即发送、测试发送 {@code :edit}；删除 {@code :remove}。
 * 站点在请求体 / 参数里指定（管理后台不走 X-Site-Code）。</p>
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/system/subscriptions")
public class SysSubscriptionController extends BaseController
{
    private final SubscriptionService service;

    public SysSubscriptionController(SubscriptionService service)
    {
        this.service = service;
    }

    /** 固定选项：{ sites:[{siteCode, siteName, timezone}], ranges:[{code, label, dataset, granularity}], maxReceivers, scheduleEnabled, sendDelayMinutes } */
    @PreAuthorize("@ss.hasPermi('system:subscription:list')")
    @GetMapping("/meta")
    public AjaxResult meta()
    {
        return success(service.meta());
    }

    /** 某站点的可选指标、Lark 群（实时拉取）、个人：{ siteCode, timezone, metrics, chats, chatsError, users } */
    @PreAuthorize("@ss.hasPermi('system:subscription:list')")
    @GetMapping("/options")
    public AjaxResult options(@RequestParam String siteCode)
    {
        return success(service.options(siteCode, getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('system:subscription:list')")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(required = false) String siteCode, @RequestParam(required = false) Boolean enabled)
    {
        return success(service.list(siteCode, enabled));
    }

    @PreAuthorize("@ss.hasPermi('system:subscription:list')")
    @GetMapping("/{id}")
    public AjaxResult get(@PathVariable Long id)
    {
        return success(service.get(id));
    }

    /** 最近 100 条发送记录（一次发送 × 一个接收者一行），新的在前 */
    @PreAuthorize("@ss.hasPermi('system:subscription:list')")
    @GetMapping("/{id}/logs")
    public AjaxResult logs(@PathVariable Long id)
    {
        return success(service.logs(id));
    }

    /**
     * 新增：{ siteCode, name, description?, rangeCode, metrics:[code], sendHour, receivers:[{type:chat|user, id, name}], enabled? }。
     * 字段错误 400 + data.fields。
     */
    @PreAuthorize("@ss.hasPermi('system:subscription:add')")
    @Log(title = "訂閱簡報", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult create(@RequestBody Map<String, Object> body)
    {
        return success(service.create(body, getUsername()));
    }

    /** 修改：请求体同新增，站点不能改 */
    @PreAuthorize("@ss.hasPermi('system:subscription:edit')")
    @Log(title = "訂閱簡報", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}")
    public AjaxResult update(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        return success(service.update(id, body, getUsername()));
    }

    /** 启用 / 暂停：{ enabled } */
    @PreAuthorize("@ss.hasPermi('system:subscription:edit')")
    @Log(title = "訂閱簡報 · 啟停", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/enabled")
    public AjaxResult enabled(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        Object v = body == null ? null : body.get("enabled");
        boolean on = v instanceof Boolean ? (Boolean) v : Boolean.parseBoolean(String.valueOf(v).trim());
        return success(service.setEnabled(id, on, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('system:subscription:remove')")
    @Log(title = "訂閱簡報", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}")
    public AjaxResult delete(@PathVariable Long id)
    {
        service.delete(id, getUsername());
        return success();
    }

    /** 用表单内容预览简报（按现在的整点取数，不保存不发送）：{ title, description, period, compare, asOf, rows, footer, card } */
    @PreAuthorize("@ss.hasPermi('system:subscription:list')")
    @PostMapping("/preview")
    public AjaxResult preview(@RequestBody Map<String, Object> body)
    {
        return success(service.preview(body, getUsername()));
    }

    /** 立即发送给全部接收者：{ status, period, sent, failed, results } */
    @PreAuthorize("@ss.hasPermi('system:subscription:edit')")
    @Log(title = "訂閱簡報 · 立即發送", businessType = BusinessType.OTHER)
    @PostMapping("/{id}/send")
    public AjaxResult send(@PathVariable Long id)
    {
        return success(service.sendNow(id, getUsername()));
    }

    /** 测试发送：只发给自己（按登录账号的邮箱），卡片标【測試】 */
    @PreAuthorize("@ss.hasPermi('system:subscription:edit')")
    @Log(title = "訂閱簡報 · 測試發送", businessType = BusinessType.OTHER)
    @PostMapping("/{id}/test")
    public AjaxResult test(@PathVariable Long id)
    {
        LoginUser user = SecurityUtils.getLoginUser();
        String email = user == null || user.getUser() == null ? null : user.getUser().getEmail();
        return success(service.test(id, getUserId(), email, getUsername()));
    }
}
