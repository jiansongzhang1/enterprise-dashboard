package com.fivetech.promo.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.domain.vo.MemberRecordVO;
import com.fivetech.dashboard.gateway.uds.UdsClient;
import com.fivetech.dashboard.gateway.uds.UdsProperties;
import com.fivetech.dashboard.gateway.uds.UdsQueryResult;
import com.fivetech.dashboard.gateway.uds.UdsRow;
import com.fivetech.promo.analysis.market.MarketGateway;
import com.fivetech.promo.analysis.market.MarketProperties;
import com.fivetech.promo.budget.PromoChannelDictionary;
import tools.jackson.databind.json.JsonMapper;

/** 用户明细 · 注册终端筛选与最近登录时间（不调真实 UDS） */
class MemberPromoFilterTest
{
    private static final PromoTerminalDictionary.Config TERMINALS = new PromoTerminalDictionary.Config(List.of(
        new PromoTerminalDictionary.Terminal("web", "Web", List.of()),
        new PromoTerminalDictionary.Terminal("android", "Android", List.of())), PromoTerminalDictionary.DEFAULT_LANDING_MIN_PCT);

    /** 记录请求体、返回预置行 */
    static final class FakeClient extends UdsClient
    {
        final List<Map<String, Object>> bodies = new ArrayList<>();
        List<UdsRow> rows = List.of();
        boolean fail;

        FakeClient()
        {
            super(new UdsProperties(), JsonMapper.builder().build(), new DashboardProperties());
        }

        @Override
        public UdsQueryResult queryWithMeta(Map<String, Object> body)
        {
            bodies.add(body);
            if (fail)
            {
                throw new IllegalStateException("uds down");
            }
            return new UdsQueryResult(rows, "b", "v1", false, LocalDateTime.of(2026, 11, 9, 9, 0));
        }
    }

    private static MemberPromoFilter filter(boolean terminalMapped)
    {
        return filter(terminalMapped, null);
    }

    private static MemberPromoFilter filter(boolean terminalMapped, String lostField)
    {
        UdsProperties uds = new UdsProperties();
        UdsProperties.DetailDataset member = new UdsProperties.DetailDataset();
        if (terminalMapped)
        {
            member.getFields().put(MemberPromoFilter.TERMINAL_FILTER, "terminal");
        }
        if (lostField != null)
        {
            member.getFilterFields().put(MemberPromoFilter.LOST_FILTER, lostField);
        }
        uds.getDetails().put("member", member);
        PromoTerminalDictionary terminals = new PromoTerminalDictionary(null, null)
        {
            @Override
            public Config of(String siteCode)
            {
                return TERMINALS;
            }
        };
        return new MemberPromoFilter(new DashboardProperties(), new PromoChannelDictionary(null, null), terminals, uds);
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

    private static MemberRecordVO vo(String userId)
    {
        MemberRecordVO v = new MemberRecordVO();
        v.setUserId(userId);
        return v;
    }

    @Test
    void terminalFilterUsesPlayerSnapshotField()
    {
        Map<String, Object> f = filter(true).filters(List.of("WEB", "unidentified", "web"), List.of(), List.of());
        assertEquals(Map.of(MemberPromoFilter.TERMINAL_FILTER, List.of("web", "unidentified")), f);
        assertTrue(filter(true).filters(List.of(), null, null).isEmpty());
    }

    @Test
    void terminalFilterWithoutMappingFailsLoudly()
    {
        String msg = null;
        try
        {
            filter(false).filters(List.of("web"), List.of(), List.of());
        }
        catch (ServiceException e)
        {
            msg = e.getMessage();
        }
        assertEquals("用戶明細的數據還沒有「註冊終端」字段（數據平台待提供），暫時不能按它篩選", msg);
    }

    /** 流失用户：为空 / false 维持原逻辑；true 时 UDS 字段没配（留空）直接报错，配了下推 lost = "true" */
    @Test
    void lostFilter()
    {
        assertTrue(filter(true).lostFilter(null).isEmpty());
        assertTrue(filter(true).lostFilter(false).isEmpty());
        assertTrue(filter(true, "").lostFilter(null).isEmpty(), "字段留空也不影响不传 lost 的查询");
        for (MemberPromoFilter f : List.of(filter(true), filter(true, "")))
        {
            String msg = null;
            try
            {
                f.lostFilter(true);
            }
            catch (ServiceException e)
            {
                msg = e.getMessage();
            }
            assertEquals("用戶明細的數據還沒有「流失」字段（數據平台待提供），暫時不能按它篩選", msg);
        }
        assertEquals(Map.of(MemberPromoFilter.LOST_FILTER, "true"), filter(true, "is_ftd").lostFilter(true));
    }

    @Test
    void lastLoginFilledFromMarketMemberSnapshot()
    {
        FakeClient c = new FakeClient();
        c.rows = List.of(row("player_id", 1001L, "last_login_time", "2026-11-08 21:15:00"),
            row("player_id", 1002L, "last_login_time", null));
        MemberPromoFilter f = filter(true);
        f.setMarket(new MarketGateway(c, new MarketProperties()));
        List<MemberRecordVO> rows = List.of(vo("1001"), vo("1002"), vo("1003"), vo(null));
        f.fillLastLogin(rows);
        assertEquals("2026-11-08 21:15:00", rows.get(0).getLastLoginTime());
        assertNull(rows.get(1).getLastLoginTime());
        assertNull(rows.get(2).getLastLoginTime(), "会员快照没有的（07-26 前注册）为 null");
        assertEquals(1, c.bodies.size(), "一页只查一次");
        Map<String, Object> body = c.bodies.get(0);
        assertEquals("dashboard_market_member_snapshot_v1", body.get("dataset"));
        assertTrue(((List<?>) body.get("dimensions")).containsAll(List.of("player_id", "last_login_time")));
        assertTrue(body.toString().contains("player_id") && body.toString().contains("[1001, 1002, 1003]"), body.toString());
    }

    @Test
    void lastLoginFailureDoesNotBreakPage()
    {
        FakeClient c = new FakeClient();
        c.fail = true;
        MemberPromoFilter f = filter(true);
        f.setMarket(new MarketGateway(c, new MarketProperties()));
        List<MemberRecordVO> rows = List.of(vo("1001"));
        f.fillLastLogin(rows);
        assertNull(rows.get(0).getLastLoginTime());
        // 没注入市场网关、空页：不查
        filter(true).fillLastLogin(rows);
        f.fillLastLogin(List.of());
        assertEquals(1, c.bodies.size());
    }
}
