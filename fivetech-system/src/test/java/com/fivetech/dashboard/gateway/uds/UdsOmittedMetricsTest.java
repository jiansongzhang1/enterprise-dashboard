package com.fivetech.dashboard.gateway.uds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.enums.Granularity;
import com.fivetech.dashboard.gateway.MetricSlotRequest;

/** 多站点：UDS meta.omitted 里本站不提供的指标，网关记下来供运营总览 / 指标汇总过滤 */
class UdsOmittedMetricsTest
{
    /** 每次查询返回的省略字段（UDS 字段名）；datasetOmitted = 整个数据集未实现 */
    private Set<String> omittedFields = Set.of();

    private boolean datasetOmitted;

    private UdsMetricDataGateway gateway()
    {
        DashboardProperties dashboard = new DashboardProperties();
        dashboard.setDefaultSite("SITE-IN");
        UdsProperties props = new UdsProperties();
        UdsProperties.Dataset amount = new UdsProperties.Dataset();
        amount.setName("dashboard_overview_amount_v2");
        amount.setTimeDimension("uds_hh");
        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.put("dep", "dep");
        metrics.put("wd", "wd");
        metrics.put("ggr", "ggr");
        metrics.put("bonus", "bonus");
        metrics.put("bonusR", "bonusR");
        amount.setMetrics(metrics);
        Map<String, UdsProperties.Dataset> datasets = new LinkedHashMap<>();
        datasets.put("overview_amount", amount);
        props.setDatasets(datasets);
        UdsProperties.Overview ov = new UdsProperties.Overview();
        ov.setAmount("overview_amount");
        Map<String, List<String>> subtract = new LinkedHashMap<>();
        subtract.put("net", List.of("dep", "wd"));
        subtract.put("ngr", List.of("ggr", "bonus"));
        ov.setSubtract(subtract);
        props.setOverview(ov);

        UdsClient client = new UdsClient(props, new tools.jackson.databind.ObjectMapper(), dashboard)
        {
            @Override
            @SuppressWarnings("unchecked")
            public UdsQueryResult queryWithMeta(Map<String, Object> body)
            {
                Map<String, Object> cells = new LinkedHashMap<>();
                for (String f : (List<String>) body.get("metrics"))
                {
                    if (!datasetOmitted && !omittedFields.contains(f))
                    {
                        cells.put(f, 1);
                    }
                }
                List<UdsRow> rows = new ArrayList<>();
                if (!datasetOmitted)
                {
                    rows.add(new UdsRow(cells));
                }
                UdsQueryResult r = new UdsQueryResult(rows, null, null, false, null);
                r.setOmittedMetrics(omittedFields);
                r.setDatasetOmitted(datasetOmitted);
                return r;
            }
        };
        return new UdsMetricDataGateway(props, client, dashboard);
    }

    private static MetricSlotRequest request(List<String> codes)
    {
        MetricSlotRequest r = new MetricSlotRequest();
        r.setSiteCode("SITE-IN");
        r.setFrom(LocalDateTime.of(2026, 10, 20, 0, 0));
        r.setTo(LocalDateTime.of(2026, 10, 21, 0, 0));
        r.setGranularity(Granularity.DAY);
        r.setMetricCodes(codes);
        return r;
    }

    @Test
    void omittedColumnsAndComposed()
    {
        UdsMetricDataGateway g = gateway();
        List<String> codes = List.of("dep", "wd", "ggr", "bonus", "bonusR", "net", "ngr");
        assertEquals(Set.of(), g.omittedMetrics("SITE-IN", codes), "还没查过：不知道，不过滤");

        omittedFields = Set.of("bonus", "bonusR");
        g.queryTotals(request(codes));
        assertEquals(Set.of("bonus", "bonusR", "ngr"), g.omittedMetrics("site-in", codes),
            "按列省略的指标 + 操作数被省略的 NGR；存提差两个操作数都有");

        omittedFields = Set.of();
        g.queryTotals(request(codes));
        assertTrue(g.omittedMetrics("SITE-IN", codes).isEmpty(), "UDS 补齐后下一次查询就恢复");
        assertTrue(g.omittedMetrics("JITO", codes).isEmpty(), "按站点分开记");
    }

    @Test
    void wholeDatasetOmitted()
    {
        UdsMetricDataGateway g = gateway();
        datasetOmitted = true;
        List<String> codes = List.of("dep", "bonus");
        g.queryTotals(request(codes));
        assertEquals(Set.of("dep", "bonus"), g.omittedMetrics("SITE-IN", codes));
    }
}
