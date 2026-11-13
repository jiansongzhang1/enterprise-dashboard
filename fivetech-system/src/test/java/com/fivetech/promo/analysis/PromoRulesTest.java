package com.fivetech.promo.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.dashboard.domain.vo.OverviewCompareVO;
import com.fivetech.dashboard.enums.CompareType;
import com.fivetech.dashboard.enums.Granularity;
import com.fivetech.promo.analysis.query.ChannelTableQuery.PathItem;
import com.fivetech.promo.budget.PromoChannelDictionary;

/** 推广分析：时间与粒度规则、对比期、筛选解析、渠道表现的行维度树、导出表（不连库、不调接口） */
class PromoRulesTest
{
    private static final List<PromoChannelDictionary.Promoter> DICT = List.of(
        new PromoChannelDictionary.Promoter("1001", "hada", List.of(
            new PromoChannelDictionary.Channel("2001", "DSP-hada", "DSP", "ACTIVE", "LANDING"),
            new PromoChannelDictionary.Channel("2002", "meta-hada", null, "DISABLED", "MAIN"))),
        new PromoChannelDictionary.Promoter("1002", "jason", List.of(new PromoChannelDictionary.Channel("2003", "TG-jason"))));

    private static final PromoTerminalDictionary.Config TERMINALS = new PromoTerminalDictionary.Config(List.of(
        new PromoTerminalDictionary.Terminal("android", "Android", List.of()),
        new PromoTerminalDictionary.Terminal("ios", "iOS", List.of())), PromoTerminalDictionary.DEFAULT_LANDING_MIN_PCT);

    private static int code(Runnable r)
    {
        try
        {
            r.run();
        }
        catch (ServiceException e)
        {
            return e.getCode();
        }
        return 0;
    }

    @Test
    void rangeAndGranularity()
    {
        assertEquals(4001, code(() -> PromoTime.range("2026-11-02 00:00", "2026-11-01 00:00")));
        assertEquals(4001, code(() -> PromoTime.range("2025-11-01 00:00", "2026-11-02 00:00")));
        assertEquals(4001, code(() -> PromoTime.range("2026-11-01", "2026-11-02 00:00")));
        PromoTime.Range oneDay = PromoTime.range("2026-11-01 00:00", "2026-11-02 00:00");
        assertEquals(Granularity.HOUR, PromoTime.allowed(oneDay, Granularity.DAY));
        PromoTime.Range week = PromoTime.range("2026-10-26 00:00", "2026-11-02 00:00");
        assertEquals(Granularity.DAY, PromoTime.allowed(week, Granularity.WEEK));
        assertEquals(Granularity.HOUR, PromoTime.allowed(week, Granularity.HOUR));
        PromoTime.Range tenDays = PromoTime.range("2026-10-23 00:00", "2026-11-02 00:00");
        assertEquals(Granularity.DAY, PromoTime.allowed(tenDays, Granularity.HOUR));
        assertEquals(Granularity.DAY, PromoTime.allowed(tenDays, Granularity.WEEK));
        PromoTime.Range month = PromoTime.range("2026-10-01 00:00", "2026-11-01 00:00");
        assertEquals(Granularity.WEEK, PromoTime.allowed(month, Granularity.WEEK));
        assertEquals(Granularity.DAY, PromoTime.allowed(month, Granularity.HOUR));
        assertEquals(365, PromoTime.range("2025-11-01 00:00", "2026-11-01 00:00").days());
    }

