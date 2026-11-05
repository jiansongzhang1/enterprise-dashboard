package com.fivetech.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.domain.MetricCardConfig;
import com.fivetech.dashboard.domain.MetricGroupConfig;
import com.fivetech.dashboard.domain.PageMetricConfig;
import com.fivetech.dashboard.domain.PageMetricLayout;
import com.fivetech.dashboard.domain.SitePageMetric;
import com.fivetech.dashboard.enums.DashboardPage;
import com.fivetech.dashboard.mapper.DashboardConfigMapper;
import com.fivetech.dashboard.mapper.SitePageMetricMapper;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.exception.MonitorFieldException;

/**
 * 站点管理 › 指標配置：运营总览 / 指标汇总按站点调整指标顺序与显示（dashboard_page_metric_site）。
 * 注册表按站点排列、隐藏；后台保存 / 恢复默认的校验；刷新器能感知站点配置变化（不连数据库）。
 */
class SitePageMetricLayoutTest
{
    /** 全站默认：运营总览 reg, dep, wd, net（net 核心）；指标汇总 reg, dep, wd */
    private final List<MetricCardConfig> cards = new ArrayList<>();

    private final List<PageMetricConfig> pages = new ArrayList<>();

    /** 站点配置表（内存） */
    private final List<SitePageMetric> siteRows = new ArrayList<>();

    private final List<String> calls = new ArrayList<>();

    private boolean siteTableMissing;

    private MetricRegistry registry;

    private SitePageMetricService service;

    private DashboardConfigMapper configMapper;

    private SitePageMetricMapper siteMapper;

    private static MetricCardConfig card(String code, int sort)
    {
        MetricCardConfig c = new MetricCardConfig();
        c.setMetricCode(code);
        c.setMetricName(code + "名");
        c.setGroupCode("eg1");
        c.setValueFormat("INT");
        c.setShowAsCard(true);
        c.setSortNo(sort);
        c.setCalcType("NONE");
        return c;
    }

    private static PageMetricConfig page(String page, String code, int sort, boolean core)
    {
        PageMetricConfig p = new PageMetricConfig();
        p.setPageCode(page);
        p.setMetricCode(code);
        p.setSortNo(sort);
        p.setIsDefault(true);
        p.setEmphasis(core ? "CORE" : "NORMAL");
        return p;
    }

    private static SitePageMetric row(String site, String page, String code, int sort, boolean visible)
    {
        SitePageMetric r = new SitePageMetric();
        r.setSiteCode(site);
        r.setPageCode(page);
        r.setMetricCode(code);
        r.setSortNo(sort);
        r.setVisible(visible);
        return r;
    }

