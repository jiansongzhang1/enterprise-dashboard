package com.fivetech.web.controller.system;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.fivetech.common.annotation.Log;
import com.fivetech.common.core.controller.BaseController;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.common.enums.BusinessType;
import com.fivetech.monitor.service.SiteSettingService;

/**
 * 系统管理 · 站点管理：站点列表（来自配置）与所属包网（monitor_site_setting.package_network）。
 * 站点本身的增删仍在配置里（dashboard.sites），这里只维护站点级设置。
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/system/site")
public class SysSiteController extends BaseController
{
    private final SiteSettingService siteSettingService;

    public SysSiteController(SiteSettingService siteSettingService)
    {
        this.siteSettingService = siteSettingService;
    }

    /** 全部站点；packageNetwork 按包网筛选，传「-」只看未设置包网的站点 */
    @PreAuthorize("@ss.hasPermi('system:site:list')")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(value = "packageNetwork", required = false) String packageNetwork)
    {
        return success(siteSettingService.list(packageNetwork));
    }

    /** 已用过的包网名称（输入框候选、筛选下拉） */
    @PreAuthorize("@ss.hasPermi('system:site:list')")
    @GetMapping("/packageNetworks")
    public AjaxResult packageNetworks()
    {
        return success(siteSettingService.packageNetworks());
    }

    /** 设置站点所属包网：body {siteCode, packageNetwork}，packageNetwork 为空表示清除 */
    @PreAuthorize("@ss.hasPermi('system:site:edit')")
    @Log(title = "站点管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult updatePackageNetwork(@RequestBody Map<String, String> body)
    {
        siteSettingService.updatePackageNetwork(body.get("siteCode"), body.get("packageNetwork"), getUsername());
        return success();
    }
}
