package com.fivetech.promo.analysis.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.enums.Granularity;
import com.fivetech.dashboard.gateway.uds.UdsClient;
import com.fivetech.dashboard.gateway.uds.UdsProperties;
import com.fivetech.dashboard.gateway.uds.UdsQueryResult;
import com.fivetech.dashboard.gateway.uds.UdsRow;
import tools.jackson.databind.json.JsonMapper;

/** 推广分析 · UDS 市场数据集：请求体、按日累加、终端拆分、页面指标换算（不调真实 UDS） */
class MarketGatewayTest
{
    /** 记录请求体、按数据集返回预置行的 UdsClient */
    static final class FakeClient extends UdsClient
    {
        final List<Map<String, Object>> bodies = new ArrayList<>();
        final List<List<UdsRow>> answers = new ArrayList<>();

        FakeClient()
        {
            super(new UdsProperties(), JsonMapper.builder().build(), new DashboardProperties());
        }

        @Override
        public List<UdsQueryResult> queryBatch(List<Map<String, Object>> list)
        {
            List<UdsQueryResult> out = new ArrayList<>();
            for (Map<String, Object> b : list)
            {
                out.add(queryWithMeta(b));
            }
            return out;
        }

        @Override
        public UdsQueryResult queryWithMeta(Map<String, Object> body)
        {
            bodies.add(body);
            List<UdsRow> rows = answers.isEmpty() ? List.of() : answers.remove(0);
            return new UdsQueryResult(rows, "b", "v1", false, LocalDateTime.of(2026, 11, 5, 9, 0));
        }
    }