    private static Map<String, Object> item(String code, Object visible)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metricCode", code);
        if (visible != null)
        {
            m.put("visible", visible);
        }
        return m;
    }

    @BeforeEach
    void setUp()
    {
        for (String c : List.of("reg", "dep", "wd", "net"))
        {
            cards.add(card(c, cards.size() + 1));
        }
        pages.add(page("OVERVIEW", "reg", 10, false));
        pages.add(page("OVERVIEW", "dep", 20, false));
        pages.add(page("OVERVIEW", "wd", 30, false));
        pages.add(page("OVERVIEW", "net", 40, true));
        pages.add(page("SUMMARY", "reg", 10, false));
        pages.add(page("SUMMARY", "dep", 20, false));
        pages.add(page("SUMMARY", "wd", 30, false));

        configMapper = (DashboardConfigMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] { DashboardConfigMapper.class }, (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "selectAllMetricCards":
                        return cards;
                    case "selectAllPageMetrics":
                        return pages;
                    case "selectEnabledGroups":
                        MetricGroupConfig g = new MetricGroupConfig();
                        g.setGroupCode("eg1");
                        g.setGroupName("获客与转化");
                        return List.of(g);
                    case "selectMetricCardVersion":
                        return "v1";
                    default:
                        return null;
                }
            });
        siteMapper = (SitePageMetricMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] { SitePageMetricMapper.class }, (proxy, method, args) -> {
                calls.add(method.getName());
                switch (method.getName())
                {
                    case "selectAll":
                        if (siteTableMissing)
                        {
                            throw new IllegalStateException("relation \"dashboard_page_metric_site\" does not exist");
                        }
                        return new ArrayList<>(siteRows);
                    case "selectVersion":
                        return siteRows.size() + "|" + siteRows.hashCode();
                    case "deleteBySitePage":
                        String s = (String) args[0];
                        String p = (String) args[1];
                        String t = (String) args[2];
                        int before = siteRows.size();
                        siteRows.removeIf(r -> r.getSiteCode().equalsIgnoreCase(s) && r.getPageCode().equals(p)
                            && r.getDisplayType().equals(t));
                        return before - siteRows.size();
                    case "insertBatch":
                        @SuppressWarnings("unchecked")
                        List<SitePageMetric> rows = (List<SitePageMetric>) args[0];
                        siteRows.addAll(rows);
                        return rows.size();
                    default:
                        return null;
                }
            });
        DashboardConfigValidator noop = new DashboardConfigValidator()
        {
            @Override
            public void validateOrFail(List<MetricCardConfig> c, List<PageMetricConfig> p, List<String> g)
            {
            }
        };
        registry = new MetricRegistry(configMapper, noop, siteMapper);
        registry.reload();

        DashboardProperties props = new DashboardProperties();
        DashboardProperties.Site jito = new DashboardProperties.Site();
        jito.setCode("JITO");
        props.applySites("SITE-IN", "uWin", "SITE-IN", List.of(jito));
        service = new SitePageMetricService(siteMapper, registry, props);
    }

    // ---------------- 注册表：排列规则 ----------------

    @Test
    void noSiteConfigIsGlobalDefault()
    {
        assertEquals(List.of("reg", "dep", "wd", "net"), registry.columnsOf(DashboardPage.OVERVIEW, "JITO"));
        assertEquals(List.of("reg", "dep", "wd", "net"), registry.columnsOf(DashboardPage.OVERVIEW, null));
        assertFalse(registry.customized(DashboardPage.OVERVIEW, "JITO"));
        assertEquals(Integer.valueOf(1), registry.pageSortNo(DashboardPage.OVERVIEW, "JITO", "reg"));
    }

    @Test
    void siteOrderAndHiddenApplyOnlyToThatSiteAndPage()
    {
        siteRows.add(row("JITO", "OVERVIEW", "net", 10, true));
        siteRows.add(row("JITO", "OVERVIEW", "wd", 20, true));
        siteRows.add(row("JITO", "OVERVIEW", "reg", 30, false));
        siteRows.add(row("JITO", "OVERVIEW", "dep", 40, true));
        registry.reload();

        assertEquals(List.of("net", "wd", "dep"), registry.columnsOf(DashboardPage.OVERVIEW, "JITO"));
        // 站点编码大小写不敏感
        assertEquals(List.of("net", "wd", "dep"), registry.columnsOf(DashboardPage.OVERVIEW, "jito"));
        // sortNo = 站点内名次（只算显示的），跨分组也照排
        assertEquals(Integer.valueOf(1), registry.pageSortNo(DashboardPage.OVERVIEW, "JITO", "net"));
        assertEquals(Integer.valueOf(3), registry.pageSortNo(DashboardPage.OVERVIEW, "JITO", "dep"));
        assertNull(registry.pageSortNo(DashboardPage.OVERVIEW, "JITO", "reg"));
        // 其他站点、其他页面不受影响
        assertEquals(List.of("reg", "dep", "wd", "net"), registry.columnsOf(DashboardPage.OVERVIEW, "SITE-IN"));
        assertEquals(List.of("reg", "dep", "wd"), registry.columnsOf(DashboardPage.SUMMARY, "JITO"));
        assertTrue(registry.customized(DashboardPage.OVERVIEW, "JITO"));
        assertFalse(registry.customized(DashboardPage.SUMMARY, "JITO"));
        // 全站方法不变
        assertEquals(List.of("reg", "dep", "wd", "net"), registry.columnsOf(DashboardPage.OVERVIEW));
    }

    @Test
    void newMetricNotInSiteConfigGoesLastAndVisible()
    {
        siteRows.add(row("JITO", "SUMMARY", "wd", 10, true));
        siteRows.add(row("JITO", "SUMMARY", "reg", 20, true));
        registry.reload();
        // dep 没配置（如之后新上的指标）：排在已配置的后面、显示
        List<PageMetricLayout> layout = registry.layoutOf(DashboardPage.SUMMARY, "JITO");
        assertEquals("wd", layout.get(0).getMetricCode());
        assertEquals("dep", layout.get(2).getMetricCode());
        assertTrue(layout.get(2).isVisible());
        assertFalse(layout.get(2).isCustomized());
        assertEquals(Integer.valueOf(20), layout.get(2).getDefaultSortNo());
    }

    @Test
    void arrangeTiesFallBackToGlobalOrderAndIgnoresUnknown()
    {
        Map<String, SitePageMetric> o = new LinkedHashMap<>();
        o.put("c", row("S", "OVERVIEW", "c", 5, true));
        o.put("a", row("S", "OVERVIEW", "a", 5, true));
        o.put("zz", row("S", "OVERVIEW", "zz", 1, true));      // 不在页面上：忽略
        Map<String, Integer> sort = new LinkedHashMap<>();
        sort.put("a", 1);
        sort.put("b", 2);
        sort.put("c", 3);
        List<PageMetricLayout> out = MetricRegistry.arrange(List.of("a", "b", "c"), sort, o);
        assertEquals(List.of("a", "c", "b"), out.stream().map(PageMetricLayout::getMetricCode).toList());
        assertEquals(3, out.get(2).getPosition());
    }

    @Test
    void summaryColumnsFollowSiteOrderAndHideHidden()
    {
        siteRows.add(row("JITO", "SUMMARY", "wd", 10, true));
        siteRows.add(row("JITO", "SUMMARY", "dep", 20, false));
        siteRows.add(row("JITO", "SUMMARY", "reg", 30, true));
        registry.reload();
        assertEquals(List.of("wd", "reg"), registry.resolveColumns(DashboardPage.SUMMARY, "JITO", null));
        // 传了列：取交集、按站点顺序；隐藏的不返回
        assertEquals(List.of("wd", "reg"), registry.resolveColumns(DashboardPage.SUMMARY, "JITO", List.of("reg", "dep", "wd")));
        // 全是隐藏的：退回页面全部（与原来的回退方式一致）
        assertEquals(List.of("wd", "reg"), registry.resolveColumns(DashboardPage.SUMMARY, "JITO", List.of("dep")));
    }

    @Test
    void coreColumnsFollowSiteOrder()
    {
        siteRows.add(row("JITO", "OVERVIEW", "net", 10, false));
        registry.reload();
        // net 是 OVERVIEW 的核心指标但该站点隐藏 → 核心列为空
        assertTrue(registry.resolveCoreColumns(DashboardPage.OVERVIEW, "JITO", null).isEmpty());
        assertEquals(List.of("net"), registry.resolveCoreColumns(DashboardPage.OVERVIEW, "SITE-IN", null));
    }

    @Test
    void siteTableMissingFallsBackToDefault()
    {
        siteRows.add(row("JITO", "OVERVIEW", "net", 10, true));
        siteTableMissing = true;
        registry.reload();                 // 不抛异常
        assertEquals(List.of("reg", "dep", "wd", "net"), registry.columnsOf(DashboardPage.OVERVIEW, "JITO"));
    }

    // ---------------- 后台：读取 / 保存 / 恢复默认 ----------------

    @Test
    void getReturnsAllItemsWithVisibility()
    {
        siteRows.add(row("JITO", "OVERVIEW", "net", 10, true));
        siteRows.add(row("JITO", "OVERVIEW", "reg", 20, false));
        registry.reload();
        Map<String, Object> r = service.get("jito", "overview");
        assertEquals("JITO", r.get("siteCode"));
        assertEquals("OVERVIEW", r.get("page"));
        assertEquals(true, r.get("customized"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) r.get("items");
        assertEquals(4, items.size());                  // 含隐藏的
        assertEquals("net", items.get(0).get("metricCode"));
        assertEquals(true, items.get(0).get("core"));
        assertEquals(1, items.get(0).get("sortNo"));
        assertEquals(40, items.get(0).get("defaultSortNo"));
        assertEquals(false, items.get(1).get("visible"));
        assertEquals("eg1", items.get(0).get("groupCode"));
    }

    @Test
    void saveReplacesAndAppendsMissing()
    {
        Map<String, Object> r = service.save("JITO", "SUMMARY",
            List.of(item("wd", null), item("reg", "false")), "admin");
        // 漏传的 dep 排在后面、显示；sort_no 10、20、30
        assertEquals(3, siteRows.size());
        assertEquals("wd", siteRows.get(0).getMetricCode());
        assertEquals(Integer.valueOf(10), siteRows.get(0).getSortNo());
        assertFalse(siteRows.get(1).isVisible());
        assertEquals("dep", siteRows.get(2).getMetricCode());
        assertEquals(Integer.valueOf(30), siteRows.get(2).getSortNo());
        assertEquals("admin", siteRows.get(0).getUpdateBy());
        // 保存后当场生效
        assertEquals(List.of("wd", "dep"), registry.columnsOf(DashboardPage.SUMMARY, "JITO"));
        assertEquals(true, r.get("customized"));

        // 再保存一次是整体替换，不会叠加
        service.save("JITO", "SUMMARY", List.of(item("dep", true), item("wd", true), item("reg", true)), "admin");
        assertEquals(3, siteRows.size());
        assertEquals(List.of("dep", "wd", "reg"), registry.columnsOf(DashboardPage.SUMMARY, "JITO"));
    }

    @Test
    void saveValidates()
    {
        MonitorFieldException e = assertThrows(MonitorFieldException.class,
            () -> service.save("JITO", "OVERVIEW", List.of(), "a"));
        assertTrue(e.getFields().containsKey("items"));
        e = assertThrows(MonitorFieldException.class,
            () -> service.save("JITO", "SUMMARY", List.of(item("net", true)), "a"));   // net 不在指标汇总上
        assertTrue(e.getFields().get("items").contains("net"));
        e = assertThrows(MonitorFieldException.class,
            () -> service.save("JITO", "SUMMARY", List.of(item("reg", true), item("reg", false)), "a"));
        assertTrue(e.getFields().get("items").contains("重複"));
        e = assertThrows(MonitorFieldException.class, () -> service.save("JITO", "SUMMARY",
            List.of(item("reg", false), item("dep", false), item("wd", false)), "a"));
        assertTrue(e.getFields().get("items").contains("至少"));
        e = assertThrows(MonitorFieldException.class,
            () -> service.save("JITO", "HOME", List.of(item("reg", true)), "a"));
        assertTrue(e.getFields().containsKey("page"));
        assertThrows(MonitorException.class, () -> service.save("NOPE", "OVERVIEW", List.of(item("reg", true)), "a"));
        // 校验失败不写库
        assertTrue(siteRows.isEmpty());
        assertFalse(calls.contains("insertBatch"));
    }

    @Test
    void resetRestoresGlobalDefault()
    {
        siteRows.add(row("JITO", "OVERVIEW", "net", 10, true));
        siteRows.add(row("JITO", "SUMMARY", "wd", 10, true));
        registry.reload();
        Map<String, Object> r = service.reset("JITO", null);       // page 不传 = OVERVIEW
        assertEquals(false, r.get("customized"));
        assertEquals(List.of("reg", "dep", "wd", "net"), registry.columnsOf(DashboardPage.OVERVIEW, "JITO"));
        // 只删这个页面
        assertEquals(1, siteRows.size());
        assertEquals(List.of("wd", "reg", "dep"), registry.columnsOf(DashboardPage.SUMMARY, "JITO"));
    }

    @Test
    void reportSetIsSeparateAndDoesNotAffectData()
    {
        siteRows.add(row("JITO", "OVERVIEW", "net", 10, true));
        SitePageMetric rep = row("JITO", "OVERVIEW", "wd", 10, true);
        rep.setDisplayType("REPORT");
        siteRows.add(rep);
        registry.reload();
        // 取数只用 DEFAULT：REPORT 的行不混进来
        assertEquals(List.of("net", "reg", "dep", "wd"), registry.columnsOf(DashboardPage.OVERVIEW, "JITO"));
        // REPORT 单独读
        Map<String, Object> r = service.get("JITO", "OVERVIEW", "report");
        assertEquals("REPORT", r.get("displayType"));
        assertEquals(true, r.get("customized"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) r.get("items");
        assertEquals("wd", items.get(0).get("metricCode"));
        assertEquals("DEFAULT", service.get("JITO", "OVERVIEW").get("displayType"));

        // 保存 REPORT 只替换 REPORT；DEFAULT 不动
        service.save("JITO", "OVERVIEW", "REPORT", List.of(item("dep", true), item("reg", false)), "admin");
        assertEquals(List.of("net", "reg", "dep", "wd"), registry.columnsOf(DashboardPage.OVERVIEW, "JITO"));
        assertEquals(1 + 4, siteRows.size());
        assertTrue(siteRows.stream().filter(x -> "REPORT".equals(x.getDisplayType())).allMatch(x -> "OVERVIEW".equals(x.getPageCode())));

        // 恢复 REPORT 默认：只删 REPORT
        service.reset("JITO", "OVERVIEW", "REPORT");
        assertEquals(1, siteRows.size());
        assertEquals("DEFAULT", siteRows.get(0).getDisplayType());
        assertTrue(registry.customized(DashboardPage.OVERVIEW, "JITO"));

        MonitorFieldException e = assertThrows(MonitorFieldException.class,
            () -> service.get("JITO", "OVERVIEW", "WEEKLY"));
        assertTrue(e.getFields().containsKey("displayType"));
    }

    // ---------------- 刷新器：其他实例跟上 ----------------

    @Test
    void refresherSeesSiteLayoutChange()
    {
        int[] reloads = { 0 };
        MetricRegistry counting = new MetricRegistry(null, null)
        {
            @Override
            public synchronized void reload()
            {
                reloads[0]++;
            }
        };
        MetricRegistryRefresher r = new MetricRegistryRefresher(counting, configMapper, siteMapper, 0);
        r.check();
        assertEquals(1, reloads[0]);
        r.check();
        assertEquals(1, reloads[0]);        // 版本没变不重载
        siteRows.add(row("JITO", "OVERVIEW", "net", 10, true));
        r.check();
        assertEquals(2, reloads[0]);        // 只改了站点排列也会重载
    }
}
