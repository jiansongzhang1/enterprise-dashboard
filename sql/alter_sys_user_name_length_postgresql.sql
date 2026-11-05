-- ============================================================================
-- 用户管理：登录账号（sys_user.user_name）长度上限 30 → 50（2026-11-02）· PostgreSQL（dashboard_db）· 可重复执行
--
-- 后台「用户管理」的用户名称改为 2–50 个字符（前端校验、SysUser @Size 同步改为 50）。
-- 只放宽长度，不改数据；PostgreSQL 放宽 varchar 长度不重写表，唯一索引照常可用。已经是 50 或更长时不改。
--
-- 执行：psql -X -v ON_ERROR_STOP=1 -d dashboard_db -f sql/alter_sys_user_name_length_postgresql.sql
-- 回滚：02 按记录的原长度改回（没有记录按 30）；有账号超过原长度时保留 50（见 sql/monitor-p1/02_rollback_monitor_p1_postgresql.sql）
-- ============================================================================

BEGIN;

DO $$
DECLARE len INT;
BEGIN
    SELECT character_maximum_length INTO len
      FROM information_schema.columns
     WHERE table_schema = current_schema() AND table_name = 'sys_user' AND column_name = 'user_name';
    IF len IS NULL THEN
        RAISE EXCEPTION '找不到 sys_user.user_name，确认连的是 dashboard_db';
    END IF;
    -- 随监控一期 01 执行时，记下原长度给 02 回滚用（只记第一次）
    IF to_regclass('monitor_p1_rollback_info') IS NOT NULL THEN
        INSERT INTO monitor_p1_rollback_info (item, value) VALUES ('sys_user.user_name.length', len::text)
        ON CONFLICT (item) DO NOTHING;
    END IF;
    IF len < 50 THEN
        ALTER TABLE sys_user ALTER COLUMN user_name TYPE VARCHAR(50);
        RAISE NOTICE 'sys_user.user_name：VARCHAR(%) → VARCHAR(50)', len;
    ELSE
        RAISE NOTICE 'sys_user.user_name 已是 VARCHAR(%)，不改', len;
    END IF;
END $$;

COMMIT;

-- 自检：应为 50
SELECT character_maximum_length AS user_name_max_length
  FROM information_schema.columns
 WHERE table_schema = current_schema() AND table_name = 'sys_user' AND column_name = 'user_name';
