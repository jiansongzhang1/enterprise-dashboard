-- 为已有数据库增加 WhatsApp 通知接口权限。
-- 执行前请确认 sys_menu.menu_id = 121 未被其他业务占用。
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT
    121, 'WhatsApp发送', 1, 11, '', '', '', '',
    1, 0, 'F', '1', '0', 'notification:whatsapp:send', '#', 'admin',
    CURRENT_TIMESTAMP, '', NULL, 'WhatsApp通知接口权限'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE menu_id = 121 OR perms = 'notification:whatsapp:send'
);
