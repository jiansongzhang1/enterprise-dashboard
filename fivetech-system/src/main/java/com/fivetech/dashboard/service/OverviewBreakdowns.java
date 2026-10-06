package com.fivetech.dashboard.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运营总览各板块用到的拆解查询名、字段编码与展示字典。
 * <p>
 * 拆解查询名对应 {@code dashboard.gateway.uds.breakdowns.<key>}；字段编码是 breakdowns 下
 * dimensions / metrics 的 key。代码只认这里的编码，UDS 侧字段叫什么由配置决定。
 * <p>
 * 注册渠道分组直接用上游 Reg_channel 的取值（official / agent / market），不在这里预置。
 *
 * @author fivetech
 */
public final class OverviewBreakdowns
{
    // ===================== 拆解查询名 =====================

    /** 排行榜 · 赠金项目构成。维度 code / name，指标 amount。TODO：上游需提供 bonus_type 维度 */
    public static final String BONUS_ITEMS = "bonus_items";

    /** 排行榜 · 热销游戏。维度 gameId / name / platformCode / vendorName / gameType，指标 betAmount / ggr / betUsers / betCount */
    public static final String HOT_GAMES = "hot_games";

    /** 留存（按首投日分群）。维度 cohortDate，指标 base + d1…d30 */
    public static final String COHORT_RETENTION = "cohort_retention";

    /** LTV（按首存日分群）。维度 cohortDate，指标 base + d1…d30 */
    public static final String COHORT_LTV = "cohort_ltv";

    /** 注册渠道。维度 groupCode / groupName / channelCode / channelName，指标 registrations */
    public static final String REG_CHANNELS = "reg_channels";

    // ===================== 队列列 =====================

    /** LTV 矩阵 10 列：列名 → 指标编码。「Pre D+0」= 分群日当天（Pre_D0_LTV）。上游是预聚合固定列，不是我们自己 pivot */
    public static final Map<String, String> LTV_COLUMNS = new LinkedHashMap<>();

    /** 留存矩阵 9 列：D+1…D+30（留存没有 D+0） */
    public static final Map<String, String> RETENTION_COLUMNS = new LinkedHashMap<>();

    /** 每列对应的观察天数，用来判断「未到观察期」 */
    public static final Map<String, Integer> COHORT_DAYS = new LinkedHashMap<>();

    static
    {
        String[][] cols = {{"D+1", "d1", "1"}, {"D+2", "d2", "2"}, {"D+3", "d3", "3"}, {"D+4", "d4", "4"},
            {"D+5", "d5", "5"}, {"D+6", "d6", "6"}, {"D+7", "d7", "7"}, {"D+15", "d15", "15"}, {"D+30", "d30", "30"}};
        LTV_COLUMNS.put("Pre D+0", "d0");
        COHORT_DAYS.put("Pre D+0", 0);
        for (String[] c : cols)
        {
            LTV_COLUMNS.put(c[0], c[1]);
            RETENTION_COLUMNS.put(c[0], c[1]);
            COHORT_DAYS.put(c[0], Integer.valueOf(c[2]));
        }
    }

    private OverviewBreakdowns()
    {
    }
}
