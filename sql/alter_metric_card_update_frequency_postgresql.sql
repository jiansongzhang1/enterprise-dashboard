-- dashboard_metric_card.time_field → update_frequency
--
-- 原来的 time_field 存的是「查询用的时间字段名」（dt 之类），但取数实际走的是 UDS 数据集
-- 配置里的 time-dimension，这一列从来没被读过。现在改成「更新频率」，前端按它显示
-- 「每小时更新」这类角标，也用来管理用户对「数据为什么还没变」的预期。
--
-- 已经建过表的环境执行这一份；全新环境直接执行 dashboard_metric_card_postgresql.sql 即可。

ALTER TABLE dashboard_metric_card RENAME COLUMN time_field TO update_frequency;

-- 旧值是字段名（dt / biz_hh …），对新语义无意义，统一重置。
-- 21 个看板指标全部来自 ops_hourly 小时表，所以是 hour。
UPDATE dashboard_metric_card SET update_frequency = 'hour';

ALTER TABLE dashboard_metric_card
    ALTER COLUMN update_frequency TYPE VARCHAR(16),
    ALTER COLUMN update_frequency SET DEFAULT 'hour',
    ALTER COLUMN update_frequency SET NOT NULL;

ALTER TABLE dashboard_metric_card
    ADD CONSTRAINT ck_update_frequency CHECK (update_frequency IN ('realtime','hour','day'));

COMMENT ON COLUMN dashboard_metric_card.update_frequency
    IS '更新频率：realtime 实时 / hour 每小时 / day 每日';

-- 自检：应全部是 hour，且没有空值
SELECT update_frequency, count(*) FROM dashboard_metric_card GROUP BY update_frequency;
