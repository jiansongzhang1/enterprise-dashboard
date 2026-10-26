-- ============================================================================
-- 指标卡配置表
-- 展示方式、计算规则全部配置化，改口径不用发版。
--
-- 告警规则不在这里：按站点配置在监控管理的 monitor_rule（注意 / 严重两级、
-- 检测周期、持续次数、负责人）。本表只存指标自带的监控属性
-- （能否建规则、判定类型、样本量口径），新建规则时带出。
-- ============================================================================

DROP TABLE IF EXISTS dashboard_metric_card;

CREATE TABLE dashboard_metric_card (
    id                  BIGSERIAL       PRIMARY KEY,

    -- ---------- 标识 ----------
    metric_code         VARCHAR(64)     NOT NULL,
    metric_name         VARCHAR(128)    NOT NULL,
    metric_name_en      VARCHAR(128),
    group_code          VARCHAR(32),

    -- ---------- 数据来源 ----------
    -- UDS 上游原始表 / 字段（UDS 数据集由哪张表加工而来，待 UDS 提供）；仅作记录，取数不读
    source_table        VARCHAR(128),
    source_field        VARCHAR(128),
    -- UDS 数据集字段（与 application.yml 的 dashboard.gateway.uds.datasets 一致，取数以 yml 为准）
    uds_hour_field      VARCHAR(128),
    uds_day_field       VARCHAR(128),
    uds_amount_field    VARCHAR(128),
    -- 跌幅基准列（与 monitor.engine.baseline-fields 一致）
    uds_baseline_field  VARCHAR(128),
    -- 更新频率。取值固定，前端按它显示「每小时更新」这类角标，
    -- 也决定用户对「数据为什么还没变」的预期。不是查询用的时间字段名
    update_frequency    VARCHAR(16)     NOT NULL DEFAULT 'hour',
    -- 仅支持单日查询：上游只按自然日给数（日去重人数、日口径比率），
    -- 运营总览区间跨天时不查、不出卡，而不是给一个跨天的错数
    single_day_only     BOOLEAN         NOT NULL DEFAULT FALSE,

    -- ---------- 展示 ----------
    chart_type          VARCHAR(16)     NOT NULL DEFAULT 'LINE',

    -- 数值的语义单位，决定加什么符号、小数位、以及变化率用 % 还是 pt。
    -- 只描述「这个数是什么」，不描述「长什么样」——渲染规则在代码里，按上下文分 CARD/TABLE/CSV
    value_format        VARCHAR(16)     NOT NULL DEFAULT 'INT',
    -- 覆盖 value_format 的默认小数位；NULL = 用默认（INT 0 / MONEY 0 / PCT 2 / MIN 1 / MULTIPLE 2）
    value_decimals      SMALLINT,

    -- 涨跌的好坏方向，决定前端变化箭头着色：
    -- up 越高越好（涨绿跌红）/ down 越低越好（涨红跌绿）/ flat 中性不着色 / range 落在正常区间内为好
    direction           VARCHAR(8)      NOT NULL DEFAULT 'up',

    show_as_card        BOOLEAN         NOT NULL DEFAULT TRUE,
    sort_no             INT             NOT NULL DEFAULT 0,

    -- ---------- 计算规则 ----------
    -- NONE 直接取 source_table.source_field；DIVIDE 分子÷分母；SUBTRACT 被减数−减数
    calc_type           VARCHAR(16)     NOT NULL DEFAULT 'NONE',
    left_code           VARCHAR(64),
    right_code          VARCHAR(64),

    -- 跨时间片聚合方式。选错会让长区间数字静默出错：
    -- SUM 可加 / AVG_WEIGHTED 按权重列加权 / DISTINCT 去重不可加 / FORMULA 先聚合再套公式
    agg_type            VARCHAR(16)     NOT NULL DEFAULT 'SUM',
    weight_field        VARCHAR(128),

    -- ---------- 监控属性 ----------
    -- 告警规则本身（阈值、周期、负责人）按站点配置在 monitor_rule；
    -- 这里只放指标自带、不随站点变化的属性，新建规则时带出并复制到规则上
    monitorable         BOOLEAN         NOT NULL DEFAULT FALSE,
    -- lo 下限 / hi 上限 / band 区间 / drop 跌幅（较近 4 周同星期同小时中位数）
    judge_type          VARCHAR(8),
    -- 最小样本量看哪个值：base = 跌幅类的基准；其余为分母指标的 metric_code
    min_sample_key      VARCHAR(64),
    min_sample_default  NUMERIC(18,4),
    -- 阈值能否为负（仅存提差）
    allow_negative      BOOLEAN         NOT NULL DEFAULT FALSE,
    -- 运营总览卡片的竖向门槛条刻度（展示单位）；两者都有值时卡片画门槛条，否则画走势图
    gauge_min           NUMERIC(18,4),
    gauge_max           NUMERIC(18,4),

    -- ---------- 通用字段 ----------
    status              CHAR(1)         NOT NULL DEFAULT '0',
    create_by           VARCHAR(64)     DEFAULT '',
    create_time         TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    update_by           VARCHAR(64)     DEFAULT '',
    update_time         TIMESTAMP,
    remark              VARCHAR(500),

    CONSTRAINT uk_metric_code UNIQUE (metric_code),

    CONSTRAINT ck_chart_type  CHECK (chart_type IN ('LINE','PIE','NUMBER')),
    CONSTRAINT ck_update_frequency CHECK (update_frequency IN ('realtime','hour','day')),
    CONSTRAINT ck_value_format CHECK (value_format IN ('INT','MONEY','PCT','MIN','MULTIPLE')),
    CONSTRAINT ck_direction    CHECK (direction IN ('up','down','flat','range')),
    CONSTRAINT ck_value_decimals CHECK (value_decimals IS NULL OR value_decimals BETWEEN 0 AND 4),
    CONSTRAINT ck_calc_type   CHECK (calc_type  IN ('NONE','DIVIDE','SUBTRACT')),
    CONSTRAINT ck_agg_type    CHECK (agg_type   IN ('SUM','AVG_WEIGHTED','DISTINCT','FORMULA')),

    -- 计算类型与分子分母必须自洽
    CONSTRAINT ck_calc_operands CHECK (
        (calc_type = 'NONE'  AND left_code IS NULL AND right_code IS NULL)
        OR (calc_type <> 'NONE' AND left_code IS NOT NULL AND right_code IS NOT NULL)
    ),
    -- source_table / source_field 是 UDS 上游原始表的记录，可以为空（2026-10-26 去掉 ck_source）
    -- 加权平均必须指定权重列
    CONSTRAINT ck_weight CHECK (
        agg_type <> 'AVG_WEIGHTED' OR weight_field IS NOT NULL
    ),
    CONSTRAINT ck_gauge CHECK (
        (gauge_min IS NULL AND gauge_max IS NULL)
        OR (gauge_min IS NOT NULL AND gauge_max IS NOT NULL AND gauge_min < gauge_max)
    ),
    CONSTRAINT ck_judge_type CHECK (
        judge_type IS NULL OR judge_type IN ('lo','hi','band','drop')
    ),
    -- 可监控的指标必须有判定类型与样本量口径，否则新建规则时无从带出
    CONSTRAINT ck_monitorable CHECK (
        monitorable = FALSE OR (judge_type IS NOT NULL AND min_sample_key IS NOT NULL)
    )
);

