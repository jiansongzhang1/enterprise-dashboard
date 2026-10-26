package com.fivetech.dashboard.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.fivetech.dashboard.domain.MetricDictItem;

/**
 * 系统管理 · 指标管理：读写 dashboard_metric_card 的口径与取数说明。
 * <p>与 {@link DashboardConfigMapper}（运营总览、指标汇总启动时加载）分开，改这里不影响线上取数。
 * 写操作只碰两类数据：所有指标的口径 / 取数说明列；仅登记（status = '1'）指标的名称、分组、格式等基础列。</p>
 *
 * @author fivetech
 */
public interface MetricDictMapper
{
    /** 全部指标（含仅登记的），按「已接入在前、分组、sort_no」排序 */
    List<MetricDictItem> selectAll();

    MetricDictItem selectByCode(@Param("metricCode") String metricCode);

    /** 编码是否已存在（不区分大小写，避免 reg / Reg 这种只差大小写的编码） */
    int countCodeIgnoreCase(@Param("metricCode") String metricCode);

    /** 分组字典：group_code、group_name */
    List<Map<String, Object>> selectGroups();

    /** 页面引用：metric_code、page_code */
    List<Map<String, Object>> selectPageRefs();

    /** 未删除的告警规则数（全部站点）：metric_code、cnt */
    List<Map<String, Object>> selectRuleCounts();

    /** 异常事件数（含已删除规则的历史事件） */
    int countEvents(@Param("metricCode") String metricCode);

    /** 登记新指标：status = '1'（运营总览、指标汇总不加载）、不上卡、不可监控 */
    int insertRegistered(@Param("item") MetricDictItem item);

    /** 改口径与取数说明（所有指标） */
    int updateDictionary(@Param("item") MetricDictItem item);

    /** 改名称、分组、格式等基础列（只对仅登记的指标生效） */
    int updateRegisteredBasic(@Param("item") MetricDictItem item);

    /** 删除仅登记的指标 */
    int deleteRegistered(@Param("metricCode") String metricCode);
}
