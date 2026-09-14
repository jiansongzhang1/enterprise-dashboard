-- 在线用户功能增量脚本。
-- 执行前提：sys_menu、sys_menu_i18n 和 sys_role_menu 已创建。
-- 在线状态保存在 Redis，不新增数据库表。

BEGIN;

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT 124, '在线用户', 108, 3, 'online', 'monitor/online/index', '', '',
       1, 0, 'C', '0', '0', 'monitor:online:list', 'people', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '在线用户和会话管理'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE menu_id = 124 OR perms = 'monitor:online:list'
);

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT 125, '强制下线', 124, 1, '', '', '', '',
       1, 0, 'F', '1', '0', 'monitor:online:forceLogout', '#', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '强制注销指定在线会话'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 124)
  AND NOT EXISTS (
      SELECT 1 FROM sys_menu WHERE menu_id = 125 OR perms = 'monitor:online:forceLogout'
  );

INSERT INTO sys_menu_i18n (menu_id, locale, menu_name, remark)
SELECT v.menu_id, v.locale, v.menu_name, v.remark
FROM (VALUES
    (124, 'zh-CN', '在线用户', '在线用户和会话管理'),
    (124, 'zh-TW', '在線使用者', '在線使用者與工作階段管理'),
    (124, 'en-US', 'Online Users', 'Online users and sessions'),
    (125, 'zh-CN', '强制下线', '强制注销指定在线会话'),
    (125, 'zh-TW', '強制下線', '強制註銷指定在線工作階段'),
    (125, 'en-US', 'Force Logout', 'Revoke the selected online session')
) AS v(menu_id, locale, menu_name, remark)
WHERE EXISTS (SELECT 1 FROM sys_menu m WHERE m.menu_id = v.menu_id)
  AND NOT EXISTS (
      SELECT 1 FROM sys_menu_i18n i
      WHERE i.menu_id = v.menu_id AND i.locale = v.locale
  );

-- 仅为超级管理员授权。普通角色需要由管理员按实际职责单独授权。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, v.menu_id
FROM (VALUES (124::BIGINT), (125::BIGINT)) AS v(menu_id)
WHERE EXISTS (SELECT 1 FROM sys_role r WHERE r.role_id = 1)
  AND EXISTS (SELECT 1 FROM sys_menu m WHERE m.menu_id = v.menu_id)
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_menu rm
      WHERE rm.role_id = 1 AND rm.menu_id = v.menu_id
  );

COMMIT;
