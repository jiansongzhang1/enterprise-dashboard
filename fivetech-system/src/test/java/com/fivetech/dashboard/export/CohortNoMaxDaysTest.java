package com.fivetech.dashboard.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.domain.query.CohortQuery;
import com.fivetech.dashboard.domain.query.OverviewExportQuery;
import com.fivetech.dashboard.gateway.BreakdownResult;
import com.fivetech.dashboard.gateway.MetricDataGateway;
import com.fivetech.dashboard.service.impl.OverviewSectionServiceImpl;

/** 留存与 LTV：不再限制最多 90 个分群日（2026-11-02）；接口与整页导出都按所选区间取数，仍受通用的 365 天区间校验 */
class CohortNoMaxDaysTest
{
    private final List<LocalDateTime[]> calls = new ArrayList<>();

    private DashboardProperties props()
    {
        DashboardProperties p = new DashboardProperties();
        p.setLaunchDate("2020-01-01");
        return p;
    }

    private OverviewSectionServiceImpl service()
    {
        MetricDataGateway gw = (MetricDataGateway) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] { MetricDataGateway.class }, (proxy, m, a) -> {
                if ("queryBreakdown".equals(m.getName()))
                {
                    calls.add(new LocalDateTime[] { (LocalDateTime) a[1], (LocalDateTime) a[2] });
                    // 网关不会返回 null：没数据时是「未就绪」
                    return BreakdownResult.notReady();
                }
                return null;
            });
        return new OverviewSectionServiceImpl(props(), gw);
    }

    private static CohortQuery query(LocalDate from, LocalDate to)
    {
        CohortQuery q = new CohortQuery();
        q.setSlotFrom(from.toString());
        q.setSlotTo(to.toString());
        q.setType("BOTH");
        return q;
    }

    @Test
    void moreThan90DaysAllowed()
    {
        LocalDate to = LocalDate.now(props().currentZone()).minusDays(10);
        LocalDate from = to.minusDays(200);
        service().cohort(query(from, to));          // 原来超过 90 天报 4001
        assertTrue(!calls.isEmpty());
        // 按所选区间整段取数，不截成最近 90 天
        assertEquals(from.atStartOfDay(), calls.get(0)[0]);
        assertEquals(to.atStartOfDay(), calls.get(0)[1]);
    }

    @Test
    void generalSpanLimitStillApplies()
    {
        LocalDate to = LocalDate.now(props().currentZone()).minusDays(1);
        ServiceException e = assertThrows(ServiceException.class, () -> service().cohort(query(to.minusDays(400), to)));
        assertTrue(e.getMessage().contains("365"));
    }

    @Test
    void exportKeepsWholeCohortRange()
    {
        OverviewExportQuery q = new OverviewExportQuery();
        q.setCohortFrom("2026-01-01");
        q.setCohortTo("2026-07-01");                // 181 天，原来只保留最近 90 天
        CohortQuery c = new OverviewWorkbookExporter(null, null, props()).cohortQuery(q);
        assertEquals("2026-01-01", c.getSlotFrom());
        assertEquals("2026-07-01", c.getSlotTo());
    }
}
