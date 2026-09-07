-- 增加邮件通知接口权限。
-- 不自动授予普通角色，遵循默认拒绝原则。

INSERT INTO sys_menu (
    menu_id,
    menu_name,
    parent_id,
    order_num,
    path,
    component,
    query,
    route_name,
    is_frame,
    is_cache,
    menu_type,
    visible,
    status,
    perms,
    icon,
    create_by,
    create_time,
    update_by,
    update_time,
    remark
)
SELECT
    119,
    '邮件发送',
    1,
    9,
    '',
    '',
    '',
    '',
    1,
    0,
    'F',
    '1',
    '0',
    'notification:email:send',
    '#',
    'system',
    CURRENT_TIMESTAMP,
    '',
    NULL,
    '邮件通知接口权限'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE perms = 'notification:email:send'
);
