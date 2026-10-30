package com.fivetech.dashboard.gateway.uds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.gateway.BreakdownResult;

/**
 * POST /dashboard/metrics/rankingboard 热销游戏：游戏中英文名取 dashboard_game_ranking 的指标 game_name_cn / game_name_en
 * （breakdowns.hot_games.text-metrics），排名查询带上、全量查询不带，按字符串读进行的 dims（接口 gameNameCn / gameNameEn）。
 */
class UdsBreakdownTextMetricsTest
{
    private final List<Map<String, Object>> bodies = new ArrayList<>();

    private UdsMetricDataGateway gateway()
    {
        return gateway(false);
    }

    /** @param namesAsDimensions true = 中英文名配在 dimensions（生产配置，2026-10-29）；false = 配在 text-metrics */
    @SuppressWarnings("unchecked")
    private UdsMetricDataGateway gateway(boolean namesAsDimensions)
    {
        DashboardProperties dashboard = new DashboardProperties();
        UdsProperties props = new UdsProperties();
        UdsProperties.Breakdown bd = new UdsProperties.Breakdown();
        bd.setDataset("dashboard_game_ranking");
        bd.setTimeDimension("India_DDHH");
        bd.setGrain("HOUR");
        bd.setLimit(20);
        bd.setWithTotal(true);
        bd.setBatch(true);
        Map<String, String> dims = new LinkedHashMap<>();
        dims.put("platformCode", "provider_code");
        dims.put("gameId", "game_id");
        if (namesAsDimensions)
        {
            dims.put("gameNameCn", "game_name_cn");
            dims.put("gameNameEn", "game_name_en");
        }
        bd.setDimensions(dims);
        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.put("betAmount", "bet_amount");
        bd.setMetrics(metrics);
        Map<String, String> text = new LinkedHashMap<>();
        text.put("gameNameCn", "game_name_cn");
        text.put("gameNameEn", "game_name_en");
        if (!namesAsDimensions)
        {
            bd.setTextMetrics(text);
        }
        Map<String, UdsProperties.Breakdown> bds = new LinkedHashMap<>();
        bds.put("hot_games", bd);
        props.setBreakdowns(bds);

        UdsClient client = new UdsClient(props, new tools.jackson.databind.ObjectMapper(), dashboard)
        {
            @Override
            public List<UdsQueryResult> queryBatch(List<Map<String, Object>> list)
            {
                bodies.addAll(list);
                Map<String, Object> game = new LinkedHashMap<>();
                game.put("provider_code", "EVO");
                game.put("game_id", "g1");
                game.put("bet_amount", 1000);
                game.put("game_name_cn", "百家樂");
                game.put("game_name_en", "Baccarat");
                Map<String, Object> noName = new LinkedHashMap<>();
                noName.put("provider_code", "JILI");
                noName.put("game_id", "g2");
                noName.put("bet_amount", 500);
                Map<String, Object> total = new LinkedHashMap<>();
                total.put("bet_amount", 5000);
                return List.of(new UdsQueryResult(List.of(new UdsRow(game), new UdsRow(noName)), null, null, false, null),
                    new UdsQueryResult(List.of(new UdsRow(total)), null, null, false, null));
            }
        };
        return new UdsMetricDataGateway(props, client, dashboard);
    }

    @Test
    @SuppressWarnings("unchecked")
    void gameNamesFromTextMetrics()
    {
        BreakdownResult r = gateway().queryBreakdown("hot_games", LocalDateTime.of(2026, 10, 29, 0, 0),
            LocalDateTime.of(2026, 10, 29, 12, 0));

        assertEquals(2, bodies.size(), "排名 + 全量一次批量查询");
        List<String> rankMetrics = (List<String>) bodies.get(0).get("metrics");
        assertTrue(rankMetrics.containsAll(List.of("bet_amount", "game_name_cn", "game_name_en")), "排名查询带上中英文名：" + rankMetrics);
        List<String> totalMetrics = (List<String>) bodies.get(1).get("metrics");
        assertFalse(totalMetrics.contains("game_name_cn") || totalMetrics.contains("game_name_en"), "全量查询不带文本指标：" + totalMetrics);

        BreakdownResult.Row g1 = r.getRows().get(0);
        assertEquals("百家樂", g1.dim("gameNameCn"));
        assertEquals("Baccarat", g1.dim("gameNameEn"));
        assertEquals(0, new BigDecimal("1000").compareTo(g1.num("betAmount")));
        assertNull(r.getRows().get(1).dim("gameNameCn"), "上游没有名称为 null");
        assertFalse(r.getTotals().containsKey("gameNameCn"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void gameNamesAsDimensions()
    {
        BreakdownResult r = gateway(true).queryBreakdown("hot_games", LocalDateTime.of(2026, 10, 29, 0, 0),
            LocalDateTime.of(2026, 10, 29, 12, 0));

        List<String> rankDims = (List<String>) bodies.get(0).get("dimensions");
        assertTrue(rankDims.containsAll(List.of("game_name_cn", "game_name_en")), "排名查询按中英文名分组：" + rankDims);
        List<String> rankMetrics = (List<String>) bodies.get(0).get("metrics");
        assertFalse(rankMetrics.contains("game_name_cn") || rankMetrics.contains("game_name_en"), "不放在 metrics：" + rankMetrics);
        assertTrue(((List<String>) bodies.get(1).get("dimensions")).isEmpty(), "全量查询不分组");

        assertEquals("百家樂", r.getRows().get(0).dim("gameNameCn"));
        assertEquals("Baccarat", r.getRows().get(0).dim("gameNameEn"));
        assertNull(r.getRows().get(1).dim("gameNameEn"), "上游没有名称为 null");
    }
}
