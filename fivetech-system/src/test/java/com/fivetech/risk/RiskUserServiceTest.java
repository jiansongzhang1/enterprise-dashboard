package com.fivetech.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.exception.MonitorFieldException;
import com.fivetech.risk.data.RiskDataSource.Kind;
import com.fivetech.risk.user.RiskUserQuery;
import com.fivetech.risk.user.RiskUserService;

/** 用户详情抽屉（FY-75）与全部用户搜索 */
@SuppressWarnings("unchecked")
class RiskUserServiceTest
{
    private RiskFixtures f;

    private RiskUserService users;

    private final String today = RiskFixtures.TODAY.toString();

    @BeforeEach
    void setUp()
    {
        f = new RiskFixtures();
        users = new RiskUserService(f.props, f.catalog, f.source, f.rank, f.config, null);
        f.user("u1", "alice01", 3, "boss1", 10000, 60000, 2);
        f.play("u1", "g1", today, 1000, 70000, 60000, 100, today + " 12:00:00");
        f.play("u1", "g2", today, 500, 300, 300, 50, today + " 08:00:00");
        f.source.profiles.put("u1", RiskFixtures.m("userId", "u1", "account", "alice01", "regTime", RiskFixtures.TODAY.minusDays(3) + " 10:00:00",
            "agentId", "A1", "channelId", "c1", "parent", "boss1", "parentId", "u9"));
        f.source.profiles.put("u5", RiskFixtures.m("userId", "u5", "account", "newbie5", "regTime", today + " 09:00:00"));
    }

    private RiskUserQuery q(String userId, String window)
    {
        RiskUserQuery q = new RiskUserQuery();
        q.setUserId(userId);
        q.setWindow(window);
        return q;
    }

    @Test
    void searchAllUsers()
    {
        List<Map<String, Object>> items = (List<Map<String, Object>>) users.search("ali").get("items");
        assertEquals(1, items.size());
        assertEquals("al***01", items.get(0).get("account"));
        assertEquals(RiskFixtures.TODAY.minusDays(3).toString(), items.get(0).get("regDate"));
        assertThrows(MonitorFieldException.class, () -> users.search("a"));
    }

    @Test
    void registrationWindows()
    {
        Map<String, Object> d0 = users.detail(q("u1", null));
        Map<?, ?> range = (Map<?, ?>) d0.get("range");
        assertEquals("REG_D0", range.get("mode"), "默认註冊當日");
        assertEquals(RiskFixtures.TODAY.minusDays(3).toString(), range.get("from"));
        assertEquals("從註冊日起算，與名單的時間範圍不同", range.get("notice"));
        Map<String, Object> idle = users.detail(q("u5", null));
        assertEquals(true, idle.get("empty"), "时间范围内没有活动");
        assertEquals("這段時間沒有存款與投注，可切換時間範圍", idle.get("emptyText"));
        assertEquals("今日", ((Map<?, ?>) idle.get("header")).get("regText"));

        Map<?, ?> r7 = (Map<?, ?>) users.detail(q("u1", "REG_7")).get("range");
        assertEquals(today, r7.get("to"), "取到资料截至");
        assertEquals("註冊第 4 天，目前有 4 天資料", r7.get("progress"));

        assertThrows(MonitorFieldException.class, () -> users.detail(q("u5", "REG_D1")), "今天注册的次日还没到");
        List<Map<String, Object>> opts = (List<Map<String, Object>>) ((Map<?, ?>) users.detail(q("u5", null)).get("range")).get("options");
        assertEquals(true, opts.get(1).get("disabled"));

        RiskUserQuery custom = q("u1", "CUSTOM");
        custom.setFrom(RiskFixtures.TODAY.minusDays(10).toString());
        custom.setTo(today);
        assertThrows(MonitorFieldException.class, () -> users.detail(custom), "注册日之前不可选");

        assertThrows(MonitorException.class, () -> users.detail(q("nobody", null)), "用户不存在");
    }

    @Test
    void registeredBeforeDataStartUsesListRange()
    {
        f.props.setDataStartDate(RiskFixtures.TODAY.minusDays(1).toString());
        RiskUserQuery qq = q("u1", null);
        qq.setListFrom(today + " 00:00");
        qq.setListTo(RiskFixtures.TODAY.plusDays(1) + " 00:00");
        Map<?, ?> range = (Map<?, ?>) users.detail(qq).get("range");
        assertEquals("CUSTOM", range.get("mode"));
        assertEquals(true, range.get("regBeforeDataStart"));
        assertEquals(today, range.get("from"));
        assertTrue(String.valueOf(range.get("notice")).contains("目前為名單的時間範圍"));
        assertThrows(MonitorFieldException.class, () -> users.detail(q("u1", "REG_7")), "按注册日期整组不可用");
    }

