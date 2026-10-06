-- ============================================================================
-- 已部署环境的增量变更（新环境直接执行 dashboard_metric_card_postgresql.sql 即可）
--   1. 新增 direction 列：涨跌好坏方向，前端据此给变化着色
--   2. 登录人数归入 eg1 获客与转化，排在组内第一位（对齐原型 EG）
-- 可重复执行。执行后重启后端，配置在启动时加载
-- ============================================================================

ALTER TABLE dashboard_metric_card
    ADD COLUMN IF NOT EXISTS direction VARCHAR(8) NOT NULL DEFAULT 'up';

ALTER TABLE dashboard_metric_card DROP CONSTRAINT IF EXISTS ck_direction;
ALTER TABLE dashboard_metric_card
    ADD CONSTRAINT ck_direction CHECK (direction IN ('up','down','flat','range'));

COMMENT ON COLUMN dashboard_metric_card.direction IS '涨跌好坏方向：up 越高越好 / down 越低越好 / flat 中性 / range 区间内为好；前端据此给变化着色';

-- 默认 up，只改例外
UPDATE dashboard_metric_card SET direction = 'down',  update_by = 'admin', update_time = CURRENT_TIMESTAMP
 WHERE metric_code IN ('dT','wT');
UPDATE dashboard_metric_card SET direction = 'flat',  update_by = 'admin', update_time = CURRENT_TIMESTAMP
 WHERE metric_code IN ('wd','bonus','pay','dTry','wTry');
UPDATE dashboard_metric_card SET direction = 'range', update_by = 'admin', update_time = CURRENT_TIMESTAMP
 WHERE metric_code IN ('turnX','killR','bonusR');

UPDATE dashboard_metric_card
   SET group_code = 'eg1', sort_no = 5, update_by = 'admin', update_time = CURRENT_TIMESTAMP
 WHERE metric_code = 'login';

-- 自检
-- SELECT direction, string_agg(metric_code, ' ' ORDER BY sort_no) FROM dashboard_metric_card
--  WHERE show_as_card GROUP BY direction;
--   up    : login reg ftd ftdr ftdA dep net arppu dOkR wOkR active bet ggr ngr
--   down  : dT wT
--   flat  : wd bonus
--   range : turnX killR bonusR
-- SELECT group_code, sort_no FROM dashboard_metric_card WHERE metric_code = 'login';  -- eg1 / 5
