-- 多站点一期：角色带站点。
--   site_code 为空 = 通用角色，在所有站点生效（存量角色全部保持通用，行为不变）；
--   site_code 有值 = 站点角色，只在该站点生效。
-- 用户在某站点的菜单（getRouters）与权限（getInfo / 接口校验）= 该站点角色 + 通用角色。
-- 前端切换站点时在请求头带 X-Site-Code，重新请求 getInfo / getRouters。
-- 站点编码需与 application.yml 的 dashboard.default-site / dashboard.sites[].code 一致。可重复执行。

ALTER TABLE sys_role ADD COLUMN IF NOT EXISTS site_code VARCHAR(32) DEFAULT NULL;
COMMENT ON COLUMN sys_role.site_code IS '所属站点：为空=通用角色（所有站点生效）；有值=只在该站点生效';
CREATE INDEX IF NOT EXISTS idx_sys_role_site_code ON sys_role (site_code);

-- 示例（按需执行）：给 uWin（默认站点 SITE-IN）和 jito（JITO）各建一个 dashboard 角色，再到「角色管理」里勾菜单、到「用户管理」里分配给用户
-- INSERT INTO sys_role (role_name, role_key, role_sort, data_scope, status, site_code, create_by, create_time)
-- VALUES ('uwin:dashboard', 'uwin_dashboard', 10, '1', '0', 'SITE-IN', 'admin', CURRENT_TIMESTAMP),
--        ('jito:dashboard', 'jito_dashboard', 11, '1', '0', 'JITO',    'admin', CURRENT_TIMESTAMP);

SELECT role_id, role_name, role_key, status, site_code FROM sys_role WHERE del_flag = '0' ORDER BY role_sort, role_id;