    @Test
    void overviewHistoryAndTrend()
    {
        f.source.snapshots.add(RiskFixtures.m("userId", "u1", "first_dep_time", RiskFixtures.TODAY.minusDays(3) + " 11:00:00"));
        f.source.series.put(Kind.USER_DAY, List.of(RiskFixtures.m("date", today, "dep_amt", new BigDecimal("10000"), "wd_amt", new BigDecimal("4000"))));
        RiskUserQuery qq = q("u1", "CUSTOM");
        qq.setFrom(today);
        qq.setTo(today);
        Map<String, Object> d = users.detail(qq);
        assertEquals(false, d.get("empty"));
        Map<?, ?> header = (Map<?, ?>) d.get("header");
        assertEquals("3 天前", header.get("regText"));
        assertEquals("u9", header.get("parentId"));
        Map<?, ?> ov = (Map<?, ?>) d.get("overview");
        List<Map<String, Object>> metrics = (List<Map<String, Object>>) ov.get("metrics");
        assertEquals(new BigDecimal("10000"), metrics.stream().filter(m -> "dep".equals(m.get("code"))).findFirst().get().get("value"));
        Map<?, ?> trend = (Map<?, ?>) ov.get("trend");
        assertEquals("HOUR", trend.get("granularity"), "1 天按小时");
        List<Map<String, Object>> points = (List<Map<String, Object>>) trend.get("points");
        assertEquals(new BigDecimal("6000"), points.get(0).get("cumNet"));
        assertEquals("歷史累計 · 註冊至今", ov.get("historyTitle"));
    }

    @Test
    void fundsByStatusAndGames()
    {
        f.source.userOrders.put(Kind.DEPOSIT, List.of(
            RiskFixtures.m("orderNo", "D1", "orderAmount", new BigDecimal("500"), "orderStatus", "SUCCESS", "submitTime", today + " 10:00:00",
                "paidTime", today + " 10:01:00", "payMethod", "UPI"),
            RiskFixtures.m("orderNo", "D2", "orderAmount", new BigDecimal("300"), "orderStatus", "FAILED", "submitTime", today + " 11:00:00"),
            RiskFixtures.m("orderNo", "D3", "orderAmount", new BigDecimal("700"), "orderStatus", "SUCCESS", "submitTime", today + " 12:00:00",
                "paidTime", today + " 12:03:00")));
        RiskUserQuery qq = q("u1", "REG_7");
        qq.setType("DEPOSIT");
        Map<String, Object> out = users.funds(qq);
        Map<?, ?> summary = (Map<?, ?>) out.get("summary");
        assertEquals(new BigDecimal("1200"), summary.get("amount"), "金额只算成功的");
        assertEquals(2, summary.get("count"));
        assertEquals(120L, summary.get("avgDurationSeconds"));
        assertEquals(Map.of("SUCCESS", 2, "FAILED", 1, "PENDING", 0), summary.get("statusCounts"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        assertEquals(List.of("D3", "D2", "D1"), rows.stream().map(r -> r.get("orderNo")).toList(), "默认创建时间从新到旧");
        assertEquals("成功", rows.get(0).get("statusText"));

        qq.setStatuses(List.of("FAILED"));
        assertEquals(1, out.get("total") instanceof Integer ? ((List<?>) users.funds(qq).get("rows")).size() : -1);
        qq.setStatuses(List.of());
        qq.setSortCol("amount");
        qq.setSortDir("asc");
        assertEquals("D2", ((List<Map<String, Object>>) users.funds(qq).get("rows")).get(0).get("orderNo"));
        qq.setStatuses(List.of("NOPE"));
        assertThrows(MonitorFieldException.class, () -> users.funds(qq));

        Map<String, Object> g = users.games(q("u1", "REG_7"));
        List<Map<String, Object>> games = (List<Map<String, Object>>) g.get("rows");
        assertEquals(List.of("g1", "g2"), games.stream().map(x -> x.get("game")).toList(), "按投注从高到低");
        Map<?, ?> best = (Map<?, ?>) g.get("maxPayout");
        assertEquals("g1", best.get("game"));
        assertNull(((Map<?, ?>) g.get("summary")).get("maxBet"), "最大單注待接入");
    }
}
