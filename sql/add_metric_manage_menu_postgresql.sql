-- ============================================================================
-- 系统管理 · 指标管理：菜单与按钮权限（2026-10-26）· PostgreSQL（dashboard_db）
--
-- 页面维护 dashboard_metric_card 的口径与取数说明（不改运营总览、指标汇总的取数）：
--   system:metric:list    指标管理页面（查看）
--   system:metric:add     登记新指标（仅登记：不上卡、不进页面、不能建告警规则）
--   system:metric:edit    修改口径与取数说明；仅登记的指标还能改名称、分组、格式
--   system:metric:remove  删除仅登记、且没有被页面 / 告警规则 / 其他指标引用的指标
-- 授给超级管理员角色（role_id = 1）。可重复执行。
-- 执行：psql -X -v ON_ERROR_STOP=1 -d dashboard_db -f sql/add_metric_manage_menu_postgresql.sql
-- ============================================================================

BEGIN;

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '指标管理', 1, 12, 'metric', 'system/metric/index', '', '',
       1, 0, 'C', '0', '0', 'system:metric:list', 'chart', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '指标口径与取数方式（dashboard_metric_card）'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:metric:list');

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '登记指标', p.menu_id, 1, '', '', '', '',
       1, 0, 'F', '0', '0', 'system:metric:add', '#', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '登记新指标（仅登记，不接入取数）'
FROM sys_menu p
WHERE p.perms = 'system:metric:list'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:metric:add');

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '修改指标', p.menu_id, 2, '', '', '', '',
       1, 0, 'F', '0', '0', 'system:metric:edit', '#', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '修改口径与取数说明'
FROM sys_menu p
WHERE p.perms = 'system:metric:list'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:metric:edit');

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '删除指标', p.menu_id, 3, '', '', '', '',
       1, 0, 'F', '0', '0', 'system:metric:remove', '#', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '删除仅登记且没有被引用的指标'
FROM sys_menu p
WHERE p.perms = 'system:metric:list'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:metric:remove');

INSERT INTO sys_menu_i18n (menu_id, locale, menu_name, remark)
SELECT m.menu_id, v.locale, v.menu_name, v.remark
FROM sys_menu m
JOIN (VALUES
    ('system:metric:list',   'zh-CN', '指标管理', '指标口径与取数方式'),
    ('system:metric:list',   'zh-TW', '指標管理', '指標口徑與取數方式'),
    ('system:metric:list',   'en-US', 'Metrics', 'Metric definitions and data sources'),
    ('system:metric:add',    'zh-CN', '登记指标', '登记新指标（仅登记，不接入取数）'),
    ('system:metric:add',    'zh-TW', '登記指標', '登記新指標（僅登記，不接入取數）'),
    ('system:metric:add',    'en-US', 'Register Metric', 'Register a metric (record only, not fetched)'),
    ('system:metric:edit',   'zh-CN', '修改指标', '修改口径与取数说明'),
    ('system:metric:edit',   'zh-TW', '修改指標', '修改口徑與取數說明'),
    ('system:metric:edit',   'en-US', 'Edit Metric', 'Edit definitions and data source notes'),
    ('system:metric:remove', 'zh-CN', '删除指标', '删除仅登记且没有被引用的指标'),
    ('system:metric:remove', 'zh-TW', '刪除指標', '刪除僅登記且沒有被引用的指標'),
    ('system:metric:remove', 'en-US', 'Delete Metric', 'Delete registered, unreferenced metrics')
) AS v(perms, locale, menu_name, remark) ON v.perms = m.perms
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu_i18n i WHERE i.menu_id = m.menu_id AND i.locale = v.locale
);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id
FROM sys_menu m
WHERE m.perms IN ('system:metric:list', 'system:metric:add', 'system:metric:edit', 'system:metric:remove')
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.role_id = 1)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id);

COMMIT;

-- 执行后应有 4 行：1 个菜单 + 3 个按钮；重新登录后台后「系统管理」下出现「指标管理」
SELECT menu_id, menu_name, parent_id, menu_type, perms FROM sys_menu WHERE perms LIKE 'system:metric:%' ORDER BY menu_id;
