package com.fivetech.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.domain.SysSite;
import com.fivetech.dashboard.mapper.SysSiteMapper;

/** 站点以库为准：首次启动从 yml 导入、加载到 DashboardProperties、表不可用时沿用 yml、多实例按版本重载 */
class SiteRegistryTest
{
    private final List<SysSite> rows = new ArrayList<>();

    private String version = "2:a";

    private boolean broken;

    private int selects;

    private DashboardProperties props;

    private SiteRegistry registry;

    @BeforeEach
    void setUp()
    {
        props = new DashboardProperties();
        props.setDefaultSite("SITE-IN");
        props.setSiteName("uWin");
        props.setDefaultUdsSite("SITE-IN");
        DashboardProperties.Site jito = new DashboardProperties.Site();
        jito.setCode("JITO");
        jito.setName("jito");
        jito.setUdsSite("SITE-B");
        DashboardProperties.Site dup = new DashboardProperties.Site();
        dup.setCode("site-in");
        props.setSites(new ArrayList<>(List.of(jito, dup)));
        SysSiteMapper mapper = (SysSiteMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { SysSiteMapper.class }, (p, m, a) -> {
                    if (broken)
                    {
                        throw new IllegalStateException("column \"site_name\" does not exist");
                    }
                    switch (m.getName())
                    {
                        case "countAll":
                            return rows.size();
                        case "insert":
                            rows.add((SysSite) a[0]);
                            return 1;
                        case "selectAll":
                            selects++;
                            List<SysSite> copy = new ArrayList<>(rows);
                            // 与 SQL 一致：sort_no 从小到大，再按编码
                            copy.sort(java.util.Comparator.comparing((SysSite x) -> x.getSortNo() == null ? 0 : x.getSortNo())
                                    .thenComparing(SysSite::getSiteCode));
                            return copy;
                        case "selectVersion":
                            return version;
                        default:
                            throw new UnsupportedOperationException(m.getName());
                    }
                });
        registry = new SiteRegistry(mapper, props, 0);
    }

    @Test
    void seedsFromConfigThenLoads()
    {
        registry.init();
        assertEquals(2, rows.size(), "默认站点 + JITO；与默认站点重复的 site-in 跳过");
        assertTrue(rows.get(0).isDefaultSite());
        assertEquals("uWin", rows.get(0).getSiteName());
        assertEquals("SITE-B", rows.get(1).getUdsSite());
        assertEquals("Asia/Kolkata", rows.get(0).getTimezone(), "时区写明确的值：默认站点用 dashboard.timezone");
        assertEquals("Asia/Kolkata", rows.get(1).getTimezone(), "站点没配时区：也写 dashboard.timezone");
        assertEquals("INR", rows.get(0).getCurrency());
        assertEquals("INR", rows.get(1).getCurrency(), "站点没配币种：写 dashboard.currency");
        assertTrue(registry.isFromTable());
        assertEquals("SITE-IN", props.getDefaultSite());
        assertEquals(1, props.getSites().size());
        assertEquals("JITO", props.canonical("jito"));
    }

    @Test
    void tableIsSourceOfTruth()
    {
        SysSite def = new SysSite();
        def.setSiteCode("IN-MAIN");
        def.setSiteName("印度主站");
        def.setUdsSite("SITE-IN");
        def.setDefaultSite(true);
        SysSite ph = new SysSite();
        ph.setSiteCode("SITE-PH");
        ph.setSiteName("菲律賓站");
        ph.setTimezone("Asia/Manila");
        rows.add(ph);
        rows.add(def);
        registry.init();
        assertEquals("IN-MAIN", props.getDefaultSite(), "默认站点编码来自表，不再用 yml 的 SITE-IN");
        assertEquals("印度主站", props.siteName("IN-MAIN"));
        assertEquals("SITE-IN", props.udsSite("IN-MAIN"), "X-Uds-Site 用表里的 UDS 站点标识");
        assertEquals("SITE-PH", props.udsSite("site-ph"), "没设置 UDS 站点标识时用编码");
        assertEquals("Asia/Manila", props.siteTimezone("SITE-PH"));
        assertFalse(props.isKnownSite("JITO"), "yml 里的 JITO 不在表里：不再是站点");
        assertEquals(List.of("IN-MAIN", "SITE-PH"), props.accessibleSites(true, null));
        assertEquals(List.of("IN-MAIN"), props.accessibleSites(false, List.of("")), "通用角色进默认站点");
    }

