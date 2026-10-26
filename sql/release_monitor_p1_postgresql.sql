-- ============================================================================
-- 监控管理 · 一期 · 生产上线 SQL 汇总（dashboard_db）· 2026-10-23
--
-- 由以下 10 个脚本按执行顺序原样拼接（内容未改动），每段前标出来源文件：
--   ① alter_sys_role_site                 sys_role.site_code（新 JAR 启动前必须有）
--   ② alter_metric_card_monitor           指标监控属性；删除 dashboard_metric_card 4 个旧告警字段（需审批、先备份）
--   ③ monitor_schema                      7 张 monitor 表 + SITE-IN 站点设置
--   ④ alter_monitor_engine                判定引擎列
--   ⑤ alter_monitor_site_package_network  站点所属包网
--   ⑥ alter_monitor_event_mute            通知状态 muted
--   ⑦ alter_monitor_event_notify_paused   通知状态 paused（必须在 ⑥ 之后）
--   ⑧ add_monitor_notify_template         通知模板表 + 菜单「通知模板」（模板正文由后端启动时写入）
--   ⑨ add_monitor_menu                    监控管理菜单 160–167
--   ⑩ add_system_site_menu                菜单「站点管理」
--
-- 执行方式（用 psql，不要用 DBeaver 等图形客户端整段执行）：
--   psql -X -v ON_ERROR_STOP=1 -d dashboard_db -f release_monitor_p1_postgresql.sql
--   * 各段自带 BEGIN/COMMIT，不要加 --single-transaction；
--   * 全部可重复执行：中途出错，修正后从头再执行一遍即可（已完成的段不会重复生效）；
--   * 执行前先备份（见 doc/monitor/监控管理-一期-生产上线步骤.md 2.2）。
--
-- 回滚 JAR 前需单独执行 rollback_metric_card_monitor_postgresql.sql（不在本文件里）。
-- 测试数据 doc/monitor/monitor_batch1_test_data.sql 不要在生产执行。
-- ============================================================================

-- ---------- 0. 前置检查：不满足直接报错退出，什么都不改 ----------
DO $$
DECLARE bad TEXT;
BEGIN
    IF to_regclass('sys_menu_i18n') IS NULL THEN
        RAISE EXCEPTION '缺少表 sys_menu_i18n（菜单多语言），请先确认基础库版本';
    END IF;
    IF to_regclass('dashboard_metric_card') IS NULL THEN
        RAISE EXCEPTION '缺少表 dashboard_metric_card，确认连的是 dashboard_db';
    END IF;
    SELECT string_agg(menu_id || ':' || menu_name || '(' || COALESCE(NULLIF(perms, ''), route_name, '') || ')', ', ')
      INTO bad
      FROM sys_menu
     WHERE menu_id BETWEEN 160 AND 167
       AND NOT (COALESCE(perms, '') LIKE 'monitor:event:%' OR COALESCE(perms, '') LIKE 'monitor:rule:%'
                OR (menu_id = 160 AND COALESCE(route_name, '') = 'monitor'));
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION 'menu_id 160–167 已被其他菜单占用：%。先处理冲突再执行', bad;
    END IF;
    -- 已有 route_name = monitor 的目录但不是 160：脚本会跳过建目录，按钮却挂到 160 下
    SELECT string_agg(menu_id || ':' || menu_name, ', ') INTO bad
      FROM sys_menu WHERE COALESCE(route_name, '') = 'monitor' AND menu_id <> 160;
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION '已有 route_name = monitor 的菜单：%（不是 160），先处理再执行', bad;
    END IF;
END $$;



-- ############################################################################
-- ## 1. alter_sys_role_site_postgresql.sql
-- ############################################################################

-- 多站点一期：角色带站点。
--   site_code 为空 = 通用角色，在所有站点生效（存量角色全部保持通用，行为不变）；
--   site_code 有值 = 站点角色，只在该站点生效。
-- 用户在某站点的菜单（getRouters）与权限（getInfo / 接口校验）= 该站点角色 + 通用角色。
-- 前端切换站点时在请求头带 X-Site-Code，重新请求 getInfo / getRouters。
-- 站点编码需与 application.yml 的 dashboard.default-site / dashboard.sites[].code 一致。可重复执行。

ALTER TABLE sys_role ADD COLUMN IF NOT EXISTS site_code VARCHAR(32) DEFAULT NULL;
COMMENT ON COLUMN sys_role.site_code IS '所属站点：为空=通用角色（所有站点生效）；有值=只在该站点生效';
CREATE INDEX IF NOT EXISTS idx_sys_role_site_code ON sys_role (site_code);

-- 示例（按需执行）：给 uWin（默认站点 SITE-IN）和 jito（JITO）各建一个 dashboard 角色，再到「角色管理」里勾菜单、到「用户管理」里分配给用户
-- INSERT INTO sys_role (role_name, role_key, role_sort, data_scope, status, site_code, create_by, create_time)
-- VALUES ('uwin:dashboard', 'uwin_dashboard', 10, '1', '0', 'SITE-IN', 'admin', CURRENT_TIMESTAMP),
--        ('jito:dashboard', 'jito_dashboard', 11, '1', '0', 'JITO',    'admin', CURRENT_TIMESTAMP);

SELECT role_id, role_name, role_key, status, site_code FROM sys_role WHERE del_flag = '0' ORDER BY role_sort, role_id;



-- ############################################################################
-- ## 2. alter_metric_card_monitor_postgresql.sql
-- ############################################################################

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



-- ############################################################################
-- ## 3. monitor_schema_postgresql.sql
-- ############################################################################

-- ============================================================================
-- 监控管理（告警规则 / 异常事件 / 通知）建表脚本 · PostgreSQL（dashboard_db）· 7 张表
--
-- 设计文档：「监控管理 · 后端设计方案」→ 数据库设计
--
-- 约定：
--   * 除 monitor_notify_log 外都带 site_code，所有查询必须带站点条件
--     （Mapper 统一引用 monitorScopeFilter 片段）。
--   * 分期：本脚本全部属于一期（多站点 + 监控管理 + 运营总览监控联动）。二期的部分标 TODO（二期完成）。
--   * 2026-10-20 按原型精简：不建 monitor_lark_bot（Lark 凭证用环境变量）、monitor_site_admin
--     （不再区分站点管理员）、monitor_event_handle_log（不保留处理修改记录）；通知群改为
--     monitor_lark_chat（群列表缓存），规则直接存 chat_id。
--   * 如果之前执行过旧版本（10 张表，且还没有业务数据），先执行文件末尾注释里的「清理旧版本」再执行本脚本。
--   * 时间字段一律存 IST 墙钟时间（timestamp without time zone），与总览口径一致。
--   * 数据库是唯一的真实来源；Redis（monitor:state:{site} 等）只是缓存，可随时从这里重建。
--   * 一期不设事件保留期限，也不做清理。TODO（二期完成）事件归档：按 trigger_hour 按月分区，旧分区移到归档表。
--   * TODO（二期完成）系统管理 · 通知机器人：按站点配置多个 Lark 机器人时再建 monitor_lark_bot，一期凭证用环境变量。
--
-- 依赖：sql/alter_metric_card_monitor_postgresql.sql 已执行（规则从指标表复制 judge_type 等）。
-- 可重复执行（IF NOT EXISTS）。
-- ============================================================================

