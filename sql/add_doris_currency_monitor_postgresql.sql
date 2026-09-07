-- Add the Doris currency summary monitor to an existing FiveTech database.
-- This script does not change existing application data.

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query,
    route_name, is_frame, is_cache, menu_type, visible, status, perms,
    icon, create_by, create_time, update_by, update_time, remark
)
SELECT 124, '数据监控', 2, 1, 'doris', 'monitor/doris/index', '', '',
       1, 0, 'C', '0', '0', 'monitor:doris:query', 'data-analysis',
       'admin', CURRENT_TIMESTAMP, '', NULL, 'Doris数据监控'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 124);

INSERT INTO sys_menu_i18n (menu_id, locale, menu_name, remark)
VALUES
  (124, 'zh-CN', '数据监控', 'Doris数据监控菜单'),
  (124, 'zh-TW', '資料監控', 'Doris資料監控選單'),
  (124, 'en-US', 'Data Monitoring', 'Doris data monitoring')
ON CONFLICT (menu_id, locale) DO UPDATE
SET menu_name = EXCLUDED.menu_name,
    remark = EXCLUDED.remark,
    updated_at = CURRENT_TIMESTAMP;

-- Grant to the normal role only after confirming the aggregated data is safe
-- for ordinary users. Uncomment when appropriate:
-- INSERT INTO sys_role_menu (role_id, menu_id)
-- SELECT 2, 124
-- WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 2 AND menu_id = 124);
