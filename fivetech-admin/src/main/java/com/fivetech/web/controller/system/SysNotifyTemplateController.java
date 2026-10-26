package com.fivetech.web.controller.system;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fivetech.common.annotation.Log;
import com.fivetech.common.core.controller.BaseController;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.common.enums.BusinessType;
import com.fivetech.monitor.domain.MonitorNotifyTemplate;
import com.fivetech.monitor.service.MonitorNotifyTemplateService;

/**
 * 系统管理 · 通知模板：告警消息模板（全站默认 + 各站点专用 × 三种告警类型）。
 * 只用 GET / POST，动作写在路径末尾（与监控管理接口一致）。
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/system/notify-templates")
public class SysNotifyTemplateController extends BaseController
{
    private final MonitorNotifyTemplateService templateService;

    public SysNotifyTemplateController(MonitorNotifyTemplateService templateService)
    {
        this.templateService = templateService;
    }

    /** 全部模板 + 每个站点每种类型实际生效的是哪一份 */
    @PreAuthorize("@ss.hasPermi('system:notifyTpl:list')")
    @GetMapping
    public AjaxResult list()
    {
        return success(templateService.list());
    }

    /** 可用的占位符（名字、写法、说明、示例值） */
    @PreAuthorize("@ss.hasPermi('system:notifyTpl:list')")
    @GetMapping("/placeholders")
    public AjaxResult placeholders()
    {
        return success(templateService.placeholders());
    }

    /** 预览：{ "content": "…" } → { errors: [], content: 渲染结果 }，不发送 */
    @PreAuthorize("@ss.hasPermi('system:notifyTpl:list')")
    @PostMapping("/preview")
    public AjaxResult preview(@RequestBody Map<String, String> body)
    {
        return success(templateService.preview(body == null ? null : body.get("content")));
    }

    /** 新增：{ siteCode（空 = 全站默认）, notifyType, content, enabled, remark } → templateId */
    @PreAuthorize("@ss.hasPermi('system:notifyTpl:edit')")
    @Log(title = "通知模板", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult create(@RequestBody MonitorNotifyTemplate body)
    {
        return success(Map.of("templateId", templateService.create(body, getUsername())));
    }

    /** 修改内容 / 启用状态 / 备注；站点和类型不能改 */
    @PreAuthorize("@ss.hasPermi('system:notifyTpl:edit')")
    @Log(title = "通知模板", businessType = BusinessType.UPDATE)
    @PostMapping("/{templateId}/update")
    public AjaxResult update(@PathVariable Long templateId, @RequestBody MonitorNotifyTemplate body)
    {
        templateService.update(templateId, body, getUsername());
        return success();
    }

    /** 删除站点专用模板（删除后该站点改用全站默认）；全站默认不能删 */
    @PreAuthorize("@ss.hasPermi('system:notifyTpl:edit')")
    @Log(title = "通知模板", businessType = BusinessType.DELETE)
    @PostMapping("/{templateId}/delete")
    public AjaxResult delete(@PathVariable Long templateId)
    {
        templateService.delete(templateId);
        return success();
    }

    /** 测试发送：{ chatId, content? }，content 不传用模板当前内容；用示例值渲染，开头加「【測試】」 */
    @PreAuthorize("@ss.hasPermi('system:notifyTpl:edit')")
    @Log(title = "通知模板测试发送", businessType = BusinessType.OTHER)
    @PostMapping("/{templateId}/test")
    public AjaxResult test(@PathVariable Long templateId, @RequestBody Map<String, String> body)
    {
        return success(templateService.test(templateId, body == null ? null : body.get("content"),
            body == null ? null : body.get("chatId")));
    }
}
