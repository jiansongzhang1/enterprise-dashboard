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
import com.fivetech.monitor.exception.MonitorFieldException;
import com.fivetech.risk.config.RiskBoards;
import com.fivetech.risk.my.RiskMyBoard;
import com.fivetech.risk.rank.RiskSheet;

/**
 * 渠道风险榜（FY-71）、风险类型、我的榜单卡片模板（FY-68）、名单内搜索与「custom」条件（FY-67）。
 */
@SuppressWarnings("unchecked")
class RiskChannelBoardTest
{
    private RiskFixtures f;

    @BeforeEach
    void setUp()
    {
        f = new RiskFixtures();
        String d1 = RiskFixtures.TODAY.minusDays(2).toString();
        String d2 = RiskFixtures.TODAY.minusDays(1).toString();
        f.user("u1", "alice01", 3, "boss1", 10000, 60000, 2);
        f.user("u2", "bob0002", 30, null, 50000, 20000, 1);
        f.user("u3", "carol03", 5, "boss1", 2000, 0, 0);
        f.user("u4", "dave004", 100, null, 80000, 1000, 1);
        f.play("u1", "g1", d1, 1000, 70000, 60000, 100, d1 + " 12:00:00");
        f.play("u1", "g2", d2, 500, 300, 300, 50, d2 + " 08:00:00");
        f.play("u2", "g2", d1, 40000, 45000, 20000, 200, d1 + " 10:00:00");
        f.play("u2", "g3", d2, 10000, 20000, 20000, 100, d2 + " 09:00:00");
        f.play("u3", "g1", d1, 2000, 0, 0, 0, null);
        f.play("u4", "g2", d1, 100000, 95000, 5000, 10, d1 + " 15:00:00");
        f.play("u4", "g4", d2, 100000, 95000, 5000, 10, d1 + " 09:00:00");
        f.channel("u1", "A1", "c1");
        f.channel("u2", "A1", "c2");
        f.channel("u3", "A2", "c3");
        f.channel("u4", "A2", "c3");
        // c4 只有注册、没有活跃用户：也要列出（FY-71 R5）
        f.source.acquisition.add(RiskFixtures.m("agentId", "A2", "channelId", "c4", "regUsers", new BigDecimal("5"), "ftdUsers", BigDecimal.ZERO,
            "ftdAmt", BigDecimal.ZERO));
        f.source.acquisition.add(RiskFixtures.m("agentId", "A1", "channelId", "c1", "regUsers", new BigDecimal("3"), "ftdUsers", BigDecimal.ONE,
            "ftdAmt", new BigDecimal("500")));
    }

    private static List<Map<String, Object>> list(Object o)
    {
        return (List<Map<String, Object>>) o;
    }

    @Test
    void highRtpRiskTypeAlsoNeedsRtpAtLeast100()
    {
        RiskQuery q = f.query("all");
        RiskQuery.Condition c = new RiskQuery.Condition();
        c.setField("riskType");
        c.setValues(List.of("highRtp"));
        q.setConditions(List.of(c));
        List<String> ids = new ArrayList<>();
        list(f.rank.list(q, 1L).get("rows")).forEach(r -> ids.add(String.valueOf(r.get("userId"))));
        assertEquals(List.of("u2"), ids, "有效投注 ≥ 20000 的 u2、u4 里只有 u2 的 RTP ≥ 100%");
    }

