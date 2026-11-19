package com.fivetech.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.gateway.uds.UdsQueryResult;
import java.util.ArrayList;
import java.util.function.Function;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.gateway.uds.UdsClient;
import com.fivetech.dashboard.gateway.uds.UdsProperties;
import tools.jackson.databind.json.JsonMapper;
import com.fivetech.risk.data.RiskDataSource;
import com.fivetech.risk.data.RiskFilter;
import com.fivetech.risk.data.UdsRiskDataSource;

/**
 * UDS 的 risk_* 窗口数据集（对照表 G 列）：按数据集覆盖字段名、固定条件、游戏范围、「数据集.字段」另取、渠道获客。
 */
class UdsRiskWindowDatasetTest
{
    /** 记下请求体；有 handler 时按它回，否则按 answers 顺序回 */
    static class FakeClient extends UdsClient
    {
        final List<Map<String, Object>> bodies = new ArrayList<>();

        final List<List<com.fivetech.dashboard.gateway.uds.UdsRow>> answers = new ArrayList<>();

        final Function<Map<String, Object>, UdsQueryResult> handler;

        FakeClient()
        {
            this(null);
        }

        FakeClient(Function<Map<String, Object>, UdsQueryResult> handler)
        {
            super(new UdsProperties(), JsonMapper.builder().build(), new DashboardProperties());
            this.handler = handler;
        }

        @Override
        public UdsQueryResult queryWithMeta(Map<String, Object> body)
        {
            bodies.add(body);
            if (handler != null)
            {
                return handler.apply(body);
            }
            return new UdsQueryResult(answers.isEmpty() ? List.of() : answers.remove(0), "b", "v", false, null);
        }
    }

    static RiskFilter filter(List<String> platforms)
    {
        return new RiskFilter("SITE-IN", LocalDateTime.of(2026, 11, 9, 0, 0), LocalDateTime.of(2026, 11, 16, 0, 0),
            platforms, List.of(), List.of(), List.of(), List.of("ch1"));
    }

    static RiskProperties props()
    {
        RiskProperties p = new RiskProperties();
        p.setUserDayDataset("risk_user_window_v1");
        p.setUserGameDayDataset("risk_game_player_cube_window_v1");
        p.setGameDataset("risk_game_player_cube_window_v1");
        p.setChannelAcqDataset("risk_channel_reg_window_v1");
        p.setGameScopeField("game_scope");
        p.getFields().put("userId", "player_id");
        p.getFields().put("account", "login_name");
        p.getFields().put("channelId", "channel");
        p.getFields().put("agentId", "");
        p.getFields().put("parent", "agent_login");
        p.getFields().put("regTime", "register_date_india");
        p.getFields().put("brand", "");
        p.getFields().put("platform", "provider_code");
        p.getFields().put("game", "game_id");
        Map<String, Map<String, String>> o = new LinkedHashMap<>();
        o.put("risk_user_window_v1", new LinkedHashMap<>(Map.of("date", "stat_dt", "parent", "parent_loginname", "regTime", "")));
        o.put("risk_game_player_cube_window_v1", new LinkedHashMap<>(Map.of("date", "node_dt", "brand", "sub_provider_name", "gameName", "game_name_en")));
        o.put("risk_game_node_judgement_window_v1", new LinkedHashMap<>(Map.of("date", "node_dt", "brand", "sub_provider_name")));
        o.put("risk_channel_reg_window_v1", new LinkedHashMap<>(Map.of("date", "stat_dt")));
        p.setDatasetFields(o);
        return p;
    }

    @Test
    @SuppressWarnings("unchecked")
    void userWindowUsesOwnFieldNamesAndGameScope()
    {
        FakeClient c = new FakeClient();
        c.answers.add(List.of(UdsRiskDataSourceTest.row("player_id", "u1", "login_name", "a1", "parent_loginname", "p1", "dep", 10)));
        UdsRiskDataSource ds = new UdsRiskDataSource(c, props());
        List<Map<String, Object>> rows = ds.userDays(filter(List.of()), List.of("dep"));
        Map<String, Object> b = c.bodies.get(0);
        assertEquals("risk_user_window_v1", b.get("dataset"));
        List<String> dims = (List<String>) b.get("dimensions");
        assertTrue(dims.contains("parent_loginname") && !dims.contains("agent_login"), "上级账号用该数据集的字段名");
        assertFalse(dims.contains("register_date_india"), "该数据集没有注册时间，不带");
        // stat_dt 不是时间维（UDS 2026-11-19）：时间维固定 uds_hh，stat_dt 只在日趋势进 dimensions
        assertEquals("uds_hh", ((Map<String, Object>) b.get("time")).get("dimension"), "时间维固定 uds_hh");
        String filters = String.valueOf(b.get("filters"));
        assertTrue(filters.contains("game_scope") && filters.contains("value=A"), "不选平台 = 全部（A）：" + filters);
        assertEquals("p1", rows.get(0).get("parent"));

        c.bodies.clear();
        ds.userDays(filter(List.of("PG")), List.of("dep"));
        assertTrue(String.valueOf(c.bodies.get(0).get("filters")).contains("V:PG"), "只选一个平台 = V:平台编码");
        c.bodies.clear();
        ds.userDays(filter(List.of("PG", "JILI")), List.of("dep"));
        assertTrue(String.valueOf(c.bodies.get(0).get("filters")).contains("value=A"), "选多个平台按全部");
    }