BEGIN;

-- ---------- 1. 站点设置 ----------
CREATE TABLE IF NOT EXISTS monitor_site_setting (
    site_code       VARCHAR(32)     PRIMARY KEY,
    next_rule_no    INTEGER         NOT NULL DEFAULT 1,
    package_network VARCHAR(64),
    update_by       VARCHAR(64)     DEFAULT '',
    update_time     TIMESTAMP,
    CONSTRAINT ck_monitor_site_next_rule_no CHECK (next_rule_no >= 1)
);
COMMENT ON TABLE  monitor_site_setting IS '监控站点设置';
COMMENT ON COLUMN monitor_site_setting.next_rule_no IS '本站下一个规则号；新建规则时 FOR UPDATE 取号，删除后不复用';
COMMENT ON COLUMN monitor_site_setting.package_network IS '所属包网：一个包网可对应多个站点，一个站点只属于一个包网；同名即同一包网，为空表示未设置。系统管理 · 站点管理维护';

-- ---------- 2. Lark 群列表缓存 ----------
-- 每次实时拉取机器人所在的群（GET /monitor/lark/chats）后刷新：拉到的置 ok 并更新群名，没拉到的置 dead。
-- 拉取失败时返回这里的上次结果。规则直接存 chat_id，不再有「绑定通知对象」。
CREATE TABLE IF NOT EXISTS monitor_lark_chat (
    site_code       VARCHAR(32)     NOT NULL,
    chat_id         VARCHAR(64)     NOT NULL,
    name            VARCHAR(100)    NOT NULL,
    status          VARCHAR(8)      NOT NULL DEFAULT 'ok',
    last_seen_at    TIMESTAMP,
    update_time     TIMESTAMP,
    PRIMARY KEY (site_code, chat_id),
    CONSTRAINT ck_monitor_chat_status CHECK (status IN ('ok', 'dead'))
);
COMMENT ON TABLE  monitor_lark_chat IS 'Lark 群列表缓存：机器人所在的群；dead = 最近一次成功拉取里没有，或发送时机器人已不在群里';
COMMENT ON COLUMN monitor_lark_chat.chat_id      IS 'Lark 群 chat_id（oc_ 开头），机器人移出再加回、改名都不变';
COMMENT ON COLUMN monitor_lark_chat.last_seen_at IS '最近一次拉取中出现的时间';

-- ---------- 3. 告警规则 ----------
CREATE TABLE IF NOT EXISTS monitor_rule (
    rule_id             BIGSERIAL       PRIMARY KEY,
    site_code           VARCHAR(32)     NOT NULL,
    rule_no             INTEGER         NOT NULL,
    metric_code         VARCHAR(64)     NOT NULL,
    owner_user_id       BIGINT          NOT NULL,
    judge_type          VARCHAR(8)      NOT NULL,
    warn_low            NUMERIC(18,4),
    warn_high           NUMERIC(18,4),
    crit_low            NUMERIC(18,4),
    crit_high           NUMERIC(18,4),
    period_hours        SMALLINT        NOT NULL DEFAULT 1,
    duration_count      SMALLINT        NOT NULL DEFAULT 1,
    escalate_hours      SMALLINT,
    mute_hours          SMALLINT        NOT NULL DEFAULT 1,
    min_sample_key      VARCHAR(64)     NOT NULL,
    min_sample_value    NUMERIC(18,4)   NOT NULL DEFAULT 0,
    notify_chat_id      VARCHAR(64),
    notify_chat_name    VARCHAR(100),
    enabled             BOOLEAN         NOT NULL DEFAULT TRUE,
    paused_at           TIMESTAMP,
    last_notified_at    TIMESTAMP,
    last_judged_at      TIMESTAMP,
    judge_error         VARCHAR(255),
    del_flag            CHAR(1)         NOT NULL DEFAULT '0',
    create_by           VARCHAR(64)     DEFAULT '',
    create_time         TIMESTAMP,
    update_by           VARCHAR(64)     DEFAULT '',
    update_time         TIMESTAMP,
    CONSTRAINT ck_monitor_rule_judge  CHECK (judge_type IN ('lo', 'hi', 'band', 'drop')),
    CONSTRAINT ck_monitor_rule_period CHECK (period_hours IN (1, 4, 6, 12, 24)),
    CONSTRAINT ck_monitor_rule_dur    CHECK (duration_count BETWEEN 1 AND 4),
    CONSTRAINT ck_monitor_rule_del    CHECK (del_flag IN ('0', '2')),
    -- 严重线必填：lo / drop 用 low，hi 用 high，band 两个都要
    CONSTRAINT ck_monitor_rule_crit CHECK (
           (judge_type IN ('lo', 'drop') AND crit_low  IS NOT NULL)
        OR (judge_type = 'hi'            AND crit_high IS NOT NULL)
        OR (judge_type = 'band'          AND crit_low  IS NOT NULL AND crit_high IS NOT NULL)
    )
);
-- 一个指标只能有一条（未删除的）规则；并发新建由它兜底，服务层转成 4801
CREATE UNIQUE INDEX IF NOT EXISTS uk_monitor_rule_metric
    ON monitor_rule (site_code, metric_code) WHERE del_flag = '0';
