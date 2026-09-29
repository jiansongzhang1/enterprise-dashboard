-- ============================================================================
-- 指标卡配置表
-- 展示方式、计算规则、告警规则全部配置化，改口径不用发版。
--
-- 告警只有两种参数形态，规则类型隐式判定，不再单独存类型和运算符：
--   区间型   配 alert_min / alert_max —— 存的是「正常区间」，落在区间外才告警
--            单边留空 = 该侧无限制，所以「低于 95% 告警」就是 min=95, max=NULL
--   变化率型 配 tolerance_pct —— |环比变化率| 超过它告警
--   两者互斥，且启用告警时必须至少配一组
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
    -- 只有表名取不到数，必须和字段名成对出现
    source_table        VARCHAR(128),
    source_field        VARCHAR(128),
    time_field          VARCHAR(64)     DEFAULT 'dt',

    -- ---------- 展示 ----------
    chart_type          VARCHAR(16)     NOT NULL DEFAULT 'LINE',

    -- 数值的语义单位，决定加什么符号、小数位、以及变化率用 % 还是 pt。
    -- 只描述「这个数是什么」，不描述「长什么样」——渲染规则在代码里，按上下文分 CARD/TABLE/CSV
    value_format        VARCHAR(16)     NOT NULL DEFAULT 'INT',
    -- 覆盖 value_format 的默认小数位；NULL = 用默认（INT 0 / MONEY 0 / PCT 2 / MIN 1 / MULTIPLE 2）
    value_decimals      SMALLINT,

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

    -- ---------- 告警 ----------
    alert_enabled       BOOLEAN         NOT NULL DEFAULT FALSE,

    -- 正常区间的下界与上界。值 < alert_min 或 > alert_max 时告警。
    -- 只填一侧即为单边阈值：min=95 等价于「低于 95 告警」，max=5 等价于「超过 5 告警」
    alert_min           NUMERIC(18,4),
    alert_max           NUMERIC(18,4),

    -- |环比变化率| 超过 tolerance_pct 告警（WARN），
    -- 超过 tolerance_pct × tolerance_crit_multiple 升级为 CRIT
    tolerance_pct       NUMERIC(8,2),
    tolerance_crit_multiple NUMERIC(4,2) DEFAULT 2,

    -- 区间型告警的级别；变化率型由上面的倍数自动分级，此列对其无效
    alert_level         VARCHAR(8)      DEFAULT 'WARN',

    -- ---------- 通用字段 ----------
    status              CHAR(1)         NOT NULL DEFAULT '0',
    create_by           VARCHAR(64)     DEFAULT '',
    create_time         TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    update_by           VARCHAR(64)     DEFAULT '',
    update_time         TIMESTAMP,
    remark              VARCHAR(500),

    CONSTRAINT uk_metric_code UNIQUE (metric_code),

    CONSTRAINT ck_chart_type  CHECK (chart_type IN ('LINE','BAR','PIE','NUMBER')),
    CONSTRAINT ck_value_format CHECK (value_format IN ('INT','MONEY','PCT','MIN','MULTIPLE')),
    CONSTRAINT ck_value_decimals CHECK (value_decimals IS NULL OR value_decimals BETWEEN 0 AND 4),
    CONSTRAINT ck_calc_type   CHECK (calc_type  IN ('NONE','DIVIDE','SUBTRACT')),
    CONSTRAINT ck_agg_type    CHECK (agg_type   IN ('SUM','AVG_WEIGHTED','DISTINCT','FORMULA')),
    CONSTRAINT ck_alert_level CHECK (alert_level IN ('WARN','CRIT')),

    -- 计算类型与分子分母必须自洽
    CONSTRAINT ck_calc_operands CHECK (
        (calc_type = 'NONE'  AND left_code IS NULL AND right_code IS NULL)
        OR (calc_type <> 'NONE' AND left_code IS NOT NULL AND right_code IS NOT NULL)
    ),
    -- 原子指标必须有取数位置
    CONSTRAINT ck_source CHECK (
        calc_type <> 'NONE' OR (source_table IS NOT NULL AND source_field IS NOT NULL)
    ),
    -- 加权平均必须指定权重列
    CONSTRAINT ck_weight CHECK (
        agg_type <> 'AVG_WEIGHTED' OR weight_field IS NOT NULL
    ),
    -- 开了告警就必须至少配一组参数，否则永远不会触发，是静默失效
    CONSTRAINT ck_alert_params_required CHECK (
        alert_enabled = FALSE
        OR alert_min IS NOT NULL OR alert_max IS NOT NULL OR tolerance_pct IS NOT NULL
    ),
    -- 两种形态互斥，且区间两侧都有界时下界必须小于上界。
    -- 配错了不会报错、只会静默漏报，所以用约束挡住
    CONSTRAINT ck_alert_params CHECK (
        (alert_min IS NULL AND alert_max IS NULL AND tolerance_pct IS NULL)
        OR ((alert_min IS NOT NULL OR alert_max IS NOT NULL)
            AND tolerance_pct IS NULL
            AND (alert_min IS NULL OR alert_max IS NULL OR alert_min < alert_max))
        OR (tolerance_pct IS NOT NULL AND tolerance_pct > 0
            AND alert_min IS NULL AND alert_max IS NULL)
    )
);

