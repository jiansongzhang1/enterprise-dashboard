package com.fivetech.dashboard.gateway.uds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 平均到账 / 出款时间后端计算（2026-10-29）：sum(dep_time_sum) / nullif(sum(dep_time_count), 0)，
 * 分子分母是同一时间片 / 区间的合计；其余组装（相减、相加）不变。
 */
class UdsComposeDivideTest
{
    private static UdsProperties.Overview overview()
    {
        UdsProperties.Overview ov = new UdsProperties.Overview();
        Map<String, List<String>> div = new LinkedHashMap<>();
        div.put("dT", List.of("dep_time_sum", "dep_time_count"));
        div.put("wT", List.of("wd_time_sum", "wd_time_count"));
        ov.setDivide(div);
        Map<String, List<String>> sub = new LinkedHashMap<>();
        sub.put("net", List.of("dep", "wd"));
        ov.setSubtract(sub);
        Map<String, List<String>> add = new LinkedHashMap<>();
        add.put("pay", List.of("bet", "winLoss"));
        ov.setAdd(add);
        return ov;
    }

    @Test
    void divideSumBySum()
    {
        UdsProperties.Overview ov = overview();
        // 区间合计：到账耗时 6120 秒、到账成功 11 单 → 556.363636 秒（页面 ÷60 显示 9.3 分钟）
        assertEquals(new BigDecimal("556.363636"), UdsMetricDataGateway.compose(ov, "dT", new BigDecimal("6120"), new BigDecimal("11")));
        assertEquals(new BigDecimal("90.000000"), UdsMetricDataGateway.compose(ov, "wT", new BigDecimal("900"), new BigDecimal("10")));
    }

    @Test
    void zeroOrMissingDenominatorIsNull()
    {
        UdsProperties.Overview ov = overview();
        assertNull(UdsMetricDataGateway.compose(ov, "dT", new BigDecimal("100"), BigDecimal.ZERO), "nullif(分母, 0)");
        assertNull(UdsMetricDataGateway.compose(ov, "dT", null, new BigDecimal("3")));
        assertNull(UdsMetricDataGateway.compose(ov, "dT", BigDecimal.ZERO, null));
        assertEquals(new BigDecimal("0.000000"), UdsMetricDataGateway.compose(ov, "dT", BigDecimal.ZERO, new BigDecimal("3")));
    }

    @Test
    void otherAssembliesUnchanged()
    {
        UdsProperties.Overview ov = overview();
        assertEquals(new BigDecimal("70"), UdsMetricDataGateway.compose(ov, "net", new BigDecimal("100"), new BigDecimal("30")));
        assertEquals(new BigDecimal("130"), UdsMetricDataGateway.compose(ov, "pay", new BigDecimal("100"), new BigDecimal("30")));
    }

    /**
     * 指标管理里配的公式（calc_type + left_code / right_code）：yml 没配、数据集也没直接映射的指标按公式算，
     * 操作数可以是另一个组装指标（RTP = 派彩 ÷ 投注，派彩本身是 投注 + 玩家输赢）。
     */
    @Test
    void dbFormulaWithNestedOperands()
    {
        UdsProperties props = new UdsProperties();
        props.setOverview(overview());
        Map<String, UdsProperties.Dataset> ds = new LinkedHashMap<>();
        UdsProperties.Dataset amount = new UdsProperties.Dataset();
        Map<String, String> m = new LinkedHashMap<>();
        m.put("bet", "bet");
        m.put("winLoss", "win_los_amt");
        m.put("dep", "dep");
        m.put("wd", "wd");
        amount.setMetrics(m);
        ds.put("overview_amount", amount);
        props.setDatasets(ds);
        UdsMetricDataGateway g = new UdsMetricDataGateway(props, null, null);
        Map<String, UdsMetricDataGateway.Formula> db = new LinkedHashMap<>();
        db.put("rtp", new UdsMetricDataGateway.Formula("DIVIDE", "pay", "bet"));
        db.put("wdr", new UdsMetricDataGateway.Formula("DIVIDE", "wd", "dep"));
        // 数据集里直接映射了的编码不按公式算（即使库里配了公式）
        db.put("dep", new UdsMetricDataGateway.Formula("ADD", "x", "y"));
        g.setDbFormulas(db::get);

        assertEquals(List.of("bet", "winLoss"), List.copyOf(g.leaves("rtp")), "展开到最底层：派彩 → 投注 + 玩家输赢");
        assertEquals(List.of("wd", "dep"), List.copyOf(g.leaves("wdr")));
        assertEquals(List.of("dep"), List.copyOf(g.leaves("dep")), "直接映射优先");

        Map<String, BigDecimal> raw = Map.of("bet", new BigDecimal("1000"), "winLoss", new BigDecimal("-40"),
            "wd", new BigDecimal("300"), "dep", new BigDecimal("1200"));
        assertEquals(new BigDecimal("0.960000"), g.value("rtp", raw::get, 0), "(1000 − 40) ÷ 1000");
        assertEquals(new BigDecimal("0.250000"), g.value("wdr", raw::get, 0));
        assertNull(g.value("rtp", Map.of("bet", BigDecimal.ZERO, "winLoss", BigDecimal.ONE)::get, 0), "除数为 0 为空");
        assertNull(g.value("wdr", Map.of("wd", BigDecimal.ONE)::get, 0), "任一操作数为空则为空");
    }
}