    @Test
    void compareSameTimeOfDay()
    {
        LocalDateTime now = LocalDateTime.of(2026, 11, 5, 9, 0);
        // 近 7 天截到今天 09:00：对比期往前 7 天，同样到 09:00
        PromoTime.Range r = new PromoTime.Range(LocalDateTime.of(2026, 10, 30, 0, 0), now);
        Map<String, Object> c = PromoTime.compare(CompareType.PREV_PERIOD, null, null, true, r, now);
        assertEquals("2026-10-23 00:00", c.get("from"));
        assertEquals("2026-10-29 09:00", c.get("to"));
        assertEquals("09:00", c.get("cutAt"));
        // 不含今天：cutAt 为 null，对比期等长紧邻
        PromoTime.Range past = PromoTime.range("2026-10-01 00:00", "2026-10-08 00:00");
        Map<String, Object> p = PromoTime.compare(null, null, null, true, past, now);
        assertEquals("2026-09-24 00:00", p.get("from"));
        assertEquals("2026-10-01 00:00", p.get("to"));
        assertNull(p.get("cutAt"));
        assertNull(PromoTime.compare(CompareType.NONE, null, null, true, past, now));
        assertEquals(4002, code(() -> PromoTime.compare(CompareType.LAST_YEAR, null, null, true, past, now)));
        assertEquals(4002, code(() -> PromoTime.compare(CompareType.CUSTOM, "2026-09-01 00:00", "2026-09-03 00:00", true, past, now)));
        assertEquals("2026-09-01 00:00",
            PromoTime.compare(CompareType.CUSTOM, "2026-09-01 00:00", "2026-09-08 00:00", true, past, now).get("from"));
    }

    @Test
    void spendCompareDropsToday()
    {
        LocalDateTime now = LocalDateTime.of(2026, 11, 5, 9, 0);
        PromoTime.Range r = new PromoTime.Range(LocalDateTime.of(2026, 10, 30, 0, 0), now);
        OverviewCompareVO cmp = new OverviewCompareVO();
        cmp.setFrom("2026-10-23 00:00");
        cmp.setTo("2026-10-29 09:00");
        Map<String, Object> s = PromoOverviewService.spendCompare(r, cmp, now);
        assertEquals("10-30", s.get("from"));
        assertEquals("11-04", s.get("to"));
        assertEquals("10-23", s.get("prevFrom"));
        assertEquals("10-28", s.get("prevTo"));
        assertEquals(6L, s.get("days"));
        // 只有今天：不返回
        assertNull(PromoOverviewService.spendCompare(new PromoTime.Range(LocalDateTime.of(2026, 11, 5, 0, 0), now), cmp, now));
    }

    @Test
    void scopeUnionUnknownAndLabel()
    {
        PromoScope all = PromoScope.resolve(DICT, List.of(), List.of(), TERMINALS, null);
        assertTrue(all.isAll());
        assertEquals(3, all.getChannels().size());
        assertEquals("全部", all.channelLabel());
        PromoScope mixed = PromoScope.resolve(DICT, List.of("1001"), List.of("2003.0"), TERMINALS, "Android");
        assertEquals(3, mixed.getChannels().size());
        assertEquals("hada、TG-jason", mixed.channelLabel());
        assertTrue(mixed.isTerminalSelected());
        assertEquals("android", mixed.getTerminal());
        assertEquals(4004, code(() -> PromoScope.resolve(DICT, List.of("9"), List.of(), TERMINALS, null)));
        assertEquals(4004, code(() -> PromoScope.resolve(DICT, List.of(), List.of("x"), TERMINALS, null)));
        assertEquals(4004, code(() -> PromoScope.resolve(DICT, List.of(), List.of(), TERMINALS, "unidentified")));
        assertEquals(1, PromoScope.resolve(DICT, List.of(), List.of("2003"), TERMINALS, "all").promotersInScope().size());
    }

    @Test
    void attributionSupport()
    {
        PromoScope all = PromoScope.resolve(DICT, List.of(), List.of(), TERMINALS, null);
        PromoScope android = PromoScope.resolve(DICT, List.of(), List.of(), TERMINALS, "android");
        PromoMetricCatalog.Metric spend = metric("spend");
        PromoMetricCatalog.Metric reg = metric("reg");
        Map<String, Object> notByTerminal = PromoOverviewService.attribution(spend, "terminal", all);
        assertEquals("NOT_BY_TERMINAL", notByTerminal.get("reasonCode"));
        assertEquals("spend不區分終端統計，不能按終端拆分。", notByTerminal.get("reason"));
        assertFalse(notByTerminal.containsKey("total"));
        assertFalse(notByTerminal.containsKey("rows"));
        Map<String, Object> selected = PromoOverviewService.attribution(reg, "terminal", android);
        assertEquals("TERMINAL_SELECTED", selected.get("reasonCode"));
        assertEquals("已篩選單個終端，不能再按終端拆分；終端選「全部」後可查看。", selected.get("reason"));
        Map<String, Object> ok = PromoOverviewService.attribution(reg, "channel", android);
        assertEquals(true, ok.get("supported"));
        assertNull(ok.get("reasonCode"));
        assertNull(ok.get("reason"));
        assertTrue(((List<?>) ok.get("rows")).isEmpty());
        Map<String, Object> dl = PromoOverviewService.attribution(metric("dl"), "terminal", all);
        assertEquals(false, dl.get("supported"));
        assertEquals("NOT_BY_TERMINAL", dl.get("reasonCode"));
        assertEquals("dl只統計 Android，不能按終端拆分。", dl.get("reason"));
    }

