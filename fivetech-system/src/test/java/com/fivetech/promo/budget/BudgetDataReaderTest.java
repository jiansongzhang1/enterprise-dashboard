package com.fivetech.promo.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.gateway.uds.UdsClient;
import com.fivetech.dashboard.gateway.uds.UdsProperties;
import com.fivetech.dashboard.gateway.uds.UdsQueryException;
import com.fivetech.dashboard.gateway.uds.UdsQueryResult;
import com.fivetech.dashboard.gateway.uds.UdsRow;

/** 预算 / 消耗读取：请求体、截断、金额文本（不调真实 UDS） */
class BudgetDataReaderTest
{
    static final class FakeClient extends UdsClient
    {
        final List<Map<String, Object>> bodies = new ArrayList<>();
        final List<UdsQueryResult> answers = new ArrayList<>();

        FakeClient()
        {
            super(new UdsProperties(), JsonMapper.builder().build(), new DashboardProperties());
        }

        @Override
        public UdsQueryResult queryWithMeta(Map<String, Object> body)
        {
            bodies.add(body);
            return answers.remove(0);
        }
    }

    static UdsQueryResult result(Boolean complete, UdsRow... rows)
    {
        UdsQueryResult r = new UdsQueryResult(List.of(rows), "import_yata_promo_cost_current", null, true, null);
        r.setImportedComplete(complete);
        return r;
    }

    static UdsRow row(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2)
        {
            m.put((String) kv[i], kv[i + 1]);
        }
        return new UdsRow(m);
    }

    static UdsRow cost(String date, String ch, String amount)
    {
        return row("biz_date", date, "promoter_id", "1001", "promoter_name", "hada", "channel_id", ch, "channel_name", "n" + ch,
            "cost_amount", new BigDecimal(amount), "row_count", 1);
    }

    @Test
    void rowsInOneQueryWithOffsetZeroAndContractBody()
    {
        FakeClient c = new FakeClient();
        // UDS 不保证顺序：后端按 日期 → 推广商 → 推广渠道 排
        c.answers.add(result(true, cost("2026-12-02", "007", "3"), cost("2026-12-01", "2002", "0"), cost("2026-12-01", "2001", "1.5")));
        BudgetDataReader r = new BudgetDataReader(c, new BudgetImportProperties());
        List<BudgetDataReader.Row> rows = r.rows(BudgetKind.SPEND, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 2));
        assertEquals(3, rows.size());
        assertEquals("007", rows.get(2).getChannelId());
        assertEquals(0, new BigDecimal("1.5").compareTo(rows.get(0).getAmount()));
        assertEquals(1, c.bodies.size());
        Map<String, Object> b = c.bodies.get(0);
        assertEquals("import_yata_promo_cost", b.get("dataset"));
        assertEquals(List.of("cost_amount", "row_count"), b.get("metrics"));
        // 维度带名称：整日替换时已有行的名称原样带回，不会被写成 null
        assertEquals(List.of("biz_date", "promoter_id", "promoter_name", "channel_id", "channel_name"), b.get("dimensions"));
        assertEquals(false, b.containsKey("orderBy"));
        assertEquals("hada", rows.get(0).getPromoterName());
        assertEquals("n2001", rows.get(0).getChannelName());
        assertEquals(Map.of("channel", "ONLINE"), b.get("options"));
        assertEquals(10000, b.get("limit"));
        assertEquals(0, b.get("offset"));
        @SuppressWarnings("unchecked")
        Map<String, Object> time = (Map<String, Object>) b.get("time");
        assertEquals("2026-12-01", time.get("from"));
        assertEquals("2026-12-02", time.get("to"));
        assertEquals("DAY", time.get("granularity"));
    }

    @Test
    void truncatedResultIsAnError()
    {
        FakeClient c = new FakeClient();
        c.answers.add(result(false, cost("2026-12-01", "2001", "1")));
        BudgetDataReader r = new BudgetDataReader(c, new BudgetImportProperties());
        assertThrows(UdsQueryException.class, () -> r.rows(BudgetKind.BUDGET, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 1)));
        assertEquals("import_yata_promo_budget", c.bodies.get(0).get("dataset"));
    }

    @Test
    void amountTextIsPlainDecimalWithAtMost4Places()
    {
        assertEquals("123.4567", BudgetDataReader.amountText(new BigDecimal("123.4567")));
        assertEquals("100", BudgetDataReader.amountText(new BigDecimal("100.00")));
        assertEquals("1000", BudgetDataReader.amountText(new BigDecimal("1E+3")));
        assertEquals("0.1235", BudgetDataReader.amountText(new BigDecimal("0.12345")));
        assertEquals("0", BudgetDataReader.amountText(new BigDecimal("0.00")));
    }
}
