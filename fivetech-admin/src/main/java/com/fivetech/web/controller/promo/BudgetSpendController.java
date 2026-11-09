package com.fivetech.web.controller.promo;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.fivetech.common.annotation.Log;
import com.fivetech.common.core.controller.BaseController;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.common.enums.BusinessType;
import com.fivetech.promo.budget.BudgetSpendService;

/**
 * 推广分析 · 预算与消耗（FY-56）。站点用请求头 X-Site-Code 指定（不传 = 默认站点）。
 *
 * <ul>
 *   <li>GET  /promo/budget/meta      页面配置：站点、币种、今天、两个页签的可选 / 默认日期、上限、渠道字典、writeEnabled</li>
 *   <li>GET  /promo/budget/data      表格数据：kind、from、to → 已发布的行与每天的发布状态（读 UDS）</li>
 *   <li>GET  /promo/budget/template  下载模板（CSV）：kind=SPEND|BUDGET&amp;from=yyyy-MM-dd&amp;to=yyyy-MM-dd（预算预填已有预算）</li>
 *   <li>POST /promo/budget/import    导入：multipart，kind=SPEND|BUDGET，file=.xlsx/.csv → 结果页数据（BudgetImportResult，含 write）</li>
 *   <li>POST /promo/budget/import/resume  {token, confirm}：确认覆盖 / 取消，或「處理中」时重新查询结果 → BudgetWriteOutcome</li>
 * </ul>
 * 不存上传的文件、不落库；有效行同步提交到数据平台（采集平台统一提交接口，整日替换）。
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/promo/budget")
public class BudgetSpendController extends BaseController
{
    private final BudgetSpendService service;

    public BudgetSpendController(BudgetSpendService service)
    {
        this.service = service;
    }

    @PreAuthorize("@ss.hasPermi('promo:budget:view')")
    @GetMapping("/meta")
    public AjaxResult meta()
    {
        return success(service.meta());
    }

    @PreAuthorize("@ss.hasPermi('promo:budget:view')")
    @GetMapping("/data")
    public AjaxResult data(@RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "from", required = false) String from, @RequestParam(value = "to", required = false) String to)
    {
        return success(service.data(kind, from, to));
    }

    @PreAuthorize("@ss.hasPermi('promo:budget:view')")
    @GetMapping("/template")
    public ResponseEntity<byte[]> template(@RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "from", required = false) String from, @RequestParam(value = "to", required = false) String to)
    {
        BudgetSpendService.Template t = service.template(kind, from, to);
        String encoded = URLEncoder.encode(t.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
            .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION)
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .body(t.getContent());
    }

    @PreAuthorize("@ss.hasPermi('promo:budget:import')")
    @Log(title = "推廣分析 · 預算與消耗確認覆蓋", businessType = BusinessType.IMPORT)
    @PostMapping("/import/resume")
    public AjaxResult resume(@RequestBody Map<String, Object> body)
    {
        Object token = body == null ? null : body.get("token");
        Object confirm = body == null ? null : body.get("confirm");
        return success(service.resume(token == null ? null : String.valueOf(token),
            confirm instanceof Boolean ? (Boolean) confirm : Boolean.valueOf(String.valueOf(confirm)), getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('promo:budget:import')")
    @Log(title = "推廣分析 · 預算與消耗匯入", businessType = BusinessType.IMPORT, isSaveRequestData = false)
    @PostMapping("/import")
    public AjaxResult importFile(@RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "file", required = false) MultipartFile file)
    {
        return success(service.importFile(kind, file, getUsername()));
    }
}
