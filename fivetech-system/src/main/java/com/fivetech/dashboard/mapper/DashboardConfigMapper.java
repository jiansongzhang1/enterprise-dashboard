package com.fivetech.dashboard.mapper;

import java.util.List;
import com.fivetech.dashboard.domain.MetricCardConfig;
import com.fivetech.dashboard.domain.MetricGroupConfig;
import com.fivetech.dashboard.domain.PageMetricConfig;

/**
 * 看板配置数据层。
 * <p>
 * 只读：配置由运营在管理后台维护，这里不提供写方法，
 * 免得有人从取数链路上把配置改了。
 *
 * @author fivetech
 */
public interface DashboardConfigMapper
{
    /**
     * 查询全部指标卡配置，<b>包含已停用的</b>。
     * <p>
     * 故意不在 SQL 里过滤停用：校验器需要看见「页面挂着一个已停用的指标」
     * 这种情况才能报出来。过滤掉的话只会表现为页面静默少一列。
     */
    List<MetricCardConfig> selectAllMetricCards();

    /**
     * 查询全部页面-指标关系，<b>包含已停用的</b>。理由同上。
     */
    List<PageMetricConfig> selectAllPageMetrics();

    /**
     * 查询启用的分组，按 sort_no 排序。
     * <p>既用于校验指标卡引用的 group_code 是否存在，也是分组名与分组按钮顺序的来源。</p>
     */
    List<MetricGroupConfig> selectEnabledGroups();
}
