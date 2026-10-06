-- 迁移：交易明细拆分为「存款明细」「提款明细」两个接口（对齐原型 MVP-V1.0）。
--
--   旧：POST /dashboard/records/transaction   权限 dashboard:record:transaction（menu_id 134）
--   新：POST /dashboard/records/deposit       权限 dashboard:record:deposit   （沿用 menu_id 134）
--       POST /dashboard/records/withdraw      权限 dashboard:record:withdraw  （新增 menu_id 137）
--
-- 原来能看交易明细的角色，迁移后同时拥有存款、提款两个权限点，不会有人因为拆分丢权限。
-- 脚本可重复执行。与新版后端一起上线：先跑脚本再发版，或同一窗口内完成；
-- 顺序反了，旧权限点会在发版到跑脚本之间让新接口 403。

-- 1. 134：交易明细 → 存款明细（权限点改名，已授权的角色关系 sys_role_menu 原样保留）
UPDATE sys_menu
SET menu_name = '存款明细查询',
    perms = 'dashboard:record:deposit',
    remark = '存款明细查询接口权限',
    update_by = 'admin', update_time = CURRENT_TIMESTAMP
WHERE perms = 'dashboard:record:transaction' OR (menu_id = 134 AND perms <> 'dashboard:record:deposit');

-- 2. 新增 137：提款明细，挂在 134 的同一个父节点下（新旧菜单结构都适用）
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT
    137, '提款明细查询', COALESCE((SELECT parent_id FROM sys_menu WHERE menu_id = 134), 132), 3, '', '', '', '',
    1, 0, 'F', '1', '0', 'dashboard:record:withdraw', '#', 'admin',
    CURRENT_TIMESTAMP, '', NULL, '提款明细查询接口权限'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE menu_id = 137 OR perms = 'dashboard:record:withdraw'
);

-- 投注明细排到最后
UPDATE sys_menu SET order_num = 4 WHERE menu_id = 135;

-- 3. 原来拥有 134 的角色，同时授予 137
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT rm.role_id, 137
FROM sys_role_menu rm
WHERE rm.menu_id = 134
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_menu x WHERE x.role_id = rm.role_id AND x.menu_id = 137
  );

-- 4. 自检：不应再有 dashboard:record:transaction；134 / 137 的授权角色数应相同
SELECT menu_id, menu_name, perms, parent_id, order_num FROM sys_menu
WHERE perms LIKE 'dashboard:record:%' ORDER BY menu_id;

SELECT menu_id, count(*) AS roles FROM sys_role_menu
WHERE menu_id IN (134, 137) GROUP BY menu_id ORDER BY menu_id;
