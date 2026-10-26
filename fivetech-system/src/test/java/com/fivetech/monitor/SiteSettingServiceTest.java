package com.fivetech.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.MonitorSiteSetting;
import com.fivetech.monitor.domain.vo.SiteSettingVO;
import com.fivetech.monitor.mapper.MonitorSiteMapper;
import com.fivetech.monitor.service.SiteSettingService;

/** 系统管理 · 站点管理：站点来自配置，包网存 monitor_site_setting */
class SiteSettingServiceTest
{
    private FakeMapper mapper;

    private SiteSettingService service;

    @BeforeEach
    void setUp()
    {
        DashboardProperties props = new DashboardProperties();
        props.setDefaultSite("SITE-IN");
        props.setSiteName("uWin");
        DashboardProperties.Site jito = new DashboardProperties.Site();
        jito.setCode("JITO");
        jito.setName("jito");
        jito.setUdsSite("SITE-B");
        props.setSites(new ArrayList<>(List.of(jito)));
        mapper = new FakeMapper();
        service = new SiteSettingService(mapper, props);
    }

    @Test
    void listsConfiguredSitesFirstThenOrphans()
    {
        mapper.put("SITE-IN", "包網A", 5);
        mapper.put("OLD", "包網A", 3);

        List<SiteSettingVO> list = service.list(null);

        assertEquals("SITE-IN,JITO,OLD", list.stream().map(SiteSettingVO::getSiteCode).collect(Collectors.joining(",")));
        SiteSettingVO in = list.get(0);
        assertTrue(in.isDefaultSite());
        assertEquals("uWin", in.getSiteName());
        assertEquals("包網A", in.getPackageNetwork());
        assertEquals(5, in.getNextRuleNo());
        SiteSettingVO j = list.get(1);
        assertEquals("SITE-B", j.getUdsSite());
        assertNull(j.getPackageNetwork(), "还没有设置行");
        assertEquals(1, j.getNextRuleNo());
        assertFalse(list.get(2).isConfigured());
    }

    @Test
    void filtersByPackageNetworkOrUnset()
    {
        mapper.put("SITE-IN", "包網A", 1);
        assertEquals(List.of("SITE-IN"),
                service.list("包網A").stream().map(SiteSettingVO::getSiteCode).collect(Collectors.toList()));
        assertEquals(List.of("JITO"),
                service.list("-").stream().map(SiteSettingVO::getSiteCode).collect(Collectors.toList()));
    }

    @Test
    void updateNormalizesNameAndCanonicalSite()
    {
        service.updatePackageNetwork("jito", "  包網   B ", "admin");
        assertEquals("包網 B", mapper.rows.get("JITO").getPackageNetwork());
        assertEquals("admin", mapper.rows.get("JITO").getUpdateBy());

        service.updatePackageNetwork("JITO", "   ", "admin");
        assertNull(mapper.rows.get("JITO").getPackageNetwork(), "空白表示清除");
    }

    @Test
    void rejectsUnknownSiteAndTooLongName()
    {
        assertThrows(ServiceException.class, () -> service.updatePackageNetwork("NOPE", "x", "admin"));
        assertThrows(ServiceException.class, () -> service.updatePackageNetwork("JITO", "包".repeat(65), "admin"));
    }

    static class FakeMapper implements MonitorSiteMapper
    {
        final Map<String, MonitorSiteSetting> rows = new LinkedHashMap<>();

        void put(String code, String pkg, int next)
        {
            MonitorSiteSetting s = new MonitorSiteSetting();
            s.setSiteCode(code);
            s.setPackageNetwork(pkg);
            s.setNextRuleNo(next);
            rows.put(code, s);
        }

        @Override
        public int ensureSiteSetting(MonitorScope scope)
        {
            return 0;
        }

        @Override
        public Integer lockNextRuleNo(MonitorScope scope)
        {
            return 1;
        }

        @Override
        public int increaseNextRuleNo(MonitorScope scope)
        {
            return 1;
        }

        @Override
        public LocalDateTime selectLastJudgedHour(MonitorScope scope)
        {
            return null;
        }

        @Override
        public List<MonitorSiteSetting> selectAllSiteSettings()
        {
            return new ArrayList<>(rows.values());
        }

        @Override
        public List<String> selectPackageNetworks()
        {
            return rows.values().stream().map(MonitorSiteSetting::getPackageNetwork).filter(n -> n != null)
                    .distinct().sorted().collect(Collectors.toList());
        }

        @Override
        public int upsertPackageNetwork(MonitorScope scope, String packageNetwork, String updateBy, LocalDateTime now)
        {
            MonitorSiteSetting s = rows.computeIfAbsent(scope.getSiteCode(), k -> {
                MonitorSiteSetting n = new MonitorSiteSetting();
                n.setSiteCode(k);
                n.setNextRuleNo(1);
                return n;
            });
            s.setPackageNetwork(packageNetwork);
            s.setUpdateBy(updateBy);
            s.setUpdateTime(now);
            return 1;
        }
    }
}
