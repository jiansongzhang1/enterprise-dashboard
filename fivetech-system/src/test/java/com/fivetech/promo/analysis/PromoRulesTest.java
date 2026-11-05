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
        assertEquals("NOT_BY_TERMINAL", PromoOverviewService.attribution(spend, "terminal", all).get("reason"));
        assertEquals("TERMINAL_SELECTED", PromoOverviewService.attribution(reg, "terminal", android).get("reason"));
        Map<String, Object> ok = PromoOverviewService.attribution(reg, "channel", android);
        assertEquals(true, ok.get("supported"));
        assertTrue(((List<?>) ok.get("rows")).isEmpty());
        assertFalse(PromoOverviewService.attribution(metric("dl"), "terminal", all).get("supported").equals(true));
    }

    private static PromoMetricCatalog.Metric metric(String code)
    {
        com.fivetech.dashboard.domain.MetricDictItem card = new com.fivetech.dashboard.domain.MetricDictItem();
        card.setMetricCode(PromoMetricCatalog.spec(code).getDbCode());
        card.setValueFormat("INT");
        card.setAttributable(true);
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
        assertEquals(4003, code(() -> ChannelTree.of(List.of("channel", "agent"), scope, TERMINALS, r, yesterday)));
        assertEquals(4003, code(() -> ChannelTree.of(List.of("agent", "agent"), scope, TERMINALS, r, yesterday)));
        assertEquals(4003, code(() -> ChannelTree.of(List.of("device"), scope, TERMINALS, r, yesterday)));
        ChannelTree tree = ChannelTree.of(List.of("agent", "channel", "date", "terminal"), scope, TERMINALS, r, yesterday);
        assertEquals(2, tree.members(0, List.of()).size());
        List<ChannelTree.Member> hada = tree.members(1, List.of(new PathItem("agent", "1001.0")));
        assertEquals(2, hada.size());
        assertTrue(hada.get(1).disabled);
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
        assertEquals("OK", ret1.get("state"));
        assertEquals(Map.of("m", 3, "n", 3), ret1.get("matured"));
        assertEquals(4003, code(() -> PromoAnalysisService.columns(List.of("reg", "nope"))));
        assertEquals(4003, code(() -> PromoAnalysisService.sortOf(null, "nope")));
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
}