    @Test
    void channelCardUsesSystemDefaultRiskTypes()
    {
        RiskQuery q = f.query(null);
        q.setBoards(List.of("chrisk"));
        Map<String, Object> card = list(f.rank.cards(q, 1L).get("cards")).get(0);
        assertEquals("CHANNEL", card.get("group"));
        assertEquals(List.of("newWin", "lowTurn", "bigWin"), ((Map<?, ?>) card.get("riskTypes")).get("selected"), "系统默认");
        List<Map<String, Object>> stats = list(card.get("stats"));
        assertEquals(new BigDecimal("2"), stats.get(0).get("value"), "風險渠道：c1、c2");
        assertEquals(new BigDecimal("2"), stats.get(1).get("value"), "風險用戶：u1、u2");
        assertEquals(new BigDecimal("0.500000"), stats.get(1).get("activeShare"), "佔活躍用戶 2 ÷ 4");
        assertEquals(new BigDecimal("80000"), stats.get(2).get("value"), "風險用戶提款 60000 + 20000");
        List<Map<String, Object>> comp = list(card.get("composition"));
        assertEquals(List.of(BigDecimal.ONE, new BigDecimal("2"), new BigDecimal("2")), comp.stream().map(x -> x.get("users")).toList(),
            "新號盈利 1、低打碼提款 2（u1、u2）、大額中獎 2");
        assertEquals(List.of("c1", "c2"), list(card.get("top")).stream().map(x -> x.get("channelId")).toList());
    }

    @Test
    void channelListIsATreeFilteredAtChannelLevel()
    {
        Map<String, Object> out = f.rank.list(f.query("chrisk"), 1L);
        List<Map<String, Object>> rows = list(out.get("rows"));
        assertEquals(List.of("A1", "A2"), rows.stream().map(r -> r.get("agentId")).toList(), "推广商按风险用户提款从高到低");
        assertEquals(List.of("c1", "c2"), list(rows.get(0).get("children")).stream().map(r -> r.get("channelId")).toList());
        assertEquals(4, out.get("total"), "c1、c2、c3 + 只有注册的 c4");
        List<String> cols = list(out.get("columns")).stream().map(c -> String.valueOf(c.get("code"))).toList();
        assertEquals("regUsers", cols.get(0), "註冊用戶固定第一列");
        assertTrue(cols.containsAll(List.of("risk:newWin", "risk:lowTurn", "risk:bigWin")), "各風險類型人數按所选类型展开");
        Map<String, Object> c1 = list(rows.get(0).get("children")).get(0);
        assertEquals(new BigDecimal("3"), ((Map<?, ?>) c1.get("values")).get("regUsers"));
        assertEquals(true, c1.get("drillable"));
        Map<String, Object> c4 = list(rows.get(1).get("children")).stream().filter(r -> "c4".equals(r.get("channelId"))).findFirst().get();
        assertEquals(false, c4.get("drillable"), "没有风险用户的渠道名称不可下钻");
        Map<?, ?> totals = (Map<?, ?>) out.get("totals");
        assertEquals(new BigDecimal("0.500000"), totals.get("riskRate"), "比率按合计重算");
        assertEquals(new BigDecimal("8"), totals.get("regUsers"));

        RiskQuery q = f.query("chrisk");
        q.setConditions(List.of(RiskRankServiceTest.cond("riskUsers", "1", null)));
        Map<String, Object> filtered = f.rank.list(q, 1L);
        assertEquals(2, filtered.get("total"));
        assertEquals(List.of("A1"), list(filtered.get("rows")).stream().map(r -> r.get("agentId")).toList(), "一个渠道都不剩的推广商不显示");
        assertEquals(true, filtered.get("modified"));

        RiskQuery bad = f.query("chrisk");
        bad.setConditions(List.of(RiskRankServiceTest.cond("ftdAmt", "1", null)));
        assertThrows(MonitorFieldException.class, () -> f.rank.list(bad, 1L), "首存金額不在 11 项条件里");
        RiskQuery rank = f.query("chrisk");
        rank.setRankCol("dep");
        assertThrows(MonitorFieldException.class, () -> f.rank.list(rank, 1L));

        RiskQuery mine = f.query("chrisk");
        mine.setRiskTypes(List.of("bigWin"));
        Map<?, ?> rt = (Map<?, ?>) f.rank.list(mine, 1L).get("riskTypes");
        assertEquals(List.of("bigWin"), rt.get("selected"));

        RiskSheet sheet = f.rank.export(f.query("chrisk"), 1L);
        assertEquals("合計（4 個渠道）", sheet.getRows().get(0)[0]);
        assertTrue(sheet.title.contains("風險用戶＝新號盈利 / 低打碼提款 / 大額中獎"), sheet.title);
    }

