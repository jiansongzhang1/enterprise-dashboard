package com.fivetech.dashboard.service;

import com.fivetech.dashboard.domain.query.OverviewQuery;
import com.fivetech.dashboard.domain.vo.OverviewVO;

/**
 * 运营总览 · 主要指标。
 *
 * @author fivetech
 */
public interface IOverviewService
{
    /**
     * 查询指标墙。
     *
     * @param query 已由 Controller 做过基础校验的入参
     * @return 按 blocks 组装的返回体；某块失败时该块缺席、原因进 blockErrors
     */
    OverviewVO query(OverviewQuery query);
}