CREATE INDEX idx_metric_card_group ON dashboard_metric_card (group_code, sort_no);

COMMENT ON TABLE  dashboard_metric_card IS '指标卡配置：展示方式、计算规则、监控属性（告警规则见 monitor_rule）';
COMMENT ON COLUMN dashboard_metric_card.update_frequency IS '更新频率：realtime 实时 / hour 每小时 / day 每日';
COMMENT ON COLUMN dashboard_metric_card.single_day_only IS '仅支持单日查询：区间跨天时运营总览不查询、不展示';
COMMENT ON COLUMN dashboard_metric_card.chart_type    IS 'LINE 折线 / PIE 饼图 / NUMBER 纯数值；运营总览卡片只按刻度区分 GAUGE / LINE，不读本列';
COMMENT ON COLUMN dashboard_metric_card.value_format   IS 'INT 计数 / MONEY 金额（站点币种主单位）/ PCT 百分比（入库已×100）/ MIN 分钟 / MULTIPLE 倍数';
COMMENT ON COLUMN dashboard_metric_card.value_decimals IS '覆盖默认小数位，NULL 用默认。仅 MONEY 类需要：ARPPU 取 1 位，其余 0 位';
COMMENT ON COLUMN dashboard_metric_card.calc_type     IS 'NONE 原始值 / DIVIDE 相除 / SUBTRACT 相减';
COMMENT ON COLUMN dashboard_metric_card.agg_type      IS '跨时间片聚合方式，选错会让长区间数字出错';
COMMENT ON COLUMN dashboard_metric_card.direction     IS '涨跌好坏方向：up 越高越好 / down 越低越好 / flat 中性 / range 区间内为好；前端据此给变化着色';
COMMENT ON COLUMN dashboard_metric_card.show_as_card  IS '是否作为卡片展示；仅供派生引用的原子量填 FALSE';
COMMENT ON COLUMN dashboard_metric_card.monitorable        IS '能否在监控管理中建告警规则';
COMMENT ON COLUMN dashboard_metric_card.judge_type         IS '判定类型：lo 下限 / hi 上限 / band 区间 / drop 跌幅（较近 4 周同星期同小时中位数）';
COMMENT ON COLUMN dashboard_metric_card.min_sample_key     IS '最小样本量看哪个值：base = 跌幅类的基准值；其余为分母指标的 metric_code';
COMMENT ON COLUMN dashboard_metric_card.min_sample_default IS '新建规则时的默认最小样本量；金额为站点币种主单位';
COMMENT ON COLUMN dashboard_metric_card.allow_negative     IS '阈值是否允许为负数（仅存提差）';
COMMENT ON COLUMN dashboard_metric_card.gauge_min          IS '卡片门槛条刻度下限（展示单位）；与 gauge_max 都有值时卡片画门槛条';
COMMENT ON COLUMN dashboard_metric_card.gauge_max          IS '卡片门槛条刻度上限（展示单位）';