    /** 指标管理里没打开「可歸因」：任何维度都返回 supported = false、NOT_CONFIGURED，原因文字带指标名，且优先于其他原因 */
    @Test
    void attributionNotConfigured()
    {
        PromoScope all = PromoScope.resolve(DICT, List.of(), List.of(), TERMINALS, null);
        PromoScope android = PromoScope.resolve(DICT, List.of(), List.of(), TERMINALS, "android");
        PromoMetricCatalog.Metric roi = metric("roi", false);
        String text = "roi是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。"
            + "整體變化見上方趨勢；各渠道的值可在渠道表現查看。";
        for (String d : List.of("agent", "channel", "terminal"))
        {
            Map<String, Object> a = PromoOverviewService.attribution(roi, d, all);
            assertEquals(d, a.get("dimension"));
            assertEquals(false, a.get("supported"), d);
            assertEquals("NOT_CONFIGURED", a.get("reasonCode"), d);
            assertEquals(text, a.get("reason"), d);
            assertFalse(a.containsKey("total"), d);
            assertFalse(a.containsKey("rows"), d);
        }
        // 已选终端又按终端拆：仍按「没配置」提示
        assertEquals("NOT_CONFIGURED", PromoOverviewService.attribution(roi, "terminal", android).get("reasonCode"));
        // fillAttribution 不覆盖已有的原因
        Map<String, Object> a = PromoOverviewService.attribution(roi, "terminal", all);
        PromoOverviewService.fillAttribution(a, roi, new com.fivetech.dashboard.domain.vo.MetricCardVO(),
            new PromoOverviewService.Data(), TERMINALS);
        assertEquals("NOT_CONFIGURED", a.get("reasonCode"));
    }

    /** 可归因但拆分数据待接入：PENDING_SOURCE，原因按维度 / 筛选写 */
    @Test
    void attributionPendingSource()
    {
        PromoScope all = PromoScope.resolve(DICT, List.of(), List.of(), TERMINALS, null);
        PromoMetricCatalog.Metric reg = metric("reg");
        com.fivetech.dashboard.domain.vo.MetricCardVO card = new com.fivetech.dashboard.domain.vo.MetricCardVO();

        Map<String, Object> agent = PromoOverviewService.attribution(reg, "agent", all);
        PromoOverviewService.fillAttribution(agent, reg, card, new PromoOverviewService.Data(), TERMINALS);
        assertEquals(false, agent.get("supported"));
        assertEquals("PENDING_SOURCE", agent.get("reasonCode"));
        assertEquals("數據平台還沒有按推廣商拆分的數據，暫不能歸因。", agent.get("reason"));
        assertFalse(agent.containsKey("total"));
        assertFalse(agent.containsKey("rows"));

        Map<String, Object> channel = PromoOverviewService.attribution(reg, "channel", all);
        PromoOverviewService.fillAttribution(channel, reg, card, new PromoOverviewService.Data(), TERMINALS);
        assertEquals("數據平台還沒有按推廣渠道拆分的數據，暫不能歸因。", channel.get("reason"));

        PromoOverviewService.Data pending = new PromoOverviewService.Data();
        pending.channelPending = true;
        Map<String, Object> terminal = PromoOverviewService.attribution(reg, "terminal", all);
        PromoOverviewService.fillAttribution(terminal, reg, card, pending, TERMINALS);
        assertEquals("PENDING_SOURCE", terminal.get("reasonCode"));
        assertEquals("已篩選推廣商 / 推廣渠道，數據平台還沒有推廣渠道映射，暫不能歸因。", terminal.get("reason"));
    }

