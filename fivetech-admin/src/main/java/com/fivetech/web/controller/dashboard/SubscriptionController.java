package com.fivetech.web.controller.dashboard;

import java.util.ArrayList;
import java.util.List;
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
import com.fivetech.common.enums.BusinessType;
import com.fivetech.common.utils.SecurityUtils;
import com.fivetech.common.utils.SiteContext;
import com.fivetech.dashboard.subscription.SubscriptionActor;
import com.fivetech.dashboard.subscription.SubscriptionCenter;
import com.fivetech.dashboard.subscription.SubscriptionService;

/**
 * 【站点页面】订阅管理（V0.4 原型）：营运总览点「訂閱」新增；侧栏「訂閱管理」两个页签「訂閱規則｜發送歷史」。
 * 按当前站点（X-Site-Code），只看得到、只能操作本站的订阅。
 *
 * <p>权限：查看 {@code dashboard:subscription:list}；新增 {@code :add}；修改、启停、立即发送、测试发送 {@code :edit}；
 * 删除 {@code :remove}。另外只有负责人和管理员（{@code dashboard:subscription:admin} 或超级管理员）能修改某条订阅，
 * 其他人调用修改类接口返回 403。</p>
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/dashboard/subscriptions")
public class SubscriptionController extends BaseController
{
    static final String ADMIN_PERM = "dashboard:subscription:admin";

    private final SubscriptionCenter center;

    private final SubscriptionService service;

    public SubscriptionController(SubscriptionCenter center, SubscriptionService service)
    {
        this.center = center;
        this.service = service;
    }

    private String site()
    {
        return center.site(SiteContext.code());
    }

    private SubscriptionActor actor()
    {
        boolean admin = SecurityUtils.isAdmin() || SecurityUtils.hasPermi(ADMIN_PERM);
        return new SubscriptionActor(getUserId(), getUsername(), admin);
    }

    /** 固定选项：统计范围、发送频率、週几、N 的范围、字数上限、截图与 AI 是否可用、当前帐号是否管理员 */
    @PreAuthorize("@ss.hasPermi('dashboard:subscription:list')")
    @GetMapping("/meta")
    public AjaxResult meta()
    {
        Map<String, Object> m = service.meta();
        m.remove("sites");
        m.put("admin", actor().isAdmin());
        m.put("userId", getUserId());
        return success(m);
    }

    /** 本站可选指标、Lark 群（打开选群选单时实时拉取；失败给 chatsError 并返回上次结果）、负责人候选 */
    @PreAuthorize("@ss.hasPermi('dashboard:subscription:list')")
    @GetMapping("/options")
    public AjaxResult options()
    {
        return success(service.options(site(), getUserId()));
    }

    /** 订阅规则列表；筛选 state（ok / off / err）、owner（用户 ID 或 me）、chat（chat_id），都可多选 */
    @PreAuthorize("@ss.hasPermi('dashboard:subscription:list')")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(required = false) List<String> state, @RequestParam(required = false) List<String> owner,
            @RequestParam(required = false) List<String> chat)
    {
        return success(center.list(site(), nvl(state), nvl(owner), nvl(chat), actor()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:subscription:list')")
    @GetMapping("/{id}")
    public AjaxResult get(@PathVariable Long id)
    {
        return success(center.get(site(), id, actor()));
    }

    /**
     * 新增（只由营运总览发起）：{ name, description?, rangeCode, viewFrom?, viewTo?, viewGran?, viewCmp?, viewCmpFrom?, viewCmpTo?,
     * metrics:[code], freqType, sendHour, freqDow?, freqEvery?, freqTo?, receivers:[{id, name}], ownerUserId?, aiEnabled?, aiModel? }。
     * 字段错误 400 + data.fields。
     */
    @PreAuthorize("@ss.hasPermi('dashboard:subscription:add')")
    @Log(title = "訂閱管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult create(@RequestBody Map<String, Object> body)
    {
        return success(center.create(site(), body, actor()));
    }

    /** 修改：可改名称、说明、频率、接收群、负责人（转交 / 接管）、指标、AI；视图不能改 */
    @PreAuthorize("@ss.hasPermi('dashboard:subscription:edit')")
    @Log(title = "訂閱管理", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}")
    public AjaxResult update(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        return success(center.update(site(), id, body, actor()));
    }

    /** 启用 / 暂停：{ enabled } */
    @PreAuthorize("@ss.hasPermi('dashboard:subscription:edit')")
    @Log(title = "訂閱管理 · 啟停", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/enabled")
    public AjaxResult enabled(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        Object v = body == null ? null : body.get("enabled");
        boolean on = v instanceof Boolean ? (Boolean) v : Boolean.parseBoolean(String.valueOf(v).trim());
        return success(center.setEnabled(site(), id, on, actor()));
    }

    /** 删除：不再发送，发送历史保留 */
    @PreAuthorize("@ss.hasPermi('dashboard:subscription:remove')")
    @Log(title = "訂閱管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}")
    public AjaxResult delete(@PathVariable Long id)
    {
        center.delete(site(), id, actor());
        return success();
    }

    /** 用表单预览（不保存不发送）：卡片、截图，以及下次发送、今天的发送时刻、组合提示 */
    @PreAuthorize("@ss.hasPermi('dashboard:subscription:list')")
    @PostMapping("/preview")
    public AjaxResult preview(@RequestBody Map<String, Object> body)
    {
        return success(center.preview(site(), body, getUsername()));
    }

    /** 立即发送（异步）：{ accepted, message }，结果到发送历史查看 */
    @PreAuthorize("@ss.hasPermi('dashboard:subscription:edit')")
    @Log(title = "訂閱管理 · 立即發送", businessType = BusinessType.OTHER)
    @PostMapping("/{id}/send")
    public AjaxResult send(@PathVariable Long id)
    {
        return success(center.sendNow(site(), id, actor()));
    }

    /** 立即发送测试：按表单发一次到所选的群，卡片标【測試】，不记录、不影响排程；返回逐群结果 */
    @PreAuthorize("@ss.hasPermi('dashboard:subscription:edit')")
    @Log(title = "訂閱管理 · 測試發送", businessType = BusinessType.OTHER)
    @PostMapping("/test")
    public AjaxResult test(@RequestBody Map<String, Object> body)
    {
        return success(center.test(site(), body, actor()));
    }

    /**
     * 发送历史：range = today / yesterday / d7（默认）/ d30 / all / custom（from、to，最多 90 天）；
     * subscriptionId、result（OK / PART / FAIL / SKIP）、chatId 可多选；每页 20 条。
     */
    @PreAuthorize("@ss.hasPermi('dashboard:subscription:list')")
    @GetMapping("/history")
    public AjaxResult history(@RequestParam(required = false) String range, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) List<Long> subscriptionId,
            @RequestParam(required = false) List<String> result, @RequestParam(required = false) List<String> chatId,
            @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "20") int pageSize)
    {
        return success(center.history(site(), range, from, to, subscriptionId == null ? new ArrayList<>() : subscriptionId,
            nvl(result), nvl(chatId), pageNum, pageSize));
    }

    /** 发送历史的筛选项：订阅（含已删除）、接收群（历史里出现过的）、结果与原因代码表 */
    @PreAuthorize("@ss.hasPermi('dashboard:subscription:list')")
    @GetMapping("/history/options")
    public AjaxResult historyOptions()
    {
        return success(center.historyOptions(site()));
    }

    private static List<String> nvl(List<String> v)
    {
        return v == null ? new ArrayList<>() : v;
    }
}
