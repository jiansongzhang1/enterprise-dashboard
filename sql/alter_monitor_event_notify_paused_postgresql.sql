-- ============================================================================
-- 监控管理 · 机器人凭证失效时暂停发送（FY-30 R13②、R14）· PostgreSQL（dashboard_db）。可重复执行。
--
-- monitor_event.notify_status 增加取值 paused：Lark 机器人凭证失效，告警暂停发送，不算发送失败；
-- 异常事件「通知送達」显示「僅記錄」，悬停「Lark 機器人憑證失效，未發送」。恢复后不补发（R15）。
-- 本站没有可用的 Lark 机器人（一期 = 没配 MONITOR_LARK_APP_ID / SECRET）时为 unset（「未配置」，R4）。
-- 需先执行 sql/alter_monitor_event_mute_postgresql.sql（muted）。
-- ============================================================================

BEGIN;

ALTER TABLE monitor_event DROP CONSTRAINT IF EXISTS ck_monitor_event_notify;
ALTER TABLE monitor_event ADD CONSTRAINT ck_monitor_event_notify
    CHECK (notify_status IN ('none', 'pending', 'sent', 'failed', 'unset', 'muted', 'paused'));

COMMENT ON COLUMN monitor_event.notify_status IS 'none 注意只记录 / pending / sent / failed / unset 未配置（没选群或本站没有 Lark 机器人） / muted 静默期内未发送 / paused 机器人凭证失效、暂停发送';

COMMIT;
