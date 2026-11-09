package com.fivetech.web.controller.dashboard;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.fivetech.common.core.controller.BaseController;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.dashboard.view.UserViewService;
import com.fivetech.dashboard.view.domain.SaveViewRequest;
import com.fivetech.framework.web.service.PermissionService;
import com.fivetech.monitor.exception.MonitorErrorCode;
import com.fivetech.monitor.exception.MonitorException;

/**
 * 个人视图（个人自定义指标集）：每人在每个站点、每个配置位上存一份自己的选择（卡片 / 表格列 / 行维度），服务端合成后下发。
 *
 * <p>站点取请求头 X-Site-Code，用户取登录令牌；有该页面的查看权限（推广分析为 dashboard:promo:view）即可读写自己的视图。
 * 写接口返回该配置位的完整最新结构（与 GET 单个相同）。错误：400（data.fields）、403 没有权限、404 配置位不存在或已停用。</p>
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/dashboard/views")
public class UserViewController extends BaseController
{
    private final UserViewService service;

    private final PermissionService permissions;

    public UserViewController(UserViewService service, PermissionService permissions)
    {
        this.service = service;
        this.permissions = permissions;
    }

    /** 本页的全部配置位：{ page, siteCode, slots[] }（进入页面、切换站点时取一次） */
    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public AjaxResult page(@RequestParam(required = false) String page)
    {
        String p = UserViewService.page(page);
        checkPage(p);
        return success(service.pageViews(p));
    }

    /** 单个配置位 */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{slotCode}")
    public AjaxResult slot(@PathVariable String slotCode)
    {
        checkPage(service.pageOfSlot(slotCode));
        return success(service.slotView(slotCode));
    }

    /** 保存（弹窗「应用」、小状态、一次性迁移） */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{slotCode}")
    public AjaxResult save(@PathVariable String slotCode, @RequestBody(required = false) SaveViewRequest body)
    {
        checkPage(service.pageOfSlot(slotCode));
        return success(service.save(slotCode, body));
    }

    /** 清空我在本站这个配置位的全部设置（含小状态） */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{slotCode}/reset")
    public AjaxResult reset(@PathVariable String slotCode)
    {
        checkPage(service.pageOfSlot(slotCode));
        return success(service.reset(slotCode));
    }

    private void checkPage(String page)
    {
        String perm = UserViewService.PAGE_PERMS.get(page);
        if (perm == null || !permissions.hasPermi(perm))
        {
            throw new MonitorException(MonitorErrorCode.FORBIDDEN, "沒有權限");
        }
    }
}
