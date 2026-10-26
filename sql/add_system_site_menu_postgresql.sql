-- 系统管理 · 站点管理 菜单与按钮权限（dashboard_db）。可重复执行。
-- 页面：列出全部站点（配置里的站点 + 数据库里有设置行的站点），可设置所属包网。
-- 权限：system:site:list 查看，system:site:edit 修改包网。只给超级管理员授权，其他角色在「角色管理」里按需勾选。
-- menu_id 取当前最大值 + 1，避免与线上已有菜单冲突。

BEGIN;

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '站点管理', 1, 10, 'site', 'system/site/index', '', '',
       1, 0, 'C', '0', '0', 'system:site:list', 'international', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '站点列表与所属包网'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:site:list');

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '修改包网', p.menu_id, 1, '', '', '', '',
       1, 0, 'F', '0', '0', 'system:site:edit', '#', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '设置站点所属包网'
FROM sys_menu p
WHERE p.perms = 'system:site:list'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:site:edit');

INSERT INTO sys_menu_i18n (menu_id, locale, menu_name, remark)
SELECT m.menu_id, v.locale, v.menu_name, v.remark
FROM sys_menu m
JOIN (VALUES
    ('system:site:list', 'zh-CN', '站点管理', '站点列表与所属包网'),
    ('system:site:list', 'zh-TW', '站點管理', '站點列表與所屬包網'),
    ('system:site:list', 'en-US', 'Sites', 'Site list and package network'),
    ('system:site:edit', 'zh-CN', '修改包网', '设置站点所属包网'),
    ('system:site:edit', 'zh-TW', '修改包網', '設置站點所屬包網'),
    ('system:site:edit', 'en-US', 'Edit Package Network', 'Set the package network a site belongs to')
) AS v(perms, locale, menu_name, remark) ON v.perms = m.perms
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu_i18n i WHERE i.menu_id = m.menu_id AND i.locale = v.locale
);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id
FROM sys_menu m
WHERE m.perms IN ('system:site:list', 'system:site:edit')
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.role_id = 1)
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id
  );

COMMIT;

SELECT menu_id, menu_name, parent_id, menu_type, perms, path, component
FROM sys_menu WHERE perms LIKE 'system:site:%' ORDER BY menu_id;
