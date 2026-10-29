package com.fivetech.web.controller.system;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
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
import com.fivetech.dashboard.service.SiteManageService;
import com.fivetech.monitor.service.SiteSettingService;

/**
 * 系统管理 · 站点管理：站点列表、新增站点、修改站点名称 / 编码 / UDS 站点标识 / 时区（以库为准，存在 monitor_site_setting，与所属包网同一行），
 * 以及所属包网（monitor_site_setting.package_network）。改编码会级联更新所有带 site_code 的表，见 {@link SiteManageService}。
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/system/site")
public class SysSiteController extends BaseController
{
    private final SiteSettingService siteSettingService;

    private final SiteManageService siteManageService;

    public SysSiteController(SiteSettingService siteSettingService, SiteManageService siteManageService)
    {
        this.siteSettingService = siteSettingService;
        this.siteManageService = siteManageService;
    }

    /** 新增站点：body {siteCode, siteName, udsSite?, timezone?, currency?, currencySymbol?, sortNo?, packageNetwork?} → { siteCode }；编码与「未配置」行相同时把它配置为站点 */
    @PreAuthorize("@ss.hasPermi('system:site:add')")
    @Log(title = "站点管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult create(@RequestBody Map<String, String> body)
    {
        return success(Map.of("siteCode", siteManageService.create(body, getUsername())));
    }

    /**
     * 修改站点：body {siteCode?（新编码）, siteName?, udsSite?, timezone?, currency?, currencySymbol?, sortNo?}，没传的字段不改。
     * 改编码会级联更新角色、告警规则、异常事件等所有带 site_code 的表 → { siteCode, renamed: {表: 行数} }
     */
    @PreAuthorize("@ss.hasPermi('system:site:edit')")
    @Log(title = "站点管理", businessType = BusinessType.UPDATE)
    @PutMapping("/{siteCode}")
    public AjaxResult update(@PathVariable String siteCode, @RequestBody Map<String, String> body)
    {
        return success(siteManageService.update(siteCode, body, getUsername()));
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
