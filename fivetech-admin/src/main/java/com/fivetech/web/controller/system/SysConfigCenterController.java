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
import com.fivetech.common.enums.BusinessType;
import com.fivetech.configcenter.ConfigCenterService;

/**
 * 系统管理 · 配置中心（config_center）：唯一键 = 配置编码 + 站点（站点为空 = 全站通用），配置值为 JSON。
 * 站点在请求体 / 参数里指定（不走 X-Site-Code）。
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/system/config-center")
public class SysConfigCenterController extends BaseController
{
    private final ConfigCenterService service;

    public SysConfigCenterController(ConfigCenterService service)
    {
        this.service = service;
    }

    /** 列表：configCode / pageName 模糊，siteCode 精确（传 "-" 只看全站通用） */
    @PreAuthorize("@ss.hasPermi('system:configCenter:list')")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(value = "configCode", required = false) String configCode,
            @RequestParam(value = "siteCode", required = false) String siteCode,
            @RequestParam(value = "pageName", required = false) String pageName)
    {
        return success(service.list(configCode, siteCode, pageName));
    }

    /** 新增 / 编辑用的选项：sites、pages（已用过的页面）、codes（代码里登记的编码：默认页面、含义、示例） */
    @PreAuthorize("@ss.hasPermi('system:configCenter:list')")
    @GetMapping("/options")
    public AjaxResult options()
    {
        return success(service.options());
    }

    @PreAuthorize("@ss.hasPermi('system:configCenter:list')")
    @GetMapping("/{id}")
    public AjaxResult get(@PathVariable Long id)
    {
        return success(service.get(id));
    }

    /** 新增：body { configCode, siteCode?（空 = 全站通用）, pageName, description, configValue（JSON） } */
    @PreAuthorize("@ss.hasPermi('system:configCenter:add')")
    @Log(title = "配置中心", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult create(@RequestBody Map<String, Object> body)
    {
        return success(service.create(body, getUsername()));
    }

    /** 编辑：body 同新增（整体替换） */
    @PreAuthorize("@ss.hasPermi('system:configCenter:edit')")
    @Log(title = "配置中心", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}")
    public AjaxResult update(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        return success(service.update(id, body, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('system:configCenter:remove')")
    @Log(title = "配置中心", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}")
    public AjaxResult delete(@PathVariable Long id)
    {
        service.delete(id);
        return success();
    }
}