CREATE UNIQUE INDEX IF NOT EXISTS uk_monitor_rule_no ON monitor_rule (site_code, rule_no);
CREATE INDEX IF NOT EXISTS idx_monitor_rule_owner ON monitor_rule (site_code, owner_user_id);
CREATE INDEX IF NOT EXISTS idx_monitor_rule_chat  ON monitor_rule (site_code, notify_chat_id);
COMMENT ON TABLE  monitor_rule IS '告警规则；一个站点一个指标最多一条未删除规则。站点内有页面权限的人都能看、能改';
COMMENT ON COLUMN monitor_rule.rule_no          IS '显示编号，前端格式化为 R-001；按站点自增，删除后不复用';
COMMENT ON COLUMN monitor_rule.owner_user_id    IS '负责人，必填；只用于展示和「我负责的」筛选，不限制可见和修改';
COMMENT ON COLUMN monitor_rule.judge_type       IS '建规则时从 dashboard_metric_card 复制的快照：lo / hi / band / drop';
COMMENT ON COLUMN monitor_rule.min_sample_key   IS '建规则时从 dashboard_metric_card 复制：base = 跌幅基准，其余为分母指标 metric_code';
COMMENT ON COLUMN monitor_rule.min_sample_value IS '最小样本量，0 = 不限';
COMMENT ON COLUMN monitor_rule.escalate_hours   IS '注意持续多少小时升级为严重；NULL = 不升级';
COMMENT ON COLUMN monitor_rule.mute_hours       IS '静默期：同一规则两次发送通知的最短间隔，只影响通知';
COMMENT ON COLUMN monitor_rule.notify_chat_id   IS '通知的 Lark 群 chat_id；为空时严重告警只记录不发送';
COMMENT ON COLUMN monitor_rule.notify_chat_name IS '保存规则时的群名；群失效、拉取失败时用它显示';
COMMENT ON COLUMN monitor_rule.last_judged_at   IS '最近一次判定时间（规则列表「最近判定」）';
COMMENT ON COLUMN monitor_rule.judge_error      IS '最近一次判定失败的原因，成功后清空（规则列表「判定失败」）';
COMMENT ON COLUMN monitor_rule.paused_at        IS '最近一次暂停时间';
COMMENT ON COLUMN monitor_rule.del_flag         IS '0 正常 / 2 已删除（逻辑删除，历史事件保留）';

