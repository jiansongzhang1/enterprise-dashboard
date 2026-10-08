package com.fivetech.dashboard.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.domain.MetricCardConfig;
import com.fivetech.dashboard.domain.MetricDefinition;
import com.fivetech.dashboard.domain.MetricGroupConfig;
import com.fivetech.dashboard.domain.PageMetricConfig;
import com.fivetech.dashboard.enums.DashboardPage;
import com.fivetech.dashboard.mapper.DashboardConfigMapper;

/**
 * 指标注册表：接口入参的<b>白名单</b>，以及页面与指标的对应关系。
 *
 * <p>数据来自 {@code dashboard_metric_card} + {@code dashboard_page_metric}，
 * 启动时加载一次，先过 {@link DashboardConfigValidator}，不通过就让应用起不来。
 * 前端只能传 metric_code，服务端据此决定向 UDS 要哪个指标——
 * 表名列名永远不进入外部输入。</p>
 *
 * <p>运行期读的是一个<b>不可变快照</b>。{@link #reload()} 整体换掉引用，
 * 不做增量改动：一半新一半旧的注册表比旧的更糟。</p>
 *
 * @author fivetech
 */
@Component
public class MetricRegistry
{
    private static final Logger log = LoggerFactory.getLogger(MetricRegistry.class);

    private static final String ATOM = "ATOM";

    private static final String DERIVED = "DERIVED";

    private final DashboardConfigMapper configMapper;

    private final DashboardConfigValidator validator;

    private volatile Snapshot snapshot = Snapshot.empty();

    /** dashboard_metric_group 的启用行，按 sort_no 排序；与快照一起在 reload 里替换 */
    private volatile List<MetricGroupConfig> groups = List.of();

    public MetricRegistry(DashboardConfigMapper configMapper, DashboardConfigValidator validator)
    {
        this.configMapper = configMapper;
        this.validator = validator;
    }

    @PostConstruct
    public void init()
    {
        reload();
    }

    /**
     * 重新加载配置。校验不通过会抛异常，<b>并且保留原有快照不变</b>——
     * 运行中热加载失败时，继续用旧配置提供服务，好过换成一份坏的。
     */
    public synchronized void reload()
    {
        List<MetricCardConfig> cards;
        List<PageMetricConfig> pageMetrics;
        List<MetricGroupConfig> groupRows;
        try
        {
            cards = configMapper.selectAllMetricCards();
            pageMetrics = configMapper.selectAllPageMetrics();
            groupRows = configMapper.selectEnabledGroups();
        }
        catch (Exception e)
        {
            // 表不存在时 JDBC 报的是「relation does not exist」，堆栈里看不出该做什么。
            // 把动作说清楚，比让人对着 SQLState 猜要快
            throw new IllegalStateException("读取看板配置表失败，请确认已执行 "
                + "sql/dashboard_metric_card_postgresql.sql 与 sql/dashboard_page_metric_postgresql.sql："
                + e.getMessage(), e);
        }

        List<String> groups = new ArrayList<>();
        for (MetricGroupConfig g : groupRows)
        {
            groups.add(g.getGroupCode());
        }
        validator.validateOrFail(cards, pageMetrics, groups);

        this.snapshot = Snapshot.build(cards, pageMetrics);
        this.groups = Collections.unmodifiableList(new ArrayList<>(groupRows));
        log.info("[dashboard-config] 注册表已加载：可选指标 {} 个，{} 页面 {} 个，{} 页面 {} 个",
            snapshot.selectable.size(),
            DashboardPage.OVERVIEW, snapshot.columnsOf(DashboardPage.OVERVIEW).size(),
            DashboardPage.SUMMARY, snapshot.columnsOf(DashboardPage.SUMMARY).size());
    }

    // ===================== 查询 =====================

    /** 启用的分组，按 sort_no 排序 */
    public List<MetricGroupConfig> groups()
    {
        return groups;
    }

    /** 分组配置；编码为空或不在字典里时返回 null */
    public MetricGroupConfig groupOf(String code)
    {
        if (code == null)
        {
            return null;
        }
        for (MetricGroupConfig g : groups)
        {
            if (code.equals(g.getGroupCode()))
            {
                return g;
            }
        }
        return null;
    }

    public MetricDefinition get(String code)
    {
        return code == null ? null : snapshot.definitions.get(code);
    }

    public boolean contains(String code)
    {
        return code != null && snapshot.definitions.containsKey(code);
    }

    /** 全部可选指标，按配置顺序 */
    public List<String> selectableCodes()
    {
        return new ArrayList<>(snapshot.selectable);
    }

    /** 某页面上的全部指标，按 sort_no */
    public List<String> columnsOf(DashboardPage page)
    {
        return new ArrayList<>(snapshot.columnsOf(page));
    }

