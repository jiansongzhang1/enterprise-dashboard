-- ============================================================================
-- 管理后台 · 监控规则「手动触发测试告警」按钮权限 monitor:rule:test（dashboard_db）。可重复执行。
--
-- 接口：POST /monitor/rules/{ruleId}/test-alert（站点取请求头 X-Site-Code）
-- 挂在「系统管理 · 通知模板」菜单下（页面入口在通知模板页），默认只给超级管理员角色（role_id = 1）。
-- 依赖：已执行 add_monitor_notify_template_postgresql.sql（通知模板菜单 system:notifyTpl:list）。
-- 测试群 chat_id 配在环境变量 MONITOR_TEST_CHAT_ID（/etc/fivetech.env），不配时接口返回「未配置測試群」。
-- ============================================================================

BEGIN;

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '手动触发测试告警', p.menu_id, 2, '', '', '', '',
       1, 0, 'F', '0', '0', 'monitor:rule:test', '#', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '取规则最近一个周期的真实数据，发一条【測試】告警到 MONITOR_TEST_CHAT_ID 测试群'
FROM sys_menu p
WHERE p.perms = 'system:notifyTpl:list'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'monitor:rule:test');

INSERT INTO sys_menu_i18n (menu_id, locale, menu_name, remark)
SELECT m.menu_id, v.locale, v.menu_name, v.remark
FROM sys_menu m
JOIN (VALUES
    ('zh-CN', '手动触发测试告警', '发一条【测试】告警到测试群'),
    ('zh-TW', '手動觸發測試告警', '發一條【測試】告警到測試群'),
    ('en-US', 'Trigger Test Alert', 'Send a [TEST] alert to the test chat')
) AS v(locale, menu_name, remark) ON TRUE
WHERE m.perms = 'monitor:rule:test'
  AND NOT EXISTS (SELECT 1 FROM sys_menu_i18n i WHERE i.menu_id = m.menu_id AND i.locale = v.locale);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id
FROM sys_menu m
WHERE m.perms = 'monitor:rule:test'
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.role_id = 1)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id);

COMMIT;

-- 应返回 1 行（父菜单为「通知模板」）；为 0 行说明还没执行 add_monitor_notify_template_postgresql.sql
SELECT m.menu_id, m.menu_name, m.perms, p.menu_name AS parent
  FROM sys_menu m JOIN sys_menu p ON p.menu_id = m.parent_id
 WHERE m.perms = 'monitor:rule:test';
