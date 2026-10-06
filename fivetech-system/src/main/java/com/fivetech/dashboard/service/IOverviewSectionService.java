package com.fivetech.dashboard.service;

import com.fivetech.dashboard.domain.query.CohortQuery;
import com.fivetech.dashboard.domain.query.RankingBoardQuery;
import com.fivetech.dashboard.domain.query.RegChannelQuery;
import com.fivetech.dashboard.domain.vo.overview.CohortVO;
import com.fivetech.dashboard.domain.vo.overview.RankingBoardVO;
import com.fivetech.dashboard.domain.vo.overview.RegChannelVO;

/**
 * 运营总览的其余板块：排行榜、留存与 LTV、注册渠道。用户与价值一期不做。
 * <p>
 * 与主要指标（{@link IOverviewService}）分开：这几块都是区间聚合或时点快照，
 * 没有时间序列和环比，取数走 {@code MetricDataGateway.queryBreakdown}。
 *
 * @author fivetech
 */
public interface IOverviewSectionService
{
    /** 排行榜：赠金项目构成 + 热销游戏 Top N */
    RankingBoardVO rankingBoard(RankingBoardQuery query);

    /** 留存与 LTV：两张 T-1 队列矩阵 */
    CohortVO cohort(CohortQuery query);

    /** 注册渠道：分组 + 全部渠道 */
    RegChannelVO regChannels(RegChannelQuery query);
}
