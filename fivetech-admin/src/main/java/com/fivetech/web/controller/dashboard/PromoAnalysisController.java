package com.fivetech.web.controller.dashboard;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.fivetech.common.annotation.Log;
import com.fivetech.common.core.controller.BaseController;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.common.enums.BusinessType;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.promo.analysis.PromoAnalysisService;
import com.fivetech.promo.analysis.PromoExportService;
import com.fivetech.promo.analysis.query.ChannelTableQuery;
import com.fivetech.promo.analysis.query.CohortQuery;
import com.fivetech.promo.analysis.query.DailyReportQuery;
import com.fivetech.promo.analysis.query.FissionQuery;
import com.fivetech.promo.analysis.query.PromoFilter;

/**
 * 推广分析（FY-46）：字典与元数据、渠道表现、获客与留存、裂变层级、导出。概览走 {@code POST /dashboard/metrics/overview}（scene = PROMO）。
 *
 * <p>权限 {@code dashboard:promo:view}；站点取请求头 X-Site-Code。错误码：4001 时间、4002 对比期、4003 未知编码、
 * 4004 未知推广商 / 推广渠道 / 终端、4005 导出超过 1,000,000 行。UDS 推广数据集未接入，取数接口返回空结构（dataPending = true）。</p>
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/dashboard/promo")
public class PromoAnalysisController extends BaseController
{
    private final PromoAnalysisService service;

    private final PromoExportService exports;

    private final DashboardProperties properties;

    public PromoAnalysisController(PromoAnalysisService service, PromoExportService exports, DashboardProperties properties)
    {
        this.service = service;
        this.exports = exports;
        this.properties = properties;
    }

    /** 7.1 渠道字典、终端字典、站点信息、指标元数据（进入页面取一次，切站点重取） */
    @PreAuthorize("@ss.hasPermi('dashboard:promo:view')")
    @GetMapping("/options")
    public AjaxResult options()
    {
        return success(service.options());
    }

    /** 5.1 渠道表现 · 取一层 */
    @PreAuthorize("@ss.hasPermi('dashboard:promo:view')")
    @PostMapping("/channels/table")
    public AjaxResult channelTable(@RequestBody ChannelTableQuery query)
    {
        return success(service.channelTable(query));
    }

    /** 5.2 渠道表现 · 导出（全部层级平铺） */
    @PreAuthorize("@ss.hasPermi('dashboard:promo:view')")
    @Log(title = "推广分析 · 渠道表现导出", businessType = BusinessType.EXPORT, isSaveResponseData = false)
    @PostMapping("/channels/export")
    public ResponseEntity<byte[]> channelExport(@RequestBody ChannelTableQuery query)
    {
        checkExportEnabled();
        return DashboardExportSupport.promo(exports.channels(query));
    }

    /** 6.1 获客漏斗 */
    @PreAuthorize("@ss.hasPermi('dashboard:promo:view')")
    @PostMapping("/acquisition/funnel")
    public AjaxResult funnel(@RequestBody PromoFilter query)
    {
        return success(service.funnel(query));
    }

    /** 6.3 留存与回收 · 概要 4 张卡 */
    @PreAuthorize("@ss.hasPermi('dashboard:promo:view')")
    @PostMapping("/cohort/summary")
    public AjaxResult cohortSummary(@RequestBody CohortQuery query)
    {
        return success(service.cohortSummary(query));
    }

    /** 6.4 留存与回收 · 曲线 */
    @PreAuthorize("@ss.hasPermi('dashboard:promo:view')")
    @PostMapping("/cohort/curves")
    public AjaxResult cohortCurves(@RequestBody CohortQuery query)
    {
        return success(service.cohortCurves(query));
    }

    /** 6.5 留存与回收 · 按注册日明细 */
    @PreAuthorize("@ss.hasPermi('dashboard:promo:view')")
    @PostMapping("/cohort/batches")
    public AjaxResult cohortBatches(@RequestBody CohortQuery query)
    {
        return success(service.cohortBatches(query));
    }

    /** 7.2 裂变层级 · 逐层代理列表 */
    @PreAuthorize("@ss.hasPermi('dashboard:promo:view')")
    @PostMapping("/fission/agents")
    public AjaxResult fissionAgents(@RequestBody FissionQuery query)
    {
        return success(service.fissionAgents(query));
    }

    /** 7.2 裂变层级 · 定位（完整帐号或用户 ID） */
    @PreAuthorize("@ss.hasPermi('dashboard:promo:view')")
    @GetMapping("/fission/locate")
    public AjaxResult fissionLocate(@RequestParam(required = false) String q)
    {
        return success(service.fissionLocate(q));
    }

    /** 7.2 裂变层级 · 导出当前层 */
    @PreAuthorize("@ss.hasPermi('dashboard:promo:view')")
    @Log(title = "推广分析 · 裂变层级导出", businessType = BusinessType.EXPORT, isSaveResponseData = false)
    @PostMapping("/fission/export")
    public ResponseEntity<byte[]> fissionExport(@RequestBody FissionQuery query)
    {
        checkExportEnabled();
        return DashboardExportSupport.promo(exports.fission(query));
    }

    /** 4.3 投放日报导出（FY-57） */
    @PreAuthorize("@ss.hasPermi('dashboard:promo:view')")
    @Log(title = "推广分析 · 投放日报导出", businessType = BusinessType.EXPORT, isSaveResponseData = false)
    @PostMapping("/daily-report/export")
    public ResponseEntity<byte[]> dailyReport(@RequestBody DailyReportQuery query)
    {
        checkExportEnabled();
        return DashboardExportSupport.promo(exports.dailyReport(query));
    }

    private void checkExportEnabled()
    {
        if (!properties.getExport().isEnabled())
        {
            throw new ServiceException("导出功能未开启");
        }
    }
}