-- ---------- 4. 规则运行状态（跨小时的连续计数） ----------
CREATE TABLE IF NOT EXISTS monitor_rule_state (
    rule_id         BIGINT          PRIMARY KEY,
    site_code       VARCHAR(32)     NOT NULL,
    run_start_hour  TIMESTAMP,
    recent_levels   JSONB           NOT NULL DEFAULT '[]'::jsonb,
    recent_periods  JSONB           NOT NULL DEFAULT '[]'::jsonb,
    open_event_id   BIGINT,
    last_period_end TIMESTAMP,
    last_data_hour  TIMESTAMP,
    update_time     TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_monitor_rule_state_site ON monitor_rule_state (site_code);
COMMENT ON TABLE  monitor_rule_state IS '规则运行状态；与事件、小时检查在同一事务里写，Redis 读不到时据此重建';
COMMENT ON COLUMN monitor_rule_state.recent_periods IS '判定窗口内各周期的值、基准和等级，重建缓存时不再查 UDS';
COMMENT ON COLUMN monitor_rule_state.last_data_hour IS '已处理到的数据小时（判定或记缺失后更新）；新建 / 启用 / 改周期时置为当前小时的上一小时，只判定之后结束的小时';

-- ---------- 5. 异常事件 ----------
CREATE TABLE IF NOT EXISTS monitor_event (
    event_id        BIGSERIAL       PRIMARY KEY,
    site_code       VARCHAR(32)     NOT NULL,
    rule_id         BIGINT          NOT NULL,
    metric_code     VARCHAR(64)     NOT NULL,
    event_type      VARCHAR(16)     NOT NULL DEFAULT 'alert',
    level           VARCHAR(8),
    peak_level      VARCHAR(8),
    escalated       BOOLEAN         NOT NULL DEFAULT FALSE,
    status          VARCHAR(10)     NOT NULL DEFAULT 'open',
    start_hour      TIMESTAMP       NOT NULL,
    trigger_hour    TIMESTAMP       NOT NULL,
    end_hour        TIMESTAMP,
    last_period_end TIMESTAMP,
    current_value   NUMERIC(18,4),
    baseline_value  NUMERIC(18,4),
    rule_snapshot   JSONB           NOT NULL DEFAULT '{}'::jsonb,
    notify_status   VARCHAR(10)     NOT NULL DEFAULT 'none',
    notified_at     TIMESTAMP,
    notify_count    INTEGER         NOT NULL DEFAULT 0,
    mute_sent_at    TIMESTAMP,
    mute_hours      SMALLINT,
    handle_status   VARCHAR(10)     NOT NULL DEFAULT 'pending',
    handled_level   VARCHAR(8),
    handle_note     VARCHAR(500),
    handled_by      BIGINT,
    handled_at      TIMESTAMP,
    handle_reset    BOOLEAN         NOT NULL DEFAULT FALSE,
    stopped_at      TIMESTAMP,
    create_time     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP,
    -- 2026-10-22 起数据缺失不再写事件记录，event_type 只有 alert（列保留，查询都带 event_type = 'alert'）
    CONSTRAINT ck_monitor_event_type   CHECK (event_type = 'alert'),
    -- IS NOT NULL 不能省：CHECK 遇到 NULL 结果按通过处理
    CONSTRAINT ck_monitor_event_level  CHECK (
            level IS NOT NULL AND peak_level IS NOT NULL
        AND level IN ('WARN', 'CRIT') AND peak_level IN ('WARN', 'CRIT')),
    CONSTRAINT ck_monitor_event_status CHECK (status IN ('open', 'resolved', 'stopped')),
    CONSTRAINT ck_monitor_event_notify CHECK (notify_status IN ('none', 'pending', 'sent', 'failed', 'unset', 'muted', 'paused')),
    CONSTRAINT ck_monitor_event_handle CHECK (handle_status IN ('pending', 'done')),
    CONSTRAINT ck_monitor_event_handled_level CHECK (handled_level IS NULL OR handled_level IN ('WARN', 'CRIT'))
);
-- 一条规则同时最多一条未恢复事件
CREATE UNIQUE INDEX IF NOT EXISTS uk_monitor_event_open ON monitor_event (rule_id) WHERE status = 'open';
CREATE INDEX IF NOT EXISTS idx_monitor_event_status  ON monitor_event (site_code, status);
CREATE INDEX IF NOT EXISTS idx_monitor_event_handle  ON monitor_event (site_code, handle_status);
CREATE INDEX IF NOT EXISTS idx_monitor_event_trigger ON monitor_event (site_code, trigger_hour DESC);
CREATE INDEX IF NOT EXISTS idx_monitor_event_rule    ON monitor_event (rule_id);
-- 列表、待处理数、总览都只查 alert
CREATE INDEX IF NOT EXISTS idx_monitor_event_type ON monitor_event (site_code, event_type, trigger_hour DESC);
-- TODO（二期完成）事件归档（一期不设保留期限、不清理）
COMMENT ON TABLE  monitor_event IS '异常事件；规则删除后事件保留（不加外键级联）。一期不设保留期限';
COMMENT ON COLUMN monitor_event.event_type    IS '固定 alert（告警事件）；数据缺失不触发告警、不写事件记录（2026-10-22 与产品确认）';
COMMENT ON COLUMN monitor_event.level         IS '当前等级 WARN / CRIT，按每个周期的判定结果可升可降';
COMMENT ON COLUMN monitor_event.peak_level    IS '事件期间出现过的最高等级，仅用于统计';
COMMENT ON COLUMN monitor_event.start_hour    IS '最初越线的小时';
COMMENT ON COLUMN monitor_event.trigger_hour  IS '第 N 个越线周期（列表的「触发时间」）';
COMMENT ON COLUMN monitor_event.rule_snapshot IS '触发时的阈值、周期、持续次数；之后改规则不影响';
COMMENT ON COLUMN monitor_event.notify_status IS 'none 注意只记录 / pending / sent / failed / unset 未配置（没选群或本站没有 Lark 机器人） / muted 静默期内未发送 / paused 机器人凭证失效、暂停发送';
COMMENT ON COLUMN monitor_event.status        IS 'open 异常中 / resolved 已结束 / stopped 规则暂停或删除，已停止判定';
COMMENT ON COLUMN monitor_event.handle_status IS '处理状态，与事件状态独立：pending 未处理 / done 已处理';
COMMENT ON COLUMN monitor_event.handled_level IS '标记已处理时的事件等级；之后由 WARN 升为 CRIT 时自动退回未处理';
COMMENT ON COLUMN monitor_event.handle_note   IS '处理说明，标记时必填；修改直接覆盖，撤销时清空（不保留修改记录）';
COMMENT ON COLUMN monitor_event.handle_reset  IS '标记已处理后由注意升为严重、自动退回未处理；重新标记时清除';
COMMENT ON COLUMN monitor_event.last_period_end IS '最近一次判定周期的结束时间；异常中事件的持续时长 = 它 − start_hour';
COMMENT ON COLUMN monitor_event.notify_count  IS '已成功发送通知的次数';
COMMENT ON COLUMN monitor_event.mute_sent_at  IS 'notify_status = muted 时：压住本次发送的那次发送时间（规则 last_notified_at）';
COMMENT ON COLUMN monitor_event.mute_hours    IS 'notify_status = muted 时：当时规则的静默时长（小时）';
COMMENT ON COLUMN monitor_event.stopped_at    IS '规则暂停或删除、事件改为 stopped 的时间';

-- ---------- 6. 发送记录 ----------
CREATE TABLE IF NOT EXISTS monitor_notify_log (
    id              BIGSERIAL       PRIMARY KEY,
    site_code       VARCHAR(32)     NOT NULL,
    msg_type        VARCHAR(16)     NOT NULL,
    event_id        BIGINT,
    data_hour       TIMESTAMP,
    chat_id         VARCHAR(64),
    platform        VARCHAR(10)     NOT NULL DEFAULT 'lark',     -- 一期只有 lark；TODO（二期完成）whatsapp / telegram
    status          VARCHAR(10)     NOT NULL DEFAULT 'pending',
    content         TEXT,
    retry_count     SMALLINT        NOT NULL DEFAULT 0,
    next_retry_at   TIMESTAMP,
    sent_at         TIMESTAMP,
    success         BOOLEAN,
    error_msg       VARCHAR(500),
    create_time     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_monitor_notify_type   CHECK (msg_type IN ('alert', 'test')),
    CONSTRAINT ck_monitor_notify_status CHECK (status IN ('pending', 'sent', 'failed'))
);
CREATE INDEX IF NOT EXISTS idx_monitor_notify_pending ON monitor_notify_log (status, next_retry_at) WHERE status = 'pending';
CREATE INDEX IF NOT EXISTS idx_monitor_notify_event   ON monitor_notify_log (event_id);
CREATE INDEX IF NOT EXISTS idx_monitor_notify_chat    ON monitor_notify_log (chat_id, sent_at DESC);
COMMENT ON TABLE  monitor_notify_log IS '发送记录：每次发送一行；pending 行与判定结果同一事务写入，提交后由发送任务投递';
COMMENT ON COLUMN monitor_notify_log.msg_type  IS 'alert 告警 / test 测试（test 预留给二期系统管理的机器人测试）；数据缺失不发通知';
-- TODO（二期完成）msg_type = 'subscription'：订阅管理的定时推送是否复用本表，在订阅设计里定
COMMENT ON COLUMN monitor_notify_log.data_hour IS '触发这次通知的数据小时';

-- ---------- 7. 小时检查状态 ----------
CREATE TABLE IF NOT EXISTS monitor_hour_check (
    site_code           VARCHAR(32)     NOT NULL,
    data_hour           TIMESTAMP       NOT NULL,
    status              VARCHAR(8)      NOT NULL DEFAULT 'waiting',
    check_count         SMALLINT        NOT NULL DEFAULT 0,
    last_check_at       TIMESTAMP,
    judged_at           TIMESTAMP,
    missing_alerted_at  TIMESTAMP,
    PRIMARY KEY (site_code, data_hour),
    CONSTRAINT ck_monitor_hour_status CHECK (status IN ('waiting', 'judged', 'missing'))
);
COMMENT ON TABLE  monitor_hour_check IS '每个站点每个数据小时的检查进度：:00–:50 每 10 分钟检查一次，:50 仍无数据记 missing';
COMMENT ON COLUMN monitor_hour_check.data_hour IS '数据小时（IST），如 10:00 表示 10:00–11:00 的数据';

-- ---------- 8. 默认站点 ----------
INSERT INTO monitor_site_setting (site_code, next_rule_no, update_by, update_time)
SELECT 'SITE-IN', 1, 'admin', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM monitor_site_setting WHERE site_code = 'SITE-IN');

COMMIT;

-- 自检：应有 7 张表
SELECT count(*) AS monitor_tables FROM information_schema.tables
WHERE table_schema = current_schema() AND table_name LIKE 'monitor\_%';


-- 然后重新执行本脚本（monitor_site_setting、monitor_hour_check 结构未变，会保留；
-- monitor_site_setting.lark_bot_id 可用 ALTER TABLE monitor_site_setting DROP COLUMN IF EXISTS lark_bot_id 去掉）。

-- 2026-10-22：告警通知模板 monitor_notify_template（及 monitor_notify_log.template_id / template_version）
-- 见 sql/add_monitor_notify_template_postgresql.sql（含全站默认模板与菜单），新环境在本脚本之后执行。



-- ############################################################################
-- ## 4. alter_monitor_engine_postgresql.sql
-- ############################################################################

