package com.fivetech.dashboard.service;

import com.fivetech.dashboard.domain.query.MetricSummaryQuery;
import com.fivetech.dashboard.domain.vo.MetricSummaryVO;

/**
 * 指标汇总表查询。
 *
 * @author fivetech
 */
public interface IMetricSummaryService
{
    /**
     * 查询指标汇总表：一行一个时间片，一列一个指标，附区间合计
     */
    MetricSummaryVO query(MetricSummaryQuery query);
}
