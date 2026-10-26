-- ============================================================================
-- 监控管理 · 异常事件「静默期内未发送」增量脚本 · PostgreSQL（dashboard_db）。可重复执行。
--
-- 异常事件「通知」列悬停「靜默期內：上次發送 MM-dd HH:mm，靜默 N 小時，本次未發送」需要的信息：
--   monitor_event.notify_status 增加取值 muted（严重、未处理，但距本规则上次发送不足静默期，本次未发送）
--   + mute_sent_at  压住本次发送的那次发送时间（规则的 last_notified_at）
--   + mute_hours    当时规则的静默时长（小时）
-- 之前因静默期没发的事件仍是 none（无法补记），只影响历史数据的悬停说明。
-- ============================================================================

BEGIN;

ALTER TABLE monitor_event ADD COLUMN IF NOT EXISTS mute_sent_at TIMESTAMP;
ALTER TABLE monitor_event ADD COLUMN IF NOT EXISTS mute_hours   SMALLINT;

ALTER TABLE monitor_event DROP CONSTRAINT IF EXISTS ck_monitor_event_notify;
ALTER TABLE monitor_event ADD CONSTRAINT ck_monitor_event_notify
    CHECK (notify_status IN ('none', 'pending', 'sent', 'failed', 'unset', 'muted'));

COMMENT ON COLUMN monitor_event.notify_status IS 'none 注意只记录 / pending / sent / failed / unset 未配置通知对象 / muted 严重但在静默期内、本次未发送';
COMMENT ON COLUMN monitor_event.mute_sent_at  IS 'notify_status = muted 时：压住本次发送的那次发送时间（规则 last_notified_at）';
COMMENT ON COLUMN monitor_event.mute_hours    IS 'notify_status = muted 时：当时规则的静默时长（小时）';

COMMIT;

SELECT column_name, data_type FROM information_schema.columns
 WHERE table_name = 'monitor_event' AND column_name IN ('mute_sent_at', 'mute_hours');
