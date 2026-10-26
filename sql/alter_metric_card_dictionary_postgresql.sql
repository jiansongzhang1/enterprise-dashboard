-- ============================================================================
-- 指标字典：口径与取数方式收口到 dashboard_metric_card（2026-10-26）· PostgreSQL（dashboard_db）
--
-- 只改表，不改取数：运营总览、指标汇总仍按 application.yml 的 dashboard.gateway.uds 取数，
-- 判定引擎的跌幅基准仍按 monitor.engine.baseline-fields；本表新增的字段是这些配置的「登记」，
-- 后台「系统管理 · 指标管理」会对比两边，不一致时标出来。
--
--   1. 备份整表到 dashboard_metric_card_bak_20261026（只在第一次执行时写入）
--   2. source_table / source_field 改为「UDS 上游原始表 / 字段」（待 UDS 提供）：清掉早期直连 Doris 时的旧表名，
--      去掉「原子指标必须有 source_table」的约束（ck_source）。这两列没有任何代码读取
--   3. 新增 4 列：uds_hour_field / uds_day_field / uds_amount_field / uds_baseline_field
--   4. 按当前 yml 填 28 个指标的 UDS 字段
--   5. remark 改为「口径：…」+「备注：…」（口径取自《Yata v0.1 操作指南 · 指标口径》与页面 ⓘ 文案）
--
-- 可重复执行：第 2 步只在第一次执行时清空；4、5 步只处理还没填过的行，不会覆盖后台已维护的内容。
-- 新环境：先执行 dashboard_metric_card_postgresql.sql（已含新列），再执行本脚本填内容。
-- 执行：psql -X -v ON_ERROR_STOP=1 -d dashboard_db -f sql/alter_metric_card_dictionary_postgresql.sql
-- ============================================================================

BEGIN;

-- 1. 备份
CREATE TABLE IF NOT EXISTS dashboard_metric_card_bak_20261026 AS SELECT * FROM dashboard_metric_card;

-- 2. 上游原始表 / 字段：旧值是早期直连 Doris 的表名（dws_* / ods_pay_order），与现在的取数无关，清空等 UDS 提供。
--    只在第一次执行（还没有 uds_hour_field 列）时清空，之后执行不会再动后台填进去的值
ALTER TABLE dashboard_metric_card DROP CONSTRAINT IF EXISTS ck_source;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = current_schema() AND table_name = 'dashboard_metric_card'
                      AND column_name = 'uds_hour_field') THEN
        UPDATE dashboard_metric_card
           SET source_table = NULL, source_field = NULL, update_by = 'admin', update_time = CURRENT_TIMESTAMP
         WHERE source_table IS NOT NULL OR source_field IS NOT NULL;
    END IF;
END $$;

-- 3. 新增列
ALTER TABLE dashboard_metric_card ADD COLUMN IF NOT EXISTS uds_hour_field     VARCHAR(128);
ALTER TABLE dashboard_metric_card ADD COLUMN IF NOT EXISTS uds_day_field      VARCHAR(128);
ALTER TABLE dashboard_metric_card ADD COLUMN IF NOT EXISTS uds_amount_field   VARCHAR(128);
ALTER TABLE dashboard_metric_card ADD COLUMN IF NOT EXISTS uds_baseline_field VARCHAR(128);