CREATE INDEX idx_metric_card_group ON dashboard_metric_card (group_code, sort_no);
CREATE INDEX idx_metric_card_alert ON dashboard_metric_card (alert_enabled) WHERE alert_enabled = TRUE;

COMMENT ON TABLE  dashboard_metric_card IS '指标卡配置：展示方式、计算规则、告警规则';
COMMENT ON COLUMN dashboard_metric_card.source_table  IS '来源表/数据集，派生指标可为空';
COMMENT ON COLUMN dashboard_metric_card.source_field  IS '来源字段，与 source_table 成对使用';
COMMENT ON COLUMN dashboard_metric_card.chart_type    IS 'LINE 折线 / BAR 柱状 / PIE 饼图 / NUMBER 纯数值';
COMMENT ON COLUMN dashboard_metric_card.value_format   IS 'INT 计数 / MONEY 金额（站点币种主单位）/ PCT 百分比（入库已×100）/ MIN 分钟 / MULTIPLE 倍数';
COMMENT ON COLUMN dashboard_metric_card.value_decimals IS '覆盖默认小数位，NULL 用默认。仅 MONEY 类需要：ARPPU 取 1 位，其余 0 位';
COMMENT ON COLUMN dashboard_metric_card.calc_type     IS 'NONE 原始值 / DIVIDE 相除 / SUBTRACT 相减';
COMMENT ON COLUMN dashboard_metric_card.agg_type      IS '跨时间片聚合方式，选错会让长区间数字出错';
COMMENT ON COLUMN dashboard_metric_card.show_as_card  IS '是否作为卡片展示；仅供派生引用的原子量填 FALSE';
COMMENT ON COLUMN dashboard_metric_card.alert_min     IS '正常区间下界，值低于它告警；留空表示下方无限制。比率按百分数存，95 表示 95%';
COMMENT ON COLUMN dashboard_metric_card.alert_max     IS '正常区间上界，值高于它告警；留空表示上方无限制';
COMMENT ON COLUMN dashboard_metric_card.tolerance_pct IS '环比变化率容忍度，单位 %；超过即 WARN。与 alert_min/alert_max 互斥';
COMMENT ON COLUMN dashboard_metric_card.alert_level   IS '区间型告警的级别；变化率型由 tolerance_crit_multiple 自动分级';

-- ============================================================================
-- 初始数据：原型全部 21 个指标卡 + 7 个仅供引用的原子量
-- ============================================================================

INSERT INTO dashboard_metric_card
(metric_code, metric_name, metric_name_en, group_code, source_table, source_field,
 chart_type, value_format, value_decimals, show_as_card, sort_no,
 calc_type, left_code, right_code, agg_type, weight_field,
 alert_enabled, alert_min, alert_max, tolerance_pct, alert_level, create_by, remark)
VALUES
-- ===== eg1 获客与转化 =====
('reg','注册人数','Signups','eg1','dws_user_reg_hourly','reg_cnt',
 'LINE','INT',NULL,TRUE,10,'NONE',NULL,NULL,'SUM',NULL,
 TRUE,NULL,NULL,15,'WARN','admin',NULL),
