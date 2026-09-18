package com.fivetech.dashboard.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.domain.MetricDefinition;
import com.fivetech.dashboard.domain.ResolvedRange;
import com.fivetech.dashboard.domain.query.MetricSummaryQuery;
import com.fivetech.dashboard.domain.vo.ColumnMetaVO;
import com.fivetech.dashboard.domain.vo.MetricSummaryRowVO;
import com.fivetech.dashboard.domain.vo.MetricSummaryVO;
import com.fivetech.dashboard.domain.vo.PageResultVO;
import com.fivetech.dashboard.domain.vo.QueryContext;
import com.fivetech.dashboard.enums.Granularity;
import com.fivetech.dashboard.gateway.DataFreshness;
import com.fivetech.dashboard.gateway.MetricDataGateway;
import com.fivetech.dashboard.gateway.MetricSlotRequest;
import com.fivetech.dashboard.gateway.ScopeFilter;
import com.fivetech.dashboard.service.DashboardScopeResolver;
import com.fivetech.dashboard.service.IMetricSummaryService;
import com.fivetech.dashboard.service.MetricRegistry;
import com.fivetech.dashboard.service.TimeRangeResolver;

/**
 * 指标汇总表实现。
 * <p>
 * 关键约定：<b>区间合计不是分页行的加总</b>。派生指标要先聚合分子分母再套公式，
 * 去重人数跨时间片不可相加，平均耗时要按笔数加权——这些只能由数据侧重算，
 * 所以合计走独立的 {@code queryTotals}。
 *
 * @author fivetech
 */
@Service
public class MetricSummaryServiceImpl implements IMetricSummaryService
{
    private static final DateTimeFormatter SLOT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM-dd");

    private final DashboardProperties properties;

    private final TimeRangeResolver timeResolver;

    private final MetricRegistry metricRegistry;

    private final DashboardScopeResolver scopeResolver;

    private final MetricDataGateway gateway;

    public MetricSummaryServiceImpl(DashboardProperties properties, TimeRangeResolver timeResolver,
            MetricRegistry metricRegistry, DashboardScopeResolver scopeResolver, MetricDataGateway gateway)
    {
        this.properties = properties;
        this.timeResolver = timeResolver;
        this.metricRegistry = metricRegistry;
        this.scopeResolver = scopeResolver;
        this.gateway = gateway;
    }

    @Override
    public MetricSummaryVO query(MetricSummaryQuery query)
    {
        String siteCode = StringUtils.isEmpty(query.getSiteCode())
            ? properties.getDefaultSite() : query.getSiteCode();

        // 1. 权限：先拿到范围，再决定能查哪些指标
        ScopeFilter scope = scopeResolver.resolve();
        List<String> columns = metricRegistry.resolveColumns(
            query.getMetricCodes(), scopeResolver.allowedMetricCodes(scope));

        // 2. 时间：解析区间、粒度、对比期，全部在服务端完成
        DataFreshness freshness = timeResolver.resolveFreshness(siteCode);
        List<String> warnings = new ArrayList<>();
        ResolvedRange main = timeResolver.resolveMain(query, freshness.getAsOf(), false);
        List<Granularity> available = timeResolver.availableGranularities(main);
        Granularity granularity = timeResolver.resolveGranularity(query.getGranularity(), available);
        if (query.getGranularity() != null && query.getGranularity() != granularity)
        {
            // 请求的粒度点数过多被降级，如实告知而不是默默返回别的东西
            warnings.add("GRANULARITY_DOWNGRADED");
        }
        main.setGranularity(granularity);
        ResolvedRange compare = timeResolver.resolveCompare(
            query.getCompareType(), query.getCompareFrom(), query.getCompareTo(), main, warnings);

        // 3. 时间片：标签与起止一次算好，下钻时直接写进明细表的筛选
        List<MetricSummaryRowVO> rows = buildSlots(main, granularity, freshness);
        main.setPoints(rows.size());

        // 4. 取数
        Map<String, List<BigDecimal>> series = gateway.querySeries(slotRequest(siteCode, main, columns, scope));
        fill(rows, series, false);
        if (compare != null)
        {
            compare.setGranularity(granularity);
            Map<String, List<BigDecimal>> compareSeries =
                gateway.querySeries(slotRequest(siteCode, compare, columns, scope));
            fill(rows, compareSeries, true);
        }

        // 5. 排序与分页
        sort(rows, metricRegistry.resolveSortColumn(query.getSortColumn(), columns), query.getSortDirection());
        PageResultVO<MetricSummaryRowVO> page = paginate(rows, query.getPageNum(), query.getPageSize());

        MetricSummaryVO vo = new MetricSummaryVO();
        QueryContext context = timeResolver.buildContext(siteCode, main, compare, freshness, warnings);
        context.setAvailableGranularities(available);
        vo.setContext(context);
        vo.setColumns(buildColumns(columns));
        vo.setPage(page);
        // 6. 合计：独立重算，不是把上面的行加起来
        vo.setTotalRow(gateway.queryTotals(slotRequest(siteCode, main, columns, scope)));
        if (compare != null)
        {
            vo.setCompareTotalRow(gateway.queryTotals(slotRequest(siteCode, compare, columns, scope)));
        }
        return vo;
    }