-- ============================================================================
-- 初始数据：原型全部 21 个指标卡 + 7 个仅供引用的原子量
-- ============================================================================

INSERT INTO dashboard_metric_card
(metric_code, metric_name, metric_name_en, group_code, direction, source_table, source_field,
 chart_type, value_format, value_decimals, show_as_card, sort_no,
 calc_type, left_code, right_code, agg_type, weight_field,
 create_by, remark)
VALUES
-- ===== eg1 获客与转化 =====
('login','登录人数','Logins','eg1','up','dws_user_login_hourly','login_uv',
 'LINE','INT',NULL,TRUE,5,'NONE',NULL,NULL,'DISTINCT',NULL,
 'admin','不参与告警扫描；不上指标墙，仅保留在指标汇总列选择器'),
('reg','注册人数','Signups','eg1','up','dws_user_reg_hourly','reg_cnt',
 'LINE','INT',NULL,TRUE,10,'NONE',NULL,NULL,'SUM',NULL,
 'admin',NULL),
('ftd','首存人数','First depositors','eg1','up','dws_user_ftd_hourly','ftd_cnt',
 'LINE','INT',NULL,TRUE,20,'NONE',NULL,NULL,'SUM',NULL,
 'admin',NULL),
('ftdr','首存转化率','First-deposit conversion','eg1','up',NULL,NULL,
 'LINE','PCT',NULL,TRUE,30,'DIVIDE','ftd','reg','FORMULA',NULL,
 'admin','首存人数 / 注册人数'),
('ftdA','首存ARPPU','First-deposit ARPPU','eg1','up',NULL,NULL,
 'LINE','MONEY',1,TRUE,40,'DIVIDE','ftdAmt','ftd','FORMULA',NULL,
 'admin','首存总金额 / 首存人数'),
-- ===== eg2 资金流 =====
('dep','存款总额','Deposit amount','eg2','up','dws_pay_deposit_hourly','dep_amt',
 'LINE','MONEY',NULL,TRUE,50,'NONE',NULL,NULL,'SUM',NULL,
 'admin','二期改用同时段历史 P5-P95 基线'),
('wd','提款总额','Withdrawal amount','eg2','flat','dws_pay_withdraw_hourly','wd_amt',
 'LINE','MONEY',NULL,TRUE,60,'NONE',NULL,NULL,'SUM',NULL,
 'admin',NULL),
