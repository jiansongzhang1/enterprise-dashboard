package com.fivetech.copilot.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fivetech.copilot.CopilotProperties;
import com.fivetech.dashboard.config.DashboardProperties;

/** 受限工具的入参处理：时间范围、条数、状态；都不碰数据库 */
class CopilotAuditToolsTest
{
    private final CopilotProperties properties = new CopilotProperties();

    private final CopilotAuditTools tools =
        new CopilotAuditTools(properties, new DashboardProperties(), null, null);

    private CopilotProperties.DataQuery cfg()
    {
        return properties.getChat().getDataQuery();
    }

    private static Map<String, Object> input(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2)
        {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @Test
    void defaultRangeIsLastSevenDaysEndingToday()
    {
        LocalDate today = LocalDate.now(new DashboardProperties().currentZone());
        LocalDate[] r = tools.range(input(), cfg());
        assertEquals(today, r[1]);
        assertEquals(today.minusDays(6), r[0], "默认 7 天含今天");
    }

    @Test
    void explicitRangeIsKept()
    {
        LocalDate[] r = tools.range(input("from", "2026-11-01", "to", "2026-11-05"), cfg());
        assertEquals(LocalDate.of(2026, 11, 1), r[0]);
        assertEquals(LocalDate.of(2026, 11, 5), r[1]);
    }

    @Test
    void spanIsCappedAndFutureEndClamped()
    {
        LocalDate today = LocalDate.now(new DashboardProperties().currentZone());
        // 跨度超过 max-days：起点被截到 to - (maxDays - 1)
        LocalDate[] capped = tools.range(input("from", "2000-01-01", "to", today.toString()), cfg());
        assertEquals(today.minusDays(cfg().getMaxDays() - 1), capped[0]);
        // 结束日期在未来：截到今天
        LocalDate[] future = tools.range(input("to", today.plusDays(30).toString()), cfg());
        assertEquals(today, future[1]);
    }

    @Test
    void badDateFallsBackInsteadOfFailing()
    {
        LocalDate today = LocalDate.now(new DashboardProperties().currentZone());
        LocalDate[] r = tools.range(input("from", "上週一", "to", "今天"), cfg());
        assertEquals(today, r[1]);
        assertEquals(today.minusDays(6), r[0]);
    }

    @Test
    void limitIsBounded()
    {
        assertEquals(cfg().getDefaultRows(), tools.limit(input(), cfg()));
        assertEquals(5, tools.limit(input("limit", 5), cfg()));
        assertEquals(5, tools.limit(input("limit", "5"), cfg()), "模型可能把数字写成字符串");
        assertEquals(cfg().getMaxRows(), tools.limit(input("limit", 9999), cfg()), "超过上限截到上限");
        assertEquals(1, tools.limit(input("limit", 0), cfg()));
        assertEquals(cfg().getDefaultRows(), tools.limit(input("limit", "很多"), cfg()));
    }

    @Test
    void statusMapping()
    {
        assertEquals(0, CopilotAuditTools.status(input("status", "SUCCESS")));
        assertEquals(0, CopilotAuditTools.status(input("status", "success")));
        assertEquals(1, CopilotAuditTools.status(input("status", "FAIL")));
        assertNull(CopilotAuditTools.status(input()), "不传 = 成功失败都要");
        assertNull(CopilotAuditTools.status(input("status", "whatever")));
    }
}