    @Test
    void acquisitionPendingWhenNotConnected()
    {
        f.source.on.remove(com.fivetech.risk.data.RiskDataSource.Kind.CHANNEL_ACQ);
        Map<String, Object> out = f.rank.list(f.query("chrisk"), 1L);
        assertEquals(true, out.get("acquisitionPending"));
        Map<String, Object> reg = list(out.get("columns")).get(0);
        assertEquals("註冊、首存數據待接入", reg.get("note"));
        assertNull(((Map<?, ?>) out.get("totals")).get("regUsers"));
        assertEquals(3, out.get("total"));
    }

    @Test
    void drillDownListsRiskUsersByWithdrawal()
    {
        RiskQuery q = f.query("chrisk");
        RiskQuery.Drill d = new RiskQuery.Drill();
        d.setRiskType("lowTurn");
        q.setDrill(d);
        q.setRankCol("bet");
        Map<String, Object> out = f.rank.list(q, 1L);
        List<String> ids = list(out.get("rows")).stream().map(r -> String.valueOf(r.get("userId"))).toList();
        assertEquals(List.of("u1", "u2"), ids, "全部渠道的低打碼提款，固定按提款从高到低");
        assertEquals("wd", out.get("rankCol"), "排行字段固定");
        assertEquals(false, out.get("shareAvailable"), "下钻名单不显示占比");
        assertEquals(List.of("wd", "dep", "turnX", "winLoss", "regTime"),
            list(out.get("columns")).stream().map(c -> c.get("code")).toList(), "默认列 存款、打碼倍數、玩家輸贏、註冊時間");

        RiskQuery one = f.query("chrisk");
        RiskQuery.Drill c2 = new RiskQuery.Drill();
        c2.setChannelId("c2");
        one.setDrill(c2);
        assertEquals(1, f.rank.list(one, 1L).get("total"));

        RiskQuery outside = f.query("chrisk");
        RiskQuery.Drill x = new RiskQuery.Drill();
        x.setRiskType("highRtp");
        outside.setDrill(x);
        assertThrows(MonitorFieldException.class, () -> f.rank.list(outside, 1L), "下钻的类型要在所选风险类型里");

        RiskQuery search = f.query("chrisk");
        search.setKeyword("u1");
        assertThrows(MonitorFieldException.class, () -> f.rank.search(search, 1L), "渠道列表只搜全部用户");
        search.setDrill(d);
        assertEquals(1, list(f.rank.search(search, 1L).get("items")).size(), "下钻后可以搜名单内");
    }

    @Test
    void riskTypesFollowPersonalThenGlobalThenSystem()
    {
        RiskBoards cfg = f.config.boards(null);
        assertEquals("SYSTEM", cfg.channelRiskTypes(null)[1]);
        assertEquals("PERSONAL", cfg.channelRiskTypes(List.of("highRtp", "nope"))[1], "只认系统 4 类");
        assertEquals("SYSTEM", cfg.channelRiskTypes(List.of("nope"))[1], "去掉后一类都不剩：按下一级");
        f.config.saveThresholds(null, Map.of("channelRiskTypes", List.of("bigWin", "highRtp")), "risk@x");
        RiskBoards saved = f.config.boards(null);
        assertEquals(List.of("highRtp", "bigWin"), saved.channelRiskTypes(null)[0], "全局默认");
        assertEquals("GLOBAL", saved.channelRiskTypes(null)[1]);
        assertEquals(new BigDecimal("7"), saved.thresholds.get("newDays"), "门槛不受影响");
        f.config.saveThresholds(null, Map.of("newDays", 10), "risk@x");
        assertEquals(List.of("highRtp", "bigWin"), f.config.boards(null).channelRiskGlobal, "只改门槛时保留全局风险类型");
        assertThrows(MonitorFieldException.class, () -> f.config.saveThresholds(null, Map.of("channelRiskTypes", List.of()), "risk@x"));

        RiskBoards d = f.config.defaults(null);
        assertEquals(new BigDecimal("7"), d.thresholds.get("newDays"), "默认值不跟着改过的值走");
        assertEquals(List.of("newWin", "lowTurn", "bigWin"), d.channelGlobalOrDefault(), "全站本身的默认 = 系统默认");
        assertEquals(new BigDecimal("10"), f.config.boards(null).thresholds.get("newDays"));

        f.rank.setPersonalRiskTypes(() -> List.of("newWin"));
        assertEquals(List.of("newWin"), f.rank.riskTypes(new RiskQuery(), f.config.boards(null)));
    }

