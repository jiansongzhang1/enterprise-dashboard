ALTER TABLE IF EXISTS sys_user
    ADD COLUMN IF NOT EXISTS ga_secret VARCHAR(64);

ALTER TABLE IF EXISTS sys_user
    ADD COLUMN IF NOT EXISTS ga_status INT DEFAULT 0;

COMMENT ON COLUMN sys_user.ga_secret IS 'Google Authenticator密钥';
COMMENT ON COLUMN sys_user.ga_status IS 'Google Authenticator状态（0未开通 1待绑定 2已绑定）';

-- 不再写死任何 TOTP 种子密钥。
-- 旧版本给 admin / test 写入了公开的示例密钥（JBSWY3DPEHPK3PXP、JBSWY3DDFSGFK3PP），
-- 任何人都能据此算出验证码。这里把它们清空并改回“未绑定”，
-- 对应账号下次在登录页「绑定验证器」时，由后端用 SecureRandom 随机生成新密钥。
-- 注意：执行后这两个账号原来在手机 App 里的条目会失效，需要删除后重新绑定。
UPDATE sys_user
SET ga_secret = NULL,
    ga_status = 0,
    update_time = CURRENT_TIMESTAMP
WHERE ga_secret IN ('JBSWY3DPEHPK3PXP', 'JBSWY3DDFSGFK3PP');
