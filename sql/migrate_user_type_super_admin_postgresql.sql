-- 将超级管理员判断从固定 user_id 改为 sys_user.user_type。
-- 00：普通用户；01：超级管理员。
-- 请先确认需要保留超级管理员身份的账号，再执行更新语句。

BEGIN;

UPDATE sys_user
SET user_type = '00'
WHERE user_type IS NULL OR user_type NOT IN ('00', '01');

-- 默认账号 admin 设为超级管理员。
UPDATE sys_user
SET user_type = '01',
    update_by = 'system',
    update_time = CURRENT_TIMESTAMP
WHERE user_name = 'admin';
Ï
COMMENT ON COLUMN sys_user.user_type IS '用户类型：00普通用户，01超级管理员；具体操作权限仍由RBAC角色控制';

COMMIT;

-- 验证结果：
-- SELECT user_id, user_name, user_type FROM sys_user ORDER BY user_id;
