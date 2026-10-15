-- 用户管理「账号解锁」按钮权限（system:user:unlock）增量脚本。
-- 解锁 = 清除 Redis 中的密码错误 / 验证码错误计数，不新增数据库表。
-- menu_id 取当前最大值 + 1，避免与线上已有菜单冲突；可重复执行。

BEGIN;

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '账号解锁', 100, 8, '', '', '', '',
       1, 0, 'F', '0', '0', 'system:user:unlock', '#', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '清除登录密码/验证码错误次数，解除锁定'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 100)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:user:unlock');

INSERT INTO sys_menu_i18n (menu_id, locale, menu_name, remark)
SELECT m.menu_id, v.locale, v.menu_name, v.remark
FROM sys_menu m
JOIN (VALUES
    ('zh-CN', '账号解锁', '清除登录密码/验证码错误次数，解除锁定'),
    ('zh-TW', '帳號解鎖', '清除登入密碼／驗證碼錯誤次數，解除鎖定'),
    ('en-US', 'Unlock Account', 'Clear failed password / code attempts and unlock')
) AS v(locale, menu_name, remark) ON TRUE
WHERE m.perms = 'system:user:unlock'
  AND NOT EXISTS (
      SELECT 1 FROM sys_menu_i18n i WHERE i.menu_id = m.menu_id AND i.locale = v.locale
  );

-- 仅为超级管理员授权；其他角色由管理员在「角色管理」里按需勾选
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id
FROM sys_menu m
WHERE m.perms = 'system:user:unlock'
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.role_id = 1)
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id
  );

-- 核对
SELECT m.menu_id, m.menu_name, m.perms,
       (SELECT count(*) FROM sys_menu_i18n i WHERE i.menu_id = m.menu_id) AS i18n_rows,
       (SELECT count(*) FROM sys_role_menu rm WHERE rm.menu_id = m.menu_id) AS role_rows
FROM sys_menu m WHERE m.perms = 'system:user:unlock';

COMMIT;
