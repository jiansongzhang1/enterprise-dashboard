package com.fivetech.dashboard.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.fivetech.common.core.domain.entity.SysUser;
import com.fivetech.common.core.domain.model.LoginUser;
import com.fivetech.common.utils.SiteContext;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.view.domain.SaveViewRequest;
import com.fivetech.dashboard.view.domain.UserView;
import com.fivetech.dashboard.view.domain.ViewPreset;
import com.fivetech.dashboard.view.domain.ViewSlot;
import com.fivetech.dashboard.view.domain.ViewSlotItem;
import com.fivetech.dashboard.view.mapper.UserViewMapper;
import com.fivetech.monitor.exception.MonitorException;
import tools.jackson.databind.json.JsonMapper;

/** 个人视图服务：读写只针对「我在这个站点」、等于默认不存、小状态、重置、取数用的生效列表（内存里的假 mapper） */
class UserViewServiceTest
{
    /** 内存里的四张表 */
    static final class FakeMapper implements UserViewMapper
    {
        final List<ViewSlot> slots = new ArrayList<>();
        final List<ViewSlotItem> items = new ArrayList<>();
        final List<ViewPreset> presets = new ArrayList<>();
        final Map<String, UserView> views = new LinkedHashMap<>();

        static String key(Long u, String site, String slot)
        {
            return u + "|" + site + "|" + slot;
        }

        public List<ViewSlot> selectSlots() { return slots; }
        public List<ViewSlotItem> selectSlotItems() { return items; }
        public List<ViewPreset> selectPresets() { return presets; }
        public String selectDefinitionVersion() { return slots.size() + "/" + items.size(); }

        public List<UserView> selectUserViews(Long userId, String siteCode)
        {
            List<UserView> out = new ArrayList<>();
            views.values().forEach(v -> {
                if (v.getUserId().equals(userId) && v.getSiteCode().equals(siteCode))
                {
                    out.add(v);
                }
            });
            return out;
        }

        public UserView selectUserView(Long userId, String siteCode, String slotCode)
        {
            return views.get(key(userId, siteCode, slotCode));
        }

        public int upsertUserView(UserView v)
        {
            views.put(key(v.getUserId(), v.getSiteCode(), v.getSlotCode()), v);
            return 1;
        }

        public int deleteUserView(Long userId, String siteCode, String slotCode)
        {
            return views.remove(key(userId, siteCode, slotCode)) == null ? 0 : 1;
        }
    }

    FakeMapper mapper;

    UserViewService service;

    @BeforeEach
    void setUp()
    {
        mapper = new FakeMapper();
        ViewSlot cols = new ViewSlot();
        cols.setSlotCode("promo.channel.columns");
        cols.setPageCode("PROMO");
        cols.setName("表格列");
        cols.setKind("COLUMNS");
        cols.setMinItems(1);
        cols.setGroupAdjacent(true);
        cols.setStatus("0");
        mapper.slots.add(cols);
        mapper.items.add(new ViewSlotItem("promo.channel.columns", "reg", null, "conversion", "轉化", 10, null));
        mapper.items.add(new ViewSlotItem("promo.channel.columns", "ftd", null, "conversion", "轉化", 20, null));
        mapper.items.add(new ViewSlotItem("promo.channel.columns", "spend", null, "spend", "投放", 30, null));
        mapper.presets.add(new ViewPreset("promo.channel.columns", "standard", "標準", "reg,ftd", true, 10));
        JsonMapper json = JsonMapper.builder().build();
        ViewDefinitions defs = new ViewDefinitions(mapper, json, 0);
        defs.reload();
        ViewItemSource names = new ViewItemSource()
        {
            public boolean supports(ViewSlot slot) { return true; }

            public Map<String, ItemInfo> items(ViewSlot slot, String site)
            {
                return Map.of("reg", ItemInfo.of("註冊人數"), "ftd", ItemInfo.of("首存人數"), "spend", ItemInfo.of("消耗"));
            }
        };
        service = new UserViewService(defs, mapper, new DashboardProperties(), json, List.of(names));
        login(7L);
        SiteContext.set("in01", "IN01");
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
        SiteContext.clear();
    }