-- ============================================================================
-- 监控管理 · 判定引擎（一期第二批）增量脚本 · PostgreSQL（dashboard_db）
--
-- 给已经执行过 monitor_schema_postgresql.sql 的环境用；新环境的建表脚本已包含这些列，
-- 再执行本脚本也不会报错（全部 IF NOT EXISTS / DROP ... IF EXISTS，可重复执行）。
--
--   monitor_event
--     + event_type       固定 alert（2026-10-22 起数据缺失不触发告警、不写事件记录、不发通知；
--                        之前写入的 data_missing 记录本脚本会删除，可重复执行）
--     + last_period_end  最近一次判定周期的结束时间（异常中事件的持续时长算到这里）
--     + notify_count     已成功发送通知的次数
--     + handle_reset     标记已处理后又由注意升为严重、自动退回未处理（前端提示「已升級為嚴重，需重新處理」）
--     + stopped_at       规则暂停 / 删除、事件改为 stopped 的时间（持续列悬停「10:40 規則暫停，之後不再判定」）
--   monitor_notify_log
--     msg_type 去掉 data_missing（只剩 alert / test）
--   monitor_rule_state
--     + last_data_hour   这条规则已处理到的数据小时（判定或数据缺失跳过后更新），同一小时不重复处理
--
-- 设计文档：「监控管理 · 后端设计方案」→ 判定引擎 → 实现设计；数据库设计 → monitor_event
-- ============================================================================

BEGIN;

ALTER TABLE monitor_event ADD COLUMN IF NOT EXISTS event_type      VARCHAR(16) NOT NULL DEFAULT 'alert';
ALTER TABLE monitor_event ADD COLUMN IF NOT EXISTS last_period_end TIMESTAMP;
ALTER TABLE monitor_event ADD COLUMN IF NOT EXISTS notify_count    INTEGER     NOT NULL DEFAULT 0;
ALTER TABLE monitor_event ADD COLUMN IF NOT EXISTS handle_reset    BOOLEAN     NOT NULL DEFAULT FALSE;
ALTER TABLE monitor_event ADD COLUMN IF NOT EXISTS stopped_at      TIMESTAMP;

-- 2026-10-22：数据缺失不再写事件记录。删除之前写入的缺失记录（只入库供排查，从未显示、计数或通知）
DELETE FROM monitor_event WHERE event_type = 'data_missing';
DROP INDEX IF EXISTS uk_monitor_event_missing;

ALTER TABLE monitor_event DROP CONSTRAINT IF EXISTS ck_monitor_event_type;
ALTER TABLE monitor_event ADD  CONSTRAINT ck_monitor_event_type CHECK (event_type = 'alert');
-- 注意 IS NOT NULL 不能省：CHECK 遇到 NULL 结果按通过处理
ALTER TABLE monitor_event DROP CONSTRAINT IF EXISTS ck_monitor_event_level;
ALTER TABLE monitor_event ADD  CONSTRAINT ck_monitor_event_level CHECK (
        level IS NOT NULL AND peak_level IS NOT NULL
    AND level IN ('WARN', 'CRIT') AND peak_level IN ('WARN', 'CRIT'));

ALTER TABLE monitor_notify_log DROP CONSTRAINT IF EXISTS ck_monitor_notify_type;
ALTER TABLE monitor_notify_log ADD  CONSTRAINT ck_monitor_notify_type CHECK (msg_type IN ('alert', 'test'));
-- 列表、待处理数、总览都只查 alert
CREATE INDEX IF NOT EXISTS idx_monitor_event_type ON monitor_event (site_code, event_type, trigger_hour DESC);

COMMENT ON COLUMN monitor_event.event_type      IS '固定 alert（告警事件）；数据缺失不触发告警、不写事件记录（2026-10-22 与产品确认）';
COMMENT ON COLUMN monitor_event.last_period_end IS '最近一次判定周期的结束时间；异常中事件的持续时长 = 它 − start_hour';
COMMENT ON COLUMN monitor_event.notify_count    IS '已成功发送通知的次数';
COMMENT ON COLUMN monitor_event.handle_reset    IS '标记已处理后由注意升为严重、自动退回未处理；重新标记时清除';
COMMENT ON COLUMN monitor_event.stopped_at      IS '规则暂停或删除、事件改为 stopped 的时间';
COMMENT ON COLUMN monitor_event.level           IS '当前等级 WARN / CRIT，按每个周期的判定结果可升可降';
COMMENT ON COLUMN monitor_notify_log.msg_type   IS 'alert 告警 / test 测试（test 预留给二期系统管理的机器人测试）；数据缺失不发通知';

ALTER TABLE monitor_rule_state ADD COLUMN IF NOT EXISTS last_data_hour TIMESTAMP;
COMMENT ON COLUMN monitor_rule_state.last_data_hour IS '已处理到的数据小时（判定或数据缺失跳过后更新）；新建 / 启用 / 改周期时置为当前小时的上一小时，只判定之后结束的小时';

COMMIT;

-- 自检：应为 5 列 + 1 列
SELECT count(*) AS monitor_engine_columns FROM information_schema.columns
 WHERE table_schema = current_schema()
   AND ((table_name = 'monitor_event' AND column_name IN ('event_type', 'last_period_end', 'notify_count', 'handle_reset', 'stopped_at'))
     OR (table_name = 'monitor_rule_state' AND column_name = 'last_data_hour'));



-- ############################################################################
-- ## 5. alter_monitor_site_package_network_postgresql.sql
-- ############################################################################

-- 站点设置增加「所属包网」（dashboard_db）。可重复执行。
-- 一个包网可对应多个站点，一个站点只属于一个包网：直接存在站点设置行上，同名即同一包网，为空表示未设置。
-- 在「系统管理 · 站点管理」页面维护；菜单和按钮权限见 sql/add_system_site_menu_postgresql.sql。

ALTER TABLE monitor_site_setting ADD COLUMN IF NOT EXISTS package_network VARCHAR(64);

COMMENT ON COLUMN monitor_site_setting.package_network IS '所属包网：一个包网可对应多个站点，一个站点只属于一个包网；同名即同一包网，为空表示未设置。系统管理 · 站点管理维护';

CREATE INDEX IF NOT EXISTS idx_monitor_site_package_network ON monitor_site_setting (package_network);

SELECT site_code, package_network, next_rule_no, update_by, update_time FROM monitor_site_setting ORDER BY site_code;



-- ############################################################################
-- ## 6. alter_monitor_event_mute_postgresql.sql
-- ############################################################################

