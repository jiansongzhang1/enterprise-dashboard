package com.fivetech.promo.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** /promo/budget/table：按 meta 的渠道全集逐日补齐，没数的 amount = null */
class BudgetTableFillTest
{
    static final LocalDate D1 = LocalDate.of(2026, 11, 1);
    static final LocalDate D2 = LocalDate.of(2026, 11, 2);

    static final List<PromoChannelDictionary.Promoter> DICT = List.of(
        new PromoChannelDictionary.Promoter("1001", "推廣商A", List.of(new PromoChannelDictionary.Channel("2001", "渠道1"),
            new PromoChannelDictionary.Channel("2002", "渠道2"))),
        new PromoChannelDictionary.Promoter("1002", "推廣商B", List.of(new PromoChannelDictionary.Channel("0007", "渠道7"))));

    static String key(Map<String, Object> m)
    {
        return m.get("date") + "|" + m.get("promoterId") + "|" + m.get("channelId");
    }

    @Test
    void noUdsDataFillsWholeDictionaryWithNullAmounts()
    {
        List<Map<String, Object>> rows = BudgetSpendService.fill(List.of(), DICT, D1, D2);
        assertEquals(6, rows.size());
        assertEquals("2026-11-01|1001|2001", key(rows.get(0)));
        assertEquals("2026-11-01|1002|0007", key(rows.get(2)));
        assertEquals("2026-11-02|1001|2001", key(rows.get(3)));
        assertEquals("推廣商A", rows.get(0).get("promoterName"));
        assertEquals("渠道1", rows.get(0).get("channelName"));
        rows.forEach(r -> assertNull(r.get("amount")));
        assertEquals(true, rows.get(0).containsKey("amount"));
    }

    @Test
    void udsValuesFillTheirCellsAndUnknownChannelsGoLastInTheirDay()
    {
        List<BudgetDataReader.Row> uds = List.of(
            new BudgetDataReader.Row(D2, "1001", "舊名A", "2002", null, new BigDecimal("12.50")),
            new BudgetDataReader.Row(D1, "9999", null, "8888", null, new BigDecimal("0")));
        List<Map<String, Object>> rows = BudgetSpendService.fill(uds, DICT, D1, D2);
        assertEquals(7, rows.size());
        assertEquals("2026-11-01|9999|8888", key(rows.get(3)));
        assertEquals("0", rows.get(3).get("amount"));
        assertEquals("8888", rows.get(3).get("channelName"));
        Map<String, Object> hit = rows.get(5);
        assertEquals("2026-11-02|1001|2002", key(hit));
        assertEquals("12.5", hit.get("amount"));
        // 名称：UDS 随行保存的优先，空的用字典
        assertEquals("舊名A", hit.get("promoterName"));
        assertEquals("渠道2", hit.get("channelName"));
        assertNull(rows.get(4).get("amount"));
    }
}
