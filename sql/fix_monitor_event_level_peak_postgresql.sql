-- ============================================================================
-- 监控管理 · 修正已被「降级」的事件等级（MON-01 / MON-04）· PostgreSQL（dashboard_db）。可重复执行。
--
-- 修复前，事件到过严重后只要有一个周期回到注意范围，monitor_event.level 就被改成 WARN（peak_level 仍记着 CRIT）。
-- 新代码只升不降；本脚本把历史数据改回：到过严重的事件 level = CRIT。
-- 判定基准（threshold.against）、提醒条、指标卡都按 level 显示，随之恢复为严重线。
-- 不改 escalated、通知记录和处理状态；当前值（MON-03）历史上已被覆盖为最近一期，无法还原，新事件起按触发周期记录。
-- 和新 jar 一起执行（先停旧 jar 的判定也可以不停：旧 jar 可能再次降级，新 jar 上线后再执行一次即可）。
-- ============================================================================
BEGIN;

-- 执行前看一下会改哪些
SELECT event_id, site_code, rule_id, metric_code, level, peak_level, status, trigger_hour
  FROM monitor_event
 WHERE event_type = 'alert' AND peak_level = 'CRIT' AND level <> 'CRIT'
 ORDER BY site_code, event_id;

UPDATE monitor_event
   SET level = 'CRIT',
       update_time = (now() AT TIME ZONE 'Asia/Kolkata')
 WHERE event_type = 'alert' AND peak_level = 'CRIT' AND level <> 'CRIT';

COMMIT;
