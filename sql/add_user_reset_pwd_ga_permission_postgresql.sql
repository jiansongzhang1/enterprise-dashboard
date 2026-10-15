-- 用户管理「重设密码与验证器」按钮权限（system:user:resetPwdGa）增量脚本。
-- 重设 = 密码换成随机占位、ga_secret 置空(待绑定)、踢出会话，并发 RESET 类型一次性链接(sys_account_link)，不新增表。
-- menu_id 取当前最大值 + 1，避免与线上已有菜单冲突；可重复执行。

BEGIN;

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '重设密码与验证器', 100, 10, '', '', '', '',
       1, 0, 'F', '0', '0', 'system:user:resetPwdGa', '#', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '原密码与验证器失效并踢出会话，邮件发送一次性设置链接'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 100)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:user:resetPwdGa');

INSERT INTO sys_menu_i18n (menu_id, locale, menu_name, remark)
SELECT m.menu_id, v.locale, v.menu_name, v.remark
FROM sys_menu m
JOIN (VALUES
    ('zh-CN', '重设密码与验证器', '原密码与验证器失效并踢出会话，邮件发送一次性设置链接'),
    ('zh-TW', '重設密碼與驗證器', '原密碼與驗證器失效並踢出會話，郵件寄送一次性設定連結'),
    ('en-US', 'Reset Password & Authenticator', 'Invalidate password and authenticator, sign out, and email a one-time setup link')
) AS v(locale, menu_name, remark) ON TRUE
WHERE m.perms = 'system:user:resetPwdGa'
  AND NOT EXISTS (
      SELECT 1 FROM sys_menu_i18n i WHERE i.menu_id = m.menu_id AND i.locale = v.locale
  );

-- 仅为超级管理员授权；其他角色由管理员在「角色管理」里按需勾选
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id
FROM sys_menu m
WHERE m.perms = 'system:user:resetPwdGa'
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.role_id = 1)
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id
  );

-- 核对
SELECT m.menu_id, m.menu_name, m.perms,
       (SELECT count(*) FROM sys_menu_i18n i WHERE i.menu_id = m.menu_id) AS i18n_rows,
       (SELECT count(*) FROM sys_role_menu rm WHERE rm.menu_id = m.menu_id) AS role_rows
FROM sys_menu m WHERE m.perms = 'system:user:resetPwdGa';

COMMIT;

COMMENT ON COLUMN sys_account_link.link_type IS '链接类型：ACTIVATE 启用账号；RESET 重设密码与验证器';