    private static PromoMetricCatalog.Metric metric(String code)
    {
        return metric(code, true);
    }

    /** 测试用指标：名称 = 接口编码，attributable 按参数 */
    private static PromoMetricCatalog.Metric metric(String code, boolean attributable)
    {
        com.fivetech.dashboard.domain.MetricDictItem card = new com.fivetech.dashboard.domain.MetricDictItem();
        card.setMetricCode(PromoMetricCatalog.spec(code).getDbCode());
        card.setMetricName(code);
        card.setValueFormat("INT");
        card.setAttributable(attributable);
        com.fivetech.dashboard.domain.PageMetricConfig page = new com.fivetech.dashboard.domain.PageMetricConfig();
        page.setPageCode("PROMO");
        page.setMetricCode(card.getMetricCode());
        page.setEmphasis("NORMAL");
        return new PromoMetricCatalog.Metric(PromoMetricCatalog.spec(code), card, page);
    }

    @Test
    void overviewCatalogCodesAreUnique()
    {
        assertEquals(16, PromoMetricCatalog.OVERVIEW.size());
        assertEquals(16, PromoMetricCatalog.OVERVIEW.stream().map(PromoMetricCatalog.Spec::getDbCode).distinct().count());
        assertEquals(17, PromoMetricCatalog.DAILY_REPORT_COLUMNS.size());
        PromoMetricCatalog.DAILY_REPORT_COLUMNS.forEach(c -> assertTrue(PromoMetricCatalog.column(c) != null, c));
    }

