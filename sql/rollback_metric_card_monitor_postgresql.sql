-- ============================================================================
-- 回滚用：把 alter_metric_card_monitor_postgresql.sql 删掉的 4 个旧字段加回来
--
-- 什么时候需要：新 jar 发布失败、要退回旧 jar（yata-deploy rollback）时，
-- 必须先执行本脚本。旧版 DashboardConfigMapper 会 select alert_enabled / alert_min /
-- alert_max / tolerance_pct，字段不存在时 MetricRegistry 在启动阶段就会失败。
--
-- 只加回旧代码查询的字段（值为空即可，旧代码不使用这些值）；
-- 新增的监控属性字段和 monitor_* 表保留不动，对旧代码无影响。
-- 如需恢复旧值，用部署前备份的 metric_card_alert_backup.csv 回填（见部署文档）。
-- 可重复执行。
-- ============================================================================

BEGIN;

ALTER TABLE dashboard_metric_card ADD COLUMN IF NOT EXISTS alert_enabled           BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE dashboard_metric_card ADD COLUMN IF NOT EXISTS alert_min               NUMERIC(18,4);
ALTER TABLE dashboard_metric_card ADD COLUMN IF NOT EXISTS alert_max               NUMERIC(18,4);
ALTER TABLE dashboard_metric_card ADD COLUMN IF NOT EXISTS tolerance_pct           NUMERIC(8,2);
ALTER TABLE dashboard_metric_card ADD COLUMN IF NOT EXISTS tolerance_crit_multiple NUMERIC(4,2) DEFAULT 2;
ALTER TABLE dashboard_metric_card ADD COLUMN IF NOT EXISTS alert_level             VARCHAR(8) DEFAULT 'WARN';

COMMIT;

-- 自检：应返回 6 行
SELECT column_name FROM information_schema.columns
WHERE table_name = 'dashboard_metric_card'
  AND column_name IN ('alert_enabled','alert_min','alert_max','tolerance_pct','tolerance_crit_multiple','alert_level')
ORDER BY column_name;