    /** 某页面的默认选中列 */
    public List<String> defaultsOf(DashboardPage page)
    {
        return new ArrayList<>(snapshot.defaultsOf(page));
    }

    /**
     * 指标在某页面上的序号，取 {@code dashboard_page_metric.sort_no}。
     * <p>页面顺序只认这一列；{@code dashboard_metric_card.sort_no} 是指标的全局顺序，
     * 不决定任何页面的排列。指标没挂在该页面上时返回 null。</p>
     */
    public Integer pageSortNo(DashboardPage page, String code)
    {
        return snapshot.pageSortNo(page, code);
    }

    /** 某页面上的大号卡（仅指标墙有意义） */
    public List<String> coreOf(DashboardPage page)
    {
        return new ArrayList<>(snapshot.coreOf(page));
    }

    /**
     * 核心指标编码。
     * <p>核心与否只在一处定义：dashboard_page_metric 中运营总览（OVERVIEW）emphasis = 'CORE' 的指标。
     * 表约束要求 SUMMARY 行的 emphasis 恒为 NORMAL，汇总表不另立一套，直接复用指标墙的定义，
     * 避免两处配置不一致（指标墙标了核心、汇总表却没标）。</p>
     */
    public java.util.Set<String> coreMetricCodes()
    {
        return new java.util.LinkedHashSet<>(snapshot.coreOf(DashboardPage.OVERVIEW));
    }

    /** 指标汇总的默认列。保留这个名字是为了不改调用方 */
    public List<String> coreCodes()
    {
        return defaultsOf(DashboardPage.SUMMARY);
    }

    /** 指标墙上的指标 */
    public List<String> wallCodes()
    {
        return columnsOf(DashboardPage.OVERVIEW);
    }

    /**
     * 过滤出合法的指标编码，按<b>页面配置的顺序</b>排列。
     * <p>
     * 未知编码直接丢弃而不是报错：前端版本可能比后端旧，少一列比整页 500 要好。
     * 隐藏原子量即使被显式请求也会被丢弃——它们不是「列」。
     *
     * @param requested 请求的编码；为空时返回该页面的全部指标
     */
    public List<String> resolveColumns(DashboardPage page, List<String> requested)
    {
        List<String> ordered = snapshot.columnsOf(page);
        // 不传 metricCodes（或传的全不在白名单内）时返回该页全部指标（指标汇总 = 原型的 21 个），
        // 不再只返回 is_default 的 8 个核心列——「默认勾选哪些列」由前端按 defaultsOf 处理
        if (requested == null || requested.isEmpty())
        {
            return new ArrayList<>(ordered);
        }
        List<String> result = new ArrayList<>();
        for (String code : ordered)
        {
            if (requested.contains(code))
            {
                result.add(code);
            }
        }
        return result.isEmpty() ? new ArrayList<>(ordered) : result;
    }

    /**
     * 只取核心指标的列：页面列顺序不变，过滤出核心指标。
     * <p>传了 requested 时取交集；交集为空（请求的全是非核心指标）则返回该页全部核心指标，
     * 与 {@link #resolveColumns(DashboardPage, List)} 的回退方式一致。</p>
     */
    public List<String> resolveCoreColumns(DashboardPage page, List<String> requested)
    {
        java.util.Set<String> core = coreMetricCodes();
        List<String> all = new ArrayList<>();
        for (String code : snapshot.columnsOf(page))
        {
            if (core.contains(code))
            {
                all.add(code);
            }
        }
        if (requested == null || requested.isEmpty())
        {
            return all;
        }
        List<String> picked = new ArrayList<>();
        for (String code : all)
        {
            if (requested.contains(code))
            {
                picked.add(code);
            }
        }
        return picked.isEmpty() ? all : picked;
    }

    /** 兼容旧调用：默认按指标汇总页解析 */
    public List<String> resolveColumns(List<String> requested)
    {
        return resolveColumns(DashboardPage.SUMMARY, requested);
    }

    /**
     * 校验排序列：必须是 time 或白名单内的指标编码
     */
    public String resolveSortColumn(String sortColumn, List<String> columns)
    {
        if (StringUtils.isEmpty(sortColumn) || "time".equalsIgnoreCase(sortColumn))
        {
            return "time";
        }
        return columns.contains(sortColumn) ? sortColumn : "time";
    }

    // ===================== 快照 =====================

    private static final class Snapshot
    {
        private final Map<String, MetricDefinition> definitions;

        private final List<String> selectable;

        private final Map<String, List<String>> columns;

        private final Map<String, List<String>> defaults;

        private final Map<String, List<String>> core;

        /** page → (metricCode → dashboard_page_metric.sort_no) */
        private final Map<String, Map<String, Integer>> pageSort;

