package com.fivetech.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.domain.SysSite;
import com.fivetech.dashboard.mapper.SysSiteMapper;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.exception.MonitorFieldException;
import com.fivetech.monitor.service.SiteSettingService;

/** 站点管理（站点存在 monitor_site_setting）：新增站点、改名称 / UDS 站点标识 / 时区、改编码（级联更新所有带 site_code 的表，不连数据库） */
class SiteManageServiceTest
{
    /** 站点表：编码 → 行 */
    private final Map<String, SysSite> sites = new LinkedHashMap<>();

    /** monitor_site_setting 里不是站点的行（site_name 为空）：已去掉的站点留下的数据 */
    private final List<String> orphans = new ArrayList<>();

    /** 其他带 site_code 的表：表名 → 每行的 site_code */
    private final Map<String, List<String>> tables = new LinkedHashMap<>();

    private int reloads;

    private int tryLoads;

    private boolean fromTable = true;

    private String pkgSite;

    private String pkgName;

    private SiteManageService service;

    private static SysSite site(String code, String name, String uds, boolean def)
    {
        SysSite s = new SysSite();
        s.setSiteCode(code);
        s.setSiteName(name);
        s.setUdsSite(uds);
        s.setDefaultSite(def);
        return s;
    }

    private SysSite find(String code)
    {
        for (SysSite s : sites.values())
        {
            if (s.getSiteCode().equalsIgnoreCase(code))
            {
                return s;
            }
        }
        return null;
    }

    @BeforeEach
    void setUp()
    {
        sites.put("SITE-IN", site("SITE-IN", "uWin", null, true));
        sites.put("JITO", site("JITO", "jito", "SITE-B", false));
        tables.put("sys_role", new ArrayList<>(List.of("JITO", "jito", "SITE-IN")));
        tables.put("monitor_rule", new ArrayList<>(List.of("JITO", "JITO", "SITE-IN")));
        tables.put("monitor_event", new ArrayList<>(List.of("JITO")));
        orphans.add("OLD");

        SysSiteMapper mapper = (SysSiteMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { SysSiteMapper.class }, (p, m, a) -> {
                    switch (m.getName())
                    {
                        case "countCodeIgnoreCase":
                        {
                            String code = (String) a[0];
                            String exclude = (String) a[1];
                            return (int) sites.values().stream().filter(s -> s.getSiteCode().equalsIgnoreCase(code)
                                    && (exclude == null || !s.getSiteCode().equalsIgnoreCase(exclude))).count();
                        }
                        case "selectByCodeIgnoreCase":
                            return find((String) a[0]);
                        case "selectOrphanCode":
                            return orphans.stream().filter(c -> c.equalsIgnoreCase((String) a[0])).findFirst().orElse(null);
                        case "insert":
                            orphans.remove(((SysSite) a[0]).getSiteCode());     // 沿用同编码的设置行
                            if (((SysSite) a[0]).getSortNo() == null)          // 不传排序号：最大值 + 1
                            {
                                ((SysSite) a[0]).setSortNo(sites.values().stream().mapToInt(x -> x.getSortNo() == null ? 0 : x.getSortNo()).max().orElse(0) + 1);
                            }
                            sites.put(((SysSite) a[0]).getSiteCode(), (SysSite) a[0]);
                            return 1;
                        case "update":
                        {
                            SysSite old = find((String) a[0]);
                            SysSite val = (SysSite) a[2];
                            String newCode = (String) a[1];
                            sites.remove(old.getSiteCode());
                            SysSite row = site(newCode == null ? old.getSiteCode() : newCode, val.getSiteName(), val.getUdsSite(), old.isDefaultSite());
                            row.setTimezone(val.getTimezone());
                            row.setCurrency(val.getCurrency());
                            row.setCurrencySymbol(val.getCurrencySymbol());
                            row.setSortNo(val.getSortNo() != null ? val.getSortNo() : old.getSortNo());   // coalesce(#{sortNo}, sort_no)
                            row.setUpdateBy(val.getUpdateBy());
                            sites.put(row.getSiteCode(), row);
                            return 1;
                        }
                        case "selectSiteCodeTables":
                            return new ArrayList<>(tables.keySet());
                        case "countSiteCode":
                            return (int) tables.get((String) a[0]).stream().filter(c -> c.equalsIgnoreCase((String) a[1])).count();
                        case "renameSiteCode":
                        {
                            List<String> rows = tables.get((String) a[0]);
                            int n = 0;
                            for (int i = 0; i < rows.size(); i++)
                            {
                                if (rows.get(i).equalsIgnoreCase((String) a[1]))
                                {
                                    rows.set(i, (String) a[2]);
                                    n++;
                                }
                            }
                            return n;
                        }
                        default:
                            throw new UnsupportedOperationException(m.getName());
                    }
                });
        SiteRegistry registry = new SiteRegistry(null, new DashboardProperties(), 0)
        {
            @Override
            public synchronized void reload()
            {
                reloads++;
            }

            @Override
            public boolean isFromTable()
            {
                return fromTable;
            }

            @Override
            public synchronized boolean tryLoad()
            {
                tryLoads++;
                return fromTable;
            }

            @Override
            public String getLoadError()
            {
                return fromTable ? null : "column \"currency\" does not exist";
            }
        };
        SiteSettingService settings = new SiteSettingService(null, null)
        {
            @Override
            public void updatePackageNetwork(String siteCode, String packageNetwork, String operator)
            {
                pkgSite = siteCode;
                pkgName = packageNetwork;
            }
        };
        service = new SiteManageService(mapper, registry, settings);
    }

