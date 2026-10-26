-- ============================================================================
-- 监控管理：菜单与按钮权限
--
-- 160        目录「监控管理」（顶级路由 route_name = monitor，Dashboard 前端按 name 映射页面）
-- 161–162    异常事件：monitor:event:list / edit
-- 163–167    告警规则：monitor:rule:list / query / add / edit / remove
--
-- 2026-10-20 按原型精简：通知群不再需要绑定（去掉 monitor:group:*），系统管理一期不做（去掉 monitor:admin:edit）。
-- TODO（二期完成）订阅管理的菜单与权限（订阅管理属于二期）。
--
-- 一期不做功能级权限：拿到「监控管理」页面的角色应把 161–167 一起勾上；
-- 数据范围只按站点（后端 MonitorScope），有页面权限的人看得到本站全部规则和事件。
--
-- 本脚本不自动授权给任何角色（超级管理员天然拥有全部权限），请在后台「角色管理」中勾选。
-- menu_id 取 160–167，避开已占用的 100–145（168–172 曾用于旧版本，见文件末尾清理）。可重复执行。
-- ============================================================================

BEGIN;

-- ---------- 1. 目录 ----------
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT 160, '监控管理', 0, 8, 'monitor', NULL, '', 'monitor',
       1, 0, 'M', '0', '0', '', 'monitor', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '监控管理（异常事件 / 告警规则 / 通知设置）'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 160 OR route_name = 'monitor');

-- ---------- 2. 按钮权限 ----------
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT v.menu_id, v.menu_name, 160, v.order_num, '', NULL, '', '',
       1, 0, 'F', '0', '0', v.perms, '#', 'admin',
       CURRENT_TIMESTAMP, '', NULL, v.remark
FROM (VALUES
    (161, '事件查询',       1,  'monitor:event:list',   'GET /monitor/events'),
    (162, '事件处理',       2,  'monitor:event:edit',   'POST /monitor/events/handle、/monitor/events/{id}/unhandle'),
    (163, '规则查询',       3,  'monitor:rule:list',    'GET /monitor/rules'),
    (164, '规则详情',       4,  'monitor:rule:query',   'GET /monitor/rules/{id}'),
    (165, '规则新增',       5,  'monitor:rule:add',     'POST /monitor/rules；GET /monitor/rules/metrics、/monitor/owners、/monitor/lark/chats'),
    (166, '规则修改',       6,  'monitor:rule:edit',    'POST /monitor/rules/{id}/update、/enabled'),
    (167, '规则删除',       7,  'monitor:rule:remove',  'POST /monitor/rules/{id}/delete')
) AS v(menu_id, menu_name, order_num, perms, remark)
WHERE NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.menu_id = v.menu_id OR m.perms = v.perms);

-- ---------- 3. 多语言名称 ----------
INSERT INTO sys_menu_i18n (menu_id, locale, menu_name, remark)
SELECT v.menu_id, v.locale, v.menu_name, v.remark
FROM (VALUES
  (160, 'zh-CN', '监控管理',     '监控管理目录'),
  (160, 'zh-TW', '監控管理',     '監控管理目錄'),
  (160, 'en-US', 'Monitoring',   'Monitoring'),
  (161, 'zh-CN', '事件查询',     ''), (161, 'zh-TW', '事件查詢',     ''), (161, 'en-US', 'Event List',      ''),
  (162, 'zh-CN', '事件处理',     ''), (162, 'zh-TW', '事件處理',     ''), (162, 'en-US', 'Event Handle',    ''),
  (163, 'zh-CN', '规则查询',     ''), (163, 'zh-TW', '規則查詢',     ''), (163, 'en-US', 'Rule List',       ''),
  (164, 'zh-CN', '规则详情',     ''), (164, 'zh-TW', '規則詳情',     ''), (164, 'en-US', 'Rule Detail',     ''),
  (165, 'zh-CN', '规则新增',     ''), (165, 'zh-TW', '規則新增',     ''), (165, 'en-US', 'Rule Add',        ''),
  (166, 'zh-CN', '规则修改',     ''), (166, 'zh-TW', '規則修改',     ''), (166, 'en-US', 'Rule Edit',       ''),
  (167, 'zh-CN', '规则删除',     ''), (167, 'zh-TW', '規則刪除',     ''), (167, 'en-US', 'Rule Delete',     '')
) AS v(menu_id, locale, menu_name, remark)
WHERE EXISTS (SELECT 1 FROM sys_menu m WHERE m.menu_id = v.menu_id)
  AND NOT EXISTS (SELECT 1 FROM sys_menu_i18n i WHERE i.menu_id = v.menu_id AND i.locale = v.locale);

-- ---------- 4. 清理旧版本加过的 168–172（通知对象、站点管理） ----------
-- 只删 perms 对得上的行，避免误删以后复用这段编号的菜单
CREATE TEMP TABLE _monitor_old_menu ON COMMIT DROP AS
SELECT menu_id FROM sys_menu
WHERE menu_id BETWEEN 168 AND 172
  AND perms IN ('monitor:group:list', 'monitor:group:add', 'monitor:group:edit', 'monitor:group:remove', 'monitor:admin:edit');
DELETE FROM sys_role_menu WHERE menu_id IN (SELECT menu_id FROM _monitor_old_menu);
DELETE FROM sys_menu_i18n WHERE menu_id IN (SELECT menu_id FROM _monitor_old_menu);
DELETE FROM sys_menu      WHERE menu_id IN (SELECT menu_id FROM _monitor_old_menu);

COMMIT;

-- ---------- 5. 自检 ----------
SELECT menu_id, menu_name, parent_id, menu_type, perms FROM sys_menu
WHERE menu_id BETWEEN 160 AND 172 ORDER BY menu_id;   -- 应 8 行（160–167）
