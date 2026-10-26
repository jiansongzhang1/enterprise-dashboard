-- ============================================================================
-- dashboard_metric_card：去掉旧告警字段，改为「指标的监控属性」
--
-- 背景：告警规则统一由监控管理的 monitor_rule 配置（按站点、注意 / 严重两级、
-- 检测周期、持续次数、负责人）。本表原有的 alert_* / tolerance_* 是早期设计，
-- 全局唯一、模型对不上，代码也没有读取，继续保留会造成「两处都能配」的误解。
--
-- 本表只保留指标本身、不随站点变化的监控属性：能否建规则、判定类型、
-- 样本量看哪个值及默认值、阈值能否为负。新建规则时从这里带出，
-- 并复制到 monitor_rule（规则创建后不随本表变化）。
--
-- 可重复执行。
-- ============================================================================

BEGIN;

-- ---------- 1. 删除旧告警约束、索引与字段 ----------
ALTER TABLE dashboard_metric_card DROP CONSTRAINT IF EXISTS ck_alert_params_required;
ALTER TABLE dashboard_metric_card DROP CONSTRAINT IF EXISTS ck_alert_params;
ALTER TABLE dashboard_metric_card DROP CONSTRAINT IF EXISTS ck_alert_level;
DROP INDEX IF EXISTS idx_metric_card_alert;

ALTER TABLE dashboard_metric_card DROP COLUMN IF EXISTS alert_enabled;
ALTER TABLE dashboard_metric_card DROP COLUMN IF EXISTS alert_min;
ALTER TABLE dashboard_metric_card DROP COLUMN IF EXISTS alert_max;
ALTER TABLE dashboard_metric_card DROP COLUMN IF EXISTS tolerance_pct;
ALTER TABLE dashboard_metric_card DROP COLUMN IF EXISTS tolerance_crit_multiple;
ALTER TABLE dashboard_metric_card DROP COLUMN IF EXISTS alert_level;

-- ---------- 2. 新增监控属性 ----------
ALTER TABLE dashboard_metric_card ADD COLUMN IF NOT EXISTS monitorable        BOOLEAN       NOT NULL DEFAULT FALSE;
ALTER TABLE dashboard_metric_card ADD COLUMN IF NOT EXISTS judge_type         VARCHAR(8);
ALTER TABLE dashboard_metric_card ADD COLUMN IF NOT EXISTS min_sample_key     VARCHAR(64);
ALTER TABLE dashboard_metric_card ADD COLUMN IF NOT EXISTS min_sample_default NUMERIC(18,4);
ALTER TABLE dashboard_metric_card ADD COLUMN IF NOT EXISTS allow_negative     BOOLEAN       NOT NULL DEFAULT FALSE;
-- 运营总览卡片的竖向门槛条刻度（展示单位，与指标值同量纲）；两者都有值的指标卡片画门槛条，否则画走势图
ALTER TABLE dashboard_metric_card ADD COLUMN IF NOT EXISTS gauge_min          NUMERIC(18,4);
ALTER TABLE dashboard_metric_card ADD COLUMN IF NOT EXISTS gauge_max          NUMERIC(18,4);

ALTER TABLE dashboard_metric_card DROP CONSTRAINT IF EXISTS ck_judge_type;
ALTER TABLE dashboard_metric_card ADD CONSTRAINT ck_judge_type
    CHECK (judge_type IS NULL OR judge_type IN ('lo', 'hi', 'band', 'drop'));

-- 可监控的指标必须有判定类型与样本量口径，否则新建规则时无从带出
ALTER TABLE dashboard_metric_card DROP CONSTRAINT IF EXISTS ck_gauge;
ALTER TABLE dashboard_metric_card ADD CONSTRAINT ck_gauge
    CHECK ((gauge_min IS NULL AND gauge_max IS NULL)
        OR (gauge_min IS NOT NULL AND gauge_max IS NOT NULL AND gauge_min < gauge_max));

ALTER TABLE dashboard_metric_card DROP CONSTRAINT IF EXISTS ck_monitorable;
ALTER TABLE dashboard_metric_card ADD CONSTRAINT ck_monitorable
    CHECK (monitorable = FALSE OR (judge_type IS NOT NULL AND min_sample_key IS NOT NULL));

COMMENT ON TABLE  dashboard_metric_card IS '指标卡配置：展示方式、计算规则、监控属性（告警规则见 monitor_rule）';
COMMENT ON COLUMN dashboard_metric_card.monitorable        IS '能否在监控管理中建告警规则';
COMMENT ON COLUMN dashboard_metric_card.judge_type         IS '判定类型：lo 下限 / hi 上限 / band 区间 / drop 跌幅（较近 4 周同星期同小时中位数）';
COMMENT ON COLUMN dashboard_metric_card.min_sample_key     IS '最小样本量看哪个值：base = 跌幅类的基准值；其余为分母指标的 metric_code';
COMMENT ON COLUMN dashboard_metric_card.min_sample_default IS '新建规则时的默认最小样本量；金额为站点币种主单位';
COMMENT ON COLUMN dashboard_metric_card.allow_negative     IS '阈值是否允许为负数（仅存提差）';
COMMENT ON COLUMN dashboard_metric_card.gauge_min          IS '卡片门槛条刻度下限（展示单位）；与 gauge_max 都有值时卡片画门槛条';
COMMENT ON COLUMN dashboard_metric_card.gauge_max          IS '卡片门槛条刻度上限（展示单位）';

