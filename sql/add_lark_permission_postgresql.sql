-- 为已有数据库增加 Lark 通知接口权限。
-- 该权限不会默认授予普通角色；超级管理员可直接使用，其他角色请按需授权。
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT
    123, 'Lark发送', 122, 3, '', '', '', '',
    1, 0, 'F', '1', '0', 'notification:lark:send', '#', 'admin',
    CURRENT_TIMESTAMP, '', NULL, 'Lark通知接口权限'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE menu_id = 123 OR perms = 'notification:lark:send'
);