('ftd','首存人数','First depositors','eg1','dws_user_ftd_hourly','ftd_cnt',
 'LINE','INT',NULL,TRUE,20,'NONE',NULL,NULL,'SUM',NULL,
 TRUE,NULL,NULL,15,'WARN','admin',NULL),
('ftdr','首存转化率','First-deposit conversion','eg1',NULL,NULL,
 'LINE','PCT',NULL,TRUE,30,'DIVIDE','ftd','reg','FORMULA',NULL,
 TRUE,NULL,NULL,10,'WARN','admin','首存人数 / 注册人数'),
('ftdA','首存ARPPU','First-deposit ARPPU','eg1',NULL,NULL,
 'LINE','MONEY',1,TRUE,40,'DIVIDE','ftdAmt','ftd','FORMULA',NULL,
 TRUE,NULL,NULL,10,'WARN','admin','首存总金额 / 首存人数'),
-- ===== eg2 资金流 =====
('dep','存款总额','Deposit amount','eg2','dws_pay_deposit_hourly','dep_amt',
 'LINE','MONEY',NULL,TRUE,50,'NONE',NULL,NULL,'SUM',NULL,
 TRUE,NULL,NULL,10,'WARN','admin','二期改用同时段历史 P5-P95 基线'),
('wd','提款总额','Withdrawal amount','eg2','dws_pay_withdraw_hourly','wd_amt',
 'LINE','MONEY',NULL,TRUE,60,'NONE',NULL,NULL,'SUM',NULL,
 TRUE,NULL,NULL,25,'WARN','admin',NULL),
('net','存提差','Net deposits','eg2',NULL,NULL,
 'BAR','MONEY',NULL,TRUE,70,'SUBTRACT','dep','wd','FORMULA',NULL,
 TRUE,NULL,NULL,35,'WARN','admin','存款总额 - 提款总额'),
('arppu','ARPPU','ARPPU','eg2',NULL,NULL,
 'LINE','MONEY',1,TRUE,80,'DIVIDE','dep','depU','FORMULA',NULL,
 TRUE,NULL,NULL,8,'WARN','admin','存款总额 / 存款人数；分母按整个区间去重'),
('turnX','流水倍数','Turnover multiple','eg2',NULL,NULL,
 'LINE','MULTIPLE',NULL,TRUE,90,'DIVIDE','bet','dep','FORMULA',NULL,
 TRUE,9.5,12.5,NULL,'WARN','admin','正常区间 9.5 - 12.5'),
-- ===== eg3 通道健康度 =====
('dOkR','存款成功率','Deposit success rate','eg3',NULL,NULL,
 'LINE','PCT',NULL,TRUE,100,'DIVIDE','dOk','dTry','FORMULA',NULL,
 TRUE,95,NULL,NULL,'CRIT','admin','低于 95% 告警（单边下界）'),
('dT','平均到帐时间','Avg settlement time','eg3','ods_pay_order','settle_minutes',
 'LINE','MIN',NULL,TRUE,110,'NONE',NULL,NULL,'AVG_WEIGHTED','dep_ok_cnt',
 TRUE,NULL,5,NULL,'CRIT','admin','按笔数加权；超过 5 分钟告警（单边上界）'),
('wOkR','出款成功率','Withdrawal success rate','eg3',NULL,NULL,
 'LINE','PCT',NULL,TRUE,120,'DIVIDE','wOk','wTry','FORMULA',NULL,
 TRUE,97,NULL,NULL,'CRIT','admin','低于 97% 告警（单边下界）'),
('wT','平均出款时间','Avg payout time','eg3','ods_pay_order','payout_minutes',
 'LINE','MIN',NULL,TRUE,130,'NONE',NULL,NULL,'AVG_WEIGHTED','wd_ok_cnt',
 TRUE,NULL,20,NULL,'CRIT','admin','按笔数加权；超过 20 分钟告警（单边上界）'),
-- ===== eg4 投注与盈收 =====
('active','活跃人数','Active members','eg4','dws_user_active_hourly','active_uv',
 'LINE','INT',NULL,TRUE,140,'NONE',NULL,NULL,'DISTINCT',NULL,
 TRUE,NULL,NULL,12,'WARN','admin','去重人数，跨时间片不可相加'),
