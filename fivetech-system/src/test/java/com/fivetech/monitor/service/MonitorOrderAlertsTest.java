package com.fivetech.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fivetech.monitor.exception.MonitorException;

/** 按规则发告警里订单信息的解析与校验 */
class MonitorOrderAlertsTest
{
    private static Map<String, Object> body(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2)
        {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String error(Runnable r)
    {
        try
        {
            r.run();
        }
        catch (MonitorException e)
        {
            assertEquals(400, e.getCode());
            return e.getMessage();
        }
        return null;
    }

    @Test
    void noOrderFieldsMeansPlainAlert()
    {
        MonitorOrderAlerts.Input in = MonitorOrderAlerts.parse(body("message", "x"));
        assertTrue(in.send());
        assertFalse(in.hasOrders());
        assertTrue(MonitorOrderAlerts.parse(null).send());
    }

    @Test
    void singleOrderAtTopLevel()
    {
        MonitorOrderAlerts.Input in = MonitorOrderAlerts.parse(body("message", "x", "orderNo", " W20261116001 ", "amount", 52000.5,
                "cumCount", 3, "cumAmount", "180000.00"));
        assertEquals(1, in.orders().size());
        MonitorOrderAlerts.Hit h = in.orders().get(0);
        assertEquals("W20261116001", h.orderNo(), "去掉首尾空白");
        assertEquals(new BigDecimal("52000.5"), h.amount());
        assertEquals(3, h.cumCount());
        assertEquals(new BigDecimal("180000"), h.cumAmount(), "字符串数字也接受，去掉多余的 0");
        assertEquals(new BigDecimal("52000"), MonitorOrderAlerts.parse(body("orderNo", "W1", "amount", 52000)).orders().get(0).amount());
    }

    @Test
    void mergedOrders()
    {
        List<Object> orders = new ArrayList<>();
        orders.add(body("orderNo", "W1", "amount", 60000, "cumCount", 1));
        orders.add(body("orderNo", "W2", "amount", "75000.25", "cumCount", "2"));
        MonitorOrderAlerts.Input in = MonitorOrderAlerts.parse(body("message", "x", "orders", orders));
        assertEquals(List.of("W1", "W2"), in.orders().stream().map(MonitorOrderAlerts.Hit::orderNo).toList());
        assertEquals(2, in.orders().get(1).cumCount());
    }

    @Test
    void sendFlag()
    {
        assertFalse(MonitorOrderAlerts.parse(body("send", false, "orderNo", "W1", "amount", 1)).send());
        assertFalse(MonitorOrderAlerts.parse(body("send", "false", "orderNo", "W1", "amount", 1)).send());
        assertTrue(MonitorOrderAlerts.parse(body("send", "TRUE")).send());
        assertTrue(error(() -> MonitorOrderAlerts.parse(body("send", "yes"))).contains("send"));
    }

    @Test
    void rejectsBadOrders()
    {
        assertTrue(error(() -> MonitorOrderAlerts.parse(body("amount", 1))).contains("orderNo"), "只有金额没有订单号");
        assertTrue(error(() -> MonitorOrderAlerts.parse(body("orderNo", "W1"))).contains("amount"), "没有金额");
        assertTrue(error(() -> MonitorOrderAlerts.parse(body("orderNo", "W 1", "amount", 1))).contains("空白"));
        assertTrue(error(() -> MonitorOrderAlerts.parse(body("orderNo", "W".repeat(65), "amount", 1))).contains("64"));
        assertTrue(error(() -> MonitorOrderAlerts.parse(body("orderNo", "W1", "amount", 0))).contains("大於 0"));
        assertTrue(error(() -> MonitorOrderAlerts.parse(body("orderNo", "W1", "amount", -5))).contains("大於 0"));
        assertTrue(error(() -> MonitorOrderAlerts.parse(body("orderNo", "W1", "amount", "1.234"))).contains("2 位小數"));
        assertTrue(error(() -> MonitorOrderAlerts.parse(body("orderNo", "W1", "amount", "abc"))).contains("數字"));
        assertTrue(error(() -> MonitorOrderAlerts.parse(body("orderNo", "W1", "amount", true))).contains("數字"));
        assertTrue(error(() -> MonitorOrderAlerts.parse(body("orderNo", "W1", "amount", "1e13"))).contains("不超過"));
        assertTrue(error(() -> MonitorOrderAlerts.parse(body("orderNo", "W1", "amount", 1, "cumCount", 0))).contains("整數"));
        assertTrue(error(() -> MonitorOrderAlerts.parse(body("orderNo", "W1", "amount", 1, "cumCount", 1.5))).contains("整數"));
        assertTrue(error(() -> MonitorOrderAlerts.parse(body("orderNo", "W1", "amount", 1, "orders", List.of()))).contains("二選一"));
        assertTrue(error(() -> MonitorOrderAlerts.parse(body("orders", List.of()))).contains("不能為空"));
        assertTrue(error(() -> MonitorOrderAlerts.parse(body("orders", "W1"))).contains("陣列"));
        assertTrue(error(() -> MonitorOrderAlerts.parse(body("orders", List.of("W1")))).contains("物件"));
        assertTrue(error(() -> MonitorOrderAlerts.parse(body("orders", List.of(body("orderNo", "W1", "amount", 1), body("orderNo", "W1",
                "amount", 2))))).contains("重複"));
        assertTrue(error(() -> MonitorOrderAlerts.parse(body("orders", List.of(body("orderNo", "W1"))))).startsWith("orders 第 1 筆"));
        List<Object> many = new ArrayList<>();
        for (int i = 0; i <= MonitorOrderAlerts.MAX_ORDERS; i++)
        {
            Map<String, Object> o = new HashMap<>();
            o.put("orderNo", "W" + i);
            o.put("amount", 1);
            many.add(o);
        }
        assertTrue(error(() -> MonitorOrderAlerts.parse(body("orders", many))).contains("最多 100"));
    }

    @Test
    void dailyTotalRuleNeedsCumCountAndSingleRuleDropsIt()
    {
        List<MonitorOrderAlerts.Hit> hits = List.of(new MonitorOrderAlerts.Hit("W1", new BigDecimal("50000"), 3, new BigDecimal("120000")));
        assertEquals(hits, MonitorOrderAlerts.forRule(hits, MonitorOrderRules.DAILY_TOTAL));
        MonitorOrderAlerts.Hit single = MonitorOrderAlerts.forRule(hits, MonitorOrderRules.SINGLE).get(0);
        assertNull(single.cumCount(), "单笔规则不存累计");
        assertNull(single.cumAmount());
        List<MonitorOrderAlerts.Hit> noCount = List.of(new MonitorOrderAlerts.Hit("W1", BigDecimal.TEN, null, null));
        assertTrue(error(() -> MonitorOrderAlerts.forRule(noCount, MonitorOrderRules.DAILY_TOTAL)).contains("cumCount"));
        List<MonitorOrderAlerts.Hit> lowCum = List.of(new MonitorOrderAlerts.Hit("W1", BigDecimal.TEN, 2, BigDecimal.ONE));
        assertTrue(error(() -> MonitorOrderAlerts.forRule(lowCum, MonitorOrderRules.DAILY_TOTAL)).contains("不能小於"));
    }
}
