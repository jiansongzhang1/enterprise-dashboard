package com.fivetech.system.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.fivetech.common.core.domain.entity.SysMenu;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.system.domain.vo.RouterVo;
import tools.jackson.databind.json.JsonMapper;

/** /getRouters 的 group_code：每个节点都输出，菜单没设置时为 site；新增 / 修改的取值校验 */
class SysMenuRouterGroupTest
{
    private final SysMenuServiceImpl service = new SysMenuServiceImpl();

    private static SysMenu menu(long id, long parentId, String type, String path, String component, String group)
    {
        SysMenu m = new SysMenu();
        m.setMenuId(id);
        m.setParentId(parentId);
        m.setMenuType(type);
        m.setMenuName("m" + id);
        m.setPath(path);
        m.setComponent(component);
        m.setIsFrame("1");
        m.setIsCache("0");
        m.setVisible("0");
        m.setGroupCode(group);
        return m;
    }

    @Test
    void everyNodeCarriesGroupCodeDefaultSite()
    {
        SysMenu overview = menu(1, 0, "M", "overview", null, null);
        overview.setChildren(new ArrayList<>(List.of(menu(11, 1, "C", "index", "dashboard/overview/index", " "))));
        SysMenu game = menu(2, 0, "M", "game", null, "game");
        game.setChildren(new ArrayList<>(List.of(menu(21, 2, "C", "overview", "game/overview/index", "game"))));
        SysMenu single = menu(3, 0, "C", "records", "dashboard/records/index", null);   // 一级菜单：会包一层 Layout

        List<RouterVo> routers = service.buildMenus(new ArrayList<>(List.of(overview, game, single)));
        assertEquals("site", routers.get(0).getGroupCode(), "没设置 = site");
        assertEquals("site", routers.get(0).getChildren().get(0).getGroupCode(), "空白 = site");
        assertEquals("game", routers.get(1).getGroupCode());
        assertEquals("game", routers.get(1).getChildren().get(0).getGroupCode());
        assertEquals("site", routers.get(2).getGroupCode());
        assertEquals("site", routers.get(2).getChildren().get(0).getGroupCode(), "一级菜单生成的子路由也带上");
    }

    @Test
    void jsonFieldIsSnakeCaseNextToComponent()
    {
        SysMenu game = menu(2, 0, "M", "game", null, "game");
        game.setChildren(new ArrayList<>(List.of(menu(21, 2, "C", "overview", "game/overview/index", "game"))));
        String json = JsonMapper.builder().build().writeValueAsString(service.buildMenus(new ArrayList<>(List.of(game))));
        assertTrue(json.contains("\"group_code\":\"game\""), json);
        assertFalse(json.contains("groupCode"), json);
    }

    @Test
    void groupCodeValidation()
    {
        assertEquals("site", SysMenuServiceImpl.checkGroupCode(null));
        assertEquals("site", SysMenuServiceImpl.checkGroupCode("  "));
        assertEquals("game", SysMenuServiceImpl.checkGroupCode(" GAME "));
        assertThrows(ServiceException.class, () -> SysMenuServiceImpl.checkGroupCode("risk"));
    }
}