        private Snapshot(Map<String, MetricDefinition> definitions, List<String> selectable,
                Map<String, List<String>> columns, Map<String, List<String>> defaults,
                Map<String, List<String>> core, Map<String, Map<String, Integer>> pageSort)
        {
            this.pageSort = pageSort;
            this.definitions = definitions;
            this.selectable = selectable;
            this.columns = columns;
            this.defaults = defaults;
            this.core = core;
        }

        private static Snapshot empty()
        {
            return new Snapshot(Map.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }

        private List<String> columnsOf(DashboardPage page)
        {
            return columns.getOrDefault(page.name(), List.of());
        }

        private List<String> defaultsOf(DashboardPage page)
        {
            return defaults.getOrDefault(page.name(), List.of());
        }

        private List<String> coreOf(DashboardPage page)
        {
            return core.getOrDefault(page.name(), List.of());
        }

        private Integer pageSortNo(DashboardPage page, String code)
        {
            return pageSort.getOrDefault(page.name(), Map.of()).get(code);
        }

        private static Snapshot build(List<MetricCardConfig> cards, List<PageMetricConfig> pageMetrics)
        {
            Map<String, MetricDefinition> definitions = new LinkedHashMap<>();
            List<String> selectable = new ArrayList<>();


            for (MetricCardConfig card : cards)
            {
                if (!card.isEnabled())
                {
                    continue;   // 停用的指标不进注册表；校验器已确认没有页面还挂着它
                }
                MetricDefinition definition = new MetricDefinition(
                    card.getMetricCode(), card.getMetricName(), card.getGroupCode(),
                    card.isDerived() ? DERIVED : ATOM,
                    card.getValueFormat(), card.getAggType(), expressionOf(card));
                definition.setDecimals(card.getValueDecimals());
                definition.setUpdateFrequency(card.getUpdateFrequency());
                definition.setSingleDayOnly(card.isSingleDayOnly());
                definition.setDirection(card.getDirection());
                definition.setSortNo(card.getSortNo());
                definition.setCalcType(card.getCalcType());
                definition.setLeftCode(card.getLeftCode());
                definition.setRightCode(card.getRightCode());
                definitions.put(card.getMetricCode(), definition);
                if (card.isShowAsCard())
                {
                    selectable.add(card.getMetricCode());
                }
            }

            Map<String, List<String>> columns = new LinkedHashMap<>();
            Map<String, List<String>> defaults = new LinkedHashMap<>();
            Map<String, List<String>> core = new LinkedHashMap<>();
            Map<String, Map<String, Integer>> pageSort = new LinkedHashMap<>();
            // pageMetrics 已按 page_code, sort_no 排序，顺序直接沿用
            for (PageMetricConfig relation : pageMetrics)
            {
                if (!relation.isEnabled() || !definitions.containsKey(relation.getMetricCode()))
                {
                    continue;
                }
                String page = relation.getPageCode();
                columns.computeIfAbsent(page, k -> new ArrayList<>()).add(relation.getMetricCode());
                pageSort.computeIfAbsent(page, k -> new LinkedHashMap<>())
                    .put(relation.getMetricCode(), relation.getSortNo());
                if (relation.getIsDefault())
                {
                    defaults.computeIfAbsent(page, k -> new ArrayList<>()).add(relation.getMetricCode());
                }
                if (relation.isCore())
                {
                    core.computeIfAbsent(page, k -> new ArrayList<>()).add(relation.getMetricCode());
                }
            }

            return new Snapshot(Collections.unmodifiableMap(definitions),
                Collections.unmodifiableList(selectable),
                unmodifiable(columns), unmodifiable(defaults), unmodifiable(core),
                unmodifiableNested(pageSort));
        }

        private static Map<String, Map<String, Integer>> unmodifiableNested(Map<String, Map<String, Integer>> source)
        {
            Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
            source.forEach((k, v) -> result.put(k, Collections.unmodifiableMap(v)));
            return Collections.unmodifiableMap(result);
        }

        private static Map<String, List<String>> unmodifiable(Map<String, List<String>> source)
        {
            Map<String, List<String>> result = new LinkedHashMap<>();
            source.forEach((k, v) -> result.put(k, Collections.unmodifiableList(v)));
            return Collections.unmodifiableMap(result);
        }

        /** 供展示与口径追溯用的公式文本，求值仍在数据侧完成 */
        private static String expressionOf(MetricCardConfig card)
        {
            if (!card.isDerived())
            {
                return null;
            }
            String operator = "DIVIDE".equalsIgnoreCase(card.getCalcType()) ? " ÷ " : " − ";
            return card.getLeftCode() + operator + card.getRightCode();
        }
    }
}
