package com.fivetech.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.exception.MonitorFieldException;
import com.fivetech.risk.data.RiskDataSource;
import com.fivetech.risk.my.RiskMyBoard;
import com.fivetech.risk.rank.RiskSheet;

/**
 * 风控排行榜（设计文档 2.2 / 7.1.2）：入榜、名次与占比、排行字段与表格排序分开、名单条件、Top X、定位、
 * 单笔最大派彩那一笔的取值规则、盈利游戏、卡片、大额提款榜（临时门槛、风险特征）、我的榜单、导出。
 */
class RiskRankServiceTest
{
    private RiskFixtures f;

    private String d1;

    private String d2;

    @BeforeEach
    void setUp()
    {
        f = new RiskFixtures();
        d1 = RiskFixtures.TODAY.minusDays(2).toString();
        d2 = RiskFixtures.TODAY.minusDays(1).toString();
        f.user("u1", "alice01", 3, "boss1", 10000, 60000, 2);
        f.user("u2", "bob0002", 30, null, 50000, 20000, 1);
        f.user("u3", "carol03", 5, "boss1", 2000, 0, 0);
        f.user("u4", "dave004", 100, null, 80000, 1000, 1);
        f.play("u1", "g1", d1, 1000, 70000, 60000, 100, d1 + " 12:00:00");
        f.play("u1", "g2", d2, 500, 300, 300, 50, d2 + " 08:00:00");
        // u2：两笔派彩同为 20000，派彩倍数高的（投注额小的）那笔优先
        f.play("u2", "g2", d1, 40000, 45000, 20000, 200, d1 + " 10:00:00");
        f.play("u2", "g3", d2, 10000, 20000, 20000, 100, d2 + " 09:00:00");
        f.play("u3", "g1", d1, 2000, 0, 0, 0, null);
        // u4：派彩、倍数都相同，取结算时间早的
        f.play("u4", "g2", d1, 100000, 95000, 5000, 10, d1 + " 15:00:00");
        f.play("u4", "g4", d2, 100000, 95000, 5000, 10, d1 + " 09:00:00");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> out)
    {
        return (List<Map<String, Object>>) out.get("rows");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> values(Map<String, Object> row)
    {
        return (Map<String, Object>) row.get("values");
    }

    private static List<String> ids(Map<String, Object> out)
    {
        List<String> ids = new ArrayList<>();
        rows(out).forEach(r -> ids.add(String.valueOf(r.get("userId"))));
        return ids;
    }

    @Test
    void pnlRanksByWinLossWithShareOfEntry()
    {
        Map<String, Object> out = f.rank.list(f.query("pnl"), 1L);
        assertEquals(List.of("u1", "u2"), ids(out), "入榜：玩家输赢 > 0，按玩家输赢从高到低");
        assertEquals("winLoss", out.get("rankCol"));
        assertEquals("desc", out.get("rankDir"));
        assertEquals(true, out.get("shareAvailable"));
        Map<String, Object> first = rows(out).get(0);
        assertEquals(1, first.get("rank"));
        assertEquals("al***01", first.get("account"), "账号打码");
        assertEquals(new BigDecimal("0.821002"), first.get("share"), "68800 ÷ 83800（入榜合计）");
        assertEquals(new BigDecimal("1.000000"), rows(out).get(1).get("cumShare"));
        assertEquals(new BigDecimal("68800"), values(first).get("winLoss"));
        assertEquals(List.of("winLoss", "bet", "rtp", "net", "regTime", "channel"),
            ((List<Map<String, Object>>) (List<?>) out.get("columns")).stream().map(c -> c.get("code")).toList(), "默认列来自榜单设置");
        assertEquals(new BigDecimal("83800"), ((Map<?, ?>) out.get("totals")).get("winLoss"));
        assertEquals(new BigDecimal("2.627184"), ((Map<?, ?>) out.get("totals")).get("rtp"), "比率合计 = 合计派彩 ÷ 合计投注");
    }

    @Test
    void rankFieldAndTableSortAreSeparate()
    {
        RiskQuery q = f.query("all");
        q.setRankCol("bet");
        q.setViewSortCol("dep");
        q.setViewSortDir("asc");
        Map<String, Object> out = f.rank.list(q, 1L);
        assertEquals(List.of("u3", "u1", "u2", "u4"), ids(out), "按存款从低到高显示");
        assertEquals(List.of(3, 4, 2, 1), rows(out).stream().map(r -> r.get("rank")).toList(), "名次按排行字段（投注），跟着人走");
        List<?> cols = ((List<Map<String, Object>>) (List<?>) out.get("columns")).stream().map(c -> c.get("code")).toList();
        assertEquals("bet", cols.get(0), "排行字段在最前");
        assertEquals("winLoss", cols.get(cols.size() - 1), "原排行字段保留在最后");
        assertFalse((Boolean) out.get("shareAvailable"), "全部用户不算占比");
    }

    @Test
    void conditionsAndRiskTypeAndTopXAndLocate()
    {
        RiskQuery q = f.query("all");
        RiskQuery.Condition c = new RiskQuery.Condition();
        c.setField("dep");
        c.setMin(new BigDecimal("20000"));
        q.setConditions(List.of(c));
        q.setRankCol("dep");
        assertEquals(List.of("u4", "u2"), ids(f.rank.list(q, 1L)));

        RiskQuery pnl = f.query("pnl");
        pnl.setConditions(List.of(c));
        Map<String, Object> changed = f.rank.list(pnl, 1L);
        assertEquals(true, changed.get("modified"));
        assertEquals(true, changed.get("shareAvailable"), "改过条件、排行仍是玩家输赢（入榜要求 > 0）从高到低：照样算占比");
        assertEquals(new BigDecimal("1.000000"), rows(changed).get(0).get("share"), "分母 = 当前名单合计（只剩 u2）");

        RiskQuery loose = f.query("pnl");
        loose.setCustom(true);
        loose.setConditions(List.of(c));
        Map<String, Object> replaced = f.rank.list(loose, 1L);
        assertEquals(List.of("u4", "u2"), ids(replaced).stream().sorted(java.util.Comparator.reverseOrder()).toList(),
            "custom：名单条件替换入榜条件（不再要求玩家输赢 > 0）");
        assertEquals(false, replaced.get("shareAvailable"), "玩家输赢没有限定 > 0，不算占比");

        RiskQuery t = f.query("all");
        RiskQuery.Condition rt = new RiskQuery.Condition();
        rt.setField("riskType");
        rt.setValues(List.of("newWin"));
        t.setConditions(List.of(rt));
        assertEquals(List.of("u1"), ids(f.rank.list(t, 1L)), "风险类型 = 命中新号榜的入榜条件");

        RiskQuery sel = f.query("all");
        RiskQuery.Condition g = new RiskQuery.Condition();
        g.setField("winGame");
        g.setValues(List.of("g3", "g4"));
        sel.setConditions(List.of(g));
        assertEquals(List.of("u2", "u4"), ids(f.rank.list(sel, 1L)).stream().sorted().toList(), "选择条件命中任一");

        RiskQuery top = f.query("all");
        top.setTopX(2);
        assertEquals(2, f.rank.list(top, 1L).get("total"));

        RiskQuery loc = f.query("all");
        loc.setPageSize(20);
        loc.setLocateKey("BOB0002");
        Map<?, ?> found = (Map<?, ?>) f.rank.list(loc, 1L).get("locate");
        assertEquals(true, found.get("found"));
        assertEquals(1, found.get("pageNum"));
    }

    @Test
    void maxPayoutColumnsComeFromTheSameBet()
    {
        RiskQuery q = f.query("all");
        q.setCols(List.of("maxPayout", "maxPayoutBet", "winTime", "winGame", "payoutX", "profitGame"));
        Map<String, Map<String, Object>> byUser = new java.util.HashMap<>();
        rows(f.rank.list(q, 1L)).forEach(r -> byUser.put(String.valueOf(r.get("userId")), values(r)));
        assertEquals("g3", byUser.get("u2").get("winGame"), "派彩并列：取派彩倍数高的");
        assertEquals(new BigDecimal("100"), byUser.get("u2").get("maxPayoutBet"));
        assertEquals(d2 + " 09:00", byUser.get("u2").get("winTime"));
        assertEquals(new BigDecimal("200.000000"), byUser.get("u2").get("payoutX"));
        assertEquals("g4", byUser.get("u4").get("winGame"), "倍数也相同：取结算时间早的");
        assertNull(byUser.get("u3").get("winGame"), "没有派彩时为空");
        assertEquals("g1", byUser.get("u1").get("profitGame"));
        assertEquals("g3", byUser.get("u2").get("profitGame"), "按游戏汇总输赢，取最大的");
        assertNull(byUser.get("u3").get("profitGame"), "都亏 = 空");
    }

    @Test
    @SuppressWarnings("unchecked")
    void cardsFollowBoardConfig()
    {
        RiskQuery q = f.query(null);
        q.setBoards(List.of("pnl", "win", "rtp", "wd", "new"));
        Map<String, Object> out = f.rank.cards(q, 1L);
        List<Map<String, Object>> cards = (List<Map<String, Object>>) out.get("cards");
        Map<String, Object> pnl = cards.get(0);
        assertEquals("USER", pnl.get("group"));
        assertEquals(2, ((Map<?, ?>) pnl.get("summary")).get("users"));
        assertEquals(new BigDecimal("83800"), ((Map<?, ?>) pnl.get("summary")).get("amount"));
        List<Map<String, Object>> stats = (List<Map<String, Object>>) pnl.get("stats");
        assertEquals(List.of("入榜", "合計", "佔活躍用戶"), stats.stream().map(x -> x.get("label")).toList());
        assertEquals(new BigDecimal("0.500000"), stats.get(2).get("value"), "2 人 ÷ 活跃 4 人");
        List<Map<String, Object>> bands = (List<Map<String, Object>>) pnl.get("bands");
        assertEquals(List.of(1, 5, 10), bands.stream().map(b -> b.get("top")).toList(), "默认占比档位");
        assertEquals(new BigDecimal("0.821002"), bands.get(0).get("share"));

        Map<String, Object> win = cards.get(1);
        List<Map<String, Object>> dist = (List<Map<String, Object>>) win.get("distribution");
        assertEquals("winGame", win.get("distCol"));
        assertEquals(List.of("g1", "g3"), dist.stream().map(d -> d.get("code")).toList(), "中奖游戏分布：u1 中 g1、u2 中 g3");
        List<Map<String, Object>> ws = (List<Map<String, Object>>) win.get("stats");
        assertEquals(new BigDecimal("60000"), ws.get(1).get("value"), "最高派彩");
        assertEquals(new BigDecimal("600.000000"), ws.get(2).get("value"), "最高倍數");

        Map<String, Object> rtp = cards.get(2);
        assertEquals("profitGame", rtp.get("distCol"));
        List<Map<String, Object>> rs = (List<Map<String, Object>>) rtp.get("stats");
        assertEquals(BigDecimal.ONE, rs.get(1).get("value"), "RTP ≥ 100% 的人數：u2");
        assertEquals(new BigDecimal("5000"), rs.get(2).get("value"), "輸贏合計：15000 - 10000");

        List<Map<String, Object>> wds = (List<Map<String, Object>>) cards.get(3).get("stats");
        assertEquals(new BigDecimal("0.578571"), wds.get(2).get("value"), "提存比 = 入榜用户提款 81000 ÷ 存款 140000");
        List<Map<String, Object>> ns = (List<Map<String, Object>>) cards.get(4).get("stats");
        assertEquals(new BigDecimal("0.821002"), ns.get(2).get("value"), "佔全部盈利 = 新号盈利 68800 ÷ 全部盈利 83800");

        RiskQuery def = f.query(null);
        List<Map<String, Object>> defaults = (List<Map<String, Object>>) f.rank.cards(def, 1L).get("cards");
        assertEquals(List.of("pnl", "wd", "new", "low", "win", "bigw"), defaults.stream().map(c -> c.get("board")).toList(), "默认 6 张");
    }

    @Test
    void bigWithdrawalBoardFollowsFy70()
    {
        f.source.orders.add(RiskFixtures.m("orderNo", "W1", "userId", "u1", "account", "alice01", "orderAmount", new BigDecimal("80000"),
            "submitTime", d1 + " 10:00:00"));
        f.source.orders.add(RiskFixtures.m("orderNo", "W2", "userId", "u2", "account", "bob0002", "orderAmount", new BigDecimal("30000"),
            "submitTime", d1 + " 11:00:00"));
        f.source.orders.add(RiskFixtures.m("orderNo", "W3", "userId", "u4", "account", "dave004", "orderAmount", new BigDecimal("55000"),
            "submitTime", d2 + " 10:00:00"));
        f.source.orders.add(RiskFixtures.m("orderNo", "W4", "userId", "u1", "account", "alice01", "orderAmount", new BigDecimal("55000"),
            "submitTime", d1 + " 09:00:00"));
        Map<String, Object> out = f.rank.list(f.query("bigw"), 1L);
        assertEquals(List.of("W1", "W3", "W4"), rows(out).stream().map(r -> r.get("orderNo")).toList(), "按金额，同额按申请时间晚的在前");
        assertEquals(new BigDecimal("50000"), f.source.lastMin, "门槛 = 榜单条件");
        assertEquals(new BigDecimal("3"), rows(out).get(0).get("regDays"));
        assertEquals(List.of("turnX"), ((List<Map<String, Object>>) (List<?>) out.get("columns")).stream().map(c -> c.get("code")).toList(),
            "默认列只有打碼倍數");
        assertEquals(false, out.get("modified"));

        RiskQuery temp = f.query("bigw");
        temp.setConditions(List.of(cond("amount", "20000", null)));
        assertEquals(4, f.rank.list(temp, 1L).get("total"), "名单里改单笔提款，只对这次查询生效");

        RiskQuery fresh = f.query("bigw");
        fresh.setConditions(List.of(cond("regDays", null, "7")));
        assertEquals(List.of("W1", "W4"), rows(f.rank.list(fresh, 1L)).stream().map(r -> r.get("orderNo")).toList(),
            "用户字段按订单所属用户判断");

        RiskQuery dep = f.query("bigw");
        dep.setConditions(List.of(cond("dep", "50000", null)));
        assertEquals(List.of("W3"), rows(f.rank.list(dep, 1L)).stream().map(r -> r.get("orderNo")).toList());

        RiskQuery low = f.query("bigw");
        low.setConditions(List.of(cond("amount", "500", null)));
        assertTrue(assertThrows(MonitorFieldException.class, () -> f.rank.list(low, 1L)).getFields().get("conditions").contains("單筆提款"));
        RiskQuery noMin = f.query("bigw");
        noMin.setCustom(true);
        assertThrows(MonitorFieldException.class, () -> f.rank.list(noMin, 1L), "最小值必填");
        RiskQuery bad = f.query("bigw");
        bad.setConditions(List.of(cond("bet", "1", null)));
        assertThrows(MonitorFieldException.class, () -> f.rank.list(bad, 1L), "只能用 filters 里的字段");
        RiskQuery badCol = f.query("bigw");
        badCol.setCols(List.of("bet"));
        assertThrows(MonitorFieldException.class, () -> f.rank.list(badCol, 1L), "只能加存款、玩家輸贏、打碼倍數");

        RiskQuery loc = f.query("bigw");
        loc.setLocateKey("alice01");
        Map<?, ?> found = (Map<?, ?>) f.rank.list(loc, 1L).get("locate");
        assertEquals(0, found.get("index"), "按账号合并，定位到最近一笔（W1）");
        assertEquals(2, found.get("count"));

        RiskQuery s = f.query("bigw");
        s.setKeyword("al");
        List<Map<String, Object>> items = (List<Map<String, Object>>) f.rank.search(s, 1L).get("items");
        assertEquals(1, items.size());
        assertEquals(2, items.get(0).get("count"), "N 筆大額提款");
        assertEquals("W1", items.get(0).get("orderNo"));

        Map<String, Object> card = ((List<Map<String, Object>>) (List<?>) f.rank.cards(withBoards("bigw"), 1L).get("cards")).get(0);
        assertEquals("ORDER", card.get("group"));
        List<Map<String, Object>> st = (List<Map<String, Object>>) card.get("stats");
        assertEquals(new BigDecimal("3"), st.get(0).get("value"));
        assertEquals(new BigDecimal("190000"), st.get(1).get("value"));
    }

    private RiskQuery withBoards(String... codes)
    {
        RiskQuery q = f.query(null);
        q.setBoards(List.of(codes));
        return q;
    }

    static RiskQuery.Condition cond(String field, String min, String max)
    {
        RiskQuery.Condition c = new RiskQuery.Condition();
        c.setField(field);
        c.setMin(min == null ? null : new BigDecimal(min));
        c.setMax(max == null ? null : new BigDecimal(max));
        return c;
    }

    @Test
    void myBoardUsesSavedConditionsAndBecomesUnavailable()
    {
        RiskMyBoard b = new RiskMyBoard();
        b.setBoardId(7L);
        b.setUserId(1L);
        b.setBoardName("大戶");
        b.setConditions("[{\"field\":\"dep\",\"min\":20000}]");
        b.setRankCol("dep");
        b.setRankDir("desc");
        f.boards.put(7L, b);
        assertEquals(List.of("u4", "u2"), ids(f.rank.list(f.query("m:7"), 1L)));
        assertThrows(MonitorException.class, () -> f.rank.list(f.query("m:7"), 2L), "别人的榜单 404");

        b.setConditions("[{\"field\":\"lastLoginDays\",\"max\":3}]");
        MonitorFieldException e = assertThrows(MonitorFieldException.class, () -> f.rank.list(f.query("m:7"), 1L));
        assertTrue(e.getFields().get("board").contains("已下線"));
        RiskQuery q = f.query(null);
        q.setBoards(List.of("m:7"));
        Map<?, ?> card = (Map<?, ?>) ((List<?>) f.rank.cards(q, 1L).get("cards")).get(0);
        assertEquals(true, card.get("unavailable"), "卡片置灰");
    }

    @Test
    void validation()
    {
        RiskQuery notRankable = f.query("all");
        notRankable.setRankCol("maxPayoutBet");
        assertTrue(assertThrows(MonitorFieldException.class, () -> f.rank.list(notRankable, 1L)).getFields().containsKey("rankCol"),
            "该笔投注额不能作排行字段");
        RiskQuery page = f.query("all");
        page.setPageSize(30);
        assertTrue(assertThrows(MonitorFieldException.class, () -> f.rank.list(page, 1L)).getFields().containsKey("pageSize"));
        RiskQuery cond = f.query("all");
        RiskQuery.Condition c = new RiskQuery.Condition();
        c.setField("topGame");
        c.setValues(List.of("g1"));
        cond.setConditions(List.of(c));
        assertTrue(assertThrows(MonitorFieldException.class, () -> f.rank.list(cond, 1L)).getFields().containsKey("conditions"),
            "不能做名单条件的字段");
        RiskQuery time = f.query("all");
        time.setSlotTo(time.getSlotFrom());
        assertTrue(assertThrows(MonitorFieldException.class, () -> f.rank.list(time, 1L)).getFields().containsKey("slotTo"));
    }

    @Test
    void pendingWhenUserDataNotConnected()
    {
        f.source.on.remove(RiskDataSource.Kind.USER_DAY);
        Map<String, Object> out = f.rank.list(f.query("pnl"), 1L);
        assertEquals(true, out.get("dataPending"));
        assertEquals(0, out.get("total"));
    }

    @Test
    void gameColumnsMarkedWhenUserGameNotConnected()
    {
        f.source.on.remove(RiskDataSource.Kind.USER_GAME);
        RiskQuery q = f.query("all");
        q.setRankCol("dep");
        q.setCols(List.of("bet", "wd"));
        Map<String, Object> out = f.rank.list(q, 1L);
        Map<?, ?> bet = ((List<Map<?, ?>>) (List<?>) out.get("columns")).stream().filter(c -> "bet".equals(c.get("code"))).findFirst().get();
        assertEquals("遊戲數據待接入", bet.get("note"));
        assertNull(values(rows(out).get(0)).get("bet"));
        assertEquals(new BigDecimal("1000"), values(rows(out).get(0)).get("wd"));
    }

    @Test
    void exportHasRankColumnAndTotalsFirst()
    {
        RiskSheet sheet = f.rank.export(f.query("pnl"), 1L);
        assertEquals("名次", sheet.getHeader().get(0));
        assertTrue(sheet.title.contains("排行：玩家輸贏 從高到低"), sheet.title);
        assertEquals("合計（2 人）", sheet.getRows().get(0)[0]);
        assertEquals(new BigDecimal("1"), sheet.getRows().get(1)[0]);
        assertEquals("alice01", sheet.getRows().get(1)[1], "导出不打码");
        assertTrue(sheet.getHeader().contains("佔比"));
        assertTrue(sheet.xlsx().length > 0);

        RiskQuery viewed = f.query("pnl");
        viewed.setViewSortCol("bet");
        assertTrue(f.rank.export(viewed, 1L).title.contains("表格按有效投注從高到低排列"));
    }

    @Test
    void cacheReusesComputedUsers()
    {
        f.props.setCacheSeconds(60);
        f.rank.list(f.query("pnl"), 1L);
        f.rank.list(f.query("wd"), 1L);
        assertEquals(1, f.source.userDayCalls, "同一筛选只取一次数");
    }
}