-- 4. 取数字段：只填还没填过的行（后台已经维护过的不覆盖）
UPDATE dashboard_metric_card c
   SET uds_hour_field = v.hour_field, uds_day_field = v.day_field,
       uds_amount_field = v.amount_field, uds_baseline_field = v.baseline_field,
       update_by = 'admin', update_time = CURRENT_TIMESTAMP
  FROM (VALUES
    ('reg', 'reg', NULL, 'reg', 'reg_baseline', '口径：完成会员注册的人数，按注册时间（IST）归入所属小时。'),
    ('ftd', 'ftd', NULL, 'ftd', 'ftd_baseline', '口径：完成生涯首笔存款的人数，按首笔存款成功时间（IST）归入所属小时。'),
    ('ftdr', 'ftdr', NULL, 'ftdr', NULL, '口径：首存人数 ÷ 注册人数 × 100%。
备注：UDS 直接提供比率；判定引擎在检测周期大于 1 小时时按 Σ首存人数 ÷ Σ注册人数 重算。'),
    ('ftdA', 'ftdA', NULL, 'ftdA', 'ftdA_baseline', '口径：首存总金额 ÷ 首存人数。
备注：UDS 直接提供；检测周期大于 1 小时时按 Σ首存总金额 ÷ Σ首存人数 重算。'),
    ('dep', 'dep', NULL, 'dep', 'dep_baseline', '口径：所有会员成功存款金额加总（站点币种主单位）。'),
    ('wd', 'wd', NULL, 'wd', 'wd_baseline', '口径：所有会员成功提款金额加总（站点币种主单位）。'),
    ('net', NULL, NULL, NULL, NULL, '口径：存款总额 − 提款总额，可为负。
备注：UDS 不返回，后端相减（dashboard.gateway.uds.overview.subtract：dep − wd）；任一为空则为空。'),
    ('arppu', 'arppu_hour', 'arppu_day', NULL, 'arppu_hour_baseline', '口径：存款总额 ÷ 去重存款人数；按小时／日去重，仅支持查看单日数据。
备注：小时取 arppu_hour，整日取 arppu_day；跨多日区间不出值。'),
    ('turnX', 'turnX', NULL, 'turnX', NULL, '口径：投注总额 ÷ 存款总额。
备注：业务参考区间 9.5 – 12.5；检测周期大于 1 小时时按 Σ投注总额 ÷ Σ存款总额 重算。'),
    ('dOkR', 'dOkR', NULL, 'dOkR', NULL, '口径：存款成功笔数 ÷ 存款总尝试笔数 × 100%。
备注：UDS 比率为 0～1，页面 ×100。'),
    ('dT', 'dT_seconds', NULL, 'dT_seconds', NULL, '口径：存款自提交到入账的平均耗时，按成功笔数加权（分钟）。
备注：UDS 字段单位为秒，页面 ÷60 显示分钟。'),
    ('wOkR', 'wOkR', NULL, 'wOkR', NULL, '口径：出款成功笔数 ÷ 出款总尝试笔数 × 100%。
备注：UDS 比率为 0～1，页面 ×100。'),
    ('wT', 'wT_seconds', NULL, 'wT_seconds', NULL, '口径：提款自申请到拨款完成的平均耗时，按成功笔数加权（分钟）。
备注：UDS 字段单位为秒，页面 ÷60 显示分钟。'),
    ('active', 'active_hour', 'active_day', NULL, 'active_hour_baseline', '口径：有投注行为的去重会员数；按小时／日去重，跨时段不可相加，仅支持查看单日数据。
备注：小时取 active_hour，整日取 active_day；区间在一个自然日内时取日数据集当天的值。'),
    ('bet', 'bet', NULL, 'bet', 'bet_baseline', '口径：所有投注金额加总。'),
    ('ggr', 'ggr', NULL, 'ggr', 'ggr_baseline', '口径：0 − 玩家输赢（win loss amount），即投注总额 − 派彩金额。
备注：UDS 直接提供 ggr。'),
    ('killR', 'killR', NULL, 'killR', NULL, '口径：GGR ÷ 投注总额 × 100%。
备注：业务参考区间 3% – 6%。'),
    ('bonus', 'bonus', NULL, 'bonus', 'bonus_baseline', '口径：各赠金项目实际发放金额加总。
备注：运营总览排行榜按赠金项目拆分展示。'),
    ('bonusR', 'bonusR', NULL, 'bonusR', NULL, '口径：发放赠金总额 ÷ 投注总额 × 100%。
备注：业务参考区间 2.5% – 5%。'),
    ('ngr', NULL, NULL, NULL, 'ngr_baseline', '口径：GGR − 发放赠金总额。
备注：UDS 不返回，后端相减（overview.subtract：ggr − bonus）；不取旧 ngr_net 口径。跌幅基准由 UDS 按每小时 NGR 取中位数。'),
    ('login', 'login_player_cnt', 'login_player_cnt', NULL, NULL, '口径：完成登录的去重会员数；按小时／日去重，跨时段不可相加。
备注：不上指标墙，仅在指标汇总的列选择器里；不参与告警。'),
    ('depU', 'dep_users_hour', 'dep_users_day', NULL, NULL, '口径：有成功存款的去重会员数；按小时／日去重。
备注：ARPPU 的分母，不单独展示。'),
    ('ftdAmt', 'ftd_amount', NULL, 'ftd_amount', NULL, '口径：首笔存款金额加总。
备注：首存 ARPPU 的分子，不单独展示。'),
    ('pay', NULL, NULL, NULL, NULL, '口径：派彩金额 = 投注总额 + 玩家输赢（win_los_amt）。
备注：UDS 没有派彩字段，后端相加（overview.add：bet + winLoss，winLoss 取金额数据集 win_los_amt）；GGR 的减数，不单独展示。'),
    ('dOk', 'dep_ok', NULL, 'dep_ok', NULL, '口径：成功的存款订单笔数。
备注：存款成功率的分子；存提差、平均到帐时间的最小样本量。'),
    ('dTry', 'dep_try', NULL, 'dep_try', NULL, '口径：存款总尝试笔数（发起的存款订单数）。
备注：存款成功率的分母和最小样本量。'),
    ('wOk', 'wd_ok', NULL, 'wd_ok', NULL, '口径：成功的提款订单笔数。
备注：出款成功率的分子；平均出款时间的最小样本量。'),
    ('wTry', 'wd_try', NULL, 'wd_try', NULL, '口径：提款总尝试笔数（发起的提款订单数）。
备注：出款成功率的分母和最小样本量。')
  ) AS v(metric_code, hour_field, day_field, amount_field, baseline_field, remark)
 WHERE c.metric_code = v.metric_code
   AND c.uds_hour_field IS NULL AND c.uds_day_field IS NULL
   AND c.uds_amount_field IS NULL AND c.uds_baseline_field IS NULL;

-- 5. 口径：只改还不是「口径：」开头的行（后台维护过的不覆盖）
UPDATE dashboard_metric_card c
   SET remark = v.remark, update_by = 'admin', update_time = CURRENT_TIMESTAMP
  FROM (VALUES
    ('reg', 'reg', NULL, 'reg', 'reg_baseline', '口径：完成会员注册的人数，按注册时间（IST）归入所属小时。'),
    ('ftd', 'ftd', NULL, 'ftd', 'ftd_baseline', '口径：完成生涯首笔存款的人数，按首笔存款成功时间（IST）归入所属小时。'),
    ('ftdr', 'ftdr', NULL, 'ftdr', NULL, '口径：首存人数 ÷ 注册人数 × 100%。
备注：UDS 直接提供比率；判定引擎在检测周期大于 1 小时时按 Σ首存人数 ÷ Σ注册人数 重算。'),
    ('ftdA', 'ftdA', NULL, 'ftdA', 'ftdA_baseline', '口径：首存总金额 ÷ 首存人数。
备注：UDS 直接提供；检测周期大于 1 小时时按 Σ首存总金额 ÷ Σ首存人数 重算。'),
    ('dep', 'dep', NULL, 'dep', 'dep_baseline', '口径：所有会员成功存款金额加总（站点币种主单位）。'),
    ('wd', 'wd', NULL, 'wd', 'wd_baseline', '口径：所有会员成功提款金额加总（站点币种主单位）。'),
    ('net', NULL, NULL, NULL, NULL, '口径：存款总额 − 提款总额，可为负。
备注：UDS 不返回，后端相减（dashboard.gateway.uds.overview.subtract：dep − wd）；任一为空则为空。'),
    ('arppu', 'arppu_hour', 'arppu_day', NULL, 'arppu_hour_baseline', '口径：存款总额 ÷ 去重存款人数；按小时／日去重，仅支持查看单日数据。
备注：小时取 arppu_hour，整日取 arppu_day；跨多日区间不出值。'),
    ('turnX', 'turnX', NULL, 'turnX', NULL, '口径：投注总额 ÷ 存款总额。
备注：业务参考区间 9.5 – 12.5；检测周期大于 1 小时时按 Σ投注总额 ÷ Σ存款总额 重算。'),
    ('dOkR', 'dOkR', NULL, 'dOkR', NULL, '口径：存款成功笔数 ÷ 存款总尝试笔数 × 100%。
备注：UDS 比率为 0～1，页面 ×100。'),
    ('dT', 'dT_seconds', NULL, 'dT_seconds', NULL, '口径：存款自提交到入账的平均耗时，按成功笔数加权（分钟）。
备注：UDS 字段单位为秒，页面 ÷60 显示分钟。'),
    ('wOkR', 'wOkR', NULL, 'wOkR', NULL, '口径：出款成功笔数 ÷ 出款总尝试笔数 × 100%。
备注：UDS 比率为 0～1，页面 ×100。'),
    ('wT', 'wT_seconds', NULL, 'wT_seconds', NULL, '口径：提款自申请到拨款完成的平均耗时，按成功笔数加权（分钟）。
备注：UDS 字段单位为秒，页面 ÷60 显示分钟。'),
    ('active', 'active_hour', 'active_day', NULL, 'active_hour_baseline', '口径：有投注行为的去重会员数；按小时／日去重，跨时段不可相加，仅支持查看单日数据。
备注：小时取 active_hour，整日取 active_day；区间在一个自然日内时取日数据集当天的值。'),
    ('bet', 'bet', NULL, 'bet', 'bet_baseline', '口径：所有投注金额加总。'),
    ('ggr', 'ggr', NULL, 'ggr', 'ggr_baseline', '口径：0 − 玩家输赢（win loss amount），即投注总额 − 派彩金额。
备注：UDS 直接提供 ggr。'),
    ('killR', 'killR', NULL, 'killR', NULL, '口径：GGR ÷ 投注总额 × 100%。
备注：业务参考区间 3% – 6%。'),
    ('bonus', 'bonus', NULL, 'bonus', 'bonus_baseline', '口径：各赠金项目实际发放金额加总。
备注：运营总览排行榜按赠金项目拆分展示。'),
    ('bonusR', 'bonusR', NULL, 'bonusR', NULL, '口径：发放赠金总额 ÷ 投注总额 × 100%。
备注：业务参考区间 2.5% – 5%。'),
    ('ngr', NULL, NULL, NULL, 'ngr_baseline', '口径：GGR − 发放赠金总额。
备注：UDS 不返回，后端相减（overview.subtract：ggr − bonus）；不取旧 ngr_net 口径。跌幅基准由 UDS 按每小时 NGR 取中位数。'),
    ('login', 'login_player_cnt', 'login_player_cnt', NULL, NULL, '口径：完成登录的去重会员数；按小时／日去重，跨时段不可相加。
备注：不上指标墙，仅在指标汇总的列选择器里；不参与告警。'),
    ('depU', 'dep_users_hour', 'dep_users_day', NULL, NULL, '口径：有成功存款的去重会员数；按小时／日去重。
备注：ARPPU 的分母，不单独展示。'),
    ('ftdAmt', 'ftd_amount', NULL, 'ftd_amount', NULL, '口径：首笔存款金额加总。
备注：首存 ARPPU 的分子，不单独展示。'),
    ('pay', NULL, NULL, NULL, NULL, '口径：派彩金额 = 投注总额 + 玩家输赢（win_los_amt）。
备注：UDS 没有派彩字段，后端相加（overview.add：bet + winLoss，winLoss 取金额数据集 win_los_amt）；GGR 的减数，不单独展示。'),
    ('dOk', 'dep_ok', NULL, 'dep_ok', NULL, '口径：成功的存款订单笔数。
备注：存款成功率的分子；存提差、平均到帐时间的最小样本量。'),
    ('dTry', 'dep_try', NULL, 'dep_try', NULL, '口径：存款总尝试笔数（发起的存款订单数）。
备注：存款成功率的分母和最小样本量。'),
    ('wOk', 'wd_ok', NULL, 'wd_ok', NULL, '口径：成功的提款订单笔数。
备注：出款成功率的分子；平均出款时间的最小样本量。'),
    ('wTry', 'wd_try', NULL, 'wd_try', NULL, '口径：提款总尝试笔数（发起的提款订单数）。
备注：出款成功率的分母和最小样本量。')
  ) AS v(metric_code, hour_field, day_field, amount_field, baseline_field, remark)
 WHERE c.metric_code = v.metric_code
   AND (c.remark IS NULL OR c.remark NOT LIKE '口径：%');

COMMENT ON COLUMN dashboard_metric_card.source_table       IS 'UDS 上游原始表（UDS 数据集由哪张表加工而来，待 UDS 提供）；仅作记录，取数不读';
COMMENT ON COLUMN dashboard_metric_card.source_field       IS 'UDS 上游原始字段（与 source_table 成对，待 UDS 提供）；仅作记录，取数不读';
COMMENT ON COLUMN dashboard_metric_card.uds_hour_field     IS 'dashboard_overview_hour_v2 里的字段（小时行）；与 dashboard.gateway.uds.datasets.overview_hour.metrics 一致，取数以 yml 为准';
COMMENT ON COLUMN dashboard_metric_card.uds_day_field      IS 'dashboard_overview_day_v2 里的字段（整日去重人数类）；与 datasets.overview_day.metrics 一致';
COMMENT ON COLUMN dashboard_metric_card.uds_amount_field   IS 'dashboard_overview_amount_v2 里的字段（金额 / 普通比率的日、周序列与区间值）；与 datasets.overview_amount.metrics 一致';
COMMENT ON COLUMN dashboard_metric_card.uds_baseline_field IS '跌幅基准列（dashboard_overview_hour_v2，近 4 周同星期同小时中位数）；与 monitor.engine.baseline-fields 一致';
COMMENT ON COLUMN dashboard_metric_card.remark             IS '业务口径与备注：第一行「口径：…」，其后「备注：…」；后台「指标管理」维护，运营总览的 ⓘ 文案仍取代码 OverviewMetricText';

COMMIT;

-- 执行后核对：28 行；口径全部以「口径：」开头；取数字段与 yml 一致（net、ngr、pay 由后端组装，没有 UDS 字段）
SELECT metric_code, uds_hour_field, uds_day_field, uds_amount_field, uds_baseline_field,
       source_table, source_field, split_part(remark, E'\n', 1) AS definition
  FROM dashboard_metric_card ORDER BY show_as_card DESC, sort_no;
SELECT count(*) AS total,
       count(*) FILTER (WHERE remark LIKE '口径：%') AS with_definition,
       count(*) FILTER (WHERE source_table IS NOT NULL) AS with_upstream
  FROM dashboard_metric_card;

-- 回滚（恢复到执行前；新增的 4 列保留为空即可，不影响取数）：
-- BEGIN;
-- UPDATE dashboard_metric_card c
--    SET source_table = b.source_table, source_field = b.source_field, remark = b.remark,
--        uds_hour_field = NULL, uds_day_field = NULL, uds_amount_field = NULL, uds_baseline_field = NULL,
--        update_by = b.update_by, update_time = b.update_time
--   FROM dashboard_metric_card_bak_20261026 b WHERE b.metric_code = c.metric_code;
-- COMMIT;
