-- ============================================================================
-- 监控管理 · 判定引擎（一期第二批）增量脚本 · PostgreSQL（dashboard_db）
--
-- 给已经执行过 monitor_schema_postgresql.sql 的环境用；新环境的建表脚本已包含这些列，
-- 再执行本脚本也不会报错（全部 IF NOT EXISTS / DROP ... IF EXISTS，可重复执行）。
--
--   monitor_event
--     + event_type       固定 alert（2026-10-22 起数据缺失不触发告警、不写事件记录、不发通知；
--                        之前写入的 data_missing 记录本脚本会删除，可重复执行）
--     + last_period_end  最近一次判定周期的结束时间（异常中事件的持续时长算到这里）
--     + notify_count     已成功发送通知的次数
--     + handle_reset     标记已处理后又由注意升为严重、自动退回未处理（前端提示「已升級為嚴重，需重新處理」）
--     + stopped_at       规则暂停 / 删除、事件改为 stopped 的时间（持续列悬停「10:40 規則暫停，之後不再判定」）
--   monitor_notify_log
--     msg_type 去掉 data_missing（只剩 alert / test）
--   monitor_rule_state
--     + last_data_hour   这条规则已处理到的数据小时（判定或数据缺失跳过后更新），同一小时不重复处理
--
-- 设计文档：「监控管理 · 后端设计方案」→ 判定引擎 → 实现设计；数据库设计 → monitor_event
-- ============================================================================

BEGIN;

ALTER TABLE monitor_event ADD COLUMN IF NOT EXISTS event_type      VARCHAR(16) NOT NULL DEFAULT 'alert';
ALTER TABLE monitor_event ADD COLUMN IF NOT EXISTS last_period_end TIMESTAMP;
ALTER TABLE monitor_event ADD COLUMN IF NOT EXISTS notify_count    INTEGER     NOT NULL DEFAULT 0;
ALTER TABLE monitor_event ADD COLUMN IF NOT EXISTS handle_reset    BOOLEAN     NOT NULL DEFAULT FALSE;
ALTER TABLE monitor_event ADD COLUMN IF NOT EXISTS stopped_at      TIMESTAMP;

-- 2026-10-22：数据缺失不再写事件记录。删除之前写入的缺失记录（只入库供排查，从未显示、计数或通知）
DELETE FROM monitor_event WHERE event_type = 'data_missing';
DROP INDEX IF EXISTS uk_monitor_event_missing;

ALTER TABLE monitor_event DROP CONSTRAINT IF EXISTS ck_monitor_event_type;
ALTER TABLE monitor_event ADD  CONSTRAINT ck_monitor_event_type CHECK (event_type = 'alert');
-- 注意 IS NOT NULL 不能省：CHECK 遇到 NULL 结果按通过处理
ALTER TABLE monitor_event DROP CONSTRAINT IF EXISTS ck_monitor_event_level;
ALTER TABLE monitor_event ADD  CONSTRAINT ck_monitor_event_level CHECK (
        level IS NOT NULL AND peak_level IS NOT NULL
    AND level IN ('WARN', 'CRIT') AND peak_level IN ('WARN', 'CRIT'));

ALTER TABLE monitor_notify_log DROP CONSTRAINT IF EXISTS ck_monitor_notify_type;
ALTER TABLE monitor_notify_log ADD  CONSTRAINT ck_monitor_notify_type CHECK (msg_type IN ('alert', 'test'));
-- 列表、待处理数、总览都只查 alert
CREATE INDEX IF NOT EXISTS idx_monitor_event_type ON monitor_event (site_code, event_type, trigger_hour DESC);

COMMENT ON COLUMN monitor_event.event_type      IS '固定 alert（告警事件）；数据缺失不触发告警、不写事件记录（2026-10-22 与产品确认）';
COMMENT ON COLUMN monitor_event.last_period_end IS '最近一次判定周期的结束时间；异常中事件的持续时长 = 它 − start_hour';
COMMENT ON COLUMN monitor_event.notify_count    IS '已成功发送通知的次数';
COMMENT ON COLUMN monitor_event.handle_reset    IS '标记已处理后由注意升为严重、自动退回未处理；重新标记时清除';
COMMENT ON COLUMN monitor_event.stopped_at      IS '规则暂停或删除、事件改为 stopped 的时间';
COMMENT ON COLUMN monitor_event.level           IS '当前等级 WARN / CRIT，按每个周期的判定结果可升可降';
COMMENT ON COLUMN monitor_notify_log.msg_type   IS 'alert 告警 / test 测试（test 预留给二期系统管理的机器人测试）；数据缺失不发通知';

ALTER TABLE monitor_rule_state ADD COLUMN IF NOT EXISTS last_data_hour TIMESTAMP;
COMMENT ON COLUMN monitor_rule_state.last_data_hour IS '已处理到的数据小时（判定或数据缺失跳过后更新）；新建 / 启用 / 改周期时置为当前小时的上一小时，只判定之后结束的小时';

COMMIT;

-- 自检：应为 5 列 + 1 列
SELECT count(*) AS monitor_engine_columns FROM information_schema.columns
 WHERE table_schema = current_schema()
   AND ((table_name = 'monitor_event' AND column_name IN ('event_type', 'last_period_end', 'notify_count', 'handle_reset', 'stopped_at'))
     OR (table_name = 'monitor_rule_state' AND column_name = 'last_data_hour'));