    @Test
    @SuppressWarnings("unchecked")
    void gameCubeBrandFilterAndQualifiedFieldsFromAnotherDataset()
    {
        FakeClient c = new FakeClient(body -> "risk_game_node_judgement_window_v1".equals(body.get("dataset"))
            ? new UdsQueryResult(List.of(UdsRiskDataSourceTest.row("provider_code", "PG", "profit_players", 3)), "b", "v", false, null)
            : new UdsQueryResult(List.of(UdsRiskDataSourceTest.row("provider_code", "PG", "valid_bet", 100),
                UdsRiskDataSourceTest.row("provider_code", "JILI", "valid_bet", 50)), "b", "v", false, null));
        UdsRiskDataSource ds = new UdsRiskDataSource(c, props());
        RiskFilter f = new RiskFilter("SITE-IN", LocalDateTime.of(2026, 11, 9, 0, 0), LocalDateTime.of(2026, 11, 16, 0, 0),
            List.of(), List.of("PGS"), List.of(), List.of(), List.of());
        List<Map<String, Object>> rows = ds.games(f, List.of("platform"), null,
            List.of("valid_bet", "risk_game_node_judgement_window_v1.profit_players"));
        assertEquals(2, c.bodies.size(), "另一个数据集另取一次");
        assertTrue(String.valueOf(c.bodies.get(0).get("filters")).contains("sub_provider_name"), "品牌按立方体的字段过滤");
        assertEquals(List.of("profit_players"), c.bodies.get(1).get("metrics"));
        // node_dt 是五个轴之一，不是时间维
        assertEquals("uds_hh", ((Map<String, Object>) c.bodies.get(1).get("time")).get("dimension"));
        assertTrue(((List<String>) c.bodies.get(1).get("dimensions")).contains("node_dt"), "node_dt 在 dimensions 里");
        assertTrue(String.valueOf(c.bodies.get(1).get("filters")).contains("scope_mask"), "每个请求必须带 scope_mask");
        assertEquals(3, rows.get(0).get("risk_game_node_judgement_window_v1.profit_players"), "按维度值并回，键仍是「数据集.字段」");
        assertNull(rows.get(1).get("risk_game_node_judgement_window_v1.profit_players"));
    }

    @Test
    void qualifiedDatasetFailureLeavesColumnsEmpty()
    {
        FakeClient c = new FakeClient(body -> {
            if (!"risk_game_player_cube_window_v1".equals(body.get("dataset")))
            {
                throw new IllegalStateException("dataset not found");
            }
            return new UdsQueryResult(List.of(UdsRiskDataSourceTest.row("provider_code", "PG", "valid_bet", 100)), "b", "v", false, null);
        });
        UdsRiskDataSource ds = new UdsRiskDataSource(c, props());
        List<Map<String, Object>> rows = ds.games(filter(List.of()), List.of("platform"), null,
            List.of("valid_bet", "risk_game_node_judgement_window_v1.loss_players"));
        assertEquals(100, rows.get(0).get("valid_bet"), "主数据集照常");
        assertNull(rows.get(0).get("risk_game_node_judgement_window_v1.loss_players"));
    }

    @Test
    void channelAcquisitionMapsFields()
    {
        FakeClient c = new FakeClient();
        c.answers.add(List.of(UdsRiskDataSourceTest.row("channel", "ch1", "reg_users", 12, "ftd_users", 5, "ftd_amount", "1500.5")));
        RiskProperties p = props();
        UdsRiskDataSource ds = new UdsRiskDataSource(c, p);
        assertTrue(ds.available(RiskDataSource.Kind.CHANNEL_ACQ));
        List<Map<String, Object>> rows = ds.channelAcquisition(filter(List.of()));
        assertEquals("risk_channel_reg_window_v1", c.bodies.get(0).get("dataset"));
        assertEquals("ch1", rows.get(0).get("channelId"));
        assertEquals(new BigDecimal("12"), rows.get(0).get("regUsers"));
        assertEquals(new BigDecimal("1500.5"), rows.get(0).get("ftdAmt"));
        assertFalse(String.valueOf(c.bodies.get(0).get("filters")).contains("game_scope"), "游戏范围只加在用户窗口上");

        p.setChannelAcqDataset("");
        assertFalse(new UdsRiskDataSource(new FakeClient(), p).available(RiskDataSource.Kind.CHANNEL_ACQ), "没配 = 待接入");
    }

    @Test
    void datasetFixedFilters()
    {
        RiskProperties p = props();
        p.setGameScopeField("");
        p.getDatasetFilters().put("risk_user_window_v1", Map.of("flow", "ALL"));
        FakeClient c = new FakeClient();
        new UdsRiskDataSource(c, p).userDays(filter(List.of()), List.of("dep"));
        String filters = String.valueOf(c.bodies.get(0).get("filters"));
        assertTrue(filters.contains("flow") && !filters.contains("game_scope"), filters);
    }
}
