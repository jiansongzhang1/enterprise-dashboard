package com.fivetech.promo.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.domain.vo.OverviewCompareVO;
import com.fivetech.dashboard.enums.Granularity;
import com.fivetech.promo.analysis.query.ChannelTableQuery.PathItem;
import com.fivetech.promo.budget.BudgetDataReader;
import com.fivetech.promo.budget.BudgetKind;
import com.fivetech.promo.budget.PromoChannelDictionary;

/** 推广分析 · 消耗 / 预算接入与成本类指标（有效格子）：不连库、不调 UDS */
class PromoSpendCostTest
{
    private static final List<PromoChannelDictionary.Promoter> DICT = List.of(
        new PromoChannelDictionary.Promoter("1001", "hada", List.of(
            new PromoChannelDictionary.Channel("2001", "DSP-hada", "DSP", "ACTIVE", "LANDING"),
            new PromoChannelDictionary.Channel("2002", "meta-hada", null, "ACTIVE", "MAIN"))),
        new PromoChannelDictionary.Promoter("1002", "jason", List.of(new PromoChannelDictionary.Channel("2003", "TG-jason"))));

    private static final PromoTerminalDictionary.Config TERMINALS = new PromoTerminalDictionary.Config(List.of(
        new PromoTerminalDictionary.Terminal("android", "Android", List.of())), PromoTerminalDictionary.DEFAULT_LANDING_MIN_PCT);

    private static final LocalDate D1 = LocalDate.of(2026, 11, 1);

    private static final LocalDate D2 = LocalDate.of(2026, 11, 2);

    private static BudgetDataReader.Row row(LocalDate d, String promoter, String channel, String amount)
    {
        return new BudgetDataReader.Row(d, promoter, "p" + promoter, channel, "c" + channel, new BigDecimal(amount));
    }

    /** 消耗：11-01 2001=100、2002=0、2003=40；11-02 2001=50。预算：11-01 2001=200 */
    private static PromoSpend.Cube cube()
    {
        return new PromoSpend.Cube(
            List.of(row(D1, "1001", "2001", "100"), row(D1, "1001", "2002", "0"), row(D1, "1002", "2003", "40"), row(D2, "1001", "2001.0", "50")),
            List.of(row(D1, "1001", "2001", "200")));
    }

    private static PromoCost.Cell cell(LocalDate d, String c)
    {
        return new PromoCost.Cell(d, c);
    }

    // ------------------------------------------------------------------ 成本类：有效格子

    @Test
    void costWithoutChannelBehaviorIsNoSource()
    {
        Map<PromoCost.Cell, BigDecimal> spend = Map.of(cell(D1, "2001"), new BigDecimal("100"));
        for (String code : List.of("cpa", "ftdCost", "roi"))
        {
            PromoCost.Result r = PromoCost.evaluate(code, spend, null);
            assertEquals("NO_SOURCE", r.state(), code);
            assertNull(r.value());
            assertEquals(PromoCost.PENDING_NOTE, r.note());
        }
        assertTrue(PromoCost.evaluate("uvCost", spend, null).note().endsWith("UV-落地頁口徑待確認"));
        assertNull(PromoCost.ChannelBehavior.NONE.byChannel("reg", D1, D2), "U1 到位前没有按推广渠道的行为数据");
    }

    @Test
    void costOnlyCountsEffectiveCells()
    {
        Map<PromoCost.Cell, BigDecimal> spend = new LinkedHashMap<>();
        spend.put(cell(D1, "2001"), new BigDecimal("100"));
        spend.put(cell(D1, "2002"), BigDecimal.ZERO);          // 消耗为 0：不是有效格子，它的注册不算
        spend.put(cell(D2, "2001"), new BigDecimal("50"));
        Map<PromoCost.Cell, BigDecimal> reg = new LinkedHashMap<>();
        reg.put(cell(D1, "2001"), new BigDecimal("10"));
        reg.put(cell(D1, "2002"), new BigDecimal("50"));
        reg.put(cell(D2, "2001"), new BigDecimal("5"));
        reg.put(cell(D2, "2003"), new BigDecimal("99"));      // 没录消耗的渠道：不算
        assertEquals(2, PromoCost.effective(spend).size());
        PromoCost.Result cpa = PromoCost.evaluate("cpa", spend, reg);
        assertEquals("OK", cpa.state());
        assertEquals(new BigDecimal("10.00"), cpa.value());    // 150 ÷ 15

        Map<PromoCost.Cell, BigDecimal> net = Map.of(cell(D1, "2001"), new BigDecimal("240"), cell(D2, "2001"), new BigDecimal("60"));
        assertEquals(new BigDecimal("100.00"), PromoCost.evaluate("roi", spend, net).value());   // 300 ÷ 150 − 1 = 100%

        assertEquals("NO_SPEND", PromoCost.evaluate("cpa", Map.of(cell(D1, "2002"), BigDecimal.ZERO), reg).state());
        PromoCost.Result noFtd = PromoCost.evaluate("ftdCost", spend, Map.of());
        assertEquals("OK", noFtd.state());
        assertNull(noFtd.value(), "分母为 0 时没有值");

        Map<PromoCost.Cell, BigDecimal> only2001 = PromoCost.filter(reg, D1, D1, "2001"::equals);
        assertEquals(Map.of(cell(D1, "2001"), new BigDecimal("10")), only2001);
        assertNull(PromoCost.filter(null, D1, D2, k -> true));
    }

