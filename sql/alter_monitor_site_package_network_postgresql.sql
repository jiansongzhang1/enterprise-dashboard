-- 站点设置增加「所属包网」（dashboard_db）。可重复执行。
-- 一个包网可对应多个站点，一个站点只属于一个包网：直接存在站点设置行上，同名即同一包网，为空表示未设置。
-- 在「系统管理 · 站点管理」页面维护；菜单和按钮权限见 sql/add_system_site_menu_postgresql.sql。

ALTER TABLE monitor_site_setting ADD COLUMN IF NOT EXISTS package_network VARCHAR(64);

COMMENT ON COLUMN monitor_site_setting.package_network IS '所属包网：一个包网可对应多个站点，一个站点只属于一个包网；同名即同一包网，为空表示未设置。系统管理 · 站点管理维护';

CREATE INDEX IF NOT EXISTS idx_monitor_site_package_network ON monitor_site_setting (package_network);

SELECT site_code, package_network, next_rule_no, update_by, update_time FROM monitor_site_setting ORDER BY site_code;
