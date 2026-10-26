package com.fivetech.monitor.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.monitor.domain.MonitorOwner;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.vo.MonitorOwnerVO;
import com.fivetech.monitor.mapper.MonitorMetaMapper;

/** 负责人候选按站点过滤：只列出、只允许能进入本站的用户（不连数据库） */
class MonitorRuleOwnerSiteTest
{
    private static final MonitorScope SITE_IN = new MonitorScope("SITE-IN", 1L);

    private static final MonitorScope JITO = new MonitorScope("JITO", 1L);

    private final Map<Long, MonitorOwner> owners = new HashMap<>();

    private MonitorRuleServiceImpl service;

    private static MonitorOwner owner(long id, String email, String userType, String... roleSites)
    {
        MonitorOwner o = new MonitorOwner();
        o.setUserId(id);
        o.setEmail(email);
        o.setUserType(userType);
        o.setRoleSiteCodes(new ArrayList<>(Arrays.asList(roleSites)));
        return o;
    }

    @BeforeEach
    void setUp()
    {
        owners.put(1L, owner(1, "admin@x.com", "01"));                 // 超级管理员：全部站点
        owners.put(2L, owner(2, "in@x.com", "00", "SITE-IN"));         // 只有 SITE-IN 角色
        owners.put(3L, owner(3, "jito@x.com", "00", "JITO"));          // 只有 JITO 角色
        owners.put(4L, owner(4, "common@x.com", "00", ""));            // 通用角色 → 默认站点
        owners.put(5L, owner(5, "both@x.com", "00", "JITO", ""));      // JITO 角色 + 通用角色
        owners.put(6L, owner(6, "norole@x.com", "00"));                // 没有站点角色（老账号）→ 默认站点

        MonitorMetaMapper meta = (MonitorMetaMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { MonitorMetaMapper.class }, (proxy, method, args) -> {
                    switch (method.getName())
                    {
                        case "selectOwnerCandidates":
                            return new ArrayList<>(owners.values());
                        case "selectOwnerCandidate":
                            return owners.get((Long) args[1]);
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
        DashboardProperties props = new DashboardProperties();
        props.setDefaultSite("SITE-IN");
        DashboardProperties.Site jito = new DashboardProperties.Site();
        jito.setCode("JITO");
        props.setSites(List.of(jito));
        service = new MonitorRuleServiceImpl(null, meta, null, null, null, null, props, null);
    }

    private List<Long> listed(MonitorScope scope)
    {
        List<Long> ids = new ArrayList<>();
        for (MonitorOwnerVO vo : service.listOwners(scope))
        {
            ids.add(vo.getUserId());
        }
        ids.sort(null);
        return ids;
    }

    private boolean accepted(MonitorScope scope, Long ownerUserId, Long existingOwnerId)
    {
        Map<String, String> errors = new HashMap<>();
        service.checkOwner(scope, ownerUserId, existingOwnerId, errors);
        return !errors.containsKey("ownerUserId");
    }

    @Test
    void ownersListedOnlyForSitesTheyCanEnter()
    {
        assertEquals(List.of(1L, 2L, 4L, 5L, 6L), listed(SITE_IN));
        assertEquals(List.of(1L, 3L, 5L), listed(JITO), "只有 SITE-IN 权限、通用角色、老账号都不能出现在 JITO 的下拉里");
    }

    @Test
    void cannotAssignOwnerFromAnotherSite()
    {
        assertFalse(accepted(JITO, 2L, null), "JITO 规则不能指派只有 SITE-IN 权限的人");
        assertFalse(accepted(JITO, 4L, null));
        assertFalse(accepted(SITE_IN, 3L, null), "SITE-IN 规则不能指派只有 JITO 权限的人");
        assertTrue(accepted(JITO, 3L, null));
        assertTrue(accepted(JITO, 1L, null), "超级管理员任何站点都可以");
        assertTrue(accepted(SITE_IN, 5L, null));
        assertFalse(accepted(SITE_IN, 99L, null), "不在候选里（没有 monitor:rule:list 等）");
    }

    @Test
    void keepingExistingOwnerOnUpdateIsAllowed()
    {
        assertTrue(accepted(JITO, 2L, 2L), "修改时保留原负责人不报错（原负责人失效由其他人改掉）");
    }

    @Test
    void roleSitesFromQueryAreParsed()
    {
        MonitorOwner o = new MonitorOwner();
        o.setRoleSites("JITO,*,SITE-IN");
        assertEquals(List.of("JITO", "", "SITE-IN"), o.getRoleSiteCodes(), "* 是通用角色");
        o.setRoleSites(null);
        assertEquals(List.of(), o.getRoleSiteCodes(), "没有正常角色");
        o.setRoleSites("*");
        assertTrue(service.ownerInSite(o, "SITE-IN"));
        assertFalse(service.ownerInSite(o, "JITO"), "只有通用角色只能进默认站点");
    }

    @Test
    void ownerInSiteHandlesNullAndUnknownSite()
    {
        assertFalse(service.ownerInSite(null, "SITE-IN"));
        assertFalse(service.ownerInSite(owners.get(1L), "NOPE"), "未配置的站点");
        assertTrue(service.ownerInSite(owners.get(3L), "jito"), "站点编码不区分大小写");
    }
}
