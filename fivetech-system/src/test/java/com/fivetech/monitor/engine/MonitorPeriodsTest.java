package com.fivetech.monitor.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** MON-18：判定窗口停在最近的已判定周期，不被后面的未判定周期挤掉 */
class MonitorPeriodsTest
{
    private static Map<String, Object> p(String from, String level)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", from);
        m.put("level", level);
        return m;
    }

    @Test
    void windowSkipsTrailingSkip()
    {
        List<Map<String, Object>> list = new ArrayList<>(List.of(p("07", "CRIT"), p("08", "CRIT"), p("09", "SKIP")));
        List<Map<String, Object>> w = MonitorPeriods.window(list, 1);
        assertEquals(1, w.size());
        assertEquals("08", w.get(0).get("from"));
        assertEquals("SKIP", MonitorPeriods.lastLevel(list));
    }

    @Test
    void trimKeepsLastJudgedAndFollowingSkips()
    {
        List<Map<String, Object>> list = new ArrayList<>(List.of(p("05", "OK"), p("06", "WARN"), p("07", "CRIT"),
                p("08", "SKIP"), p("09", "SKIP")));
        MonitorPeriods.trim(list, 2);
        assertEquals(List.of("06", "07", "08", "09"), list.stream().map(m -> m.get("from")).toList());
        assertEquals(List.of("06", "07"), MonitorPeriods.window(list, 2).stream().map(m -> m.get("from")).toList());
    }

    @Test
    void noJudgedFallsBackToLastN()
    {
        List<Map<String, Object>> list = new ArrayList<>(List.of(p("08", "SKIP"), p("09", "SKIP")));
        assertEquals(List.of("09"), MonitorPeriods.window(list, 1).stream().map(m -> m.get("from")).toList());
    }

    @Test
    void trimCapsTrailingSkips()
    {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(p("00", "CRIT"));
        for (int i = 0; i < MonitorPeriods.MAX_TRAILING_SKIPS + 10; i++)
        {
            list.add(p("s" + i, "SKIP"));
        }
        MonitorPeriods.trim(list, 1);
        assertEquals(1 + MonitorPeriods.MAX_TRAILING_SKIPS, list.size());
    }
}