-- ============================================================================
-- 监控管理 · 异常事件「静默期内未发送」增量脚本 · PostgreSQL（dashboard_db）。可重复执行。
--
-- 异常事件「通知」列悬停「靜默期內：上次發送 MM-dd HH:mm，靜默 N 小時，本次未發送」需要的信息：
--   monitor_event.notify_status 增加取值 muted（严重、未处理，但距本规则上次发送不足静默期，本次未发送）
--   + mute_sent_at  压住本次发送的那次发送时间（规则的 last_notified_at）
--   + mute_hours    当时规则的静默时长（小时）
-- 之前因静默期没发的事件仍是 none（无法补记），只影响历史数据的悬停说明。
-- ============================================================================

BEGIN;

ALTER TABLE monitor_event ADD COLUMN IF NOT EXISTS mute_sent_at TIMESTAMP;
ALTER TABLE monitor_event ADD COLUMN IF NOT EXISTS mute_hours   SMALLINT;

ALTER TABLE monitor_event DROP CONSTRAINT IF EXISTS ck_monitor_event_notify;
ALTER TABLE monitor_event ADD CONSTRAINT ck_monitor_event_notify
    CHECK (notify_status IN ('none', 'pending', 'sent', 'failed', 'unset', 'muted'));

COMMENT ON COLUMN monitor_event.notify_status IS 'none 注意只记录 / pending / sent / failed / unset 未配置通知对象 / muted 严重但在静默期内、本次未发送';
COMMENT ON COLUMN monitor_event.mute_sent_at  IS 'notify_status = muted 时：压住本次发送的那次发送时间（规则 last_notified_at）';
COMMENT ON COLUMN monitor_event.mute_hours    IS 'notify_status = muted 时：当时规则的静默时长（小时）';

COMMIT;

SELECT column_name, data_type FROM information_schema.columns
 WHERE table_name = 'monitor_event' AND column_name IN ('mute_sent_at', 'mute_hours');



-- ############################################################################
-- ## 7. alter_monitor_event_notify_paused_postgresql.sql
-- ############################################################################

-- ============================================================================
-- 监控管理 · 机器人凭证失效时暂停发送（FY-30 R13②、R14）· PostgreSQL（dashboard_db）。可重复执行。
--
-- monitor_event.notify_status 增加取值 paused：Lark 机器人凭证失效，告警暂停发送，不算发送失败；
-- 异常事件「通知送達」显示「僅記錄」，悬停「Lark 機器人憑證失效，未發送」。恢复后不补发（R15）。
-- 本站没有可用的 Lark 机器人（一期 = 没配 MONITOR_LARK_APP_ID / SECRET）时为 unset（「未配置」，R4）。
-- 需先执行 sql/alter_monitor_event_mute_postgresql.sql（muted）。
-- ============================================================================

BEGIN;

ALTER TABLE monitor_event DROP CONSTRAINT IF EXISTS ck_monitor_event_notify;
ALTER TABLE monitor_event ADD CONSTRAINT ck_monitor_event_notify
    CHECK (notify_status IN ('none', 'pending', 'sent', 'failed', 'unset', 'muted', 'paused'));

COMMENT ON COLUMN monitor_event.notify_status IS 'none 注意只记录 / pending / sent / failed / unset 未配置（没选群或本站没有 Lark 机器人） / muted 静默期内未发送 / paused 机器人凭证失效、暂停发送';

COMMIT;



-- ############################################################################
-- ## 8. add_monitor_notify_template_postgresql.sql
-- ############################################################################

-- ============================================================================
-- 监控管理 · 通知模板（dashboard_db）。可重复执行；执行过旧版本（含 ALERT_REPEAT）也直接执行本版。
--
-- 告警消息改为数据库模板：按「站点 + 通知类型 + 平台」各一份。取模板顺序：站点专用 → 全站默认（site_code 为空）
-- → 代码内置默认模板。格式按 FY-30「四、页面与交互」；同一事件只在两种时机各发一次，不重复提醒（FY-30 R2）：
--   ALERT_NEW        新触发的严重（一触发就严重，或越过严重线由注意变严重）
--   ALERT_ESCALATED  注意持续未恢复，经注意升级升为严重
--
-- 本脚本只建表、加列、建菜单，不写模板正文：全站默认模板由后端启动时自动写入（内容在代码
-- MonitorNotifyTemplate.DEFAULT_CONTENT 里维护，只此一份），所以执行时不会再提示输入参数。
-- 已经存在、且还是改版前预置内容的全站默认模板，后端启动时自动换成 FY-30 格式；有人改过的不动。
-- ============================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS monitor_notify_template (
    template_id     BIGSERIAL       PRIMARY KEY,
    site_code       VARCHAR(32),
    notify_type     VARCHAR(32)     NOT NULL,
    platform        VARCHAR(10)     NOT NULL DEFAULT 'lark',
    msg_format      VARCHAR(10)     NOT NULL DEFAULT 'text',
    content         TEXT            NOT NULL,
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE,
    version         INTEGER         NOT NULL DEFAULT 1,
    remark          VARCHAR(500)    DEFAULT '',
    create_by       VARCHAR(64)     DEFAULT '',
    create_time     TIMESTAMP,
    update_by       VARCHAR(64)     DEFAULT '',
    update_time     TIMESTAMP,
    CONSTRAINT ck_monitor_tpl_platform CHECK (platform IN ('lark')),
    CONSTRAINT ck_monitor_tpl_format   CHECK (msg_format IN ('text'))
);

-- 去掉「重复提醒」类型（FY-30 R2）：先删旧数据，再换约束
DELETE FROM monitor_notify_template WHERE notify_type NOT IN ('ALERT_NEW', 'ALERT_ESCALATED');
ALTER TABLE monitor_notify_template DROP CONSTRAINT IF EXISTS ck_monitor_tpl_type;
ALTER TABLE monitor_notify_template ADD CONSTRAINT ck_monitor_tpl_type CHECK (notify_type IN ('ALERT_NEW', 'ALERT_ESCALATED'));

CREATE UNIQUE INDEX IF NOT EXISTS uk_monitor_tpl
    ON monitor_notify_template (coalesce(site_code, '*'), notify_type, platform);

COMMENT ON TABLE  monitor_notify_template IS '告警通知模板：站点专用 → 全站默认（site_code 为空）→ 代码内置默认模板';
COMMENT ON COLUMN monitor_notify_template.site_code   IS '站点编码；为空 = 全站默认（不能删除、不能停用）';
COMMENT ON COLUMN monitor_notify_template.notify_type IS 'ALERT_NEW 新触发的严重 / ALERT_ESCALATED 注意升级为严重；同一事件同一类型只发一次';
COMMENT ON COLUMN monitor_notify_template.msg_format  IS '一期只有 text：纯文本，网址或「[文字](网址)」发送时转成可点击的链接（Lark 富文本）';
COMMENT ON COLUMN monitor_notify_template.content     IS '正文，用双花括号占位符；可选段落值为空时整段（含所在行）不输出';
COMMENT ON COLUMN monitor_notify_template.version     IS '每次保存 +1；发送记录记下用的版本';

