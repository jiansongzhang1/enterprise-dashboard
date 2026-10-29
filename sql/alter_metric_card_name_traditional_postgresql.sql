-- ============================================================================
-- 指标名称改为繁体、以库为准（2026-10-27）· PostgreSQL（dashboard_db）
--
-- 后台「系统管理 · 指标管理」放开已接入指标的名称、分组、格式修改后，运营总览、指标汇总、告警规则、异常事件、
-- Lark 消息都直接用 dashboard_metric_card.metric_name，不再在代码里按原型覆盖或按字转繁体。
-- 本脚本把 28 个已接入指标的名称写成页面一直在显示的繁体（原型名称；原子指标按字转繁体），上线前后页面显示不变。
--
--   1. 备份原名称到 dashboard_metric_card_name_bak_20261027（只在第一次执行时写入）
--   2. 只改「名称还等于备份里的原值」的行：后台改过的名称不覆盖；可重复执行
--
-- 执行：psql -X -v ON_ERROR_STOP=1 -d dashboard_db -f sql/alter_metric_card_name_traditional_postgresql.sql
-- 回滚：UPDATE dashboard_metric_card c SET metric_name = b.metric_name
--         FROM dashboard_metric_card_name_bak_20261027 b WHERE b.metric_code = c.metric_code;
--      （监控管理一期整体回滚时 02_rollback 按 0.5 的整表备份恢复，并删除本备份表）
-- ============================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS dashboard_metric_card_name_bak_20261027 (
    metric_code  VARCHAR(64)  PRIMARY KEY,
    metric_name  VARCHAR(128),
    backup_time  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO dashboard_metric_card_name_bak_20261027 (metric_code, metric_name)
SELECT metric_code, metric_name FROM dashboard_metric_card WHERE status = '0'
ON CONFLICT (metric_code) DO NOTHING;

UPDATE dashboard_metric_card c
   SET metric_name = v.name, update_by = 'admin', update_time = CURRENT_TIMESTAMP
  FROM (VALUES
    ('reg',    '註冊人數'),
    ('ftd',    '首存人數'),
    ('ftdr',   '首存轉化率'),
    ('dep',    '存款總額'),
    ('wd',     '提款總額'),
    ('net',    '存提差'),
    ('arppu',  'ARPPU'),
    ('ftdA',   '首存ARPPU'),
    ('dOkR',   '存款成功率'),
    ('dT',     '平均到帳時間'),
    ('wOkR',   '出款成功率'),
    ('wT',     '平均出款時間'),
    ('bonus',  '發放贈金總額'),
    ('bonusR', '贈金比'),
    ('bet',    '投注總額'),
    ('ggr',    'GGR'),
    ('killR',  '平均殺率'),
    ('active', '活躍人數'),
    ('login',  '登錄人數'),
    ('ngr',    'NGR'),
    ('turnX',  '流水倍數'),
    -- 原子指标（页面上作为口径的操作数出现）
    ('dOk',    '存款成功筆數'),
    ('dTry',   '存款總訂單數'),   -- 原型 V0.2 优化版（OPT-01 / MON-26）：嘗試筆數 → 總訂單數
    ('depU',   '存款人數'),
    ('ftdAmt', '首存總金額'),
    ('pay',    '派彩金額'),
    ('wOk',    '出款成功筆數'),
    ('wTry',   '出款總訂單數')    -- 同上；出款成功率的分母（OPT-02）
  ) AS v(code, name)
  JOIN dashboard_metric_card_name_bak_20261027 b ON b.metric_code = v.code
 WHERE c.metric_code = v.code
   AND c.metric_name IS NOT DISTINCT FROM b.metric_name
   AND c.metric_name IS DISTINCT FROM v.name;

-- 已按旧版本执行过本脚本的库：「存款／出款嘗試筆數」改为「總訂單數」（只改仍是旧繁体名称的行，后台改过的不动；可重复执行）
UPDATE dashboard_metric_card
   SET metric_name = CASE metric_code WHEN 'dTry' THEN '存款總訂單數' ELSE '出款總訂單數' END,
       update_by = 'admin', update_time = CURRENT_TIMESTAMP
 WHERE (metric_code = 'dTry' AND metric_name = '存款嘗試筆數')
    OR (metric_code = 'wTry' AND metric_name = '出款嘗試筆數');

COMMIT;

-- 自检：已接入指标里还是简体名称的（应为 0 行；有行说明是后台改过或不在上面的列表里，人工确认）
SELECT c.metric_code, c.metric_name, b.metric_name AS original
  FROM dashboard_metric_card c
  JOIN dashboard_metric_card_name_bak_20261027 b ON b.metric_code = c.metric_code
 WHERE c.status = '0' AND c.metric_name = b.metric_name
   AND c.metric_name ~ '[数额总户转赠杀动录汇单尝试账笔]'
 ORDER BY c.metric_code;
