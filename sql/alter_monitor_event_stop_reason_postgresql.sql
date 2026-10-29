-- ============================================================================
-- 监控管理 · 事件停止原因（MON-28 / MON-29）· PostgreSQL（dashboard_db）。可重复执行。
--
-- 2026-10-27 与产品确认：修改规则的阈值、检测周期或注意升级后，异常中的事件标为「已停止判定」，按新规则重新起算
-- （不再按新阈值直接结束旧事件、也不会按新升级时长立刻升级推送）。
-- monitor_event + stop_reason：pause 暂停 / delete 删除 / update 修改判定条件；之前停止的事件为空（悬停按暂停 / 删除写）。
-- 「持續」列悬停：update →「10:40 規則修改，之後按新規則重新判定」。
-- ============================================================================
BEGIN;

ALTER TABLE monitor_event ADD COLUMN IF NOT EXISTS stop_reason VARCHAR(10);
ALTER TABLE monitor_event DROP CONSTRAINT IF EXISTS ck_monitor_event_stop_reason;
ALTER TABLE monitor_event ADD CONSTRAINT ck_monitor_event_stop_reason
    CHECK (stop_reason IS NULL OR stop_reason IN ('pause', 'delete', 'update'));
COMMENT ON COLUMN monitor_event.stop_reason IS '事件停止原因（status = stopped）：pause 规则暂停 / delete 规则删除 / update 修改了阈值、检测周期或注意升级';

-- 老数据：规则已删除的补 delete，其余已停止的补 pause
UPDATE monitor_event e
   SET stop_reason = CASE WHEN r.del_flag = '2' THEN 'delete' ELSE 'pause' END
  FROM monitor_rule r
 WHERE r.rule_id = e.rule_id AND r.site_code = e.site_code
   AND e.status = 'stopped' AND e.stop_reason IS NULL;

COMMIT;

SELECT stop_reason, count(*) FROM monitor_event WHERE status = 'stopped' GROUP BY 1;
