package com.fivetech.web.controller.dashboard;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fivetech.common.core.controller.BaseController;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.dashboard.domain.query.MetricSummaryQuery;
import com.fivetech.dashboard.domain.vo.MetricSummaryVO;
import com.fivetech.dashboard.export.DashboardCsvExporter;
import com.fivetech.dashboard.service.IMetricSummaryService;

/**
 * 指标汇总表接口。
 * <p>
 * 一行是一个时间片，一列是一个指标；返回体附带区间合计与查询上下文
 * （asOf / updatedAt / 口径版本 / 延迟标记），前端一律以上下文为准，
 * 不得自行取当前时间。
 * <p>
 * 请求体传 {@code "export_csv": true} 时不返回 JSON，直接返回 CSV 文件流，
 * 浏览器按 {@code Content-Disposition: attachment} 走原生下载。
 * 导出与页面查询<b>复用同一条链路</b>（权限、时间语义、筛选、白名单完全一致），
 * 保证「看到的」与「导出的」是同一份数据。
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/dashboard/metrics")
public class MetricSummaryController extends BaseController
{
    private final IMetricSummaryService metricSummaryService;

    private final DashboardCsvExporter csvExporter;

    public MetricSummaryController(IMetricSummaryService metricSummaryService,
            DashboardCsvExporter csvExporter)
    {
        this.metricSummaryService = metricSummaryService;
        this.csvExporter = csvExporter;
    }

    /**
     * 查询指标汇总表；export_csv=true 时下载 CSV
     */
//    @PreAuthorize("@ss.hasPermi('dashboard:metric:summary')")
    @PostMapping("/summary")
    public ResponseEntity<?> summary(@Validated @RequestBody MetricSummaryQuery query)
    {
        if (query.isExportCsv())
        {
            // 文件名先算出来放进响应头，实际数据在流写出时才产生
            String fileName = csvExporter.fileName("指标汇总");
            return DashboardExportSupport.csv(fileName,
                out -> csvExporter.exportMetricSummary(query, out));
        }
        MetricSummaryVO data = metricSummaryService.query(query);
        return ResponseEntity.ok(AjaxResult.success(data));
    }
}