    private static UdsRow row(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2)
        {
            m.put((String) kv[i], kv[i + 1]);
        }
        return new UdsRow(m);
    }

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 11, 1, 0, 0);

    private static final LocalDateTime TO = LocalDateTime.of(2026, 11, 3, 0, 0);

    private FakeClient twoDays()
    {
        FakeClient c = new FakeClient();
        c.answers.add(List.of(
            row("uds_hh", "2026-11-01 00:00:00", "reg", 100, "ftd_players", 20, "ftd_amount", "2000.50", "redeposit_amount", 300,
                "android_first_open", 40, "web_reg", 60, "android_reg", 30, "unknown_reg", 10, "web_ftd_players", 12, "android_ftd_players", 8),
            row("uds_hh", "2026-11-02 00:00:00", "reg", 50, "ftd_players", 5, "ftd_amount", 500, "redeposit_amount", 0,
                "android_first_open", 10, "web_reg", 50, "web_ftd_players", 5)));
        c.answers.add(List.of(
            row("uds_hh", "2026-11-01 00:00:00", "terminal", "Web", "deposit_users", 30, "redeposit_users", 10, "bet_users", 50,
                "deposit_amount", "3000.25", "withdraw_amount", 1000, "bet_amount", 9000),
            row("uds_hh", "2026-11-01 00:00:00", "terminal", "Android", "deposit_users", 20, "redeposit_users", 5, "bet_users", 25,
                "deposit_amount", 1000, "withdraw_amount", "500.25", "bet_amount", 1000),
            row("uds_hh", "2026-11-02 00:00:00", "terminal", null, "deposit_users", 10, "redeposit_users", 2, "bet_users", 5,
                "deposit_amount", 500, "withdraw_amount", 0, "bet_amount", 100)));
        return c;
    }

    @Test
    void requestBodiesSplitWideAndPrecise()
    {
        FakeClient c = twoDays();
        MarketProperties p = new MarketProperties();
        new MarketGateway(c, p).cube(FROM, TO, Granularity.DAY);
        Map<String, Object> wide = c.bodies.get(0);
        Map<String, Object> precise = c.bodies.get(1);
        assertEquals("dashboard_market_hour_v1", wide.get("dataset"));
        assertEquals(List.of("uds_hh"), wide.get("dimensions"));
        assertTrue(((List<?>) wide.get("metrics")).contains("web_reg"));
        // 激活口径待确认（字段映射 2026-11-05：lp_install 是设备数），不再取 android_first_open
        assertTrue(!((List<?>) wide.get("metrics")).contains("android_first_open"));
        assertTrue(!((List<?>) wide.get("metrics")).contains("deposit_users"));
        assertEquals(List.of("uds_hh", "terminal"), precise.get("dimensions"));
        @SuppressWarnings("unchecked")
        Map<String, Object> time = (Map<String, Object>) wide.get("time");
        assertEquals("2026-11-01 00:00:00", time.get("from"));
        assertEquals("2026-11-02 23:00:00", time.get("to"));        // to = 最后一个包含的整点
        assertEquals("DAY", time.get("granularity"));
        assertNull(wide.get("filters"));

        FakeClient c2 = twoDays();
        p.setMerchantCode("M01");
        new MarketGateway(c2, p).cube(FROM, TO, Granularity.DAY);
        assertEquals("{and=[{field=merchant_code, op=EQ, value=M01}]}", String.valueOf(c2.bodies.get(0).get("filters")));
    }

    @Test
    void todayRangeSendsToCeiledToNextHour()
    {
        // 现在 11-02 16:50：区间含今天时 uds_hh 的 to 向右取整到 17:00（UDS 实时数据）
        FakeClient c = twoDays();
        MarketGateway g = new MarketGateway(c, new MarketProperties());
        g.clock = () -> LocalDateTime.of(2026, 11, 2, 16, 50);
        g.cube(FROM, TO, Granularity.DAY);
        @SuppressWarnings("unchecked")
        Map<String, Object> time = (Map<String, Object>) c.bodies.get(0).get("time");
        assertEquals("2026-11-01 00:00:00", time.get("from"));
        assertEquals("2026-11-02 17:00:00", time.get("to"));
        @SuppressWarnings("unchecked")
        Map<String, Object> precise = (Map<String, Object>) c.bodies.get(1).get("time");
        assertEquals("2026-11-02 17:00:00", precise.get("to"));

        // 现在已是 11-03：区间全在过去，照旧发最后一个包含的整点
        FakeClient c2 = twoDays();
        MarketGateway g2 = new MarketGateway(c2, new MarketProperties());
        g2.clock = () -> LocalDateTime.of(2026, 11, 3, 9, 10);
        g2.cube(FROM, TO, Granularity.DAY);
        @SuppressWarnings("unchecked")
        Map<String, Object> t2 = (Map<String, Object>) c2.bodies.get(0).get("time");
        assertEquals("2026-11-02 23:00:00", t2.get("to"));
    }

    @Test
    void pageValuesFromCube()
    {
        MarketCube cube = new MarketGateway(twoDays(), new MarketProperties()).cube(FROM, TO, Granularity.DAY);
        assertEquals(new BigDecimal("150"), MarketValues.value("reg", cube, MarketCube.ALL, null));
        assertEquals(new BigDecimal("110"), MarketValues.value("reg", cube, "web", null));
        assertEquals(new BigDecimal("2500.50"), MarketValues.value("ftdAmt", cube, MarketCube.ALL, null));
        // 精确人数逐日相加，空终端归 unknown
        assertEquals(new BigDecimal("17"), MarketValues.value("rd", cube, MarketCube.ALL, null));
        assertEquals(new BigDecimal("2"), MarketValues.value("rd", cube, "unknown", null));
        // 淨充值 = 存提差：存款 4500.25 − 提款 1500.25（2026-11-12 起与运营总览一致）
        assertEquals(new BigDecimal("3000.00"), MarketValues.value("net", cube, MarketCube.ALL, null));
        assertEquals(new BigDecimal("4500.25"), MarketValues.value("dep", cube, MarketCube.ALL, null));
        assertEquals(new BigDecimal("2000.25"), MarketValues.value("net", cube, "web", null));
        assertEquals(new BigDecimal("16.67"), MarketValues.value("ftdR", cube, MarketCube.ALL, null));   // 25 / 150
        assertEquals(new BigDecimal("75.00"), MarketValues.value("arppu", cube, MarketCube.ALL, null));  // 4500.25 / 60
        // 需确认的指标不取数：激活（设备数不是人数）、复存率（分母未定）
        assertNull(MarketValues.value("act", cube, "android", null));
        assertNull(MarketValues.value("rdR", cube, MarketCube.ALL, null));
        assertTrue(MarketValues.CALIBER_DIFF.containsAll(java.util.Set.of("act", "rdR")));
        assertNull(MarketValues.value("lpUV", cube, MarketCube.ALL, null));
        // 按日期过滤
        assertEquals(new BigDecimal("50"), MarketValues.value("reg", cube, MarketCube.ALL,
            b -> b.toLocalDate().equals(LocalDate.of(2026, 11, 2))));
        assertEquals(LocalDateTime.of(2026, 11, 5, 9, 0), cube.getWatermark());
    }

    @Test
    void emptyIsNullNotZero()
    {
        FakeClient c = new FakeClient();
        MarketCube cube = new MarketGateway(c, new MarketProperties()).cube(FROM, TO, Granularity.HOUR);
        assertNull(MarketValues.value("reg", cube, MarketCube.ALL, null));
        assertNull(MarketValues.value("ftdR", cube, MarketCube.ALL, null));
        assertEquals("HOUR", ((Map<?, ?>) c.bodies.get(0).get("time")).get("granularity"));
    }

    @Test
    void bucketsTerminalsAndDelta()
    {
        assertEquals(LocalDateTime.of(2026, 11, 1, 5, 0), MarketGateway.bucket("2026-11-01T05:00:00", Granularity.HOUR));
        assertEquals(LocalDateTime.of(2026, 11, 1, 0, 0), MarketGateway.bucket("2026-11-01 05:00:00", Granularity.DAY));
        assertEquals(LocalDateTime.of(2026, 11, 1, 0, 0), MarketGateway.bucket("2026-11-01", Granularity.DAY));
        assertEquals("ios", MarketGateway.terminalKey("iOS"));
        assertEquals("unknown", MarketGateway.terminalKey(""));
        assertEquals("web", MarketValues.terminalKey("h5"));
        assertEquals("unknown", MarketValues.terminalKey("unidentified"));
        assertNull(MarketValues.terminalKey("tv"));
        assertEquals("Android", MarketValues.udsTerminal("android"));
        BigDecimal[] d = MarketValues.delta("reg", new BigDecimal("150"), new BigDecimal("100"));
        assertEquals(new BigDecimal("50.00"), d[0]);
        BigDecimal[] pt = MarketValues.delta("ftdR", new BigDecimal("16.67"), new BigDecimal("20.00"));
        assertNull(pt[0]);
        assertEquals(new BigDecimal("-3.33"), pt[1]);
    }

    @Test
    void agentStatsAndChildrenCounts()
    {
        FakeClient c = new FakeClient();
        c.answers.add(List.of(row("agent_login", "a1", "reg", 3, "ftd_players", 1)));
        c.answers.add(List.of(row("uds_hh", "2026-11-01 00:00:00", "agent_login", "a1", "deposit_users", 2, "deposit_amount", 100, "bet_amount", 50),
            row("uds_hh", "2026-11-02 00:00:00", "agent_login", "a1", "deposit_users", 1, "deposit_amount", 10, "bet_amount", 5)));
        MarketGateway g = new MarketGateway(c, new MarketProperties());
        Map<String, Map<String, BigDecimal>> s = g.byAgent(FROM, TO, List.of("a1"));
        assertEquals(new BigDecimal("3"), s.get("a1").get("deposit_users"));
        assertEquals(new BigDecimal("110"), s.get("a1").get("deposit_amount"));
        assertEquals("{and=[{field=agent_login, op=IN, value=[a1]}]}", String.valueOf(c.bodies.get(0).get("filters")));
        c.answers.add(List.of(row("record_count", 7)));
        assertEquals(List.of(7L), g.memberCounts(List.of(List.of(MarketGateway.cond("is_ftd", "EQ", 0)))));
        Map<String, Object> snap = c.bodies.get(2);
        assertEquals("dashboard_market_member_snapshot_v1", snap.get("dataset"));
        assertEquals("now", ((Map<?, ?>) snap.get("time")).get("from"));
    }

    // ------------------------------------------------------------------ 只取请求的指标用到的字段

    /** 按请求体的维度回答：带 terminal 的是精确来源，否则是宽表（不依赖调用顺序） */
    static final class RoutedClient extends UdsClient
    {
        final List<Map<String, Object>> bodies = new ArrayList<>();
        int batches;
        List<UdsRow> wide = List.of();
        List<UdsRow> precise = List.of();

        RoutedClient()
        {
            super(new UdsProperties(), JsonMapper.builder().build(), new DashboardProperties());
        }

        @Override
        public List<UdsQueryResult> queryBatch(List<Map<String, Object>> list)
        {
            batches++;
            List<UdsQueryResult> out = new ArrayList<>();
            for (Map<String, Object> b : list)
            {
                out.add(answer(b));
            }
            return out;
        }

        @Override
        public UdsQueryResult queryWithMeta(Map<String, Object> body)
        {
            return answer(body);
        }

        private UdsQueryResult answer(Map<String, Object> body)
        {
            bodies.add(body);
            boolean isPrecise = ((List<?>) body.get("dimensions")).contains("terminal");
            return new UdsQueryResult(isPrecise ? precise : wide, "b", "v1", false, LocalDateTime.of(2026, 11, 5, 9, 0));
        }
    }

    private RoutedClient routed()
    {
        FakeClient f = twoDays();
        RoutedClient c = new RoutedClient();
        c.wide = f.answers.get(0);
        c.precise = f.answers.get(1);
        return c;
    }

    @SuppressWarnings("unchecked")
    private static List<String> metricsOf(Map<String, Object> body)
    {
        return (List<String>) body.get("metrics");
    }

    @Test
    void onlyWideQueryForFtd()
    {
        RoutedClient c = routed();
        MarketCube cube = new MarketGateway(c, new MarketProperties()).cube(FROM, TO, Granularity.DAY, List.of("ftd"), false);
        assertEquals(1, c.bodies.size(), "用不到精确来源就不发精确那条查询");
        assertEquals(0, c.batches, "只有一条时走 /query，不走 batch");
        assertEquals(List.of("ftd_players"), metricsOf(c.bodies.get(0)));
        assertEquals(List.of("uds_hh"), c.bodies.get(0).get("dimensions"));
        assertEquals(new BigDecimal("25"), MarketValues.value("ftd", cube, MarketCube.ALL, null));
    }

    @Test
    void onlyPreciseQueryForRepeatDepositors()
    {
        RoutedClient c = routed();
        MarketCube cube = new MarketGateway(c, new MarketProperties()).cube(FROM, TO, Granularity.DAY, List.of("rd"), false);
        assertEquals(1, c.bodies.size(), "用不到宽表就不发宽表那条查询");
        assertEquals(List.of("redeposit_users"), metricsOf(c.bodies.get(0)));
        assertEquals(new BigDecimal("17"), MarketValues.value("rd", cube, MarketCube.ALL, null));
    }

    @Test
    void terminalPrefixedFieldsOnlyWhenSplittingByTerminal()
    {
        RoutedClient c = routed();
        new MarketGateway(c, new MarketProperties()).cube(FROM, TO, Granularity.DAY, List.of("ftdR", "rd", "lpUV"), true);
        assertEquals(1, c.batches, "两条都要时一次 batch 发出");
        assertEquals(List.of("reg", "ftd_players", "web_reg", "web_ftd_players", "android_reg", "android_ftd_players",
            "ios_reg", "ios_ftd_players", "unknown_reg", "unknown_ftd_players"), metricsOf(c.bodies.get(0)));
        assertEquals(List.of("redeposit_users"), metricsOf(c.bodies.get(1)));
        RoutedClient none = routed();
        MarketCube empty = new MarketGateway(none, new MarketProperties()).cube(FROM, TO, Granularity.DAY, List.of("lpUV", "spend"), true);
        assertEquals(0, none.bodies.size(), "没有能取的指标：不调 UDS");
        assertNull(MarketValues.value("reg", empty, MarketCube.ALL, null));
    }

    /** 每个可取指标只按自己的字段取数，结果与取全部字段时完全一致（FIELDS 与 value() 的算法对得上） */
    @Test
    void fieldsCoverEveryAvailableMetric()
    {
        assertEquals(MarketValues.AVAILABLE, MarketValues.FIELDS.keySet());
        MarketCube full = new MarketGateway(routed(), new MarketProperties()).cube(FROM, TO, Granularity.DAY);
        for (String code : MarketValues.AVAILABLE)
        {
            MarketCube only = new MarketGateway(routed(), new MarketProperties()).cube(FROM, TO, Granularity.DAY, List.of(code), true);
            for (String t : List.of(MarketCube.ALL, "web", "android", "unknown"))
            {
                assertEquals(MarketValues.value(code, full, t, null), MarketValues.value(code, only, t, null), code + " @ " + t);
            }
        }
    }

    /** 複存率由上游直接给出：后端不算、不取它的分子分母，提供字段前为 NO_SOURCE */
    @Test
    void repeatDepositRateComesFromUpstream()
    {
        assertTrue(!MarketValues.AVAILABLE.contains("rdR"));
        assertTrue(MarketValues.UPSTREAM.contains("rdR"));
        assertTrue(MarketValues.fieldsOf(List.of("rdR")).isEmpty());
        MarketCube full = new MarketGateway(routed(), new MarketProperties()).cube(FROM, TO, Granularity.DAY);
        assertNull(MarketValues.value("rdR", full, MarketCube.ALL, null));
        assertEquals("由數據平台直接提供（不由後端計算），待接入", MarketValues.noSourceNote("rdR"));
        RoutedClient c = routed();
        new MarketGateway(c, new MarketProperties()).cube(FROM, TO, Granularity.DAY, List.of("rdR"), false);
        assertEquals(0, c.bodies.size(), "只选複存率时不调 UDS");
    }

    /** 注册分群：dataset、reg_dt 按日、to 写日期（闭区间），一次取齐 1/3/7/15/30 日的到期 / 留存 / 存款额 */
    @Test
    @SuppressWarnings("unchecked")
    void cohortRequestBody()
    {
        FakeClient c = new FakeClient();
        c.answers.add(List.of(row("reg_dt", "2026-11-10", "reg", 1262, "matured_d1", 1262, "retained_d1", 53)));
        List<UdsRow> rows = new MarketGateway(c, new MarketProperties()).cohort(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 11, 11),
            List.of("reg_dt"), MarketGateway.cohortMetrics(List.of(1, 3, 7, 15, 30)));
        assertEquals(1, rows.size());
        Map<String, Object> body = c.bodies.get(0);
        assertEquals("dashboard_market_cohort_v1", body.get("dataset"));
        assertEquals(List.of("reg_dt"), body.get("dimensions"));
        List<String> metrics = (List<String>) body.get("metrics");
        assertEquals(16, metrics.size());
        assertTrue(metrics.containsAll(List.of("reg", "matured_d1", "retained_d3", "deposit_amount_d30")));
        Map<String, Object> time = (Map<String, Object>) body.get("time");
        assertEquals("reg_dt", time.get("dimension"));
        assertEquals("DAY", time.get("granularity"));
        assertEquals("2026-10-13", time.get("from"));
        assertEquals("2026-11-11", time.get("to"));
        assertEquals("ONLINE", ((Map<String, Object>) body.get("options")).get("channel"));
    }

    /** 淨充值要存款、提款两项；提款取不到时不提供（不当 0） */
    @Test
    void netNeedsBothDepositAndWithdraw()
    {
        assertEquals(List.of("deposit_amount", "withdraw_amount"), MarketValues.FIELDS.get("net"));
        MarketCube cube = new MarketCube();
        cube.add(FROM, MarketCube.ALL, "deposit_amount", new BigDecimal("100"));
        assertNull(MarketValues.value("net", cube, MarketCube.ALL, null));
        cube.add(FROM, MarketCube.ALL, "withdraw_amount", new BigDecimal("130"));
        assertEquals(new BigDecimal("-30"), MarketValues.value("net", cube, MarketCube.ALL, null), "提款多于存款时为负数");
        // 提款總額单独取 withdraw_amount
        assertEquals(List.of("withdraw_amount"), MarketValues.FIELDS.get("wd"));
        assertEquals(new BigDecimal("130"), MarketValues.value("wd", cube, MarketCube.ALL, null));
    }
}
