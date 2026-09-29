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
import com.fivetech.dashboard.domain.query.BetRecordQuery;
import com.fivetech.dashboard.domain.query.MemberRecordQuery;
import com.fivetech.dashboard.domain.query.TransactionRecordQuery;
import com.fivetech.dashboard.export.DashboardCsvExporter;
import com.fivetech.dashboard.service.IRecordQueryService;

/**
 * 明细查询接口：会员 / 交易 / 投注三张表。
 * <p>
 * 从指标汇总表下钻时，传 slotFrom / slotTo 即可把条件带过来；
 * 会员表还需按来源指标传 timeField（注册 / 首存 / 活跃），否则行数与指标值对不上。
 * <p>
 * 三个接口都支持 {@code "export_csv": true}：不返回 JSON，直接返回 CSV 文件流，
 * 浏览器按 {@code Content-Disposition: attachment} 走原生下载。
 * 导出内容与同参数下的 JSON 查询完全同源。
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/dashboard/records")
public class RecordQueryController extends BaseController
{
    private final IRecordQueryService recordQueryService;

    private final DashboardCsvExporter csvExporter;

    public RecordQueryController(IRecordQueryService recordQueryService,
            DashboardCsvExporter csvExporter)
    {
        this.recordQueryService = recordQueryService;
        this.csvExporter = csvExporter;
    }

    /**
     * 会员明细；export_csv=true 时下载 CSV
     */
    @PreAuthorize("@ss.hasPermi('dashboard:record:member')")
    @PostMapping("/member")
    public ResponseEntity<?> member(@Validated @RequestBody MemberRecordQuery query)
    {
        if (query.isExportCsv())
        {
            return DashboardExportSupport.csv(csvExporter.fileName("会员明细"),
                out -> csvExporter.exportMembers(query, out));
        }
        return ResponseEntity.ok(AjaxResult.success(recordQueryService.queryMembers(query)));
    }

    /**
     * 交易明细（存款与提款合并，由 type 区分方向）；export_csv=true 时下载 CSV
     */
    @PreAuthorize("@ss.hasPermi('dashboard:record:transaction')")
    @PostMapping("/transaction")
    public ResponseEntity<?> transaction(@Validated @RequestBody TransactionRecordQuery query)
    {
        if (query.isExportCsv())
        {
            return DashboardExportSupport.csv(csvExporter.fileName("交易明细"),
                out -> csvExporter.exportTransactions(query, out));
        }
        return ResponseEntity.ok(AjaxResult.success(recordQueryService.queryTransactions(query)));
    }

    /**
     * 投注明细；export_csv=true 时下载 CSV
     */
    @PreAuthorize("@ss.hasPermi('dashboard:record:bet')")
    @PostMapping("/bet")
    public ResponseEntity<?> bet(@Validated @RequestBody BetRecordQuery query)
    {
        if (query.isExportCsv())
        {
            return DashboardExportSupport.csv(csvExporter.fileName("投注明细"),
                out -> csvExporter.exportBets(query, out));
        }
        return ResponseEntity.ok(AjaxResult.success(recordQueryService.queryBets(query)));
    }
}