ALTER TABLE monitor_notify_log ADD COLUMN IF NOT EXISTS template_id      BIGINT;
ALTER TABLE monitor_notify_log ADD COLUMN IF NOT EXISTS template_version INTEGER;
ALTER TABLE monitor_notify_log ADD COLUMN IF NOT EXISTS notify_type      VARCHAR(32);
COMMENT ON COLUMN monitor_notify_log.template_id      IS '生成这条消息用的模板；为空 = 代码内置默认模板';
COMMENT ON COLUMN monitor_notify_log.template_version IS '生成这条消息时模板的版本';
COMMENT ON COLUMN monitor_notify_log.notify_type      IS 'ALERT_NEW / ALERT_ESCALATED；同一事件同一类型只发一次（不论成败）';
COMMENT ON COLUMN monitor_notify_log.notify_type      IS 'ALERT_NEW / ALERT_ESCALATED；同一事件同一类型只发一次（不论成败）';
CREATE INDEX IF NOT EXISTS idx_monitor_notify_event_type ON monitor_notify_log (event_id, notify_type);

-- 旧的发送记录补上类型，免得改版后同一事件又发一次「新触发」
UPDATE monitor_notify_log SET notify_type = 'ALERT_NEW' WHERE notify_type IS NULL AND msg_type = 'alert';

-- ---------- 菜单：系统管理 · 通知模板 ----------
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '通知模板', 1, 11, 'notifyTemplate', 'system/notifyTemplate/index', '', '',
       1, 0, 'C', '0', '0', 'system:notifyTpl:list', 'message', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '告警消息模板（按站点、通知类型）'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:notifyTpl:list');

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '修改通知模板', p.menu_id, 1, '', '', '', '',
       1, 0, 'F', '0', '0', 'system:notifyTpl:edit', '#', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '新增、修改、删除、测试发送通知模板'
FROM sys_menu p
WHERE p.perms = 'system:notifyTpl:list'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:notifyTpl:edit');

INSERT INTO sys_menu_i18n (menu_id, locale, menu_name, remark)
SELECT m.menu_id, v.locale, v.menu_name, v.remark
FROM sys_menu m
JOIN (VALUES
    ('system:notifyTpl:list', 'zh-CN', '通知模板', '告警消息模板（按站点、通知类型）'),
    ('system:notifyTpl:list', 'zh-TW', '通知模板', '告警訊息模板（按站點、通知類型）'),
    ('system:notifyTpl:list', 'en-US', 'Notification Templates', 'Alert message templates by site and type'),
    ('system:notifyTpl:edit', 'zh-CN', '修改通知模板', '新增、修改、删除、测试发送通知模板'),
    ('system:notifyTpl:edit', 'zh-TW', '修改通知模板', '新增、修改、刪除、測試發送通知模板'),
    ('system:notifyTpl:edit', 'en-US', 'Edit Notification Templates', 'Create, edit, delete and test notification templates')
) AS v(perms, locale, menu_name, remark) ON v.perms = m.perms
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu_i18n i WHERE i.menu_id = m.menu_id AND i.locale = v.locale
);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id
FROM sys_menu m
WHERE m.perms IN ('system:notifyTpl:list', 'system:notifyTpl:edit')
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.role_id = 1)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id);

COMMIT;

-- 执行后：重启后端，再查这里应有两条全站默认模板（ALERT_NEW、ALERT_ESCALATED，update_by = system）
SELECT template_id, coalesce(site_code, '(全站默认)') AS site, notify_type, enabled, version, update_by, update_time
  FROM monitor_notify_template ORDER BY 2, 3;
SELECT menu_id, menu_name, menu_type, perms FROM sys_menu WHERE perms LIKE 'system:notifyTpl:%' ORDER BY menu_id;



-- ############################################################################
-- ## 9. add_monitor_menu_postgresql.sql
-- ############################################################################

-- ============================================================================
-- 监控管理：菜单与按钮权限
--
-- 160        目录「监控管理」（顶级路由 route_name = monitor，Dashboard 前端按 name 映射页面）
-- 161–162    异常事件：monitor:event:list / edit
-- 163–167    告警规则：monitor:rule:list / query / add / edit / remove
--
-- 2026-10-20 按原型精简：通知群不再需要绑定（去掉 monitor:group:*），系统管理一期不做（去掉 monitor:admin:edit）。
-- TODO（二期完成）订阅管理的菜单与权限（订阅管理属于二期）。
--
-- 一期不做功能级权限：拿到「监控管理」页面的角色应把 161–167 一起勾上；
-- 数据范围只按站点（后端 MonitorScope），有页面权限的人看得到本站全部规则和事件。
--
-- 本脚本不自动授权给任何角色（超级管理员天然拥有全部权限），请在后台「角色管理」中勾选。
-- menu_id 取 160–167，避开已占用的 100–145（168–172 曾用于旧版本，见文件末尾清理）。可重复执行。
-- ============================================================================

BEGIN;

-- ---------- 1. 目录 ----------
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT 160, '监控管理', 0, 8, 'monitor', NULL, '', 'monitor',
       1, 0, 'M', '0', '0', '', 'monitor', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '监控管理（异常事件 / 告警规则 / 通知设置）'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 160 OR route_name = 'monitor');

-- ---------- 2. 按钮权限 ----------
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT v.menu_id, v.menu_name, 160, v.order_num, '', NULL, '', '',
       1, 0, 'F', '0', '0', v.perms, '#', 'admin',
       CURRENT_TIMESTAMP, '', NULL, v.remark
FROM (VALUES
    (161, '事件查询',       1,  'monitor:event:list',   'GET /monitor/events'),
    (162, '事件处理',       2,  'monitor:event:edit',   'POST /monitor/events/handle、/monitor/events/{id}/unhandle'),
    (163, '规则查询',       3,  'monitor:rule:list',    'GET /monitor/rules'),
    (164, '规则详情',       4,  'monitor:rule:query',   'GET /monitor/rules/{id}'),
    (165, '规则新增',       5,  'monitor:rule:add',     'POST /monitor/rules；GET /monitor/rules/metrics、/monitor/owners、/monitor/lark/chats'),
    (166, '规则修改',       6,  'monitor:rule:edit',    'POST /monitor/rules/{id}/update、/enabled'),
    (167, '规则删除',       7,  'monitor:rule:remove',  'POST /monitor/rules/{id}/delete')
) AS v(menu_id, menu_name, order_num, perms, remark)
WHERE NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.menu_id = v.menu_id OR m.perms = v.perms);

