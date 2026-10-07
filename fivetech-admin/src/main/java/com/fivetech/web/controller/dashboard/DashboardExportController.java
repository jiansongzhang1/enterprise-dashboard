package com.fivetech.web.controller.dashboard;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fivetech.common.core.controller.BaseController;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.dashboard.domain.query.BetRecordQuery;
import com.fivetech.dashboard.domain.query.DepositRecordQuery;
import com.fivetech.dashboard.domain.query.MemberRecordQuery;
import com.fivetech.dashboard.domain.query.WithdrawRecordQuery;
import com.fivetech.dashboard.service.IDashboardExportService;

/**
 * 明细异步导出。
 * <p>
 * 与 {@code /dashboard/records/*?export_csv=true} 的同步导出不同，这里只负责
 * 「提交 → 查状态 → 拿链接」，文件由数据平台直接写到对象存储，浏览器用预签名链接直连下载，
 * 不经过本服务，因此不受本服务内存、30 秒网关超时和带宽的限制。
 * <pre>
 * POST   /dashboard/export/{member|deposit|withdraw|bet} 提交，入参与对应明细查询相同，返回 jobId
 * POST   /dashboard/export/benchmark                   压测导出（bulk_bets），验证大文件下载
 * GET    /dashboard/export/tasks/{jobId}               查状态；DONE 时带 1 小时有效的下载链接
 * DELETE /dashboard/export/tasks/{jobId}               取消
 * GET    /dashboard/export/tasks                       我的导出（最近 20 条）
 * </pre>
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/dashboard/export")
public class DashboardExportController extends BaseController
{
    private final IDashboardExportService exportService;

    public DashboardExportController(IDashboardExportService exportService)
    {
        this.exportService = exportService;
    }


    /**
     * 压测导出：UDS 的 bulk_bets 压测表（只开放 2026-09-10 一天），用来测大文件的生成耗时与浏览器下载速度。
     * 导出权限由服务层统一校验。
     */
    @PostMapping("/benchmark")
    public AjaxResult benchmark(@RequestBody(required = false) BenchmarkRequest request)
    {
        BenchmarkRequest r = request == null ? new BenchmarkRequest() : request;
        return AjaxResult.success(exportService.submitBenchmark(
            r.getMerchantCode(), r.isDetailRows(), r.getMaxRows() == null ? 0 : r.getMaxRows()));
    }

    @GetMapping("/tasks/{jobId}")
    public AjaxResult task(@PathVariable String jobId)
    {
        return AjaxResult.success(exportService.getTask(jobId));
    }

    @DeleteMapping("/tasks/{jobId}")
    public AjaxResult cancel(@PathVariable String jobId)
    {
        return AjaxResult.success(exportService.cancelTask(jobId));
    }

    @GetMapping("/tasks")
    public AjaxResult myTasks()
    {
        return AjaxResult.success(exportService.listMyTasks());
    }

    /** 压测导出参数 */
    public static class BenchmarkRequest
    {
        /** 商户：M001 / M002，空表示两个都导 */
        private String merchantCode = "M001";

        /** true 明细行（每行约 512 字节，大文件）；false 按天 × 商户汇总 */
        private boolean detailRows = true;

        /** 行数上限，0 或不传表示不限 */
        private Long maxRows = 1000000L;

        public String getMerchantCode() { return merchantCode; }
        public void setMerchantCode(String merchantCode) { this.merchantCode = merchantCode; }
        public boolean isDetailRows() { return detailRows; }
        public void setDetailRows(boolean detailRows) { this.detailRows = detailRows; }
        public Long getMaxRows() { return maxRows; }
        public void setMaxRows(Long maxRows) { this.maxRows = maxRows; }
    }
}
