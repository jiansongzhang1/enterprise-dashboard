package com.fivetech.promo.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.gateway.uds.UdsRow;

/** 留存与回收：UDS 注册分群 → 注册留存 / LTV / ROAS（样例数取自 UDS 2026-11-12 生产实跑） */
class PromoCohortTest
{
    private static final List<Integer> OFFSETS = PromoAnalysisService.OFFSETS;

    private static UdsRow row(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2)
        {
            m.put((String) kv[i], kv[i + 1]);
        }
        return new UdsRow(m);
    }

    /** 11-11：D1 未结束（matured 0、其余 null）；11-10：D1 已结束、D7 未结束；11-01：D7 已结束 */
    private static Map<LocalDate, PromoCohort.Day> days()
    {
        return PromoCohort.byDate(List.of(
            row("reg_dt", "2026-11-11", "reg", 1183, "matured_d1", 0, "retained_d1", null, "matured_d7", 0),
            row("reg_dt", "2026-11-10", "reg", 1262, "matured_d1", 1262, "retained_d1", 53, "deposit_amount_d1", "6310.00",
                "matured_d7", 0, "retained_d7", null),
            row("reg_dt", "2026-11-01T00:00:00", "reg", 1000, "matured_d1", 1000, "retained_d1", 40, "deposit_amount_d1", 5000,
                "matured_d7", 1000, "retained_d7", 7, "deposit_amount_d7", "37150.00")), OFFSETS);
    }

    @Test
    void parsesRowsAndMaturity()
    {
        Map<LocalDate, PromoCohort.Day> d = days();
        assertEquals(3, d.size());
        PromoCohort.Day nov11 = d.get(LocalDate.of(2026, 11, 11));
        assertEquals(false, nov11.isMatured(1));
        assertNull(nov11.retained(1), "未到期是 null，不是 0");
        assertTrue(d.get(LocalDate.of(2026, 11, 10)).isMatured(1));
        assertEquals(false, d.get(LocalDate.of(2026, 11, 10)).isMatured(7));
    }

    @Test
    void retentionIsWeightedByMaturedMembers()
    {
        // 次日留存 = (53 + 40) ÷ (1262 + 1000) = 4.11%，只算到期的两天，11-11 不进分母
        PromoCohort.Agg a = PromoCohort.agg(days().values(), 1, null);
        assertEquals(2, a.days);
        assertEquals(LocalDate.of(2026, 11, 1), a.first);
        assertEquals(LocalDate.of(2026, 11, 10), a.last);
        assertEquals(new BigDecimal("4.11"), PromoCohort.value(PromoCohort.REG_RET, a));
        // 单日与 UDS rt_d1 一致：53 ÷ 1262 = 0.0419968 → 4.20%
        PromoCohort.Agg one = PromoCohort.agg(List.of(days().get(LocalDate.of(2026, 11, 10))), 1, null);
        assertEquals(new BigDecimal("4.20"), PromoCohort.value(PromoCohort.REG_RET, one));
        assertNull(PromoCohort.mark(a));
    }

    @Test
    void ltvIsDepositPerMaturedMember()
    {
        // LTV D7 = 37150 ÷ 1000（UDS 文档：1,367,095.77 ÷ 36,798 = 37.15 同一算法）
        PromoCohort.Agg a = PromoCohort.agg(days().values(), 7, null);
        assertEquals(1, a.days);
        assertEquals(new BigDecimal("37.15"), PromoCohort.value(PromoCohort.LTV, a));
        assertEquals(new BigDecimal("37.15"),
            PromoCohort.value(PromoCohort.LTV, PromoCohort.agg(List.of(PromoCohort.day(
                row("reg", 45102, "matured_d7", 36798, "deposit_amount_d7", "1367095.77"), null, OFFSETS)), 7, null)));
    }

    @Test
    void roasOnlyCountsDaysWithSpend()
    {
        Map<LocalDate, BigDecimal> spend = Map.of(LocalDate.of(2026, 11, 10), new BigDecimal("12620"));
        // 11-01 没有消耗：不进分子分母；ROAS D1 = 6310 ÷ 12620 = 50.00%
        PromoCohort.Agg a = PromoCohort.agg(days().values(), 1, spend::get);
        assertEquals(1, a.days);
        assertEquals(new BigDecimal("50.00"), PromoCohort.value(PromoCohort.ROAS, a));
        // D7 只有 11-01 到期，但它没有消耗 → NO_SPEND
        PromoCohort.Agg d7 = PromoCohort.agg(days().values(), 7, spend::get);
        assertNull(PromoCohort.value(PromoCohort.ROAS, d7));
        assertEquals("NO_SPEND", PromoCohort.mark(d7));
        // D30 全部未到期 → PENDING
        assertEquals("PENDING", PromoCohort.mark(PromoCohort.agg(days().values(), 30, spend::get)));
    }

    @Test
    void valuesFollowOffsets()
    {
        List<BigDecimal> v = PromoCohort.values(PromoCohort.REG_RET, days().values(), OFFSETS, d -> null);
        assertEquals(OFFSETS.size(), v.size());
        assertEquals(new BigDecimal("4.11"), v.get(0));
        assertNull(v.get(1), "D3 没有字段 → 未到期");
        assertEquals(new BigDecimal("0.70"), v.get(2));
    }

    @Test
    void batchStates()
    {
        PromoCohort.Day nov10 = days().get(LocalDate.of(2026, 11, 10));
        assertEquals("PENDING", PromoAnalysisService.batchState(true, nov10, 7, true, null, false));
        assertEquals("NO_SOURCE", PromoAnalysisService.batchState(false, nov10, 1, false, null, false));
        assertEquals("OK", PromoAnalysisService.batchState(true, nov10, 1, false, null, false));
        assertEquals("PENDING", PromoAnalysisService.batchState(true, nov10, 7, false, null, false), "UDS 说还没到期");
        assertEquals("NO_SPEND", PromoAnalysisService.batchState(true, nov10, 1, false, null, true));
        assertEquals("OK", PromoAnalysisService.batchState(true, nov10, 1, false, BigDecimal.TEN, true));
        assertEquals("OK", PromoAnalysisService.batchState(true, null, 1, false, null, false), "当天没有注册");
    }

    @Test
    void cardMetric()
    {
        assertEquals("regRet", PromoAnalysisService.cardMetric("regRet7"));
        assertEquals("ltv", PromoAnalysisService.cardMetric("ltv7"));
        assertEquals("roas", PromoAnalysisService.cardMetric("roas7"));
    }
}