-- ---------- 3. 初始值：需求文档「监控预警」第四、五节的 20 个指标 ----------
-- 先全部清空，再按清单写入，保证可重复执行的结果一致
UPDATE dashboard_metric_card
   SET monitorable = FALSE, judge_type = NULL, min_sample_key = NULL,
       min_sample_default = NULL, allow_negative = FALSE;

UPDATE dashboard_metric_card AS c
   SET monitorable = TRUE,
       judge_type = v.judge_type,
       min_sample_key = v.min_sample_key,
       min_sample_default = v.min_sample_default,
       allow_negative = v.allow_negative
  FROM (VALUES
    -- 下限
    ('dOkR',   'lo',   'dTry',  30,      FALSE),  -- 存款成功率：存款尝试笔数 ≥ 30（R-001）
    ('wOkR',   'lo',   'wTry',  20,      FALSE),  -- 出款成功率：提款尝试笔数 ≥ 20（R-003）
    ('ftdr',   'lo',   'reg',   10,      FALSE),  -- 首存转化率：注册人数 ≥ 10（R-013）
    ('net',    'lo',   'dOk',   20,      TRUE),   -- 存提差：存款成功笔数 ≥ 20，阈值可为负
    -- 上限
    ('dT',     'hi',   'dOk',   20,      FALSE),  -- 平均到帐时间：存款成功笔数 ≥ 20（R-002）
    ('wT',     'hi',   'wOk',   15,      FALSE),  -- 平均出款时间：提款成功笔数 ≥ 15（R-004）
    -- 区间（投注人数 betU 尚未入本表，见下方说明）
    ('killR',  'band', 'betU',  50,      FALSE),  -- 平均杀率（R-005）
    ('bonusR', 'band', 'betU',  50,      FALSE),  -- 赠金比（R-006）
    ('turnX',  'band', 'betU',  50,      FALSE),  -- 流水倍数（R-007）
    -- 跌幅
    ('reg',    'drop', 'base',  15,      FALSE),  -- 注册人数：基准 ≥ 15 人（R-012）
    ('ftd',    'drop', 'base',  5,       FALSE),  -- 首存人数：基准 ≥ 5 人
    ('ftdA',   'drop', 'ftd',   3,       FALSE),  -- 首存ARPPU：首存人数 ≥ 3
    ('dep',    'drop', 'base',  50000,   FALSE),  -- 存款总额：基准 ≥ ₹50,000（R-008）
    ('wd',     'drop', 'base',  50000,   FALSE),  -- 提款总额：基准 ≥ ₹50,000
    ('arppu',  'drop', 'depU',  20,      FALSE),  -- ARPPU：存款人数 ≥ 20
    ('active', 'drop', 'base',  30,      FALSE),  -- 活跃人数：基准 ≥ 30 人（R-011）
    ('bet',    'drop', 'base',  100000,  FALSE),  -- 投注总额：基准 ≥ ₹100,000（R-010）
    ('ggr',    'drop', 'base',  30000,   FALSE),  -- GGR：基准 ≥ ₹30,000（R-009）
    ('bonus',  'drop', 'base',  15000,   FALSE),  -- 发放赠金总额：基准 ≥ ₹15,000
    ('ngr',    'drop', 'base',  10000,   FALSE)   -- NGR：基准 ≥ ₹10,000
  ) AS v(metric_code, judge_type, min_sample_key, min_sample_default, allow_negative)
 WHERE c.metric_code = v.metric_code;

-- 说明：
-- 1. 区间类的样本量口径是「投注人数」（需求文档 R-005～R-007），本表目前没有这个原子量，
--    暂记为 betU。判定引擎上线前需与 UDS 确认字段，并补一行 betU（show_as_card = FALSE）。
-- 2. 默认样本量来自需求文档预置规则与原型；量级较小的站点在新建 / 预置规则时自行调整，
--    这里只是新建规则表单的默认值，不影响已建规则。

-- ---------- 4. 门槛条刻度：运营总览 7 个指标 ----------
UPDATE dashboard_metric_card SET gauge_min = NULL, gauge_max = NULL;
UPDATE dashboard_metric_card AS c
   SET gauge_min = v.lo, gauge_max = v.hi
  FROM (VALUES
    ('dOkR',85,100), ('wOkR',88,100), ('dT',0,20), ('wT',0,80),
    ('killR',0,10), ('bonusR',0,8), ('turnX',5,18)
  ) AS v(metric_code, lo, hi)
 WHERE c.metric_code = v.metric_code;

-- ---------- 5. 校验：可监控 20 个、门槛条 7 个 ----------
DO $$
DECLARE n INT;
BEGIN
    SELECT COUNT(*) INTO n FROM dashboard_metric_card WHERE monitorable;
    IF n <> 20 THEN
        RAISE EXCEPTION 'monitorable 指标数为 %，应为 20，请检查 metric_code', n;
    END IF;
    SELECT COUNT(*) INTO n FROM dashboard_metric_card WHERE gauge_min IS NOT NULL;
    IF n <> 7 THEN
        RAISE EXCEPTION '门槛条指标数为 %，应为 7，请检查 metric_code', n;
    END IF;
END $$;

COMMIT;
