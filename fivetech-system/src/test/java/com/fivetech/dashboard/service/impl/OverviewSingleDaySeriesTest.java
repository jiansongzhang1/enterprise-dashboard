package com.fivetech.dashboard.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.domain.query.OverviewQuery;
import com.fivetech.dashboard.domain.vo.OverviewCompareVO;
import com.fivetech.dashboard.domain.vo.OverviewNoticeVO;
import com.fivetech.dashboard.domain.vo.OverviewVO;
import com.fivetech.dashboard.enums.Granularity;
import com.fivetech.dashboard.gateway.MetricDataGateway;
import com.fivetech.dashboard.gateway.MetricSlotRequest;

/**
 * POST /dashboard/metrics/overview：区间跨天时「仅支持单日」的指标（ARPPU、活跃人数）没有区间值（不查合计），
 * 但 series / prevSeries 照常向 UDS 取每小时 / 每天的值（2026-10-29）。
 */
class OverviewSingleDaySeriesTest
{
    private final List<List<String>> seriesCalls = new ArrayList<>();

    private final List<List<String>> totalCalls = new ArrayList<>();

    private OverviewServiceImpl service()
    {
        MetricDataGateway gateway = (MetricDataGateway) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { MetricDataGateway.class }, (p, m, a) -> {
                    switch (m.getName())
                    {
                        case "querySeries":
                        {
                            MetricSlotRequest r = (MetricSlotRequest) a[0];
                            seriesCalls.add(r.getMetricCodes());
                            Map<String, List<BigDecimal>> out = new LinkedHashMap<>();
                            r.getMetricCodes().forEach(c -> out.put(c, List.of(BigDecimal.ONE, BigDecimal.TEN)));
                            return out;
                        }
                        case "queryTotals":
                        {
                            MetricSlotRequest r = (MetricSlotRequest) a[0];
                            totalCalls.add(r.getMetricCodes());
                            Map<String, BigDecimal> out = new LinkedHashMap<>();
                            r.getMetricCodes().forEach(c -> out.put(c, BigDecimal.ONE));
                            return out;
                        }
                        case "getFreshness":
                            return null;
                        default:
                            if (m.isDefault())
                            {
                                return java.lang.reflect.InvocationHandler.invokeDefault(p, m, a);
                            }
                            throw new UnsupportedOperationException(m.getName());
                    }
                });
        return new OverviewServiceImpl(new DashboardProperties(), null, null, gateway, null, null, null, null);
    }

    @Test
    void seriesStillFetchedForSingleDayOnlyMetrics()
    {
        OverviewServiceImpl service = service();
        OverviewQuery q = new OverviewQuery();
        q.setIncludeSeries(true);
        OverviewVO vo = new OverviewVO();
        OverviewCompareVO cmp = new OverviewCompareVO();
        cmp.setFrom("2026-10-20 00:00");
        cmp.setTo("2026-10-22 00:00");
        vo.setCompare(cmp);
        List<OverviewNoticeVO> notices = new ArrayList<>();
        // 跨天：卡片数值只取 dep（及 ARPPU 的分子分母不再展开）；序列包含 arppu、active 及 arppu 的操作数
        List<String> valueCodes = List.of("dep");
        List<String> seriesCodes = List.of("dep", "arppu", "active", "depU");

        Object fetched = service.fetch(q, LocalDateTime.of(2026, 10, 22, 0, 0), LocalDateTime.of(2026, 10, 24, 0, 0),
                Granularity.DAY, valueCodes, seriesCodes, vo, notices);

        assertTrue(fetched != null);
        assertEquals(2, seriesCalls.size(), "当期 + 对比期两次序列请求");
        for (List<String> call : seriesCalls)
        {
            assertTrue(call.containsAll(List.of("arppu", "active")), "序列照常取 ARPPU、活跃人数：" + call);
        }
        assertEquals(2, totalCalls.size(), "当期 + 对比期两次合计请求");
        for (List<String> call : totalCalls)
        {
            assertFalse(call.contains("arppu") || call.contains("active"), "跨天不取区间值：" + call);
        }
    }

    @Test
    void cardTitleAndHintByGranularity()
    {
        assertEquals(List.of("僅支援單日", "點擊查看每日資料"), List.of(OverviewServiceImpl.singleDayOnlyText(Granularity.DAY)));
        assertEquals(List.of("僅支援單日", "點擊查看每小時資料"), List.of(OverviewServiceImpl.singleDayOnlyText(Granularity.HOUR)));
        assertEquals(List.of("不支援週粒度", "請切換為日或小時"), List.of(OverviewServiceImpl.singleDayOnlyText(Granularity.WEEK)));
        assertEquals(List.of("僅支援單日", "點擊查看每日資料"), List.of(OverviewServiceImpl.singleDayOnlyText(null)), "没有粒度按日");
    }

    @Test
    void noSeriesRequestWhenSeriesNotIncluded()
    {
        OverviewServiceImpl service = service();
        OverviewQuery q = new OverviewQuery();
        q.setIncludeSeries(false);
        service.fetch(q, LocalDateTime.of(2026, 10, 22, 0, 0), LocalDateTime.of(2026, 10, 24, 0, 0),
                Granularity.DAY, List.of("dep"), List.of("dep", "arppu", "active"), new OverviewVO(), new ArrayList<>());
        assertTrue(seriesCalls.isEmpty());
        assertEquals(1, totalCalls.size());
    }
}