    private MetricSlotRequest slotRequest(String siteCode, ResolvedRange range,
            List<String> columns, ScopeFilter scope)
    {
        MetricSlotRequest request = new MetricSlotRequest();
        request.setSiteCode(siteCode);
        request.setFrom(range.getFrom());
        request.setTo(range.getTo());
        request.setGranularity(range.getGranularity());
        request.setMetricCodes(columns);
        request.setScopeFilter(scope);
        return request;
    }

    /**
     * 生成时间片。
     * <p>
     * 小时粒度跨天时标签带上日期，否则「09:00 – 10:00」出现两次分不清哪天；
     * 周粒度的最后一周多半不满 7 天，末端要夹到区间结束，不能印出超出区间的日期。
     */
    private List<MetricSummaryRowVO> buildSlots(ResolvedRange range, Granularity granularity,
            DataFreshness freshness)
    {
        List<MetricSummaryRowVO> rows = new ArrayList<>();
        LocalDateTime cursor = range.getFrom();
        LocalDateTime end = range.getTo();
        boolean crossDay = cursor.toLocalDate().isBefore(end.minusMinutes(1).toLocalDate());
        LocalDateTime delayFrom = freshness == null || freshness.getAsOf() == null
            ? null : freshness.getAsOf().minusHours(properties.getDelayWindowHours());

        while (cursor.isBefore(end))
        {
            LocalDateTime next;
            String label;
            switch (granularity)
            {
                case HOUR:
                    next = cursor.plusHours(1);
                    if (next.isAfter(end))
                    {
                        next = end;
                    }
                    label = (crossDay ? cursor.format(MD) + " " : "")
                        + cursor.format(HHMM) + " – " + next.format(HHMM);
                    break;
                case WEEK:
                    next = cursor.plusDays(7);
                    if (next.isAfter(end))
                    {
                        next = end;
                    }
                    label = cursor.format(MD) + " – " + next.minusMinutes(1).format(MD);
                    break;
                case DAY:
                default:
                    next = cursor.plusDays(1);
                    if (next.isAfter(end))
                    {
                        next = end;
                    }
                    label = cursor.toLocalDate().toString();
                    break;
            }
            MetricSummaryRowVO row = new MetricSummaryRowVO();
            row.setLabel(label);
            row.setSlotFrom(cursor.format(SLOT));
            row.setSlotTo(next.format(SLOT));
            row.setDelayed(delayFrom != null && next.isAfter(delayFrom));
            rows.add(row);
            cursor = next;
        }
        return rows;
    }

    /**
     * 把序列填进行。序列长度与时间片数不一致时按短的来，缺的位置保持 null，
     * 绝不补 0——无数据和 0 在业务上不是一回事。
     */
    private void fill(List<MetricSummaryRowVO> rows, Map<String, List<BigDecimal>> series, boolean compare)
    {
        if (series == null)
        {
            return;
        }
        series.forEach((code, values) -> {
            if (values == null)
            {
                return;
            }
            int size = Math.min(rows.size(), values.size());
            for (int i = 0; i < size; i++)
            {
                Map<String, BigDecimal> target = compare
                    ? rows.get(i).getCompareValues() : rows.get(i).getValues();
                target.put(code, values.get(i));
            }
        });
    }

    private void sort(List<MetricSummaryRowVO> rows, String sortColumn, String direction)
    {
        boolean asc = "asc".equalsIgnoreCase(direction);
        if ("time".equals(sortColumn))
        {
            // 默认最新在上
            if (!asc)
            {
                java.util.Collections.reverse(rows);
            }
            return;
        }
        Comparator<MetricSummaryRowVO> comparator = Comparator.comparing(
            row -> row.getValues().get(sortColumn),
            Comparator.nullsLast(Comparator.naturalOrder()));
        rows.sort(asc ? comparator : comparator.reversed());
    }

    private PageResultVO<MetricSummaryRowVO> paginate(List<MetricSummaryRowVO> rows, Integer pageNum, Integer pageSize)
    {
        int num = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
        int fromIndex = Math.min((num - 1) * size, rows.size());
        int toIndex = Math.min(fromIndex + size, rows.size());
        return PageResultVO.of(new ArrayList<>(rows.subList(fromIndex, toIndex)), rows.size(), num, size);
    }

    private List<ColumnMetaVO> buildColumns(List<String> codes)
    {
        List<ColumnMetaVO> columns = new ArrayList<>();
        columns.add(ColumnMetaVO.of("time", "时间", "TEXT").sortable(true));
        for (String code : codes)
        {
            MetricDefinition definition = metricRegistry.get(code);
            if (definition == null)
            {
                continue;
            }
            columns.add(ColumnMetaVO.of(code, definition.getLabel(), definition.getFormat())
                .group(definition.getGroup()).sortable(true));
        }
        return columns;
    }

    /** 预留：供导出复用同一套时间片定义 */
    protected Map<String, BigDecimal> emptyRow()
    {
        return new LinkedHashMap<>();
    }
}
