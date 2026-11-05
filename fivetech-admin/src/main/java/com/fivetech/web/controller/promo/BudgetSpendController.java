package com.fivetech.web.controller.promo;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
 *   <li>GET  /promo/budget/template  下载模板（CSV）：kind=SPEND|BUDGET&amp;from=yyyy-MM-dd&amp;to=yyyy-MM-dd</li>
 *   <li>POST /promo/budget/import    导入：multipart，kind=SPEND|BUDGET，file=.xlsx/.csv → 结果页数据（BudgetImportResult）</li>
 * </ul>
 * 只做校验、不存文件、不落库；写入 UDS 暂未接入（结果里 written=false）。
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
    @Log(title = "推廣分析 · 預算與消耗匯入", businessType = BusinessType.IMPORT, isSaveRequestData = false)
    @PostMapping("/import")
    public AjaxResult importFile(@RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "file", required = false) MultipartFile file)
    {
        return success(service.importFile(kind, file, getUsername()));
    }
}
