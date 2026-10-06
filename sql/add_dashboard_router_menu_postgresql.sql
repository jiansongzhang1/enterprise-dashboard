-- 前端路由：/getRouters 需要返回 overview / metrics / detail 三个顶级路由。
--
-- 前端自己维护 name → 页面组件的映射，后端只负责按权限下发 name 和 hidden。
-- 因此这三条只需要是「顶级目录」，path/component 前端不看，但仍按规范填好，
-- 以便后台菜单管理里能正常显示和授权。
--
-- 为什么必须是 menu_type='M'（目录）而不是 'C'（菜单）：
--   SysMenuServiceImpl.isMenuFrame() 对「顶级 + C 类型 + 非外链」的菜单
--   会把 name 置空、并把自己包成 children 的一层。要让 name 出现在顶层，
--   只能用目录类型。
--
-- 为什么 route_name 必须显式填：
--   没配 route_name 时服务端会退回 path 并首字母大写，得到 "Overview"；
--   Vue 路由 name 大小写敏感，前端映射会落空。配置版本已改为「配了就原样下发」。
--
-- 依赖：SysMenuServiceImpl.getRouteName() 的改动必须一起上线，否则本脚本
--       下发的仍是首字母大写的名字。
--
-- menu_id 取 140-142，避开已占用的 100-137 段。

-- ---------- 1. 三个顶级路由 ----------
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT 140, '运营总览', 0, 5, 'overview', NULL, '', 'overview',
       1, 0, 'M', '0', '0', '', 'chart', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '运营总览（指标墙 + 获客漏斗 + 排行榜 + 留存LTV）'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 140 OR route_name = 'overview');

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT 141, '指标汇总', 0, 6, 'metrics', NULL, '', 'metrics',
       1, 0, 'M', '0', '0', '', 'table', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '指标汇总表'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 141 OR route_name = 'metrics');

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT 142, '明细查询', 0, 7, 'detail', NULL, '', 'detail',
       1, 0, 'M', '0', '0', '', 'list', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '会员/存款/提款/投注明细查询'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 142 OR route_name = 'detail');

-- ---------- 2. 把原有的权限点挂到新的父节点下 ----------
-- 131/132 原本是 C 类型菜单，会各自下发一条路由，与新的三条重复。
-- 改成 F（按钮）后不再出现在路由里，但 131 身上的 dashboard:metric:summary
-- 权限点原样保留——权限不能跟着菜单结构一起丢。
UPDATE sys_menu SET parent_id = 141, menu_type = 'F', visible = '1',
       path = '', component = NULL, update_by = 'admin', update_time = CURRENT_TIMESTAMP
WHERE menu_id = 131;

UPDATE sys_menu SET parent_id = 142, menu_type = 'F', visible = '1',
       path = '', component = NULL, update_by = 'admin', update_time = CURRENT_TIMESTAMP
WHERE menu_id = 132;

-- 会员 / 存款 / 提款 / 投注四个明细权限点 → 明细查询
UPDATE sys_menu SET parent_id = 142, update_by = 'admin', update_time = CURRENT_TIMESTAMP
WHERE menu_id IN (133, 134, 135, 137);

-- 导出权限点 → 运营总览（明细导出也校验同一个权限点，挂在哪个父节点下不影响判定）
UPDATE sys_menu SET parent_id = 140, update_by = 'admin', update_time = CURRENT_TIMESTAMP
WHERE menu_id = 136;

-- ---------- 3. 移除旧的「营运仪表板」目录 ----------
-- 它已经没有子节点，留着会多下发一条名为 Dashboard 的顶级路由，
-- 前端没有对应映射，会出现一个点不开的入口。
DELETE FROM sys_role_menu WHERE menu_id = 130;
DELETE FROM sys_menu WHERE menu_id = 130;

-- ---------- 4. 授权给所有现有角色 ----------
-- 注意这是一次性授权：本脚本之后新建的角色仍需在后台单独勾选。
-- RuoYi 的菜单按 sys_role_menu 下发，没有「对所有人永远可见」的开关，
-- 真要那样只能在 getRouters 里硬塞，那会绕过整套权限模型。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
FROM sys_role r
CROSS JOIN (VALUES (140), (141), (142)) AS m(menu_id)
WHERE r.del_flag = '0'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.role_id AND rm.menu_id = m.menu_id
  );

-- ---------- 5. 自检 ----------
-- 5.1 三条顶级路由应各一行，visible='0'（即 hidden=false）
SELECT menu_id, menu_name, route_name, path, menu_type, visible
FROM sys_menu WHERE menu_id IN (140, 141, 142) ORDER BY menu_id;

-- 5.2 不应再有 menu_type in ('M','C') 的孤儿节点指向已删除的 130
SELECT menu_id, menu_name, parent_id FROM sys_menu
WHERE parent_id = 130;

-- 5.3 每个角色都应拿到三条
SELECT r.role_id, r.role_name, count(rm.menu_id) AS granted
FROM sys_role r
LEFT JOIN sys_role_menu rm ON rm.role_id = r.role_id AND rm.menu_id IN (140, 141, 142)
WHERE r.del_flag = '0'
GROUP BY r.role_id, r.role_name ORDER BY r.role_id;

-- 5.4 权限点没有因为改结构而丢失
SELECT menu_id, menu_name, perms, parent_id, menu_type FROM sys_menu
WHERE perms LIKE 'dashboard:%' ORDER BY menu_id;
