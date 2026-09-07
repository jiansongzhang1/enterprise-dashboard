ALTER TABLE IF EXISTS sys_user
    ADD COLUMN IF NOT EXISTS ga_secret VARCHAR(64);

ALTER TABLE IF EXISTS sys_user
    ADD COLUMN IF NOT EXISTS ga_status INT DEFAULT 0;

COMMENT ON COLUMN sys_user.ga_secret IS 'Google Authenticator密钥';
COMMENT ON COLUMN sys_user.ga_status IS 'Google Authenticator状态（0未开通 1待绑定 2已绑定）';


UPDATE sys_user
SET ga_secret = 'JBSWY3DPEHPK3PXP'
WHERE user_name = 'admin';


UPDATE sys_user
SET ga_secret = 'JBSWY3DDFSGFK3PP'
WHERE user_name = 'test';

UPDATE sys_user
SET ga_status = CASE
    WHEN ga_secret IS NULL THEN 0
    ELSE 2
END;
