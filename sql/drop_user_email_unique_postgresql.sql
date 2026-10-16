-- 去掉 sys_user 邮箱唯一索引：邮箱改为选填（「管理员设置密码」方式新增用户可不填邮箱）。
-- 邮箱重复仍由应用层 checkEmailUnique 校验（仅对非空邮箱）。可重复执行。

BEGIN;

DROP INDEX IF EXISTS uk_sys_user_email_active;

-- 占位邮箱 mock@mail.com 已不再特殊处理：清空，避免通知邮件发到该地址
UPDATE sys_user SET email = '', update_time = CURRENT_TIMESTAMP
WHERE lower(trim(email)) = 'mock@mail.com';

-- 核对：索引应不存在；mock 邮箱应为 0
SELECT count(*) AS email_index_left FROM pg_indexes WHERE indexname = 'uk_sys_user_email_active';
SELECT count(*) AS mock_left FROM sys_user WHERE lower(trim(email)) = 'mock@mail.com';

COMMIT;
