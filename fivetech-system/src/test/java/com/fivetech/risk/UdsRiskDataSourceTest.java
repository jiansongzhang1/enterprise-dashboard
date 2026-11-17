package com.fivetech.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.gateway.uds.UdsClient;
import com.fivetech.dashboard.gateway.uds.UdsProperties;
import com.fivetech.dashboard.gateway.uds.UdsQueryResult;
import com.fivetech.dashboard.gateway.uds.UdsRow;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.risk.data.RiskDataSource;
import com.fivetech.risk.data.RiskFilter;
import com.fivetech.risk.data.UdsRiskDataSource;
import tools.jackson.databind.json.JsonMapper;

/** 风控向 UDS 取数：数据集与维度字段来自配置、自动翻页、筛选、上限、未接入 */
class UdsRiskDataSourceTest
{
    static final class FakeClient extends UdsClient
    {
        final List<Map<String, Object>> bodies = new ArrayList<>();

        final List<List<UdsRow>> answers = new ArrayList<>();

        FakeClient()
        {
            super(new UdsProperties(), JsonMapper.builder().build(), new DashboardProperties());
        }

        @Override
        public UdsQueryResult queryWithMeta(Map<String, Object> body)
        {
            bodies.add(body);
            return new UdsQueryResult(answers.isEmpty() ? List.of() : answers.remove(0), "b", "v", false, null);
        }
    }

    static UdsRow row(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2)
        {
            m.put((String) kv[i], kv[i + 1]);
        }
        return new UdsRow(m);
    }

    static RiskFilter filter()
    {
        return new RiskFilter("SITE-IN", LocalDateTime.of(2026, 11, 9, 0, 0), LocalDateTime.of(2026, 11, 16, 0, 0),
            List.of("PG"), List.of(), List.of(), List.of("1001"), List.of("2001"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void userDaysUsesConfiguredDatasetAndFieldsAndPages()
    {
        RiskProperties p = new RiskProperties();
        p.setUserDayDataset("risk_user_day_v1");
        p.setPageSize(2);
        p.getFields().put("userId", "uid");
        p.getFields().put("parent", "");
        FakeClient c = new FakeClient();
        c.answers.add(List.of(row("uid", "u1", "account", "a1", "dep_amount", 10), row("uid", "u2", "account", "a2", "dep_amount", 20)));
        c.answers.add(List.of(row("uid", "u3", "account", "a3", "dep_amount", 30)));
        UdsRiskDataSource ds = new UdsRiskDataSource(c, p);
        List<Map<String, Object>> rows = ds.userDays(filter(), List.of("dep_amount"));
        assertEquals(3, rows.size(), "满页继续翻");
        assertEquals("u1", rows.get(0).get("userId"), "维度换回逻辑名");
        assertEquals(10, rows.get(0).get("dep_amount"));
        Map<String, Object> b = c.bodies.get(0);
        assertEquals("risk_user_day_v1", b.get("dataset"));
        assertEquals(List.of("uid", "account", "register_time", "promoter_id", "channel_id"), b.get("dimensions"), "parent 配成空 = 没有这个字段");
        Map<String, Object> time = (Map<String, Object>) b.get("time");
        assertEquals("2026-11-09", time.get("from"));
        assertEquals("2026-11-15", time.get("to"), "左闭右开 → 日期闭区间");
        String filters = String.valueOf(b.get("filters"));
        assertTrue(filters.contains("channel_id") && filters.contains("promoter_id") && filters.contains("or"), "推广商与渠道取并集：" + filters);
        assertEquals(2, c.bodies.get(1).get("offset"));
    }

    @Test
    void paidWithdrawalsFilterStatusAndAmount()
    {
        RiskProperties p = new RiskProperties();
        p.setWithdrawOrderDataset("risk_withdraw_v1");
        FakeClient c = new FakeClient();
        c.answers.add(List.of(row("order_no", "W1", "user_id", "u1", "amount", "80000"), row("order_no", "W2", "user_id", "u2", "amount", 900)));
        List<Map<String, Object>> rows = new UdsRiskDataSource(c, p).paidWithdrawals(filter(), new BigDecimal("1000"));
        assertEquals(1, rows.size());
        assertEquals(new BigDecimal("80000"), rows.get(0).get("orderAmount"));
        assertTrue(String.valueOf(c.bodies.get(0).get("filters")).contains("PAID"), "只取成功出款");
    }

    @Test
    void availabilityAndRowLimit()
    {
        RiskProperties p = new RiskProperties();
        UdsRiskDataSource ds = new UdsRiskDataSource(new FakeClient(), p);
        assertFalse(ds.available(RiskDataSource.Kind.USER_DAY), "数据集名为空 = 未接入");
        p.setGameDataset("risk_game_v1");
        assertTrue(ds.available(RiskDataSource.Kind.GAME));
        p.setEnabled(false);
        assertFalse(ds.available(RiskDataSource.Kind.GAME), "总开关");
        p.setEnabled(true);
        p.setPageSize(1);
        p.setMaxRows(2);
        FakeClient c = new FakeClient();
        for (int i = 0; i < 4; i++)
        {
            c.answers.add(List.of(row("game_code", "g" + i)));
        }
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
            () -> new UdsRiskDataSource(c, p).games(filter(), List.of("game"), Map.of(), List.of("bet_amount"))).getMessage().contains("2"));
    }
}