    @Test
    void channelTreeRowsMembersAndCells()
    {
        PromoScope scope = PromoScope.resolve(DICT, List.of(), List.of(), TERMINALS, null);
        PromoTime.Range r = PromoTime.range("2026-10-30 00:00", "2026-11-02 00:00");
        LocalDate yesterday = LocalDate.of(2026, 11, 4);
        assertEquals(4003, code(() -> ChannelTree.of(List.of("agent", "agent"), scope, TERMINALS, r, yesterday)));
        assertEquals(4003, code(() -> ChannelTree.of(List.of("device"), scope, TERMINALS, r, yesterday)));
        // 推廣關係 2026-11-12 去掉：传了按不认识的维度；最多 4 层
        assertEquals(4003, code(() -> ChannelTree.of(List.of("agent", "relation"), scope, TERMINALS, r, yesterday)));
        assertEquals(4003, code(() -> ChannelTree.of(List.of("agent", "channel", "date", "terminal", "agent"), scope, TERMINALS, r, yesterday)));
        assertFalse(ChannelTree.DIM_NAMES.containsKey("relation"));
        ChannelTree tree = ChannelTree.of(List.of("agent", "channel", "date", "terminal"), scope, TERMINALS, r, yesterday);
        assertEquals(2, tree.members(0, List.of()).size());
        List<ChannelTree.Member> hada = tree.members(1, List.of(new PathItem("agent", "1001.0")));
        assertEquals(2, hada.size());
        assertTrue(hada.get(1).disabled);
        // 推廣渠道可以在推廣商之上（2026-11-10 起不限先后）：渠道下只列它所属的推廣商
        ChannelTree rev = ChannelTree.of(List.of("channel", "agent"), scope, TERMINALS, r, yesterday);
        assertEquals(3, rev.members(0, List.of()).size());
        List<ChannelTree.Member> owners = rev.members(1, List.of(new PathItem("channel", "2002")));
        assertEquals(1, owners.size());
        assertEquals(hada.get(0).agent, owners.get(0).name);
        assertEquals("2026-11-01", tree.members(2, List.of()).get(0).key);   // 日期从新到旧
        assertEquals(2 + 3 + 3 * 3 + 3 * 3 * 2, tree.count(0, List.of(), 1000));
        assertEquals(4003, code(() -> tree.checkParent(List.of(new PathItem("agent", "9")))));
        assertEquals(4003, code(() -> tree.checkParent(List.of(new PathItem("channel", "2001")))));
        tree.checkParent(List.of(new PathItem("agent", "1001"), new PathItem("channel", "2002")));

        List<PromoMetricCatalog.Column> cols = PromoAnalysisService.columns(List.of("spend", "reg", "roas7", "regRet1"));
        @SuppressWarnings("unchecked")
        Map<String, Object> spendByTerminal = (Map<String, Object>) tree.cells(cols, List.of(new PathItem("terminal", "ios"))).get("spend");
        assertEquals("NA", spendByTerminal.get("state"));
        assertEquals("消耗不按終端拆分", spendByTerminal.get("note"));
        Map<String, Object> cells = tree.cells(cols, List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> roas = (Map<String, Object>) cells.get("roas7");
        assertEquals("PENDING", roas.get("state"));                 // 10-30 + 7 > 11-04
        @SuppressWarnings("unchecked")
        Map<String, Object> ret1 = (Map<String, Object>) cells.get("regRet1");
        assertEquals("NO_SOURCE", ret1.get("state"));                // 已到期，但注册分群数据集未接入
        assertEquals(Map.of("m", 3, "n", 3), ret1.get("matured"));
        @SuppressWarnings("unchecked")
        Map<String, Object> regByAgent = (Map<String, Object>) tree.cells(cols, List.of(new PathItem("agent", "1001"))).get("reg");
        assertEquals("NO_SOURCE", regByAgent.get("state"));            // 渠道映射待接入
        @SuppressWarnings("unchecked")
        Map<String, Object> regTotal = (Map<String, Object>) cells.get("reg");
        assertEquals("OK", regTotal.get("state"));
        assertNull(regTotal.get("value"));                              // 没装数据
        assertEquals(false, regTotal.get("drillable"));                 // 没有值不能下钻
        assertEquals("INT", regTotal.get("format"));
        assertEquals("MONEY", spendByTerminal.get("format"));
        assertEquals("PCT", roas.get("format"));
        assertEquals(false, regByAgent.get("drillable"));
        assertEquals(false, roas.get("drillable"));
        assertTrue(PromoMetricCatalog.column("reg").isDrillable());
        assertTrue(PromoMetricCatalog.column("ftd").isDrillable());
        assertFalse(PromoMetricCatalog.column("dep").isDrillable());
        assertEquals(4003, code(() -> PromoAnalysisService.columns(List.of("reg", "nope"))));
        assertEquals(4003, code(() -> PromoAnalysisService.sortOf(null, "nope")));
    }

    /** 成本类全站估算：只算有消耗的日；CPA = 消耗 ÷ 全站注册，D0 ROI = 存款总额 ÷ 消耗 − 1（百分数），带全站估算说明 */
    @Test
    void siteCostEstimate()
    {
        LocalDate d1 = LocalDate.of(2026, 11, 9);
        LocalDate d2 = LocalDate.of(2026, 11, 10);
        Map<PromoCost.Cell, BigDecimal> spend = new java.util.LinkedHashMap<>();
        spend.put(new PromoCost.Cell(d1, "2001"), new BigDecimal("100"));
        spend.put(new PromoCost.Cell(d1, "2002"), new BigDecimal("50"));
        com.fivetech.promo.analysis.market.MarketCube cube = new com.fivetech.promo.analysis.market.MarketCube();
        String all = com.fivetech.promo.analysis.market.MarketCube.ALL;
        cube.add(d1.atStartOfDay(), all, "reg", new BigDecimal("30"));
        cube.add(d2.atStartOfDay(), all, "reg", new BigDecimal("10"));
        cube.add(d1.atStartOfDay(), all, "deposit_amount", new BigDecimal("300"));
        cube.add(d1.atStartOfDay(), all, "withdraw_amount", new BigDecimal("75"));
        PromoCost.Result cpa = PromoOverviewService.siteCost("cpa", spend, cube, d1, d2);
        assertEquals("OK", cpa.state());
        assertEquals(0, new BigDecimal("5.00").compareTo(cpa.value()));
        assertEquals(PromoOverviewService.SITE_COST_NOTE, cpa.note());
        PromoCost.Result roi = PromoOverviewService.siteCost("roi", spend, cube, d1, d2);
        // 淨充值 = 存提差 300 − 75 = 225（2026-11-12 起）；ROI = 225 ÷ 150 − 1 = 50%
        assertEquals(0, new BigDecimal("50.00").compareTo(roi.value()));
        assertEquals("NO_SPEND", PromoOverviewService.siteCost("cpa", Map.of(), cube, d1, d2).state());
    }

    /** 卡片「?」口径与终端不适用文案按 FY-48 第四节 / R6 */
    @Test
    void overviewDefinitionsFollowFy48()
    {
        assertEquals("消耗 ÷ 註冊人數。只計已錄入消耗的推廣渠道與日期；沒有消耗的渠道與日期不計入。", PromoMetricCatalog.spec("cpa").getDefinition());
        assertEquals("消耗 ÷ 首存人數。只計已錄入消耗的推廣渠道與日期；沒有消耗的渠道與日期不計入。", PromoMetricCatalog.spec("ftdCost").getDefinition());
        assertEquals("淨充值 ÷ 消耗 − 1。只計已錄入消耗的推廣渠道與日期；沒有消耗的渠道與日期不計入。", PromoMetricCatalog.spec("roi").getDefinition());
        assertEquals("存款總額 − 提款總額。", PromoMetricCatalog.spec("net").getDefinition());
        assertEquals("首存人數 ÷ 註冊人數。", PromoMetricCatalog.spec("ftdR").getDefinition());
        assertEquals(16, PromoMetricCatalog.OVERVIEW.size());
        PromoMetricCatalog.OVERVIEW.forEach(x -> assertTrue(x.getDefinition().endsWith("。"), x.getCode()));
        // 下載點擊、激活选 Web：不適用於所選終端；依赖消耗的：消耗不按終端拆分
        assertEquals("不適用於所選終端", PromoMetricCatalog.spec("dl").getTerminalNote());
        assertEquals("不適用於所選終端", PromoMetricCatalog.spec("act").getTerminalNote());
        assertEquals("消耗不按終端拆分", PromoMetricCatalog.spec("cpa").getTerminalNote());
    }

    /** 成本类序列：每片用同一套算法各算一次；没有消耗的片、超过最后一天的片为 null；周粒度 7 天一片；小时粒度全 null */
    @Test
    void costSeriesPerPoint()
    {
        LocalDate d1 = LocalDate.of(2026, 11, 9);
        LocalDate d2 = LocalDate.of(2026, 11, 10);
        Map<PromoCost.Cell, BigDecimal> spend = new java.util.LinkedHashMap<>();
        spend.put(new PromoCost.Cell(d1, "2001"), new BigDecimal("150"));
        spend.put(new PromoCost.Cell(d2.plusDays(1), "2001"), new BigDecimal("40"));
        com.fivetech.promo.analysis.market.MarketCube cube = new com.fivetech.promo.analysis.market.MarketCube();
        String all = com.fivetech.promo.analysis.market.MarketCube.ALL;
        cube.add(d1.atStartOfDay(), all, "reg", new BigDecimal("30"));
        cube.add(d2.atStartOfDay(), all, "reg", new BigDecimal("10"));
        cube.add(d2.plusDays(1).atStartOfDay(), all, "reg", new BigDecimal("8"));
        java.util.function.BiFunction<LocalDate, LocalDate, PromoCost.Result> calc =
            (s, e) -> PromoOverviewService.siteCost("cpa", PromoCost.filter(spend, s, e, k -> true), cube, s, e);
        // 日：11-09 = 150 ÷ 30；11-10 没有消耗；11-11 有消耗但超过最后一天（11-10）不算；第 4 片超出
        List<BigDecimal> day = PromoOverviewService.costSeries(d1, d2, com.fivetech.dashboard.enums.Granularity.DAY, 4, calc);
        assertEquals(4, day.size());
        assertEquals(0, new BigDecimal("5.00").compareTo(day.get(0)));
        assertNull(day.get(1));
        assertNull(day.get(2));
        assertNull(day.get(3));
        // 最后一天放到 11-11：第 3 片 = 40 ÷ 8
        assertEquals(0, new BigDecimal("5.00").compareTo(
            PromoOverviewService.costSeries(d1, d2.plusDays(1), com.fivetech.dashboard.enums.Granularity.DAY, 3, calc).get(2)));
        // 周：一片含 11-09 ~ 11-11，有消耗的日 = 11-09、11-11 → (150 + 40) ÷ (30 + 8)
        List<BigDecimal> week = PromoOverviewService.costSeries(d1, d2.plusDays(1), com.fivetech.dashboard.enums.Granularity.WEEK, 2, calc);
        assertEquals(0, new BigDecimal("5.00").compareTo(week.get(0)));
        assertNull(week.get(1));
        // 小时：消耗按日录入，没有序列
        assertTrue(PromoOverviewService.costSeries(d1, d2, com.fivetech.dashboard.enums.Granularity.HOUR, 24, calc).stream()
            .allMatch(java.util.Objects::isNull));
        // 首存成本、D0 ROI 走同一条路：11-09 首存 6 → 150 ÷ 6 = 25；淨充值 300 − 75 = 225 → 225 ÷ 150 − 1 = 50%
        cube.add(d1.atStartOfDay(), all, "ftd_players", new BigDecimal("6"));
        cube.add(d1.atStartOfDay(), all, "deposit_amount", new BigDecimal("300"));
        cube.add(d1.atStartOfDay(), all, "withdraw_amount", new BigDecimal("75"));
        for (String[] c : new String[][] { { "ftdCost", "25.00" }, { "roi", "50.00" } })
        {
            List<BigDecimal> v = PromoOverviewService.costSeries(d1, d2, com.fivetech.dashboard.enums.Granularity.DAY, 2,
                (s, e) -> PromoOverviewService.siteCost(c[0], PromoCost.filter(spend, s, e, k -> true), cube, s, e));
            assertEquals(0, new BigDecimal(c[1]).compareTo(v.get(0)), c[0]);
            assertNull(v.get(1), c[0] + "：11-10 没有消耗");
        }
    }

    /** 投放日报合计行的全站值：按日筛桶；已有值（消耗等）不覆盖；数据平台没有的列（提款）留空；比率按合计后的分子分母 */
    @Test
    void dailyReportSiteTotals()
    {
        com.fivetech.promo.analysis.market.MarketCube cube = new com.fivetech.promo.analysis.market.MarketCube();
        LocalDateTime d1 = LocalDateTime.of(2026, 11, 9, 0, 0);
        LocalDateTime d2 = LocalDateTime.of(2026, 11, 10, 0, 0);
        String all = com.fivetech.promo.analysis.market.MarketCube.ALL;
        cube.add(d1, all, "reg", new BigDecimal("100"));
        cube.add(d1, all, "ftd_players", new BigDecimal("20"));
        cube.add(d2, all, "reg", new BigDecimal("50"));
        cube.add(d2, all, "ftd_players", new BigDecimal("5"));
        cube.add(d2, all, "deposit_amount", new BigDecimal("880.5"));
        cube.add(d2, all, "withdraw_amount", new BigDecimal("300"));
        List<String> cols = PromoMetricCatalog.DAILY_REPORT_COLUMNS;
        Object[] total = new Object[4 + cols.size()];
        total[4 + cols.indexOf("spend")] = new BigDecimal("10");
        PromoExportService.fillSiteTotals(total, cube, null);
        assertEquals(0, new BigDecimal("150").compareTo((BigDecimal) total[4 + cols.indexOf("reg")]));
        assertEquals(0, new BigDecimal("25").compareTo((BigDecimal) total[4 + cols.indexOf("ftd")]));
        assertEquals(0, new BigDecimal("16.67").compareTo((BigDecimal) total[4 + cols.indexOf("ftdR")]));
        assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) total[4 + cols.indexOf("spend")]));
        // 提款總額：精确 withdraw_amount（2026-11-12 接入）；提款人數数据平台还没有，留空
        assertEquals(0, new BigDecimal("300").compareTo((BigDecimal) total[4 + cols.indexOf("wd")]));
        assertNull(total[4 + cols.indexOf("wdU")]);
        Object[] day = new Object[4 + cols.size()];
        PromoExportService.fillSiteTotals(day, cube, b -> b.toLocalDate().equals(d2.toLocalDate()));
        assertEquals(0, new BigDecimal("50").compareTo((BigDecimal) day[4 + cols.indexOf("reg")]));
        assertEquals(0, new BigDecimal("880.5").compareTo((BigDecimal) day[4 + cols.indexOf("dep")]));
    }

    @Test
    void sheetWritesNumbersAndFallsBackToCsv() throws Exception
    {
        PromoSheet s = new PromoSheet("投放日報", "站 · 推廣分析 · 投放日報")
            .column("日期", PromoSheet.TEXT).column("消耗", PromoSheet.MONEY).column("首存轉化率", PromoSheet.PCT).column("人數", PromoSheet.INT);
        Object[] row = s.newRow();
        row[0] = "區間合計";
        row[1] = new BigDecimal("1234.5");
        row[2] = new BigDecimal("12.3456");
        row[3] = null;
        byte[] xlsx = s.xlsx();
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx)))
        {
            org.apache.poi.ss.usermodel.Sheet sh = wb.getSheet("投放日報");
            assertEquals("站 · 推廣分析 · 投放日報", sh.getRow(0).getCell(0).getStringCellValue());
            assertEquals(1, sh.getNumMergedRegions());
            assertEquals("消耗", sh.getRow(1).getCell(1).getStringCellValue());
            assertTrue(Math.abs(1234.5 - sh.getRow(2).getCell(1).getNumericCellValue()) < 1e-9);
            assertTrue(Math.abs(0.123456 - sh.getRow(2).getCell(2).getNumericCellValue()) < 1e-9);
            assertEquals("0.00%", sh.getRow(2).getCell(2).getCellStyle().getDataFormatString());
            assertNull(sh.getRow(2).getCell(3));
        }
        String csv = new String(s.csv(), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("﻿"));
        assertTrue(csv.contains("區間合計,1234.5,0.1235,"), csv);
    }

    @Test
    void cohortMaturity()
    {
        List<String> states = new ArrayList<>();
        LocalDate yesterday = LocalDate.of(2026, 11, 4);
        for (int n : PromoAnalysisService.OFFSETS)
        {
            states.add(LocalDate.of(2026, 10, 28).plusDays(n).isAfter(yesterday) ? "PENDING" : "OK");
        }
        assertEquals(List.of("OK", "OK", "OK", "PENDING", "PENDING"), states);
    }

    @Test
    void funnelStepsNamesRatesAndDrill()
    {
        com.fivetech.promo.analysis.market.MarketCube cube = new com.fivetech.promo.analysis.market.MarketCube();
        LocalDateTime d = LocalDateTime.of(2026, 11, 1, 0, 0);
        cube.add(d, "all", "android_first_open", new BigDecimal("50"));
        cube.add(d, "android", "reg", new BigDecimal("20"));
        cube.add(d, "android", "ftd_players", new BigDecimal("5"));
        List<PromoTerminalDictionary.Step> defs = List.of(
            new PromoTerminalDictionary.Step("lpUV", "ENTRY", true),
            new PromoTerminalDictionary.Step("dl", null, false),
            new PromoTerminalDictionary.Step("act", null, false),
            new PromoTerminalDictionary.Step("reg", null, true),
            new PromoTerminalDictionary.Step("ftd", null, true));
        java.util.Map<String, String[]> meta = new java.util.LinkedHashMap<>();
        meta.put("reg", new String[] { "註冊人數", "註冊說明" });
        meta.put(PromoAnalysisService.LOST, new String[] { "流失", "流失說明" });
        List<Map<String, Object>> steps = PromoAnalysisService.steps(defs, cube, "android", meta);
        assertEquals("NO_SOURCE", steps.get(0).get("state"));
        assertNull(steps.get(2).get("rate"));                       // 非严格步
        assertEquals(new BigDecimal("40.00"), steps.get(3).get("rate")); // 20 ÷ 激活 50（跳过没有来源的步骤）
        assertEquals("act", steps.get(3).get("rateFrom"));
        assertEquals(new BigDecimal("25.00"), steps.get(4).get("rate")); // 5 ÷ 20
        assertEquals("註冊人數", steps.get(3).get("name"));
        assertEquals("註冊說明", steps.get(3).get("description"));
        assertEquals(true, steps.get(3).get("drillable"));
        assertEquals(true, steps.get(4).get("drillable"));
        assertEquals(false, steps.get(2).get("drillable"));
        Map<String, Object> lost = PromoAnalysisService.funnelItem(PromoAnalysisService.LOST, meta, new BigDecimal("12"));
        assertEquals("流失", lost.get("name"));
        assertEquals(true, lost.get("drillable"));
    }
}
