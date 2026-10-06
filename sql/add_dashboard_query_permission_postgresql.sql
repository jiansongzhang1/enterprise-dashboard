-- 仪表板查询接口权限（指标汇总 + 四张明细表：会员 / 存款 / 提款 / 投注）。
-- 权限不会默认授予普通角色；超级管理员可直接使用，其他角色请按需授权。
-- menu_id 取 130-137，避开已占用的 100-125 段。
-- 已部署过旧版（134 = 交易明细）的库，请执行 alter_dashboard_split_deposit_withdraw_postgresql.sql 迁移。

-- 目录：营运仪表板
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT
    130, '营运仪表板', 0, 5, 'dashboard', NULL, '', '',
    1, 0, 'M', '0', '0', '', 'chart', 'admin',
    CURRENT_TIMESTAMP, '', NULL, '营运总览仪表板目录'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE menu_id = 130
);

-- 菜单：指标汇总
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT
    131, '指标汇总', 130, 1, 'metric-summary', 'dashboard/metricSummary/index', '', '',
    1, 0, 'C', '0', '0', 'dashboard:metric:summary', 'table', 'admin',
    CURRENT_TIMESTAMP, '', NULL, '指标汇总表查询'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE menu_id = 131 OR perms = 'dashboard:metric:summary'
);

-- 菜单：明细查询
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT
    132, '明细查询', 130, 2, 'records', 'dashboard/records/index', '', '',
    1, 0, 'C', '0', '0', '', 'list', 'admin',
    CURRENT_TIMESTAMP, '', NULL, '会员/存款/提款/投注明细查询'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE menu_id = 132
);

-- 按钮权限：会员明细
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT
    133, '会员明细查询', 132, 1, '', '', '', '',
    1, 0, 'F', '1', '0', 'dashboard:record:member', '#', 'admin',
    CURRENT_TIMESTAMP, '', NULL, '会员明细查询接口权限'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE menu_id = 133 OR perms = 'dashboard:record:member'
);

-- 按钮权限：存款明细
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT
    134, '存款明细查询', 132, 2, '', '', '', '',
    1, 0, 'F', '1', '0', 'dashboard:record:deposit', '#', 'admin',
    CURRENT_TIMESTAMP, '', NULL, '存款明细查询接口权限'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE menu_id = 134 OR perms = 'dashboard:record:deposit'
);

-- 按钮权限：提款明细
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT
    137, '提款明细查询', 132, 3, '', '', '', '',
    1, 0, 'F', '1', '0', 'dashboard:record:withdraw', '#', 'admin',
    CURRENT_TIMESTAMP, '', NULL, '提款明细查询接口权限'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE menu_id = 137 OR perms = 'dashboard:record:withdraw'
);

-- 按钮权限：投注明细
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT
    135, '投注明细查询', 132, 4, '', '', '', '',
    1, 0, 'F', '1', '0', 'dashboard:record:bet', '#', 'admin',
    CURRENT_TIMESTAMP, '', NULL, '投注明细查询接口权限'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE menu_id = 135 OR perms = 'dashboard:record:bet'
);

-- 按钮权限：CSV 导出（独立权限点）
-- 明细含会员账号等个人信息，「能查看」不等于「能批量导出」，故单独授权。
-- 若不想区分，把 dashboard.export.require-permission 设为 false 即可。
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT
    136, 'CSV导出', 130, 3, '', '', '', '',
    1, 0, 'F', '1', '0', 'dashboard:export:csv', '#', 'admin',
    CURRENT_TIMESTAMP, '', NULL, '仪表板查询结果导出 CSV 的权限'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE menu_id = 136 OR perms = 'dashboard:export:csv'
);
