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
import com.fivetech.dashboard.domain.query.DepositRecordQuery;
import com.fivetech.dashboard.domain.query.MemberRecordQuery;
import com.fivetech.dashboard.domain.query.WithdrawRecordQuery;
import com.fivetech.dashboard.export.DashboardXlsxExporter;
import com.fivetech.dashboard.service.IRecordQueryService;

/**
 * 明细查询接口：会员 / 存款 / 提款 / 投注四张表。
 * <p>
 * 从指标汇总表下钻时，传 slotFrom / slotTo 即可把条件带过来；
 * 会员表按来源指标写入对应的时间条件（注册 / 首存 / 最近投注），否则行数与指标值对不上。
 * <p>
 * 四个接口都支持 {@code "export_csv": true}（字段名为兼容历史保留，也可传 {@code "export": true}）：
 * 不返回 JSON，直接返回 XLSX 文件，浏览器按 {@code Content-Disposition: attachment} 走原生下载。
 * 导出内容与同参数下的 JSON 查询完全同源。
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/dashboard/records")
public class RecordQueryController extends BaseController
{
    private final IRecordQueryService recordQueryService;

    private final DashboardXlsxExporter xlsxExporter;

    public RecordQueryController(IRecordQueryService recordQueryService,
            DashboardXlsxExporter xlsxExporter)
    {
        this.recordQueryService = recordQueryService;
        this.xlsxExporter = xlsxExporter;
    }

    /**
     * 会员明细；export_csv=true 时下载 XLSX
     */
//    @PreAuthorize("@ss.hasPermi('dashboard:record:member')")
    @PostMapping("/member")
    public ResponseEntity<?> member(@Validated @RequestBody MemberRecordQuery query)
    {
        if (query.isExportCsv())
        {
            return DashboardExportSupport.xlsx(xlsxExporter.fileName("会员明细"), xlsxExporter.exportMembers(query));
        }
        return ResponseEntity.ok(AjaxResult.success(recordQueryService.queryMembers(query)));
    }

    /**
     * 存款明细（时间按创建时间）；export_csv=true 时下载 XLSX
     */
//    @PreAuthorize("@ss.hasPermi('dashboard:record:deposit')")
    @PostMapping("/deposit")
    public ResponseEntity<?> deposit(@Validated @RequestBody DepositRecordQuery query)
    {
        if (query.isExportCsv())
        {
            return DashboardExportSupport.xlsx(xlsxExporter.fileName("存款明细"), xlsxExporter.exportDeposits(query));
        }
        return ResponseEntity.ok(AjaxResult.success(recordQueryService.queryDeposits(query)));
    }

    /**
     * 提款明细（时间按创建时间）；export_csv=true 时下载 XLSX
     */
//    @PreAuthorize("@ss.hasPermi('dashboard:record:withdraw')")
    @PostMapping("/withdraw")
    public ResponseEntity<?> withdraw(@Validated @RequestBody WithdrawRecordQuery query)
    {
        if (query.isExportCsv())
        {
            return DashboardExportSupport.xlsx(xlsxExporter.fileName("提款明细"), xlsxExporter.exportWithdrawals(query));
        }
        return ResponseEntity.ok(AjaxResult.success(recordQueryService.queryWithdrawals(query)));
    }

    /**
     * 投注明细（时间按投注时间）；export_csv=true 时下载 XLSX
     */
//    @PreAuthorize("@ss.hasPermi('dashboard:record:bet')")
    @PostMapping("/bet")
    public ResponseEntity<?> bet(@Validated @RequestBody BetRecordQuery query)
    {
        if (query.isExportCsv())
        {
            return DashboardExportSupport.xlsx(xlsxExporter.fileName("投注明细"), xlsxExporter.exportBets(query));
        }
        return ResponseEntity.ok(AjaxResult.success(recordQueryService.queryBets(query)));
    }
}
