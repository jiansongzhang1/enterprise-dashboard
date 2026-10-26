package com.fivetech.dashboard.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 用户能进入的站点：登录后可切换的站点与监控负责人候选共用（accessibleSites） */
class DashboardPropertiesAccessibleSitesTest
{
    private static DashboardProperties props()
    {
        DashboardProperties p = new DashboardProperties();
        p.setDefaultSite("SITE-IN");
        DashboardProperties.Site jito = new DashboardProperties.Site();
        jito.setCode("JITO");
        DashboardProperties.Site bdw = new DashboardProperties.Site();
        bdw.setCode("BDW");
        p.setSites(Arrays.asList(jito, bdw));
        return p;
    }

    @Test
    void adminGetsAllSitesInConfigOrder()
    {
        assertEquals(List.of("SITE-IN", "JITO", "BDW"), props().accessibleSites(true, Collections.emptyList()));
    }

    @Test
    void siteRoleOnlyGetsThatSite()
    {
        assertEquals(List.of("JITO"), props().accessibleSites(false, List.of("JITO")));
        assertEquals(List.of("JITO"), props().accessibleSites(false, List.of(" jito ")), "站点编码去空白、不区分大小写");
    }

    @Test
    void commonRoleAddsDefaultSite()
    {
        assertEquals(List.of("SITE-IN", "JITO"), props().accessibleSites(false, Arrays.asList("JITO", "")));
        assertEquals(List.of("SITE-IN", "JITO"), props().accessibleSites(false, Arrays.asList(null, "JITO")));
    }

    @Test
    void noSiteRoleFallsBackToDefaultSite()
    {
        assertEquals(List.of("SITE-IN"), props().accessibleSites(false, Collections.emptyList()), "老账号没有角色");
        assertEquals(List.of("SITE-IN"), props().accessibleSites(false, null));
        assertEquals(List.of("SITE-IN"), props().accessibleSites(false, List.of("NOT-CONFIGURED")),
                "角色上的站点没有配置，按没有站点角色处理");
    }

    @Test
    void multipleSiteRoles()
    {
        assertEquals(List.of("JITO", "BDW"), props().accessibleSites(false, List.of("BDW", "JITO", "JITO")));
    }
}
