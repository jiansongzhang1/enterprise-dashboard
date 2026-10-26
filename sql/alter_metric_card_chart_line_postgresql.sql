-- ============================================================================
-- 运营总览卡片图形去掉 BAR（2026-10-23）· PostgreSQL（dashboard_db）
--
-- 总览接口的 chart.type 只有 GAUGE / LINE（后端已不读 chart_type 决定卡片图形）。
-- 这里把配置表里仍是 BAR 的 4 个指标（存提差 net、投注总额 bet、GGR ggr、NGR ngr）改成 LINE，
-- 并把约束收紧为 LINE / PIE / NUMBER，防止再配 BAR。
--
-- 只给已有环境用：新环境的建表脚本 dashboard_metric_card_postgresql.sql 已同步修改；
-- migrate_appdb_to_dashboard_db.sh 的 metrics 步骤按仓库建表脚本重建，也不需要再执行本脚本。
-- 可重复执行：第二次执行没有 BAR 行，UPDATE 0 行；备份表只在第一次写入。
-- ============================================================================

BEGIN;

-- 1. 备份要改的行（只在第一次执行时写入）
CREATE TABLE IF NOT EXISTS dashboard_metric_card_chart_bak_20261023 (
    metric_code VARCHAR(64) PRIMARY KEY,
    chart_type  VARCHAR(16) NOT NULL,
    backup_time TIMESTAMP   NOT NULL DEFAULT localtimestamp
);
INSERT INTO dashboard_metric_card_chart_bak_20261023 (metric_code, chart_type)
SELECT metric_code, chart_type FROM dashboard_metric_card WHERE chart_type = 'BAR'
ON CONFLICT (metric_code) DO NOTHING;

-- 2. BAR → LINE
UPDATE dashboard_metric_card SET chart_type = 'LINE' WHERE chart_type = 'BAR';

-- 3. 约束去掉 BAR
ALTER TABLE dashboard_metric_card DROP CONSTRAINT IF EXISTS ck_chart_type;
ALTER TABLE dashboard_metric_card ADD  CONSTRAINT ck_chart_type CHECK (chart_type IN ('LINE','PIE','NUMBER'));
COMMENT ON COLUMN dashboard_metric_card.chart_type IS 'LINE 折线 / PIE 饼图 / NUMBER 纯数值；运营总览卡片只按刻度区分 GAUGE / LINE，不读本列';

COMMIT;

-- 自检：应为 0 行 BAR；备份表应有 4 行（net、bet、ggr、ngr）
SELECT count(*) AS bar_left FROM dashboard_metric_card WHERE chart_type = 'BAR';
SELECT metric_code, chart_type AS old_chart_type, backup_time FROM dashboard_metric_card_chart_bak_20261023 ORDER BY metric_code;

-- 回滚（确认后手动执行）：
-- BEGIN;
-- ALTER TABLE dashboard_metric_card DROP CONSTRAINT IF EXISTS ck_chart_type;
-- ALTER TABLE dashboard_metric_card ADD  CONSTRAINT ck_chart_type CHECK (chart_type IN ('LINE','BAR','PIE','NUMBER'));
-- UPDATE dashboard_metric_card c SET chart_type = b.chart_type
--   FROM dashboard_metric_card_chart_bak_20261023 b WHERE b.metric_code = c.metric_code;
-- COMMIT;
