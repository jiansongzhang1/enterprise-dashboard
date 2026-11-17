package com.fivetech.monitor.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.gateway.uds.UdsClient;
import com.fivetech.dashboard.gateway.uds.UdsProperties;
import com.fivetech.dashboard.gateway.uds.UdsRow;
import tools.jackson.databind.json.JsonMapper;

/** 订单状态、用户信息查 UDS：字段来自配置、按批查、状态映射、缓存（審核中短、终态长）、未接入 */
class UdsOrderLookupTest
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
        public List<UdsRow> query(Map<String, Object> body)
        {
            bodies.add(body);
            return answers.isEmpty() ? List.of() : answers.remove(0);
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

    @Test
    @SuppressWarnings("unchecked")
    void ordersUseConfiguredFieldsBatchesMapStatusAndCache()
    {
        OrderLookupProperties p = new OrderLookupProperties();
        p.setOrderDataset("ds_withdraw");
        p.setOrderNoField("ono");
        p.setOrderStatusField("st");
        p.setBatchSize(2);
        p.getStatusMap().put("2", "PAID");
        p.getStatusMap().put("0", "PENDING");
        FakeClient c = new FakeClient();
        c.answers.add(List.of(row("ono", "W1", "st", "2", "user_id", "u1"), row("ono", "W2", "st", "0", "user_id", "u2")));
        c.answers.add(List.of(row("ono", "W3", "st", "rejected", "user_id", "u3")));
        UdsOrderLookup l = new UdsOrderLookup(c, p);
        assertTrue(l.available());
        Map<String, OrderLookup.OrderInfo> out = l.orders("SITE-IN", List.of("W1", "W2", "W3"), LocalDateTime.of(2026, 11, 10, 8, 30));
        assertEquals(2, c.bodies.size(), "每批 2 个");
        assertEquals("PAID", out.get("W1").status());
        assertEquals("PENDING", out.get("W2").status());
        assertEquals("REJECTED", out.get("W3").status(), "没配映射的按原值大写");
        assertEquals("u1", out.get("W1").userId());
        Map<String, Object> b = c.bodies.get(0);
        assertEquals("ds_withdraw", b.get("dataset"));
        Map<String, Object> time = (Map<String, Object>) b.get("time");
        assertEquals("submit_time", time.get("dimension"));
        assertEquals("2026-11-09 08:00:00", time.get("from"), "最早事件往前 1 天");
        List<Map<String, Object>> and = (List<Map<String, Object>>) ((Map<String, Object>) b.get("filters")).get("and");
        assertEquals("ono", and.get(0).get("field"));
        assertEquals(List.of("W1", "W2"), and.get(0).get("value"));
        assertEquals("site_code", and.get(1).get("field"));

        l.orders("SITE-IN", List.of("W1", "W3"), null);
        assertEquals(2, c.bodies.size(), "终态走缓存");
        assertTrue(l.cachedOrders().contains("SITE-IN|W2"));
    }

    @Test
    void usersAndNotConfigured()
    {
        OrderLookupProperties p = new OrderLookupProperties();
        FakeClient c = new FakeClient();
        UdsOrderLookup l = new UdsOrderLookup(c, p);
        assertFalse(l.available());
        assertTrue(l.orders("SITE-IN", List.of("W1"), null).isEmpty());
        assertTrue(l.users("SITE-IN", List.of("u1")).isEmpty());
        assertEquals(0, c.bodies.size(), "未接入不调 UDS");

        p.setUserDataset("ds_player");
        p.setSiteField("");
        c.answers.add(List.of(row("user_id", "u1", "account", "player1", "register_time", "2026-11-13T09:00:00")));
        Map<String, OrderLookup.UserInfo> u = l.users("SITE-IN", List.of("u1"));
        assertEquals("player1", u.get("u1").account());
        assertEquals(LocalDateTime.of(2026, 11, 13, 9, 0), u.get("u1").registerTime());
        assertEquals(1, ((List<?>) ((Map<?, ?>) c.bodies.get(0).get("filters")).get("and")).size(), "不配站点字段时不按站点过滤");
    }
}