    static void login(Long userId)
    {
        LoginUser u = new LoginUser(userId, null, new SysUser(), Set.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
    }

    static SaveViewRequest custom(String... items)
    {
        SaveViewRequest r = new SaveViewRequest();
        r.setMode("CUSTOM");
        r.setItems(List.of(items));
        return r;
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageViewsForNewUserAreDefaultsAndStoreNothing()
    {
        Map<String, Object> page = service.pageViews("promo");
        assertEquals("PROMO", page.get("page"));
        assertEquals("IN01", page.get("siteCode"));
        List<Map<String, Object>> slots = (List<Map<String, Object>>) page.get("slots");
        assertEquals(1, slots.size());
        assertEquals(List.of("reg", "ftd"), slots.get(0).get("items"));
        assertEquals("standard", slots.get(0).get("defaultPresetCode"));
        assertTrue(mapper.views.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void customIsSavedPerUserAndSite()
    {
        Map<String, Object> slot = service.save("promo.channel.columns", custom("spend", "reg"));
        assertEquals(List.of("spend", "reg"), slot.get("items"));
        UserView row = mapper.views.get(FakeMapper.key(7L, "IN01", "promo.channel.columns"));
        assertEquals(UserView.CUSTOM, row.getMode());
        assertEquals("[\"spend\",\"reg\"]", row.getItems());
        assertEquals(List.of("spend", "reg"), service.itemsOf("promo.channel.columns"));

        // 另一个站点、另一个人：各自的默认
        SiteContext.set("jito", "JITO");
        assertEquals(List.of("reg", "ftd"), service.itemsOf("promo.channel.columns"));
        SiteContext.set("in01", "IN01");
        login(8L);
        assertEquals(List.of("reg", "ftd"), ((Map<String, Object>) service.slotView("promo.channel.columns")).get("items"));
    }

    @Test
    void backToDefaultDeletesRowButLastCustomKeepsIt()
    {
        service.save("promo.channel.columns", custom("spend"));
        SaveViewRequest def = new SaveViewRequest();
        def.setMode("DEFAULT");
        Map<String, Object> slot = service.save("promo.channel.columns", def);
        assertEquals(List.of("reg", "ftd"), slot.get("items"));
        // 留着最近一次自定义（表格列的「自定义」按钮要用）
        assertEquals(List.of("spend"), slot.get("lastCustom"));
        assertEquals(1, mapper.views.size());
        // 重置：连同小状态和最近一次自定义一起清空
        Map<String, Object> reset = service.reset("promo.channel.columns");
        assertNull(reset.get("lastCustom"));
        assertTrue(mapper.views.isEmpty());
    }

    @Test
    void attrsOnlyCreatesDefaultRowAndEmptyAttrsRemovesIt()
    {
        SaveViewRequest a = new SaveViewRequest();
        a.setAttrs(Map.of("spark", true));
        Map<String, Object> slot = service.save("promo.channel.columns", a);
        assertEquals(Map.of("spark", true), slot.get("attrs"));
        UserView row = mapper.views.values().iterator().next();
        assertEquals(UserView.DEFAULT, row.getMode());
        assertNull(row.getItems());
        assertEquals("{\"spark\":true}", row.getAttrs());
        a.setAttrs(Map.of());
        service.save("promo.channel.columns", a);
        assertTrue(mapper.views.isEmpty());
    }

    @Test
    void brokenJsonInRowIsIgnored()
    {
        UserView bad = new UserView();
        bad.setUserId(7L);
        bad.setSiteCode("IN01");
        bad.setSlotCode("promo.channel.columns");
        bad.setMode("CUSTOM");
        bad.setItems("[oops");
        bad.setAttrs("{\"selected\":\"reg\"}");
        mapper.upsertUserView(bad);
        assertEquals(List.of("reg", "ftd"), service.itemsOf("promo.channel.columns"));
    }

    @Test
    void unknownPageOrSlotIs404AndNoLoginGivesNullItems()
    {
        MonitorException page = assertThrows(MonitorException.class, () -> UserViewService.page("NOPE"));
        assertEquals(404, page.getCode());
        MonitorException slot = assertThrows(MonitorException.class, () -> service.slotView("nope"));
        assertEquals(404, slot.getCode());
        assertEquals("PROMO", service.pageOfSlot("promo.channel.columns"));
        assertNull(service.itemsOf("nope"));
        SecurityContextHolder.clearContext();
        assertNull(service.itemsOf("promo.channel.columns"));
    }
}