    @Test
    void myBoardCardTemplates()
    {
        RiskMyBoard b = new RiskMyBoard();
        b.setBoardId(7L);
        b.setUserId(1L);
        b.setBoardName("大戶");
        b.setConditions("[{\"field\":\"dep\",\"min\":20000}]");
        b.setRankCol("dep");
        b.setRankDir("desc");
        f.boards.put(7L, b);
        Map<String, Object> list = f.rank.list(f.query("m:7"), 1L);
        assertEquals(true, list.get("shareAvailable"), "模板 A：金额类从高到低，算占比");
        assertEquals(new BigDecimal("0.615385"), list(list.get("rows")).get(0).get("share"), "分母 = 本榜合计 130000");
        RiskQuery q = f.query(null);
        q.setBoards(List.of("m:7"));
        Map<String, Object> card = list(f.rank.cards(q, 1L).get("cards")).get(0);
        assertEquals("MY", card.get("group"));
        assertEquals("A", card.get("template"));
        List<Map<String, Object>> stats = list(card.get("stats"));
        assertEquals("合計存款", stats.get(1).get("label"));
        assertEquals(new BigDecimal("0.915493"), stats.get(2).get("value"), "佔全部 130000 ÷ 142000");

        b.setRankCol("rtp");
        Map<String, Object> rtp = list(f.rank.cards(q, 1L).get("cards")).get(0);
        assertEquals("B", rtp.get("template"));
        assertFalse(rtp.containsKey("bands"));
        assertTrue(list(rtp.get("top")).get(0).containsKey("diff"), "Top 5 写比全部用户高或低多少");

        b.setRankCol("wdN");
        assertEquals("C", list(f.rank.cards(q, 1L).get("cards")).get(0).get("template"));
    }

    @Test
    void searchInListMatchesAccountOrIdPrefix()
    {
        RiskQuery q = f.query("all");
        q.setKeyword("00");
        List<Map<String, Object>> items = list(f.rank.search(q, 1L).get("items"));
        assertEquals(List.of("u2", "u4"), items.stream().map(i -> i.get("userId")).sorted().toList(), "账号包含 00");
        assertTrue(items.get(0).containsKey("rank") && items.get(0).containsKey("pageNum"));
        q.setKeyword("u3");
        assertEquals(1, list(f.rank.search(q, 1L).get("items")).size(), "用户 ID 开头");
        q.setKeyword("x");
        assertThrows(MonitorFieldException.class, () -> f.rank.search(q, 1L), "至少 2 个字");
    }

    @Test
    void optionsGiveGroupsAndPrefilledConditions()
    {
        RiskOptionsService opts = new RiskOptionsService(f.catalog, f.config, f.source, null, null);
        RiskBoards cfg = f.config.boards(null);
        List<Map<String, Object>> pnl = opts.defaultConditions(cfg.board("pnl"), cfg);
        assertEquals(new BigDecimal("0.01"), pnl.get(0).get("min"), "「> 0」预填最小值 0.01");
        List<Map<String, Object>> bigw = opts.defaultConditions(cfg.board("bigw"), cfg);
        assertEquals("amount", bigw.get(0).get("field"));
        assertEquals(new BigDecimal("50000"), bigw.get(0).get("min"));
        List<Map<String, Object>> nb = opts.defaultConditions(cfg.board("new"), cfg);
        assertEquals(new BigDecimal("7"), nb.get(0).get("max"));
        assertTrue(opts.defaultConditions(cfg.board("all"), cfg).isEmpty(), "全部用户没有默认条件");
    }
}