    @Test
    void sitesOrderedBySortNo()
    {
        SysSite jp = new SysSite();
        jp.setSiteCode("SITE-JP");
        jp.setSiteName("日本站");
        jp.setSortNo(0);                            // 最早建的
        SysSite def = new SysSite();
        def.setSiteCode("SITE-IN");
        def.setSiteName("uWin");
        def.setDefaultSite(true);
        def.setSortNo(1);
        SysSite ph = new SysSite();
        ph.setSiteCode("SITE-PH");
        ph.setSiteName("菲律賓站");
        ph.setSortNo(2);                            // 后台新增：最大值 + 1
        rows.add(ph);
        rows.add(def);
        rows.add(jp);
        registry.init();
        assertEquals(List.of("SITE-JP", "SITE-IN", "SITE-PH"), props.allSiteCodes(), "按创建先后（排序号从小到大），默认站点不强制排第一");
        assertEquals(List.of("SITE-JP", "SITE-IN", "SITE-PH"), props.accessibleSites(true, null), "登录可切换的站点同一顺序");
        assertEquals(List.of("SITE-IN", "SITE-PH"), props.accessibleSites(false, List.of("site-ph", "")));
        assertEquals("SITE-IN", props.getDefaultSite());
    }

    @Test
    void fallsBackToConfigWhenTableMissing()
    {
        broken = true;
        registry.init();
        assertFalse(registry.isFromTable());
        assertEquals("SITE-IN", props.getDefaultSite());
        assertEquals("JITO", props.canonical("JITO"), "继续用 yml 的站点");
    }

    @Test
    void retriesAfterSqlWithoutRestart()
    {
        broken = true;
        registry.init();
        assertFalse(registry.isFromTable());
        assertTrue(registry.getLoadError().contains("site_name"), "记下失败原因");

        registry.check();
        assertFalse(registry.isFromTable(), "表还不可用：继续用 yml");

        broken = false;                     // 执行了 SQL
        registry.check();                   // 刷新周期里重试
        assertTrue(registry.isFromTable(), "不用重启，改为以库为准");
        assertNull(registry.getLoadError());
        assertEquals(2, rows.size(), "从 yml 导入默认站点和 JITO");
    }

    @Test
    void tryLoadOnDemandAndReportsNoDefault()
    {
        SysSite only = new SysSite();
        only.setSiteCode("X");
        only.setSiteName("x");
        rows.add(only);
        assertFalse(registry.tryLoad());
        assertTrue(registry.getLoadError().contains("默认站点"), registry.getLoadError());
        only.setDefaultSite(true);
        assertTrue(registry.tryLoad(), "补上默认站点后重试成功");
        assertEquals("X", props.getDefaultSite());
    }

    @Test
    void rootMessageIsFirstLineOfDeepestCause()
    {
        RuntimeException e = new RuntimeException("### Error querying database",
                new IllegalStateException("ERROR: column \"currency\" does not exist\n  Position: 52"));
        assertEquals("ERROR: column \"currency\" does not exist", SiteRegistry.rootMessage(e));
    }

    @Test
    void defaultSiteTimezoneAndCurrencyFromTable()
    {
        SysSite def = new SysSite();
        def.setSiteCode("SITE-IN");
        def.setSiteName("uWin");
        def.setDefaultSite(true);
        def.setTimezone("Asia/Tokyo");
        def.setCurrency("JPY");
        rows.add(def);
        registry.reload();
        assertEquals("Asia/Tokyo", props.siteTimezone("SITE-IN"), "默认站点的时区取站点表，不再固定 dashboard.timezone");
        assertEquals("JPY", props.siteCurrency("SITE-IN"));
        assertEquals("Asia/Tokyo", props.currentTimezone(), "没有请求上下文 = 默认站点");
    }

    @Test
    void noDefaultKeepsOldConfig()
    {
        SysSite only = new SysSite();
        only.setSiteCode("X");
        only.setSiteName("x");
        rows.add(only);
        assertThrows(IllegalStateException.class, () -> registry.reload());
        assertEquals("SITE-IN", props.getDefaultSite());
        assertNull(props.canonical("X"));
    }

    @Test
    void reloadsWhenVersionChanges()
    {
        registry.init();
        int base = selects;
        registry.check();
        assertEquals(base, selects, "版本没变不重载");
        version = "3:b";
        registry.check();
        assertEquals(base + 1, selects);
    }
}