    // ------------------------------------------------------------------ 消耗 / 预算数据

    @Test
    void spendCubeSumsByScopeAndPath()
    {
        PromoSpend.Cube c = cube();
        PromoScope all = PromoScope.resolve(DICT, List.of(), List.of(), TERMINALS, null);
        PromoScope hada = PromoScope.resolve(DICT, List.of("1001"), List.of(), TERMINALS, null);
        assertEquals(new BigDecimal("190"), c.sum(BudgetKind.SPEND, D1, D2, PromoSpend.inScope(all)));
        assertEquals(new BigDecimal("150"), c.sum(BudgetKind.SPEND, D1, D2, PromoSpend.inScope(hada)));
        assertEquals(new BigDecimal("40"), c.sum(BudgetKind.SPEND, D1, D1, PromoSpend.ofPath("1002", null)));
        assertEquals(new BigDecimal("150"), c.sum(BudgetKind.SPEND, D1, D2, PromoSpend.ofPath(null, "2001")), "2001.0 与 2001 是同一渠道");
        assertEquals(new BigDecimal("200"), c.sum(BudgetKind.BUDGET, D1, D2, r -> true));
        assertNull(c.sum(BudgetKind.BUDGET, D2, D2, r -> true), "没录入是 null，不是 0");
        assertEquals(new BigDecimal("50"), c.cells(BudgetKind.SPEND, D2, D2, r -> true).get(cell(D2, "2001")));
        assertEquals(List.of("2001", "2002", "2003"), c.listed(all, D1, D1).stream().map(PromoChannelDictionary.Channel::getId).toList());
        assertEquals(List.of("2001"), c.listed(all, D2, D2).stream().map(PromoChannelDictionary.Channel::getId).toList());
        assertTrue(PromoSpend.Cube.failed().isFailed());
        assertEquals(BudgetKind.BUDGET, PromoSpend.kind("budget"));
        assertEquals(BudgetKind.SPEND, PromoSpend.kind("spend"));
    }

    @Test
    void spendLoadReturnsNullWhenReadDisabledAndFailedOnError()
    {
        com.fivetech.promo.budget.BudgetImportProperties off = new com.fivetech.promo.budget.BudgetImportProperties();
        off.setReadEnabled(false);
        assertNull(new PromoSpend(null, off).load(D1, D2));
        com.fivetech.promo.budget.BudgetImportProperties on = new com.fivetech.promo.budget.BudgetImportProperties();
        BudgetDataReader broken = new BudgetDataReader(null, on)
        {
            @Override
            public List<Row> rows(BudgetKind kind, LocalDate from, LocalDate to)
            {
                throw new IllegalStateException("uds down");
            }
        };
        assertTrue(new PromoSpend(broken, on).load(D1, D2).isFailed());
    }

    // ------------------------------------------------------------------ 概览：日期与序列