-- ---------- 3. 多语言名称 ----------
INSERT INTO sys_menu_i18n (menu_id, locale, menu_name, remark)
SELECT v.menu_id, v.locale, v.menu_name, v.remark
FROM (VALUES
  (160, 'zh-CN', '监控管理',     '监控管理目录'),
  (160, 'zh-TW', '監控管理',     '監控管理目錄'),
  (160, 'en-US', 'Monitoring',   'Monitoring'),
  (161, 'zh-CN', '事件查询',     ''), (161, 'zh-TW', '事件查詢',     ''), (161, 'en-US', 'Event List',      ''),
  (162, 'zh-CN', '事件处理',     ''), (162, 'zh-TW', '事件處理',     ''), (162, 'en-US', 'Event Handle',    ''),
  (163, 'zh-CN', '规则查询',     ''), (163, 'zh-TW', '規則查詢',     ''), (163, 'en-US', 'Rule List',       ''),
  (164, 'zh-CN', '规则详情',     ''), (164, 'zh-TW', '規則詳情',     ''), (164, 'en-US', 'Rule Detail',     ''),
  (165, 'zh-CN', '规则新增',     ''), (165, 'zh-TW', '規則新增',     ''), (165, 'en-US', 'Rule Add',        ''),
  (166, 'zh-CN', '规则修改',     ''), (166, 'zh-TW', '規則修改',     ''), (166, 'en-US', 'Rule Edit',       ''),
  (167, 'zh-CN', '规则删除',     ''), (167, 'zh-TW', '規則刪除',     ''), (167, 'en-US', 'Rule Delete',     '')
) AS v(menu_id, locale, menu_name, remark)
WHERE EXISTS (SELECT 1 FROM sys_menu m WHERE m.menu_id = v.menu_id)
  AND NOT EXISTS (SELECT 1 FROM sys_menu_i18n i WHERE i.menu_id = v.menu_id AND i.locale = v.locale);

-- ---------- 4. 清理旧版本加过的 168–172（通知对象、站点管理） ----------
-- 只删 perms 对得上的行，避免误删以后复用这段编号的菜单
CREATE TEMP TABLE _monitor_old_menu ON COMMIT DROP AS
SELECT menu_id FROM sys_menu
WHERE menu_id BETWEEN 168 AND 172
  AND perms IN ('monitor:group:list', 'monitor:group:add', 'monitor:group:edit', 'monitor:group:remove', 'monitor:admin:edit');
DELETE FROM sys_role_menu WHERE menu_id IN (SELECT menu_id FROM _monitor_old_menu);
DELETE FROM sys_menu_i18n WHERE menu_id IN (SELECT menu_id FROM _monitor_old_menu);
DELETE FROM sys_menu      WHERE menu_id IN (SELECT menu_id FROM _monitor_old_menu);

COMMIT;

-- ---------- 5. 自检 ----------
SELECT menu_id, menu_name, parent_id, menu_type, perms FROM sys_menu
WHERE menu_id BETWEEN 160 AND 172 ORDER BY menu_id;   -- 应 8 行（160–167）



-- ############################################################################
-- ## 10. add_system_site_menu_postgresql.sql
-- ############################################################################

-- 系统管理 · 站点管理 菜单与按钮权限（dashboard_db）。可重复执行。
-- 页面：列出全部站点（配置里的站点 + 数据库里有设置行的站点），可设置所属包网。
-- 权限：system:site:list 查看，system:site:edit 修改包网。只给超级管理员授权，其他角色在「角色管理」里按需勾选。
-- menu_id 取当前最大值 + 1，避免与线上已有菜单冲突。

BEGIN;

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '站点管理', 1, 10, 'site', 'system/site/index', '', '',
       1, 0, 'C', '0', '0', 'system:site:list', 'international', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '站点列表与所属包网'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:site:list');

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '修改包网', p.menu_id, 1, '', '', '', '',
       1, 0, 'F', '0', '0', 'system:site:edit', '#', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '设置站点所属包网'
FROM sys_menu p
WHERE p.perms = 'system:site:list'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:site:edit');

INSERT INTO sys_menu_i18n (menu_id, locale, menu_name, remark)
SELECT m.menu_id, v.locale, v.menu_name, v.remark
FROM sys_menu m
JOIN (VALUES
    ('system:site:list', 'zh-CN', '站点管理', '站点列表与所属包网'),
    ('system:site:list', 'zh-TW', '站點管理', '站點列表與所屬包網'),
    ('system:site:list', 'en-US', 'Sites', 'Site list and package network'),
    ('system:site:edit', 'zh-CN', '修改包网', '设置站点所属包网'),
    ('system:site:edit', 'zh-TW', '修改包網', '設置站點所屬包網'),
    ('system:site:edit', 'en-US', 'Edit Package Network', 'Set the package network a site belongs to')
) AS v(perms, locale, menu_name, remark) ON v.perms = m.perms
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu_i18n i WHERE i.menu_id = m.menu_id AND i.locale = v.locale
);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id
FROM sys_menu m
WHERE m.perms IN ('system:site:list', 'system:site:edit')
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.role_id = 1)
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id
  );

COMMIT;

SELECT menu_id, menu_name, parent_id, menu_type, perms, path, component
FROM sys_menu WHERE perms LIKE 'system:site:%' ORDER BY menu_id;



-- ############################################################################
-- ## 汇总自检（只查询）
-- ############################################################################

-- 应为 8 张：monitor_event / hour_check / lark_chat / notify_log / notify_template / rule / rule_state / site_setting
SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema() AND table_name LIKE 'monitor\_%' ORDER BY 1;
-- 应为 20 / 7
SELECT count(*) FILTER (WHERE monitorable) AS monitorable, count(*) FILTER (WHERE gauge_min IS NOT NULL) AS gauge FROM dashboard_metric_card;
-- 应含 muted、paused
SELECT pg_get_constraintdef(oid) AS notify_check FROM pg_constraint WHERE conname = 'ck_monitor_event_notify';
-- 菜单：监控管理 8 行 + 站点管理 2 行 + 通知模板 2 行
SELECT menu_id, menu_name, parent_id, perms FROM sys_menu
 WHERE menu_id BETWEEN 160 AND 167 OR perms LIKE 'system:site:%' OR perms LIKE 'system:notifyTpl:%' ORDER BY menu_id;
-- sys_role.site_code 存在
SELECT column_name FROM information_schema.columns WHERE table_name = 'sys_role' AND column_name = 'site_code';