    private static Map<String, String> body(String... kv)
    {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2)
        {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void createSite()
    {
        String code = service.create(body("siteCode", " SITE-PH ", "siteName", " 菲律賓站 ", "udsSite", "SITE-C",
                "timezone", "Asia/Manila", "packageNetwork", "  包網  A "), "admin");
        assertEquals("SITE-PH", code);
        SysSite s = sites.get("SITE-PH");
        assertEquals("菲律賓站", s.getSiteName());
        assertEquals("SITE-C", s.getUdsSite());
        assertEquals("Asia/Manila", s.getTimezone());
        assertFalse(s.isDefaultSite(), "新增的不是默认站点");
        assertEquals("admin", s.getUpdateBy());
        assertEquals(1, reloads, "保存后重载站点");
        assertEquals("SITE-PH", pkgSite);
        assertEquals("包網 A", pkgName, "包网去掉首尾和连续空白");

        service.create(body("siteCode", "SITE-X", "siteName", "x", "udsSite", "", "timezone", ""), "admin");
        assertNull(sites.get("SITE-X").getUdsSite(), "UDS 站点标识留空 = 用站点编码");
        assertNull(sites.get("SITE-X").getTimezone(), "时区留空 = 统计时区");
    }

    @Test
    void createValidates()
    {
        MonitorFieldException e = assertThrows(MonitorFieldException.class, () -> service.create(body("siteCode", "1ab",
                "siteName", " ", "udsSite", "SITE B", "timezone", "Mars/Base", "packageNetwork", "x".repeat(65)), "admin"));
        for (String f : new String[] { "siteCode", "siteName", "udsSite", "timezone", "packageNetwork" })
        {
            assertTrue(e.getFields().containsKey(f), f);
        }
        MonitorFieldException dup = assertThrows(MonitorFieldException.class,
                () -> service.create(body("siteCode", "jito", "siteName", "x"), "admin"));
        assertTrue(dup.getFields().get("siteCode").contains("已存在"));
        assertEquals(0, reloads);
        assertNull(pkgSite);
    }

    @Test
    void updateNameUdsTimezoneWithoutRename()
    {
        Map<String, Object> out = service.update("jito", body("siteName", "JITO 站", "timezone", "Asia/Tokyo"), "ops");
        assertEquals("JITO", out.get("siteCode"));
        assertEquals(Map.of(), out.get("renamed"));
        SysSite s = sites.get("JITO");
        assertEquals("JITO 站", s.getSiteName());
        assertEquals("SITE-B", s.getUdsSite(), "没传的沿用原值");
        assertEquals("Asia/Tokyo", s.getTimezone());
        assertEquals(List.of("JITO", "jito", "SITE-IN"), tables.get("sys_role"), "不改编码不动业务表");
        assertEquals(1, reloads);
    }

    @Test
    @SuppressWarnings("unchecked")
    void renameCascadesAllTables()
    {
        Map<String, Object> out = service.update("JITO", body("siteCode", "SITE-JP", "siteName", "日本站"), "ops");
        assertEquals("SITE-JP", out.get("siteCode"));
        Map<String, Integer> renamed = (Map<String, Integer>) out.get("renamed");
        assertEquals(2, renamed.get("sys_role"), "角色的站点（大小写不同也算）");
        assertEquals(2, renamed.get("monitor_rule"));
        assertEquals(1, renamed.get("monitor_event"));
        assertFalse(renamed.containsKey("monitor_site_setting"), "站点行自己单独改，不在级联列表里");
        assertEquals(List.of("SITE-JP", "SITE-JP", "SITE-IN"), tables.get("sys_role"));
        assertNull(find("JITO"));
        SysSite s = sites.get("SITE-JP");
        assertEquals("日本站", s.getSiteName());
        assertEquals("SITE-B", s.getUdsSite(), "原来就设置了 UDS 站点标识：保持");
        assertEquals(1, reloads);
    }

    @Test
    void renameKeepsUdsIdentityWhenBlank()
    {
        // 默认站点没设置 UDS 站点标识（原来用编码 SITE-IN）：改编码后固定写成 SITE-IN，X-Uds-Site 不变
        service.update("SITE-IN", body("siteCode", "IN-MAIN"), "ops");
        SysSite s = sites.get("IN-MAIN");
        assertEquals("SITE-IN", s.getUdsSite());
        assertTrue(s.isDefaultSite(), "仍是默认站点");
        assertEquals(List.of("JITO", "jito", "IN-MAIN"), tables.get("sys_role"));
    }

    @Test
    void renameRejectedWhenCodeTakenOrHasHistory()
    {
        MonitorFieldException taken = assertThrows(MonitorFieldException.class,
                () -> service.update("JITO", body("siteCode", "site-in"), "ops"));
        assertTrue(taken.getFields().get("siteCode").contains("其他站點"));

        // monitor_site_setting 里还有已去掉的站点 OLD 的设置行：不能改成 OLD，免得两个站点的数据混在一起
        MonitorFieldException history = assertThrows(MonitorFieldException.class,
                () -> service.update("JITO", body("siteCode", "old"), "ops"));
        assertTrue(history.getFields().get("siteCode").contains("monitor_site_setting（1 行）"), history.getFields().toString());
        assertEquals(List.of("JITO", "jito", "SITE-IN"), tables.get("sys_role"), "校验失败不改任何表");
        assertEquals(0, reloads);
    }

    @Test
    void caseOnlyRename()
    {
        service.update("JITO", body("siteCode", "Jito"), "ops");
        assertEquals(List.of("Jito", "Jito", "SITE-IN"), tables.get("sys_role"));
        assertEquals("Jito", find("jito").getSiteCode());
    }

    @Test
    void defaultSiteTimezoneEditableAndOtherErrors()
    {
        // 2026-10-29 起默认站点的时区也存站点表、后台可改（dashboard 各接口按站点时区）
        service.update("SITE-IN", body("timezone", "Asia/Tokyo"), "ops");
        assertEquals("Asia/Tokyo", sites.get("SITE-IN").getTimezone());
        MonitorFieldException tz = assertThrows(MonitorFieldException.class,
                () -> service.update("SITE-IN", body("timezone", "Mars/Base"), "ops"));
        assertTrue(tz.getFields().containsKey("timezone"));
        assertEquals("Asia/Tokyo", sites.get("SITE-IN").getTimezone(), "校验失败不改");
        assertEquals(404, assertThrows(MonitorException.class, () -> service.update("NOPE", body("siteName", "x"), "ops")).getCode());

        tables.put("bad;drop", new ArrayList<>());
        assertEquals(400, assertThrows(MonitorException.class,
                () -> service.update("JITO", body("siteCode", "SITE-Z"), "ops")).getCode(), "表名不合法时停止");
    }

    @Test
    void createAdoptsOrphanSettingRow()
    {
        // 编码大小写不一致：拒绝，提示用历史数据的写法
        MonitorFieldException e = assertThrows(MonitorFieldException.class,
                () -> service.create(body("siteCode", "old", "siteName", "舊站"), "admin"));
        assertTrue(e.getFields().get("siteCode").contains("「OLD」"), e.getFields().toString());
        // 一致：沿用这一行（包网、规则号、历史数据接上）
        assertEquals("OLD", service.create(body("siteCode", "OLD", "siteName", "舊站"), "admin"));
        assertEquals("舊站", sites.get("OLD").getSiteName());
        assertTrue(orphans.isEmpty());
    }

    @Test
    void notAvailableBeforeTableEnabled()
    {
        fromTable = false;
        MonitorException e = assertThrows(MonitorException.class, () -> service.create(body("siteCode", "A", "siteName", "a"), "admin"));
        assertEquals(400, e.getCode());
        assertEquals(1, tryLoads, "报错前当场重试加载一次");
        assertTrue(e.getMessage().contains("alter_monitor_site_setting_site"));
        assertTrue(e.getMessage().contains("alter_monitor_site_setting_currency"), "提示两个脚本都要执行");
        assertTrue(e.getMessage().contains("column \"currency\" does not exist"), "带上失败原因");
    }

    @Test
    void currencyAndSymbol()
    {
        service.create(body("siteCode", "SITE-CN", "siteName", "cn", "currency", " cny ", "currencySymbol", " ¥ "), "admin");
        assertEquals("CNY", sites.get("SITE-CN").getCurrency(), "币种存大写");
        assertEquals("¥", sites.get("SITE-CN").getCurrencySymbol());

        service.update("JITO", body("currency", "CNY", "currencySymbol", "¥"), "ops");
        assertEquals("CNY", sites.get("JITO").getCurrency());
        service.update("JITO", body("siteName", "JITO 站"), "ops");
        assertEquals("CNY", sites.get("JITO").getCurrency(), "没传的沿用原值");
        assertEquals("¥", sites.get("JITO").getCurrencySymbol());
        service.update("JITO", body("currency", "", "currencySymbol", ""), "ops");
        assertNull(sites.get("JITO").getCurrency(), "传空串清空 = 用 dashboard.currency");
        assertNull(sites.get("JITO").getCurrencySymbol());

        MonitorFieldException e = assertThrows(MonitorFieldException.class, () -> service.update("JITO",
                body("currency", "人民幣", "currencySymbol", "x".repeat(9)), "ops"));
        assertTrue(e.getFields().containsKey("currency"));
        assertTrue(e.getFields().containsKey("currencySymbol"));
    }

    @Test
    void sortNoCreateAndUpdate()
    {
        sites.get("SITE-IN").setSortNo(0);
        sites.get("JITO").setSortNo(1);
        service.create(body("siteCode", "SITE-CN", "siteName", "cn"), "admin");
        assertEquals(2, sites.get("SITE-CN").getSortNo(), "不传排序号：排最后（最大值 + 1）");
        service.create(body("siteCode", "SITE-PH", "siteName", "ph", "sortNo", " 5 "), "admin");
        assertEquals(5, sites.get("SITE-PH").getSortNo());

        service.update("JITO", body("sortNo", "0"), "ops");
        assertEquals(0, sites.get("JITO").getSortNo(), "改排序号");
        assertEquals("jito", sites.get("JITO").getSiteName(), "其他字段不变");
        service.update("JITO", body("siteName", "JITO 站"), "ops");
        assertEquals(0, sites.get("JITO").getSortNo(), "没传排序号：不改");
        service.update("JITO", body("sortNo", ""), "ops");
        assertEquals(0, sites.get("JITO").getSortNo(), "空串：不改");

        for (String bad : new String[] { "-1", "100000", "1.5", "abc" })
        {
            MonitorFieldException e = assertThrows(MonitorFieldException.class, () -> service.update("JITO", body("sortNo", bad), "ops"), bad);
            assertTrue(e.getFields().containsKey("sortNo"), bad);
        }
        assertEquals(0, sites.get("JITO").getSortNo(), "校验失败不改");
    }

    @Test
    void configureOrphanAsSiteWithSortNo()
    {
        // 页面上「未配置」行点修改 = 用同一编码新增站点：沿用设置行，按填的排序号排
        service.create(body("siteCode", "OLD", "siteName", "舊站", "sortNo", "3", "packageNetwork", "P1"), "admin");
        assertFalse(orphans.contains("OLD"));
        assertEquals("舊站", sites.get("OLD").getSiteName());
        assertEquals(3, sites.get("OLD").getSortNo());
        assertEquals("OLD", pkgSite);
        assertEquals("P1", pkgName);
    }
}