    @Test
    void overviewSpendDaysDropTodayForSpendOnly()
    {
        LocalDateTime now = LocalDateTime.of(2026, 11, 5, 9, 0);
        PromoTime.Range r = new PromoTime.Range(LocalDateTime.of(2026, 10, 30, 0, 0), now);
        OverviewCompareVO cmp = new OverviewCompareVO();
        cmp.setFrom("2026-10-23 00:00");
        cmp.setTo("2026-10-29 09:00");
        LocalDate[] spend = PromoOverviewService.spendDays("spend", r, cmp, now);
        assertEquals(List.of(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 4), LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 28)),
            List.of(spend));
        LocalDate[] budget = PromoOverviewService.spendDays("budget", r, cmp, now);
        assertEquals(List.of(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 5), LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 29)),
            List.of(budget));
        LocalDate[] noCmp = PromoOverviewService.spendDays("spend", r, null, now);
        assertNull(noCmp[2]);
        assertEquals(LocalDate.of(2026, 11, 5), noCmp[1]);
    }

    @Test
    void overviewSpendSeriesByDayAndNoneByHour()
    {
        List<BigDecimal> s = PromoOverviewService.spendSeries(cube(), BudgetKind.SPEND, r -> true, D1.atStartOfDay(), Granularity.DAY, 3);
        assertEquals(new BigDecimal("140"), s.get(0));
        assertEquals(new BigDecimal("50"), s.get(1));
        assertNull(s.get(2));
        assertTrue(PromoOverviewService.spendSeries(cube(), BudgetKind.SPEND, r -> true, D1.atStartOfDay(), Granularity.HOUR, 24)
            .stream().allMatch(v -> v == null), "小时粒度没有消耗序列");
    }

    // ------------------------------------------------------------------ 渠道表现

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cell(ChannelTree tree, String code, PathItem... path)
    {
        return (Map<String, Object>) tree.cells(PromoAnalysisService.columns(List.of(code)), List.of(path)).get(code);
    }

    @Test
    void channelTableSpendByAgentChannelDateAndCostPending()
    {
        PromoScope scope = PromoScope.resolve(DICT, List.of(), List.of(), TERMINALS, null);
        PromoTime.Range r = PromoTime.range("2026-11-01 00:00", "2026-11-03 00:00");
        ChannelTree tree = ChannelTree.of(List.of("agent", "channel", "date", "terminal"), scope, TERMINALS, r, LocalDate.of(2026, 11, 8));
        // 没装消耗：数据待接入
        assertEquals("NO_SOURCE", cell(tree, "spend").get("state"));
        tree.setSpend(cube(), null);
        assertEquals(new BigDecimal("190"), cell(tree, "spend").get("value"));
        assertEquals("OK", cell(tree, "spend").get("state"));
        assertEquals(new BigDecimal("150"), cell(tree, "spend", new PathItem("agent", "1001")).get("value"));
        assertEquals(new BigDecimal("0"), cell(tree, "spend", new PathItem("agent", "1001"), new PathItem("channel", "2002")).get("value"));
        assertEquals(new BigDecimal("50"), cell(tree, "spend", new PathItem("agent", "1001"), new PathItem("channel", "2001"),
            new PathItem("date", "2026-11-02")).get("value"));
        assertEquals(new BigDecimal("200"), cell(tree, "budget", new PathItem("agent", "1001")).get("value"));
        assertNull(cell(tree, "budget", new PathItem("agent", "1002")).get("value"), "没录入预算");
        assertEquals("NA", cell(tree, "spend", new PathItem("agent", "1001"), new PathItem("channel", "2001"),
            new PathItem("date", "2026-11-01"), new PathItem("terminal", "android")).get("state"));
        // 成本类：行为数据还不能按推广渠道拆
        Map<String, Object> cpa = cell(tree, "cpa", new PathItem("agent", "1001"));
        assertEquals("NO_SOURCE", cpa.get("state"));
        assertEquals(PromoCost.PENDING_NOTE, cpa.get("note"));
        // U1 到位后（有按推广渠道的注册）：按有效格子出数，推广商行只算自己的渠道
        tree.setSpend(cube(), (metric, from, to) -> Map.of(
            new PromoCost.Cell(D1, "2001"), new BigDecimal("10"), new PromoCost.Cell(D2, "2001"), new BigDecimal("5"),
            new PromoCost.Cell(D1, "2003"), new BigDecimal("4"), new PromoCost.Cell(D1, "2002"), new BigDecimal("50")));
        assertEquals(new BigDecimal("10.00"), cell(tree, "cpa", new PathItem("agent", "1001")).get("value"));   // 150 ÷ 15
        assertEquals(new BigDecimal("10.00"), cell(tree, "cpa").get("value"));                                 // 190 ÷ 19
        // 读取失败
        tree.setSpend(PromoSpend.Cube.failed(), null);
        assertEquals(PromoSpend.FAILED_NOTE, cell(tree, "spend").get("note"));
    }
}
