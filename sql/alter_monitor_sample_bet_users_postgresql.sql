-- ============================================================================
-- 监控管理 · 区间类指标的最小样本量改为投注人数（活跃人数 active）· PostgreSQL（dashboard_db）。可重复执行。
--
-- MON-34：平均杀率 killR、赠金比 bonusR、流水倍数 turnX 的样本量口径原来记为 betU，UDS 没有这个字段，
-- 取不到样本量时按「样本足够」处理，最小样本量（默认 50）完全不生效，凌晨一两个人的小时也参与判定。
-- 原型（Yata-V0.2 MINNL）这三个指标的样本量用 active「投注人數」：活跃人数的口径就是有投注行为的去重会员数。
-- 本脚本：指标配置 + 已建规则一起改成 active；后端同时改为取不到样本量 = 样本不足、不判定。
-- 已建规则的最小样本量数值不变（默认 50）。
-- ============================================================================
BEGIN;

UPDATE dashboard_metric_card
   SET min_sample_key = 'active'
 WHERE metric_code IN ('killR', 'bonusR', 'turnX')
   AND min_sample_key = 'betU';

UPDATE monitor_rule
   SET min_sample_key = 'active'
 WHERE metric_code IN ('killR', 'bonusR', 'turnX')
   AND min_sample_key = 'betU';

COMMIT;

-- 自检：应都为 active
SELECT metric_code, min_sample_key, min_sample_default FROM dashboard_metric_card WHERE metric_code IN ('killR', 'bonusR', 'turnX') ORDER BY 1;
SELECT site_code, rule_no, metric_code, min_sample_key, min_sample_value FROM monitor_rule
 WHERE metric_code IN ('killR', 'bonusR', 'turnX') AND del_flag = '0' ORDER BY 1, 2;
