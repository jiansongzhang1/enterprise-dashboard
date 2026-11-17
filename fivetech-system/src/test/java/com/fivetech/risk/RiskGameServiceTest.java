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
import com.fivetech.monitor.exception.MonitorFieldException;
import com.fivetech.risk.game.RiskGameService;
import com.fivetech.risk.rank.RiskSheet;

/**
 * 游戏分析（设计文档 2.3 / 7.1.4）：行维度校验、每层单独取数、计算列、排序、展开、游戏抽屉三块、平铺导出。
 */
class RiskGameServiceTest
{
    private RiskFixtures f;

    private RiskGameService games;

    @BeforeEach
    void setUp()
    {
        f = new RiskFixtures();
        games = new RiskGameService(f.props, f.catalog, f.source, f.config, f.rank, null);
        f.source.games.put("", RiskFixtures.m("bet_amt", new BigDecimal("1000"), "pay_amt", new BigDecimal("950"), "ggr", new BigDecimal("50"),
            "bet_users", new BigDecimal("30")));
        f.source.games.put("platform=PG", RiskFixtures.m("bet_amt", new BigDecimal("600"), "pay_amt", new BigDecimal("600"), "bet_users", new BigDecimal("20")));
        f.source.games.put("platform=JL", RiskFixtures.m("bet_amt", new BigDecimal("400"), "pay_amt", new BigDecimal("350"), "bet_users", new BigDecimal("15")));
        f.source.games.put("platform=PG|game=g1", RiskFixtures.m("bet_amt", new BigDecimal("500"), "pay_amt", new BigDecimal("520"),
            "bet_users", new BigDecimal("18"), "gameName", "麻將胡了"));
        f.source.games.put("platform=PG|game=g2", RiskFixtures.m("bet_amt", new BigDecimal("100"), "pay_amt", new BigDecimal("80"), "bet_users", new BigDecimal("5")));
    }

    private RiskQuery q()
    {
        RiskQuery q = f.query(null);
        q.setPath(List.of("platform", "game"));
        return q;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> out)
    {
        return (List<Map<String, Object>>) out.get("rows");
    }

    @Test
    @SuppressWarnings("unchecked")
    void firstLevelWithTotalAndStandardColumns()
    {
        Map<String, Object> out = games.table(q());
        assertEquals(List.of("PG", "JL"), rows(out).stream().map(r -> r.get("code")).toList(), "默认按第一个标准列（投注）从高到低");
        Map<String, Object> total = (Map<String, Object>) out.get("total");
        assertEquals(new BigDecimal("0.950000"), total.get("rtp"), "计算列：派彩 ÷ 投注");
        assertEquals(new BigDecimal("30"), total.get("betU"), "人数取 UDS 这一层的去重值，不是下级相加（20 + 15）");
        assertEquals(List.of("bet", "betU", "ggr", "rtp"), ((List<Map<String, Object>>) out.get("columns")).stream().map(c -> c.get("code")).toList());
        assertEquals("platform:PG", rows(out).get(0).get("key"));
        assertEquals(true, rows(out).get(0).get("hasChildren"));
    }

    @Test
    void expandReturnsOnlyNextLevel()
    {
        RiskQuery q = q();
        q.setExpandKey("platform:PG");
        q.setSortCol("rtp");
        q.setSortDir("desc");
        Map<String, Object> out = games.table(q);
        assertNull(out.get("total"), "展开时不返回合计");
        assertEquals(List.of("g1", "g2"), rows(out).stream().map(r -> r.get("code")).toList());
        assertEquals("麻將胡了", rows(out).get(0).get("name"));
        assertEquals("platform:PG|game:g1", rows(out).get(0).get("key"));
        assertEquals(false, rows(out).get(0).get("hasChildren"));
        assertTrue(f.source.gameQueries.contains("[platform, game] {platform=PG}"), "按上层限定取下一层：" + f.source.gameQueries);
    }

    @Test
    void pathRules()
    {
        RiskQuery q = q();
        q.setPath(List.of("game", "platform"));
        assertTrue(assertThrows(MonitorFieldException.class, () -> games.table(q)).getFields().get("path").contains("遊戲"));
        q.setPath(List.of("brand", "platform"));
        assertTrue(assertThrows(MonitorFieldException.class, () -> games.table(q)).getFields().get("path").contains("品牌"));
        q.setPath(List.of("platform", "platform"));
        assertThrows(MonitorFieldException.class, () -> games.table(q));
        RiskQuery bad = q();
        bad.setExpandKey("game:g1");
        assertThrows(MonitorFieldException.class, () -> games.table(bad), "展开的行与行维度不一致");
    }

    @Test
    @SuppressWarnings("unchecked")
    void drawerHasThreeBlocks()
    {
        f.source.gameTotals.put("g1", RiskFixtures.m("bet_amt", new BigDecimal("500"), "pay_amt", new BigDecimal("520"), "ggr", new BigDecimal("-20")));
        String today = RiskFixtures.TODAY.toString();
        f.source.gameDays.add(RiskFixtures.m("date", today, "bet_amt", new BigDecimal("100"), "pay_amt", new BigDecimal("90"), "ggr", new BigDecimal("10")));
        String d = RiskFixtures.TODAY.minusDays(1).toString();
        f.user("u1", "alice01", 3, null, 100, 0, 0);
        f.user("u2", "bob0002", 3, null, 100, 0, 0);
        f.play("u1", "g1", d, 1000, 5000, 4000, 10, d + " 10:00:00");
        f.play("u2", "g1", d, 800, 100, 50, 10, d + " 11:00:00");
        f.play("u2", "g9", d, 100, 0, 0, 0, null);
        RiskQuery q = f.query(null);
        q.setGameCode("g1");
        Map<String, Object> out = games.detail(q);
        assertEquals(new BigDecimal("1.040000"), ((Map<String, Object>) out.get("summary")).get("rtp"));
        List<Map<String, Object>> days = (List<Map<String, Object>>) out.get("days");
        assertEquals(7, days.size(), "固定 7 天，含今天");
        assertEquals(today, days.get(0).get("date"));
        assertEquals(true, days.get(0).get("today"));
        assertEquals(new BigDecimal("0.900000"), days.get(0).get("rtp"));
        assertEquals(BigDecimal.ZERO, days.get(1).get("bet"), "没有投注的日子为 0");
        assertNull(days.get(1).get("rtp"), "比率为空");
        List<Map<String, Object>> top = (List<Map<String, Object>>) out.get("topUsers");
        assertEquals(List.of("u1", "u2"), top.stream().map(u -> u.get("userId")).toList(), "常玩此游戏 = 投注最多的游戏是它，按玩家输赢排");
        assertEquals(new BigDecimal("4000"), top.get(0).get("winLoss"));
    }

    @Test
    void exportFlattensAllLevelsWithSubtotals()
    {
        RiskSheet sheet = games.export(q());
        assertEquals(List.of("層級", "遊戲平台", "遊戲", "有效投注", "投注人數", "GGR", "RTP"), sheet.getHeader());
        assertEquals(4, sheet.getRows().size());
        assertEquals("PG（小計）", sheet.getRows().get(0)[1]);
        assertEquals("麻將胡了", sheet.getRows().get(1)[2]);
        assertEquals("JL（小計）", sheet.getRows().get(3)[1]);
        assertEquals(0, new BigDecimal("104").compareTo((BigDecimal) sheet.getRows().get(1)[6]), "比率按百分数写");
    }
}
