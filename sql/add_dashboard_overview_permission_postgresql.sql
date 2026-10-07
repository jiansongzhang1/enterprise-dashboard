-- 运营总览按钮权限（OverviewController 的 @PreAuthorize 依赖这些 perms）
-- 依赖：add_dashboard_router_menu_postgresql.sql（菜单 140 运营总览）已执行
-- menu_id 取 143-145，避开已占用的 100-142。可重复执行。
-- 导出不单独设权限：能查询页面就能导出（整页导出复用 dashboard:overview:metrics）。

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT v.menu_id, v.menu_name, 140, v.order_num, '', NULL, '', '',
       1, 0, 'F', '0', '0', v.perms, '#', 'admin',
       CURRENT_TIMESTAMP, '', NULL, v.remark
FROM (VALUES
    (143, '指标墙查询',   1, 'dashboard:overview:metrics',     'POST /dashboard/metrics/overview'),
    (144, '排行榜查询',   2, 'dashboard:overview:leaderboard', 'POST /dashboard/metrics/rankingboard'),
    (145, '留存/渠道查询', 3, 'dashboard:overview:view',        'cohort、reg-channels')
) AS v(menu_id, menu_name, order_num, perms, remark)
WHERE NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.menu_id = v.menu_id OR m.perms = v.perms);

-- 授权：已拥有「运营总览」菜单(140)的角色自动获得 143-145
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT rm.role_id, m.menu_id
FROM sys_role_menu rm
CROSS JOIN (VALUES (143), (144), (145)) AS m(menu_id)
WHERE rm.menu_id = 140
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_menu x WHERE x.role_id = rm.role_id AND x.menu_id = m.menu_id
  );

-- 自检
SELECT menu_id, menu_name, parent_id, menu_type, perms FROM sys_menu
WHERE perms LIKE 'dashboard:%' ORDER BY menu_id;