('bet','投注总额','Turnover','eg4','dws_bet_hourly','bet_amt',
 'BAR','MONEY',NULL,TRUE,150,'NONE',NULL,NULL,'SUM',NULL,
 TRUE,NULL,NULL,12,'WARN','admin',NULL),
('ggr','GGR','GGR','eg4',NULL,NULL,
 'BAR','MONEY',NULL,TRUE,160,'SUBTRACT','bet','pay','FORMULA',NULL,
 TRUE,NULL,NULL,20,'WARN','admin','投注总额 - 派彩金额'),
('killR','平均杀率','Average hold rate','eg4',NULL,NULL,
 'LINE','PCT',NULL,TRUE,170,'DIVIDE','ggr','bet','FORMULA',NULL,
 TRUE,3,6,NULL,'WARN','admin','正常区间 3% - 6%'),
('bonus','发放赠金总额','Bonuses granted','eg4','dws_bonus_hourly','bonus_amt',
 'PIE','MONEY',NULL,TRUE,180,'NONE',NULL,NULL,'SUM',NULL,
 TRUE,NULL,NULL,25,'WARN','admin','按赠金项目拆分展示'),
('bonusR','赠金比','Bonus ratio','eg4',NULL,NULL,
 'LINE','PCT',NULL,TRUE,190,'DIVIDE','bonus','bet','FORMULA',NULL,
 TRUE,2.5,5,NULL,'WARN','admin','正常区间 2.5% - 5%'),
('ngr','NGR','NGR','eg4',NULL,NULL,
 'BAR','MONEY',NULL,TRUE,200,'SUBTRACT','ggr','bonus','FORMULA',NULL,
 TRUE,NULL,NULL,20,'WARN','admin','GGR - 发放赠金总额'),
('login','登录人数','Logins','eg4','dws_user_login_hourly','login_uv',
 'LINE','INT',NULL,TRUE,210,'NONE',NULL,NULL,'DISTINCT',NULL,
 FALSE,NULL,NULL,NULL,'WARN','admin','不参与告警扫描；不上指标墙，仅保留在指标汇总列选择器'),
-- ===== 仅供派生指标引用，不做卡片 =====
('depU','存款人数',NULL,'eg2','dws_pay_deposit_hourly','dep_uv',
 'NUMBER','INT',NULL,FALSE,900,'NONE',NULL,NULL,'DISTINCT',NULL,
 FALSE,NULL,NULL,NULL,'WARN','admin','ARPPU 的分母'),
('ftdAmt','首存总金额',NULL,'eg1','dws_user_ftd_hourly','ftd_amt',
 'NUMBER','MONEY',NULL,FALSE,901,'NONE',NULL,NULL,'SUM',NULL,
 FALSE,NULL,NULL,NULL,'WARN','admin','首存ARPPU 的分子'),
('pay','派彩金额',NULL,'eg4','dws_bet_hourly','payout_amt',
 'NUMBER','MONEY',NULL,FALSE,902,'NONE',NULL,NULL,'SUM',NULL,
 FALSE,NULL,NULL,NULL,'WARN','admin','GGR 的减数'),
('dOk','存款成功笔数',NULL,'eg3','ods_pay_order','dep_ok_cnt',
 'NUMBER','INT',NULL,FALSE,903,'NONE',NULL,NULL,'SUM',NULL,
 FALSE,NULL,NULL,NULL,'WARN','admin','存款成功率的分子'),
('dTry','存款尝试笔数',NULL,'eg3','ods_pay_order','dep_try_cnt',
 'NUMBER','INT',NULL,FALSE,904,'NONE',NULL,NULL,'SUM',NULL,
 FALSE,NULL,NULL,NULL,'WARN','admin','存款成功率的分母'),
('wOk','出款成功笔数',NULL,'eg3','ods_pay_order','wd_ok_cnt',
 'NUMBER','INT',NULL,FALSE,905,'NONE',NULL,NULL,'SUM',NULL,
 FALSE,NULL,NULL,NULL,'WARN','admin','出款成功率的分子'),
('wTry','出款尝试笔数',NULL,'eg3','ods_pay_order','wd_try_cnt',
 'NUMBER','INT',NULL,FALSE,906,'NONE',NULL,NULL,'SUM',NULL,
 FALSE,NULL,NULL,NULL,'WARN','admin','出款成功率的分母');
