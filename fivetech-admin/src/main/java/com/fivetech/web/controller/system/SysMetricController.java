package com.fivetech.web.controller.system;

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
import com.fivetech.dashboard.domain.MetricDictItem;
import com.fivetech.dashboard.service.MetricDictService;

/**
 * 系统管理 · 指标管理：每个指标的口径与取数方式（dashboard_metric_card）。
 * <p>不改线上取数：运营总览、指标汇总仍按启动时加载的配置与 application.yml 取数；
 * 已接入的指标只能改口径与取数说明，后台新增的指标都是「仅登记」。只用 GET / POST，动作写在路径末尾。</p>
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/system/metrics")
public class SysMetricController extends BaseController
{
    private final MetricDictService metricDictService;

    public SysMetricController(MetricDictService metricDictService)
    {
        this.metricDictService = metricDictService;
    }

    /** 列表：{ rows, total, groups, datasets }；keyword 匹配编码 / 名称 / 口径，status = 0 已接入 / 1 仅登记 */
    @PreAuthorize("@ss.hasPermi('system:metric:list')")
    @GetMapping
    public AjaxResult list(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String groupCode,
            @RequestParam(required = false) String status)
    {
        return success(metricDictService.list(keyword, groupCode, status));
    }

    /** 详情：全部列 + 线上配置对照 + 引用情况 */
    @PreAuthorize("@ss.hasPermi('system:metric:list')")
    @GetMapping("/{metricCode}")
    public AjaxResult detail(@PathVariable String metricCode)
    {
        return success(metricDictService.detail(metricCode));
    }

    /** 登记新指标（仅登记：不上卡、不进页面、不能建告警规则）→ { metricCode } */
    @PreAuthorize("@ss.hasPermi('system:metric:add')")
    @Log(title = "指标管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult create(@RequestBody MetricDictItem body)
    {
        return success(Map.of("metricCode", metricDictService.create(body, getUsername())));
    }

    /** 修改口径与取数说明；仅登记的指标还能改名称、分组、格式、方向、更新频率 */
    @PreAuthorize("@ss.hasPermi('system:metric:edit')")
    @Log(title = "指标管理", businessType = BusinessType.UPDATE)
    @PostMapping("/{metricCode}/update")
    public AjaxResult update(@PathVariable String metricCode, @RequestBody MetricDictItem body)
    {
        metricDictService.update(metricCode, body, getUsername());
        return success();
    }

    /** 删除仅登记、且没有被页面 / 告警规则 / 异常事件 / 其他指标引用的指标 */
    @PreAuthorize("@ss.hasPermi('system:metric:remove')")
    @Log(title = "指标管理", businessType = BusinessType.DELETE)
    @PostMapping("/{metricCode}/delete")
    public AjaxResult delete(@PathVariable String metricCode)
    {
        metricDictService.delete(metricCode);
        return success();
    }
}