('net','存提差','Net deposits','eg2','up',NULL,NULL,
 'LINE','MONEY',NULL,TRUE,70,'SUBTRACT','dep','wd','FORMULA',NULL,
 'admin','存款总额 - 提款总额'),
('arppu','ARPPU','ARPPU','eg2','up',NULL,NULL,
 'LINE','MONEY',1,TRUE,80,'DIVIDE','dep','depU','FORMULA',NULL,
 'admin','存款总额 / 存款人数；分母按整个区间去重'),
('turnX','流水倍数','Turnover multiple','eg2','range',NULL,NULL,
 'LINE','MULTIPLE',NULL,TRUE,90,'DIVIDE','bet','dep','FORMULA',NULL,
 'admin','正常区间 9.5 - 12.5'),
-- ===== eg3 通道健康度 =====
('dOkR','存款成功率','Deposit success rate','eg3','up',NULL,NULL,
 'LINE','PCT',NULL,TRUE,100,'DIVIDE','dOk','dTry','FORMULA',NULL,
 'admin','低于 95% 告警（单边下界）'),
('dT','平均到帐时间','Avg settlement time','eg3','down','ods_pay_order','settle_minutes',
 'LINE','MIN',NULL,TRUE,110,'NONE',NULL,NULL,'AVG_WEIGHTED','dep_ok_cnt',
 'admin','按笔数加权；超过 5 分钟告警（单边上界）'),
('wOkR','出款成功率','Withdrawal success rate','eg3','up',NULL,NULL,
 'LINE','PCT',NULL,TRUE,120,'DIVIDE','wOk','wTry','FORMULA',NULL,
 'admin','低于 97% 告警（单边下界）'),
('wT','平均出款时间','Avg payout time','eg3','down','ods_pay_order','payout_minutes',
 'LINE','MIN',NULL,TRUE,130,'NONE',NULL,NULL,'AVG_WEIGHTED','wd_ok_cnt',
 'admin','按笔数加权；超过 20 分钟告警（单边上界）'),
-- ===== eg4 投注与盈收 =====
('active','活跃人数','Active members','eg4','up','dws_user_active_hourly','active_uv',
 'LINE','INT',NULL,TRUE,140,'NONE',NULL,NULL,'DISTINCT',NULL,
 'admin','去重人数，跨时间片不可相加'),
('bet','投注总额','Turnover','eg4','up','dws_bet_hourly','bet_amt',
 'LINE','MONEY',NULL,TRUE,150,'NONE',NULL,NULL,'SUM',NULL,
 'admin',NULL),
('ggr','GGR','GGR','eg4','up',NULL,NULL,
 'LINE','MONEY',NULL,TRUE,160,'SUBTRACT','bet','pay','FORMULA',NULL,
 'admin','投注总额 - 派彩金额'),
('killR','平均杀率','Average hold rate','eg4','range',NULL,NULL,
 'LINE','PCT',NULL,TRUE,170,'DIVIDE','ggr','bet','FORMULA',NULL,
 'admin','正常区间 3% - 6%'),
('bonus','发放赠金总额','Bonuses granted','eg4','flat','dws_bonus_hourly','bonus_amt',
 'PIE','MONEY',NULL,TRUE,180,'NONE',NULL,NULL,'SUM',NULL,
 'admin','按赠金项目拆分展示'),
('bonusR','赠金比','Bonus ratio','eg4','range',NULL,NULL,
 'LINE','PCT',NULL,TRUE,190,'DIVIDE','bonus','bet','FORMULA',NULL,
 'admin','正常区间 2.5% - 5%'),
('ngr','NGR','NGR','eg4','up',NULL,NULL,
 'LINE','MONEY',NULL,TRUE,200,'SUBTRACT','ggr','bonus','FORMULA',NULL,
 'admin','GGR - 发放赠金总额'),
-- ===== 仅供派生指标引用，不做卡片 =====
('depU','存款人数',NULL,'eg2','up','dws_pay_deposit_hourly','dep_uv',
 'NUMBER','INT',NULL,FALSE,900,'NONE',NULL,NULL,'DISTINCT',NULL,
 'admin','ARPPU 的分母'),
