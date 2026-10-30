-- ============================================================================
-- 平均到账时间 / 平均出款时间改为后端计算（2026-10-29）· PostgreSQL（dashboard_db）· 可重复执行
--
-- dT、wT 不再取 UDS 的均值字段 dT_seconds / wT_seconds，改为取耗时合计与单数，后端相除：
--   平均到账时间 dT = sum(dep_time_sum) / nullif(sum(dep_time_count), 0)
--     dep_time_sum   到账耗时合计（秒），源字段 deposit_arrival_seconds_total
--     dep_time_count 计入耗时的到账成功单数，源字段 deposit_arrival_cnt
--   平均出款时间 wT = sum(wd_time_sum) / nullif(sum(wd_time_count), 0)
--     wd_time_sum    出款耗时合计（秒），源字段 withdraw_seconds_total
--     wd_time_count  出款耗时的单数，源字段 withdraw_seconds_cnt
-- 取数以 application.yml 为准（datasets.overview_hour / overview_amount 的 metrics 与 overview.divide）；
-- 本脚本只把指标字典里登记的 UDS 字段改成和线上配置一致（与 net / ngr / pay 一样不登记单一字段），
-- 否则「系统管理 · 指标管理」的「對照」会显示不一致。
--
-- 执行顺序：在 ⑫ alter_metric_card_dictionary 之后。
-- 执行：psql -X -v ON_ERROR_STOP=1 -d dashboard_db -f sql/alter_metric_card_avg_time_divide_postgresql.sql
-- 回滚：UPDATE dashboard_metric_card SET uds_hour_field = metric_code || '_seconds', uds_amount_field = metric_code || '_seconds'
--         WHERE metric_code IN ('dT', 'wT');（同时把 application.yml 改回 dT_seconds / wT_seconds）
-- ============================================================================

BEGIN;

UPDATE dashboard_metric_card
   SET uds_hour_field = NULL, uds_amount_field = NULL, update_by = 'admin', update_time = CURRENT_TIMESTAMP
 WHERE metric_code IN ('dT', 'wT')
   AND (uds_hour_field IN ('dT_seconds', 'wT_seconds') OR uds_amount_field IN ('dT_seconds', 'wT_seconds'));

COMMIT;

-- 自检：dT、wT 不再登记单一 UDS 字段（应为 0 行）
SELECT metric_code, uds_hour_field, uds_day_field, uds_amount_field FROM dashboard_metric_card
 WHERE metric_code IN ('dT', 'wT') AND coalesce(uds_hour_field, uds_day_field, uds_amount_field) IS NOT NULL;
