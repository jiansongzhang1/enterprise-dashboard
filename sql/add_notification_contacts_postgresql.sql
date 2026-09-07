-- FiveTech 用户通知联系人扩展
-- 仅保存接收方标识，例如 Telegram Chat ID、WhatsApp 联系人标识或 Lark open_id。
-- 不保存 Telegram Bot Token、WhatsApp Access Token 等平台密钥。

ALTER TABLE IF EXISTS sys_user
    ADD COLUMN IF NOT EXISTS notification_contacts JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN sys_user.notification_contacts IS
    '用户通知联系人信息JSONB，仅保存Telegram、WhatsApp、Lark等接收方标识，不保存平台访问密钥';

UPDATE sys_user
SET notification_contacts = '{}'::jsonb
WHERE notification_contacts IS NULL;

ALTER TABLE IF EXISTS sys_user
    ALTER COLUMN notification_contacts SET DEFAULT '{}'::jsonb,
    ALTER COLUMN notification_contacts SET NOT NULL;

-- 验证字段
-- SELECT user_id, user_name, notification_contacts FROM sys_user ORDER BY user_id;
