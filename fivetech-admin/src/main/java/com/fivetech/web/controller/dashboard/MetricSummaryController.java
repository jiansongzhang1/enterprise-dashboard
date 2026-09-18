package com.fivetech.web.controller.dashboard;

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
import com.fivetech.dashboard.service.IMetricSummaryService;

/**
 * 指标汇总表接口。
 * <p>
 * 一行是一个时间片，一列是一个指标；返回体附带区间合计与查询上下文
 * （asOf / updatedAt / 口径版本 / 延迟标记），前端一律以上下文为准，
 * 不得自行取当前时间。
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/dashboard/metrics")
public class MetricSummaryController extends BaseController
{
    private final IMetricSummaryService metricSummaryService;

    public MetricSummaryController(IMetricSummaryService metricSummaryService)
    {
        this.metricSummaryService = metricSummaryService;
    }

    /**
     * 查询指标汇总表
     */
    @PreAuthorize("@ss.hasPermi('dashboard:metric:summary')")
    @PostMapping("/summary")
    public AjaxResult summary(@Validated @RequestBody MetricSummaryQuery query)
    {
        MetricSummaryVO data = metricSummaryService.query(query);
        return AjaxResult.success(data);
    }
}
