package com.fivetech.web.controller.monitor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fivetech.common.core.controller.BaseController;
import com.fivetech.common.core.domain.AjaxResult;

/** Read-only Doris metrics for the data monitoring page. */
@RestController
@RequestMapping("/monitor/doris")
public class DorisMonitorController extends BaseController
{
    @Autowired
    private DorisMonitorService dorisMonitorService;

    @PreAuthorize("@ss.hasPermi('monitor:doris:query')")
    @GetMapping("/source-currency-summary")
    public AjaxResult sourceCurrencySummary()
    {
        return success(dorisMonitorService.querySourceCurrencySummary());
    }
}
