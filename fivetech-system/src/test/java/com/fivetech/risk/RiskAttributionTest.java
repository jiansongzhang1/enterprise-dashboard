package com.fivetech.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.configcenter.ConfigCenterService;
import com.fivetech.configcenter.ConfigCodes;
import com.fivetech.configcenter.domain.ConfigItem;
import com.fivetech.configcenter.mapper.ConfigItemMapper;
import com.fivetech.dashboard.domain.query.OverviewQuery;
import com.fivetech.dashboard.domain.vo.MetricCardVO;
import com.fivetech.dashboard.domain.vo.MetricsBlockVO;
import com.fivetech.dashboard.domain.vo.OverviewVO;
import com.fivetech.risk.overview.RiskAttribution;
import tools.jackson.databind.json.JsonMapper;

/**
 * 风控概览的变化归因：维度名称来自配置中心 ATTRIBUTION_DIMENSIONS、随概览接口下发；各类指标的 reasonCode。
 */
class RiskAttributionTest
{
    /** key = code + "|" + site */
    private final Map<String, ConfigItem> rows = new LinkedHashMap<>();

    private RiskAttribution attribution()
    {
        ConfigItemMapper cm = (ConfigItemMapper) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { ConfigItemMapper.class },
            (p, method, args) -> switch (method.getName())
            {
                case "selectOne" -> rows.get(args[0] + "|" + (args[1] == null ? "" : args[1]));
                default -> throw new UnsupportedOperationException(method.getName());
            });
        return new RiskAttribution(new ConfigCenterService(cm, null, JsonMapper.builder().build()));
    }

    private void put(String site, String json)
    {
        ConfigItem it = new ConfigItem();
        it.setId((long) rows.size() + 1);
        it.setConfigCode(RiskAttribution.CODE);
        it.setSiteCode(site);
        it.setConfigValue(json);
        rows.put(RiskAttribution.CODE + "|" + site, it);
    }

    private static MetricCardVO card(String code, String label)
    {
        MetricCardVO c = new MetricCardVO();
        c.setCode(code);
        c.setLabel(label);
        return c;
    }

    private static OverviewQuery query(String dimension)
    {
        OverviewQuery q = new OverviewQuery();
        q.setScene("RISK");
        if (dimension != null)
        {
            OverviewQuery.Attribution a = new OverviewQuery.Attribution();
            a.setDimension(dimension);
            q.setAttribution(a);
        }
        return q;
    }

    private static MetricsBlockVO block()
    {
        MetricsBlockVO b = new MetricsBlockVO();
        b.getItems().add(card("net", "存提差"));
        b.getItems().add(card("reg", "註冊人數"));
        b.getItems().add(card("wdr", "提存比"));
        b.getItems().add(card("wPendN", "待審核筆數"));
        b.getItems().add(card("ggr", "GGR"));
        return b;
    }

    @Test
    void defaultIsValidAndRegistered()
    {
        assertNull(RiskAttribution.validate(RiskAttribution.DEFAULT));
        assertTrue(ConfigCodes.get(RiskAttribution.CODE) != null, "在配置中心登记了示例与校验");
    }

    @Test
    void namesComeWithOverviewWithoutAttributionRequest()
    {
        RiskAttribution ra = attribution();
        RiskAttribution.Config c = ra.config("");
        OverviewVO vo = new OverviewVO();
        MetricsBlockVO b = block();
        ra.apply(vo, b, ra.dimension(query(null), c), c);

        assertEquals(7, vo.getAttributionDimensions().size());
        assertEquals(Map.of("code", "agent", "name", "推廣商"), vo.getAttributionDimensions().get(0));
        // 存提差：推廣商、推廣渠道、新老用戶、存款檔位，名称随卡片下发
        List<Map<String, Object>> net = b.getItems().get(0).getAttributionDims();
        assertEquals(List.of("推廣商", "推廣渠道", "新老用戶", "存款檔位"), net.stream().map(m -> m.get("name")).toList());
        assertEquals(List.of("agent", "channel", "age", "depBand"), net.stream().map(m -> m.get("code")).toList());
        assertEquals(2, b.getItems().get(1).getAttributionDims().size(), "注册人数只按推广商、推广渠道");
        assertTrue(b.getItems().get(2).getAttributionDims().isEmpty(), "比率类没有维度");
        assertTrue(b.getItems().get(3).getAttributionDims().isEmpty(), "快照没有维度");
        assertEquals(7, b.getItems().get(4).getAttributionDims().size(), "GGR 另有游戏平台、类型、游戏");
        assertNull(b.getItems().get(0).getAttribution(), "没请求归因不返回 attribution");
    }

    @Test
    void reasonCodesPerCard()
    {
        RiskAttribution ra = attribution();
        RiskAttribution.Config c = ra.config("");
        OverviewVO vo = new OverviewVO();
        MetricsBlockVO b = block();
        ra.apply(vo, b, ra.dimension(query("DEPBAND"), c), c);

        Map<String, Object> net = b.getItems().get(0).getAttribution();
        assertEquals("depBand", net.get("dimension"), "维度编码不区分大小写，返回配置里的写法");
        assertEquals("存款檔位", net.get("dimensionName"));
        assertEquals(false, net.get("supported"));
        assertEquals("PENDING_SOURCE", net.get("reasonCode"));
        assertTrue(((String) net.get("reason")).contains("存款檔位"));
        assertEquals("NOT_CONFIGURED", b.getItems().get(1).getAttribution().get("reasonCode"));
        Map<String, Object> wdr = b.getItems().get(2).getAttribution();
        assertEquals("RATIO", wdr.get("reasonCode"));
        assertEquals(Map.of("tab", "rank", "board", "wd"), wdr.get("goTo"));
        assertEquals("SNAPSHOT", b.getItems().get(3).getAttribution().get("reasonCode"));
        assertTrue(!net.containsKey("total") && !net.containsKey("rows"), "不支持时没有 total / rows");
    }

    @Test
    void defaultCarriesUdsFieldsFromMappingSheet()
    {
        RiskAttribution.Config c = attribution().config("");
        assertEquals(Map.of("dataset", "risk_user_window_v1", "field", "dep_bucket"), c.uds("depBand"));
        assertEquals("reg_bucket", c.uds("age").get("field"));
        assertTrue(c.uds("agent").isEmpty(), "推廣商 UDS 没有");
    }

    @Test
    void unknownDimensionRejected()
    {
        RiskAttribution ra = attribution();
        ServiceException e = assertThrows(ServiceException.class, () -> ra.dimension(query("terminal"), ra.config("")));
        assertTrue(e.getMessage().contains("terminal"));
    }

    @Test
    void siteOverridesNamesAndBadConfigFallsBack()
    {
        put("", "{\"RISK\":{\"dimensions\":[{\"code\":\"agent\",\"name\":\"代理\"},{\"code\":\"vip\",\"name\":\"VIP 等級\"}],"
            + "\"metrics\":{\"net\":[\"vip\",\"agent\"]}}}");
        put("J9", "{\"RISK\":{\"dimensions\":[{\"code\":\"agent\",\"name\":\"推廣夥伴\"}],\"metrics\":{\"net\":[\"agent\"]}}}");
        RiskAttribution ra = attribution();

        MetricsBlockVO b = block();
        OverviewVO vo = new OverviewVO();
        RiskAttribution.Config all = ra.config("XX");
        ra.apply(vo, b, ra.dimension(query("vip"), all), all);
        assertEquals(List.of("VIP 等級", "代理"), b.getItems().get(0).getAttributionDims().stream().map(m -> m.get("name")).toList(),
            "新维度只改配置；按 metrics 里的顺序");
        assertEquals("PENDING_SOURCE", b.getItems().get(0).getAttribution().get("reasonCode"));

        RiskAttribution.Config j9 = ra.config("J9");
        assertEquals(Map.of("agent", "推廣夥伴"), j9.getNames(), "站点覆盖全部站点");

        put("BAD", "{\"RISK\":{\"dimensions\":[{\"code\":\"agent\"}]}}");
        assertEquals(7, ra.config("BAD").getNames().size(), "配置有误时用代码默认");
    }

    @Test
    void structureErrors()
    {
        assertTrue(RiskAttribution.validate("{\"PROMO\":{}}").contains("場景"));
        assertTrue(RiskAttribution.validate("{\"RISK\":{\"dimensions\":[]}}").contains("dimensions"));
        assertTrue(RiskAttribution.validate("{\"RISK\":{\"dimensions\":[{\"code\":\"a\",\"name\":\"A\"},{\"code\":\"a\",\"name\":\"B\"}]}}")
            .contains("重複"));
        assertTrue(RiskAttribution.validate("{\"RISK\":{\"dimensions\":[{\"code\":\"a\",\"name\":\"A\"}],\"metrics\":{\"net\":[\"b\"]}}}")
            .contains("不在 dimensions"));
        assertTrue(RiskAttribution.validate("{\"RISK\":{\"dimensions\":[{\"code\":\"d\",\"name\":\"D\",\"uds\":{\"dataset\":\"x\"}}]}}")
            .contains("uds"));
        assertTrue(RiskAttribution.validate("{\"RISK\":{\"dimensions\":[{\"code\":\"d\",\"name\":\"D\",\"bands\":[100,10]}]}}")
            .contains("升序"));
    }
}