('ftdAmt','首存总金额',NULL,'eg1','up','dws_user_ftd_hourly','ftd_amt',
 'NUMBER','MONEY',NULL,FALSE,901,'NONE',NULL,NULL,'SUM',NULL,
 'admin','首存ARPPU 的分子'),
('pay','派彩金额',NULL,'eg4','flat','dws_bet_hourly','payout_amt',
 'NUMBER','MONEY',NULL,FALSE,902,'NONE',NULL,NULL,'SUM',NULL,
 'admin','GGR 的减数'),
('dOk','存款成功笔数',NULL,'eg3','up','ods_pay_order','dep_ok_cnt',
 'NUMBER','INT',NULL,FALSE,903,'NONE',NULL,NULL,'SUM',NULL,
 'admin','存款成功率的分子'),
('dTry','存款尝试笔数',NULL,'eg3','flat','ods_pay_order','dep_try_cnt',
 'NUMBER','INT',NULL,FALSE,904,'NONE',NULL,NULL,'SUM',NULL,
 'admin','存款成功率的分母'),
('wOk','出款成功笔数',NULL,'eg3','up','ods_pay_order','wd_ok_cnt',
 'NUMBER','INT',NULL,FALSE,905,'NONE',NULL,NULL,'SUM',NULL,
 'admin','出款成功率的分子'),
('wTry','出款尝试笔数',NULL,'eg3','flat','ods_pay_order','wd_try_cnt',
 'NUMBER','INT',NULL,FALSE,906,'NONE',NULL,NULL,'SUM',NULL,
 'admin','出款成功率的分母');

-- 仅支持单日查询的指标：活跃人数（日去重）、ARPPU（日口径）
UPDATE dashboard_metric_card SET single_day_only = TRUE WHERE metric_code IN ('active', 'arppu');

-- 监控属性：需求文档「监控预警」的 20 个可监控指标。
-- 区间类的样本量口径是投注人数，本表暂无该原子量，记为 betU，判定引擎上线前补齐
UPDATE dashboard_metric_card AS c
   SET monitorable = TRUE, judge_type = v.judge_type, min_sample_key = v.min_sample_key,
       min_sample_default = v.min_sample_default, allow_negative = v.allow_negative
  FROM (VALUES
    ('dOkR','lo','dTry',30,FALSE), ('wOkR','lo','wTry',20,FALSE), ('ftdr','lo','reg',10,FALSE), ('net','lo','dOk',20,TRUE),
    ('dT','hi','dOk',20,FALSE), ('wT','hi','wOk',15,FALSE),
    ('killR','band','betU',50,FALSE), ('bonusR','band','betU',50,FALSE), ('turnX','band','betU',50,FALSE),
    ('reg','drop','base',15,FALSE), ('ftd','drop','base',5,FALSE), ('ftdA','drop','ftd',3,FALSE),
    ('dep','drop','base',50000,FALSE), ('wd','drop','base',50000,FALSE), ('arppu','drop','depU',20,FALSE),
    ('active','drop','base',30,FALSE), ('bet','drop','base',100000,FALSE), ('ggr','drop','base',30000,FALSE),
    ('bonus','drop','base',15000,FALSE), ('ngr','drop','base',10000,FALSE)
  ) AS v(metric_code, judge_type, min_sample_key, min_sample_default, allow_negative)
 WHERE c.metric_code = v.metric_code;

-- 运营总览卡片的竖向门槛条刻度（展示单位）
UPDATE dashboard_metric_card AS c
   SET gauge_min = v.lo, gauge_max = v.hi
  FROM (VALUES
    ('dOkR',85,100), ('wOkR',88,100), ('dT',0,20), ('wT',0,80),
    ('killR',0,10), ('bonusR',0,8), ('turnX',5,18)
  ) AS v(metric_code, lo, hi)
 WHERE c.metric_code = v.metric_code;

-- ============================================================================
-- 指标字典（2026-10-26）：口径与取数方式。与 alter_metric_card_dictionary_postgresql.sql 第 4、5 步内容相同，
-- 改口径或 UDS 字段时两处一起改；上线后以后台「系统管理 · 指标管理」维护的为准。
-- source_table / source_field 记 UDS 上游原始表 / 字段（待 UDS 提供），这里先清空早期 Doris 表名。
-- ============================================================================
UPDATE dashboard_metric_card SET source_table = NULL, source_field = NULL;

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
