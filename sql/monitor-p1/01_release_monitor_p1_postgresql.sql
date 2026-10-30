-- ============================================================================
-- 监控管理 · 一期 · 生产上线 SQL 汇总（dashboard_db）· 2026-10-29（10-23 版 + ⑪～㉓）
--
-- 开头是前置检查（0）和回滚用备份（0.5），之后由以下 23 个脚本按执行顺序原样拼接（内容未改动），每段前标出来源文件：
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
--   ⑪ alter_metric_card_chart_line        卡片图形 BAR → LINE，约束去掉 BAR（2026-10-23）
--   ⑫ alter_metric_card_dictionary        指标口径与 UDS 取数字段收口到 dashboard_metric_card（2026-10-26，只改表不改取数）
--   ⑬ add_metric_manage_menu              菜单「系统管理 · 指标管理」system:metric:*
--   ⑭ add_monitor_rule_test_permission    按钮「手动触发测试告警」monitor:rule:test（挂在通知模板下，必须在 ⑧ 之后）
--   ⑮ alter_metric_card_name_traditional  已接入指标名称改为繁体、以库为准（2026-10-27，后台可改名称 / 分组 / 格式）
--   ⑯ alter_monitor_site_setting_site    站点以库为准：monitor_site_setting 兼作站点表（首次启动从 yml 导入）+ 按钮「新增站点」（2026-10-28）
--   ⑰ alter_metric_card_caliber_traditional 运营总览口径以库为准：⑫ 的简体口径换成页面显示的繁体口径（2026-10-28）
--   ⑱ alter_monitor_site_setting_currency 站点币种、币种符号（后台站点管理可改）；JITO 的 UDS 站点标识 SITE-B → JITO（2026-10-28，必须在 ⑯ 之后）
--   ⑲ alter_monitor_site_setting_zone_currency 站点时区、币种以站点表为准：补齐空值（Asia/Kolkata / INR），dashboard 各接口按站点取（2026-10-29）
--   ⑳ alter_metric_card_avg_time_divide 平均到账 / 出款时间改为后端相除（耗时合计 ÷ 单数），指标字典不再登记 dT_seconds / wT_seconds（2026-10-29）
--   ㉑ alter_monitor_sample_bet_users      区间类指标（killR / bonusR / turnX）样本量改为投注人数 active（2026-10-27 MON-34）
--   ㉒ alter_monitor_event_stop_reason     事件停止原因 stop_reason（2026-10-27 MON-28 / MON-29，新 jar 启动前必须有）
--   ㉓ fix_monitor_event_level_peak        到过严重的事件等级改回严重（2026-10-27 MON-01；新 jar 上线后再执行一次本文件或该脚本即可）
--
-- 执行方式（用 psql，不要用 DBeaver 等图形客户端整段执行）：
--   psql -X -v ON_ERROR_STOP=1 -d dashboard_db -f 01_release_monitor_p1_postgresql.sql
--   * 各段自带 BEGIN/COMMIT，不要加 --single-transaction；
--   * 全部可重复执行：中途出错，修正后从头再执行一遍即可（已完成的段不会重复生效）；
--   * 执行前先备份（见 doc/monitor/监控管理-一期-生产上线步骤.md 2.2）。
--
-- 回滚：只回滚 JAR 时先执行 rollback_metric_card_monitor_postgresql.sql；要把数据库恢复到上线前，执行同目录的
--   02_rollback_monitor_p1_postgresql.sql（依赖 0.5 的备份，会删除 monitor_* 表与菜单）。
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



-- ---------- 0.5 回滚用备份（只在第一次执行时写入；配套 02_rollback_monitor_p1_postgresql.sql） ----------
-- dashboard_metric_card 整表（含将被删除的旧告警字段 alert_* / tolerance_*）及其约束、索引、默认值、注释，sys_role.site_code 原本是否存在
CREATE TABLE IF NOT EXISTS dashboard_metric_card_bak_monitor_p1 AS SELECT * FROM dashboard_metric_card;
CREATE TABLE IF NOT EXISTS monitor_p1_rollback_info (
    item        VARCHAR(128) PRIMARY KEY,
    value       TEXT,
    create_time TIMESTAMP NOT NULL DEFAULT localtimestamp
);
COMMENT ON TABLE monitor_p1_rollback_info IS '监控管理一期上线前的状态（回滚脚本用）；回滚后删除';
-- 只在第一次执行时记录（以 captured 标记为准）：重复执行 01 时表已经改过，不能再记，否则回滚会按改过的样子恢复
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM monitor_p1_rollback_info WHERE item = 'captured') THEN
        RETURN;
    END IF;
    INSERT INTO monitor_p1_rollback_info (item, value)
    SELECT 'sys_role.site_code.existed',
           (EXISTS (SELECT 1 FROM information_schema.columns
                     WHERE table_schema = current_schema() AND table_name = 'sys_role' AND column_name = 'site_code'))::text;
    -- 表与列注释
    INSERT INTO monitor_p1_rollback_info (item, value)
    SELECT 'comment:table', obj_description('dashboard_metric_card'::regclass, 'pg_class');
    INSERT INTO monitor_p1_rollback_info (item, value)
    SELECT 'comment:' || a.attname, col_description(a.attrelid, a.attnum)
      FROM pg_attribute a
     WHERE a.attrelid = 'dashboard_metric_card'::regclass AND a.attnum > 0 AND NOT a.attisdropped;
    -- CHECK 约束、索引、列默认值与非空
    INSERT INTO monitor_p1_rollback_info (item, value)
    SELECT 'check:' || conname, pg_get_constraintdef(oid)
      FROM pg_constraint WHERE conrelid = 'dashboard_metric_card'::regclass AND contype = 'c';
    INSERT INTO monitor_p1_rollback_info (item, value)
    SELECT 'index:' || indexname, indexdef
      FROM pg_indexes WHERE schemaname = current_schema() AND tablename = 'dashboard_metric_card';
    INSERT INTO monitor_p1_rollback_info (item, value)
    SELECT 'column:' || a.attname, json_build_object('notnull', a.attnotnull, 'default', pg_get_expr(d.adbin, d.adrelid))::text
      FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
     WHERE a.attrelid = 'dashboard_metric_card'::regclass AND a.attnum > 0 AND NOT a.attisdropped;
    INSERT INTO monitor_p1_rollback_info (item, value) VALUES ('captured', localtimestamp::text);
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
-- ## 11. alter_metric_card_chart_line_postgresql.sql
-- ############################################################################

-- ============================================================================
-- 运营总览卡片图形去掉 BAR（2026-10-23）· PostgreSQL（dashboard_db）
--
-- 总览接口的 chart.type 只有 GAUGE / LINE（后端已不读 chart_type 决定卡片图形）。
-- 这里把配置表里仍是 BAR 的 4 个指标（存提差 net、投注总额 bet、GGR ggr、NGR ngr）改成 LINE，
-- 并把约束收紧为 LINE / PIE / NUMBER，防止再配 BAR。
--
-- 只给已有环境用：新环境的建表脚本 dashboard_metric_card_postgresql.sql 已同步修改；
-- migrate_appdb_to_dashboard_db.sh 的 metrics 步骤按仓库建表脚本重建，也不需要再执行本脚本。
-- 可重复执行：第二次执行没有 BAR 行，UPDATE 0 行；备份表只在第一次写入。
-- ============================================================================

BEGIN;

-- 1. 备份要改的行（只在第一次执行时写入）
CREATE TABLE IF NOT EXISTS dashboard_metric_card_chart_bak_20261023 (
    metric_code VARCHAR(64) PRIMARY KEY,
    chart_type  VARCHAR(16) NOT NULL,
    backup_time TIMESTAMP   NOT NULL DEFAULT localtimestamp
);
INSERT INTO dashboard_metric_card_chart_bak_20261023 (metric_code, chart_type)
SELECT metric_code, chart_type FROM dashboard_metric_card WHERE chart_type = 'BAR'
ON CONFLICT (metric_code) DO NOTHING;

-- 2. BAR → LINE
UPDATE dashboard_metric_card SET chart_type = 'LINE' WHERE chart_type = 'BAR';

-- 3. 约束去掉 BAR
ALTER TABLE dashboard_metric_card DROP CONSTRAINT IF EXISTS ck_chart_type;
ALTER TABLE dashboard_metric_card ADD  CONSTRAINT ck_chart_type CHECK (chart_type IN ('LINE','PIE','NUMBER'));
COMMENT ON COLUMN dashboard_metric_card.chart_type IS 'LINE 折线 / PIE 饼图 / NUMBER 纯数值；运营总览卡片只按刻度区分 GAUGE / LINE，不读本列';

COMMIT;

-- 自检：应为 0 行 BAR；备份表应有 4 行（net、bet、ggr、ngr）
SELECT count(*) AS bar_left FROM dashboard_metric_card WHERE chart_type = 'BAR';
SELECT metric_code, chart_type AS old_chart_type, backup_time FROM dashboard_metric_card_chart_bak_20261023 ORDER BY metric_code;

-- 回滚（确认后手动执行）：
-- BEGIN;
-- ALTER TABLE dashboard_metric_card DROP CONSTRAINT IF EXISTS ck_chart_type;
-- ALTER TABLE dashboard_metric_card ADD  CONSTRAINT ck_chart_type CHECK (chart_type IN ('LINE','BAR','PIE','NUMBER'));
-- UPDATE dashboard_metric_card c SET chart_type = b.chart_type
--   FROM dashboard_metric_card_chart_bak_20261023 b WHERE b.metric_code = c.metric_code;
-- COMMIT;



-- ############################################################################
-- ## 12. alter_metric_card_dictionary_postgresql.sql
-- ############################################################################

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
    ('dT', NULL, NULL, NULL, NULL, '口径：存款自提交到入账的平均耗时，按成功笔数加权（分钟）。
备注：UDS 字段单位为秒，页面 ÷60 显示分钟。'),
    ('wOkR', 'wOkR', NULL, 'wOkR', NULL, '口径：出款成功笔数 ÷ 出款总尝试笔数 × 100%。
备注：UDS 比率为 0～1，页面 ×100。'),
    ('wT', NULL, NULL, NULL, NULL, '口径：提款自申请到拨款完成的平均耗时，按成功笔数加权（分钟）。
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

-- 5. 口径：只改还不是「口径：」/「口徑：」开头的行（后台维护过的、⑰ 改成繁体的都不覆盖）
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
    ('dT', NULL, NULL, NULL, NULL, '口径：存款自提交到入账的平均耗时，按成功笔数加权（分钟）。
备注：UDS 字段单位为秒，页面 ÷60 显示分钟。'),
    ('wOkR', 'wOkR', NULL, 'wOkR', NULL, '口径：出款成功笔数 ÷ 出款总尝试笔数 × 100%。
备注：UDS 比率为 0～1，页面 ×100。'),
    ('wT', NULL, NULL, NULL, NULL, '口径：提款自申请到拨款完成的平均耗时，按成功笔数加权（分钟）。
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
   AND (c.remark IS NULL OR (c.remark NOT LIKE '口径：%' AND c.remark NOT LIKE '口徑：%'));

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
       count(*) FILTER (WHERE remark LIKE '口径：%' OR remark LIKE '口徑：%') AS with_definition,
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



-- ############################################################################
-- ## 13. add_metric_manage_menu_postgresql.sql
-- ############################################################################

-- ============================================================================
-- 系统管理 · 指标管理：菜单与按钮权限（2026-10-26）· PostgreSQL（dashboard_db）
--
-- 页面维护 dashboard_metric_card 的口径与取数说明（不改运营总览、指标汇总的取数）：
--   system:metric:list    指标管理页面（查看）
--   system:metric:add     登记新指标（仅登记：不上卡、不进页面、不能建告警规则）
--   system:metric:edit    修改口径与取数说明；仅登记的指标还能改名称、分组、格式
--   system:metric:remove  删除仅登记、且没有被页面 / 告警规则 / 其他指标引用的指标
-- 授给超级管理员角色（role_id = 1）。可重复执行。
-- 执行：psql -X -v ON_ERROR_STOP=1 -d dashboard_db -f sql/add_metric_manage_menu_postgresql.sql
-- ============================================================================

BEGIN;

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '指标管理', 1, 12, 'metric', 'system/metric/index', '', '',
       1, 0, 'C', '0', '0', 'system:metric:list', 'chart', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '指标口径与取数方式（dashboard_metric_card）'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:metric:list');

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '登记指标', p.menu_id, 1, '', '', '', '',
       1, 0, 'F', '0', '0', 'system:metric:add', '#', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '登记新指标（仅登记，不接入取数）'
FROM sys_menu p
WHERE p.perms = 'system:metric:list'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:metric:add');

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '修改指标', p.menu_id, 2, '', '', '', '',
       1, 0, 'F', '0', '0', 'system:metric:edit', '#', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '修改口径与取数说明'
FROM sys_menu p
WHERE p.perms = 'system:metric:list'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:metric:edit');

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '删除指标', p.menu_id, 3, '', '', '', '',
       1, 0, 'F', '0', '0', 'system:metric:remove', '#', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '删除仅登记且没有被引用的指标'
FROM sys_menu p
WHERE p.perms = 'system:metric:list'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:metric:remove');

INSERT INTO sys_menu_i18n (menu_id, locale, menu_name, remark)
SELECT m.menu_id, v.locale, v.menu_name, v.remark
FROM sys_menu m
JOIN (VALUES
    ('system:metric:list',   'zh-CN', '指标管理', '指标口径与取数方式'),
    ('system:metric:list',   'zh-TW', '指標管理', '指標口徑與取數方式'),
    ('system:metric:list',   'en-US', 'Metrics', 'Metric definitions and data sources'),
    ('system:metric:add',    'zh-CN', '登记指标', '登记新指标（仅登记，不接入取数）'),
    ('system:metric:add',    'zh-TW', '登記指標', '登記新指標（僅登記，不接入取數）'),
    ('system:metric:add',    'en-US', 'Register Metric', 'Register a metric (record only, not fetched)'),
    ('system:metric:edit',   'zh-CN', '修改指标', '修改口径与取数说明'),
    ('system:metric:edit',   'zh-TW', '修改指標', '修改口徑與取數說明'),
    ('system:metric:edit',   'en-US', 'Edit Metric', 'Edit definitions and data source notes'),
    ('system:metric:remove', 'zh-CN', '删除指标', '删除仅登记且没有被引用的指标'),
    ('system:metric:remove', 'zh-TW', '刪除指標', '刪除僅登記且沒有被引用的指標'),
    ('system:metric:remove', 'en-US', 'Delete Metric', 'Delete registered, unreferenced metrics')
) AS v(perms, locale, menu_name, remark) ON v.perms = m.perms
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu_i18n i WHERE i.menu_id = m.menu_id AND i.locale = v.locale
);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id
FROM sys_menu m
WHERE m.perms IN ('system:metric:list', 'system:metric:add', 'system:metric:edit', 'system:metric:remove')
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.role_id = 1)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id);

COMMIT;

-- 执行后应有 4 行：1 个菜单 + 3 个按钮；重新登录后台后「系统管理」下出现「指标管理」
SELECT menu_id, menu_name, parent_id, menu_type, perms FROM sys_menu WHERE perms LIKE 'system:metric:%' ORDER BY menu_id;



-- ############################################################################
-- ## 14. add_monitor_rule_test_permission_postgresql.sql
-- ############################################################################

-- ============================================================================
-- 管理后台 · 监控规则「手动触发测试告警」按钮权限 monitor:rule:test（dashboard_db）。可重复执行。
--
-- 接口：POST /monitor/rules/{ruleId}/test-alert（站点取请求头 X-Site-Code）
-- 挂在「系统管理 · 通知模板」菜单下（页面入口在通知模板页），默认只给超级管理员角色（role_id = 1）。
-- 依赖：已执行 add_monitor_notify_template_postgresql.sql（通知模板菜单 system:notifyTpl:list）。
-- 测试群 chat_id 配在环境变量 MONITOR_TEST_CHAT_ID（/etc/fivetech.env），不配时接口返回「未配置測試群」。
-- ============================================================================

BEGIN;

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '手动触发测试告警', p.menu_id, 2, '', '', '', '',
       1, 0, 'F', '0', '0', 'monitor:rule:test', '#', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '取规则最近一个周期的真实数据，发一条【測試】告警到 MONITOR_TEST_CHAT_ID 测试群'
FROM sys_menu p
WHERE p.perms = 'system:notifyTpl:list'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'monitor:rule:test');

INSERT INTO sys_menu_i18n (menu_id, locale, menu_name, remark)
SELECT m.menu_id, v.locale, v.menu_name, v.remark
FROM sys_menu m
JOIN (VALUES
    ('zh-CN', '手动触发测试告警', '发一条【测试】告警到测试群'),
    ('zh-TW', '手動觸發測試告警', '發一條【測試】告警到測試群'),
    ('en-US', 'Trigger Test Alert', 'Send a [TEST] alert to the test chat')
) AS v(locale, menu_name, remark) ON TRUE
WHERE m.perms = 'monitor:rule:test'
  AND NOT EXISTS (SELECT 1 FROM sys_menu_i18n i WHERE i.menu_id = m.menu_id AND i.locale = v.locale);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id
FROM sys_menu m
WHERE m.perms = 'monitor:rule:test'
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.role_id = 1)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id);

COMMIT;

-- 应返回 1 行（父菜单为「通知模板」）；为 0 行说明还没执行 add_monitor_notify_template_postgresql.sql
SELECT m.menu_id, m.menu_name, m.perms, p.menu_name AS parent
  FROM sys_menu m JOIN sys_menu p ON p.menu_id = m.parent_id
 WHERE m.perms = 'monitor:rule:test';



-- ############################################################################
-- ## 15. alter_metric_card_name_traditional_postgresql.sql
-- ############################################################################

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



-- ############################################################################
-- ## 16. alter_monitor_site_setting_site_postgresql.sql
-- ############################################################################

-- ============================================================================
-- 站点改为以库为准，存在 monitor_site_setting（2026-10-28）· PostgreSQL（dashboard_db）· 可重复执行
--
-- monitor_site_setting 原来只存站点的附加设置（所属包网、下一个规则号），站点列表在 application.yml。
-- 现在兼作站点表：site_name 不为空的行就是站点（编码、名称、UDS 站点标识、时区、是否默认站点），
-- site_name 为空的行是早期按需补的设置行或已去掉的站点留下的数据（站点管理页显示「未配置」）。
--
--   1. 加列：site_name / uds_site / timezone / is_default / sort_no；编码不区分大小写唯一、默认站点只有一个
--      站点列表（登录可切换的站点、站点管理、角色的站点选项）按 sort_no 从小到大：新增站点取最大值 + 1，即创建先后
--   2. 站点数据：后端首次启动时（还没有 site_name 不为空的行）把 application.yml 里的站点导入
--      （dashboard.default-site / site-name / default-uds-site 为默认站点，dashboard.sites 为其余站点），已有的设置行补上站点字段；
--      之后登录的站点列表、X-Site-Code 校验、X-Uds-Site、判定引擎都读这张表，yml 的这几项不再生效
--   3. 之前按旧版本建过 sys_site 的库：把 sys_site 的站点写进来，再删除 sys_site
--   4. 菜单按钮「新增站点」system:site:add（挂在「站点管理」下），只授权超级管理员；「修改包网」改名为「修改站点」
--
-- 执行：psql -X -v ON_ERROR_STOP=1 -d dashboard_db -f sql/alter_monitor_site_setting_site_postgresql.sql（先执行再部署新 JAR）
-- 回滚：去掉第 1 步加的列与索引、删除 system:site:add 菜单、system:site:edit 改回「修改包网」（旧 JAR 只认 yml 站点）。
--      后台改过站点编码时，业务表里已经是新编码，回滚前要把 yml 的站点编码改成一致。
-- ============================================================================

BEGIN;

-- ---------- 1. 站点列 ----------
ALTER TABLE monitor_site_setting ADD COLUMN IF NOT EXISTS site_name  VARCHAR(64);
ALTER TABLE monitor_site_setting ADD COLUMN IF NOT EXISTS uds_site   VARCHAR(64);
ALTER TABLE monitor_site_setting ADD COLUMN IF NOT EXISTS timezone   VARCHAR(64);
ALTER TABLE monitor_site_setting ADD COLUMN IF NOT EXISTS is_default BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE monitor_site_setting ADD COLUMN IF NOT EXISTS sort_no    INTEGER NOT NULL DEFAULT 0;
-- 本脚本中间版本加过 create_time（排序改用 sort_no，不需要）
ALTER TABLE monitor_site_setting DROP COLUMN IF EXISTS create_time;

-- 编码不区分大小写唯一（已有大小写重复的数据时报错停止，先人工处理）
DO $$
DECLARE dup TEXT;
BEGIN
    SELECT string_agg(site_code, ', ') INTO dup
      FROM monitor_site_setting
     WHERE upper(site_code) IN (SELECT upper(site_code) FROM monitor_site_setting GROUP BY upper(site_code) HAVING count(*) > 1);
    IF dup IS NOT NULL THEN
        RAISE EXCEPTION 'monitor_site_setting 里有只差大小写的站点编码：%，先合并再执行', dup;
    END IF;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS uk_monitor_site_code_ci ON monitor_site_setting (upper(site_code));
CREATE UNIQUE INDEX IF NOT EXISTS uk_monitor_site_default ON monitor_site_setting (is_default) WHERE is_default;

COMMENT ON TABLE  monitor_site_setting            IS '站点与站点设置：site_name 不为空的行是站点（系统管理 · 站点管理维护，以库为准）；同一行存所属包网、规则号';
COMMENT ON COLUMN monitor_site_setting.site_code  IS '站点编码（请求头 X-Site-Code、各业务表 site_code）；后台改编码时级联更新所有带 site_code 的表';
COMMENT ON COLUMN monitor_site_setting.site_name  IS '站点显示名；为空表示这一行不是站点（早期补的设置行或已去掉站点留下的数据）';
COMMENT ON COLUMN monitor_site_setting.uds_site   IS 'UDS 站点标识（请求头 X-Uds-Site）；为空用站点编码';
COMMENT ON COLUMN monitor_site_setting.timezone   IS '站点时区（IANA）；为空用 dashboard.timezone；默认站点固定用 dashboard.timezone';
COMMENT ON COLUMN monitor_site_setting.is_default IS '默认站点（没带 X-Site-Code、通用角色时进入的站点），只有一个';
COMMENT ON COLUMN monitor_site_setting.sort_no    IS '站点排序号：新增站点取最大值 + 1（即创建先后），站点列表按它从小到大；首次导入时默认站点为 0、其余按 yml 顺序';

-- ---------- 3. 旧版本建过的 sys_site：搬过来再删除 ----------
DO $$
BEGIN
    IF to_regclass('sys_site') IS NOT NULL THEN
        EXECUTE $q$
            INSERT INTO monitor_site_setting (site_code, next_rule_no, site_name, uds_site, timezone, is_default, sort_no, update_by, update_time)
            SELECT s.site_code, 1, s.site_name, s.uds_site, s.timezone, s.is_default, s.sort_no,
                   coalesce(nullif(s.update_by, ''), s.create_by, 'system'), coalesce(s.update_time, s.create_time, localtimestamp)
              FROM sys_site s
            ON CONFLICT (site_code) DO UPDATE
               SET site_name = excluded.site_name, uds_site = excluded.uds_site, timezone = excluded.timezone,
                   is_default = excluded.is_default, sort_no = excluded.sort_no,
                   update_by = excluded.update_by, update_time = excluded.update_time
             WHERE monitor_site_setting.site_name IS NULL
        $q$;
        DROP TABLE sys_site;
    END IF;
END $$;

-- ---------- 4. 按钮：新增站点；「修改包网」→「修改站点」 ----------
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '新增站点', p.menu_id, 2, '', '', '', '',
       1, 0, 'F', '0', '0', 'system:site:add', '#', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '新增站点'
FROM sys_menu p
WHERE p.perms = 'system:site:list'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:site:add');

UPDATE sys_menu SET menu_name = '修改站点', remark = '修改站点名称、编码、UDS 站点标识、时区、所属包网'
 WHERE perms = 'system:site:edit' AND menu_name = '修改包网';
UPDATE sys_menu_i18n i SET menu_name = v.menu_name, remark = v.remark
  FROM sys_menu m, (VALUES
    ('zh-CN', '修改站点', '修改站点名称、编码、UDS 站点标识、时区、所属包网'),
    ('zh-TW', '修改站點', '修改站點名稱、編碼、UDS 站點標識、時區、所屬包網'),
    ('en-US', 'Edit Site', 'Edit site name, code, UDS site, timezone and package network')
  ) AS v(locale, menu_name, remark)
 WHERE m.perms = 'system:site:edit' AND i.menu_id = m.menu_id AND i.locale = v.locale;

INSERT INTO sys_menu_i18n (menu_id, locale, menu_name, remark)
SELECT m.menu_id, v.locale, v.menu_name, v.remark
FROM sys_menu m
JOIN (VALUES
    ('system:site:add', 'zh-CN', '新增站点', '新增站点'),
    ('system:site:add', 'zh-TW', '新增站點', '新增站點'),
    ('system:site:add', 'en-US', 'Add Site', 'Add a site')
) AS v(perms, locale, menu_name, remark) ON v.perms = m.perms
WHERE NOT EXISTS (SELECT 1 FROM sys_menu_i18n i WHERE i.menu_id = m.menu_id AND i.locale = v.locale);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id
FROM sys_menu m
WHERE m.perms = 'system:site:add'
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.role_id = 1)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id);

COMMIT;

-- 自检：站点行数（后端首次启动前为 0，启动后为 yml 里的站点数）、设置行总数；按钮 3 个
SELECT count(*) FILTER (WHERE site_name IS NOT NULL) AS sites, count(*) AS setting_rows FROM monitor_site_setting;
SELECT menu_id, menu_name, parent_id, perms FROM sys_menu WHERE perms LIKE 'system:site:%' ORDER BY menu_id;



-- ############################################################################
-- ## 17. alter_metric_card_caliber_traditional_postgresql.sql
-- ############################################################################

-- ============================================================================
-- 运营总览指标口径改为以库为准（2026-10-28）· PostgreSQL（dashboard_db）· 可重复执行
--
-- 运营总览 overview 返回的 expression（指标卡口径、计算构成口径）原来写死在代码里（按原型的繁体），
-- 现在取 dashboard_metric_card.remark 里「口徑：」那一行（后台「系统管理 · 指标管理」的「口徑與備註」）。
-- 本脚本把 ⑫ 写入的 28 条简体口径换成页面一直在显示的繁体口径（原型；原子指标按 MON-26「總訂單數」写法），
-- 上线前后页面显示不变；「備註：」等其余行保留。
--
--   1. 备份 remark 到 dashboard_metric_card_remark_bak_20261028（只在第一次执行时写入）
--   2. 只改第一行还是 ⑫ 原值的行：后台改过口径的不覆盖；remark 为空的补上口径
--
-- 执行：psql -X -v ON_ERROR_STOP=1 -d dashboard_db -f sql/alter_metric_card_caliber_traditional_postgresql.sql
-- 回滚：UPDATE dashboard_metric_card c SET remark = b.remark
--         FROM dashboard_metric_card_remark_bak_20261028 b WHERE b.metric_code = c.metric_code;
--      （监控管理一期整体回滚时 02_rollback 按 0.5 的整表备份恢复，并删除本备份表）
-- ============================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS dashboard_metric_card_remark_bak_20261028 (
    metric_code  VARCHAR(64)  PRIMARY KEY,
    remark       TEXT,
    backup_time  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO dashboard_metric_card_remark_bak_20261028 (metric_code, remark)
SELECT metric_code, remark FROM dashboard_metric_card
ON CONFLICT (metric_code) DO NOTHING;

UPDATE dashboard_metric_card c
   SET remark = v.caliber || CASE WHEN position(E'\n' IN coalesce(c.remark, '')) > 0
                                  THEN substr(c.remark, position(E'\n' IN c.remark)) ELSE '' END,
       update_by = 'admin', update_time = CURRENT_TIMESTAMP
  FROM (VALUES
    ('reg', '口径：完成会员注册的人数，按注册时间（IST）归入所属小时。', '口徑：完成會員註冊的人數'),
    ('ftd', '口径：完成生涯首笔存款的人数，按首笔存款成功时间（IST）归入所属小时。', '口徑：完成生涯首筆存款的人數'),
    ('ftdr', '口径：首存人数 ÷ 注册人数 × 100%。', '口徑：首存人數 ÷ 註冊人數 × 100%'),
    ('ftdA', '口径：首存总金额 ÷ 首存人数。', '口徑：首存總金額 ÷ 首存人數'),
    ('dep', '口径：所有会员成功存款金额加总（站点币种主单位）。', '口徑：所有會員成功存款金額加總'),
    ('wd', '口径：所有会员成功提款金额加总（站点币种主单位）。', '口徑：所有會員成功提款金額加總'),
    ('net', '口径：存款总额 − 提款总额，可为负。', '口徑：存款總額 − 提款總額'),
    ('arppu', '口径：存款总额 ÷ 去重存款人数；按小时／日去重，仅支持查看单日数据。', '口徑：存款總額 ÷ 存款人數。同一人在區間內只計一次：區間值的分母按整個區間去重，不能用各行存款人數相加，也不等於各行 ARPPU 的平均。支援按小時／日去重，僅支援查看單日資料。'),
    ('turnX', '口径：投注总额 ÷ 存款总额。', '口徑：投注總額 ÷ 存款總額'),
    ('dOkR', '口径：存款成功笔数 ÷ 存款总尝试笔数 × 100%。', '口徑：存款成功筆數 ÷ 存款總訂單數 × 100%'),
    ('dT', '口径：存款自提交到入账的平均耗时，按成功笔数加权（分钟）。', '口徑：存款自送出到入帳的平均耗時'),
    ('wOkR', '口径：出款成功笔数 ÷ 出款总尝试笔数 × 100%。', '口徑：出款成功筆數 ÷ 出款總訂單數 × 100%'),
    ('wT', '口径：提款自申请到拨款完成的平均耗时，按成功笔数加权（分钟）。', '口徑：提款自申請到撥款完成的平均耗時'),
    ('active', '口径：有投注行为的去重会员数；按小时／日去重，跨时段不可相加，仅支持查看单日数据。', '口徑：有投注行為的去重會員數。支援按小時／日去重，僅支援查看單日資料。'),
    ('bet', '口径：所有投注金额加总。', '口徑：所有投注金額加總（＝總流水）'),
    ('ggr', '口径：0 − 玩家输赢（win loss amount），即投注总额 − 派彩金额。', '口徑：0 − 玩家輸贏（win loss amount）'),
    ('killR', '口径：GGR ÷ 投注总额 × 100%。', '口徑：GGR ÷ 投注總額'),
    ('bonus', '口径：各赠金项目实际发放金额加总。', '口徑：各贈金項目實際發放金額加總'),
    ('bonusR', '口径：发放赠金总额 ÷ 投注总额 × 100%。', '口徑：贈金發放總額 ÷ 總流水 × 100%'),
    ('ngr', '口径：GGR − 发放赠金总额。', '口徑：GGR − 發放贈金總額'),
    ('login', '口径：完成登录的去重会员数；按小时／日去重，跨时段不可相加。', '口徑：完成登入的去重會員數'),
    ('depU', '口径：有成功存款的去重会员数；按小时／日去重。', '口徑：有成功存款的去重會員數；按小時／日去重'),
    ('ftdAmt', '口径：首笔存款金额加总。', '口徑：首筆存款金額加總'),
    ('pay', '口径：派彩金额 = 投注总额 + 玩家输赢（win_los_amt）。', '口徑：派彩金額 = 投注總額 + 玩家輸贏（win_los_amt）'),
    ('dOk', '口径：成功的存款订单笔数。', '口徑：成功的存款訂單筆數'),
    ('dTry', '口径：存款总尝试笔数（发起的存款订单数）。', '口徑：存款總訂單數（發起的存款訂單數，不限狀態）'),
    ('wOk', '口径：成功的提款订单笔数。', '口徑：成功的提款訂單筆數'),
    ('wTry', '口径：提款总尝试笔数（发起的提款订单数）。', '口徑：提款總訂單數（發起的提款訂單數，不限狀態）')
  ) AS v(code, seeded, caliber)
 WHERE c.metric_code = v.code
   AND (coalesce(c.remark, '') = '' OR split_part(c.remark, E'\n', 1) = v.seeded);

COMMIT;

-- 自检：口径还不是繁体「口徑：」开头的（应为 0 行；有行说明是后台改过，人工确认）
SELECT metric_code, split_part(remark, E'\n', 1) AS caliber
  FROM dashboard_metric_card
 WHERE status = '0' AND coalesce(remark, '') NOT LIKE '口徑：%'
 ORDER BY metric_code;



-- ############################################################################
-- ## 18. alter_monitor_site_setting_currency_postgresql.sql
-- ############################################################################

-- ============================================================================
-- 站点币种、币种符号；JITO 的 UDS 站点标识改为 JITO（2026-10-28）· PostgreSQL（dashboard_db）· 可重复执行
--
-- monitor_site_setting 兼作站点表（见 alter_monitor_site_setting_site_postgresql.sql）后，按站点保存币种：
--   currency         币种代码（大写，如 INR、CNY）；为空用 application.yml 的 dashboard.currency
--   currency_symbol  币种符号（如 ₹、¥）；为空按币种代码取常用符号，认不出用币种代码
-- 在「系统管理 · 站点管理」新增 / 修改站点时编辑；登录 getInfo 的 sites[] 返回 currency、currencySymbol（与 timezone 一起）。
--
-- JITO 的 UDS 站点标识：UDS 多站点联调（2026-10-28）约定 X-Uds-Site 为 SITE-IN / JITO（大小写敏感）。
--   首次启动从 application.yml 导入时默认是 SITE-B，这里改成 JITO；只改仍是 SITE-B 的行，后台改过的不动。
--
-- 执行顺序：在 alter_monitor_site_setting_site_postgresql.sql 之后、部署新 JAR 之前。
--   新 JAR 读站点表时会查这两列；没执行本脚本时站点表加载失败，后端退回 application.yml 的站点配置（站点管理页不能保存）。
-- 执行：psql -X -v ON_ERROR_STOP=1 -d dashboard_db -f sql/alter_monitor_site_setting_currency_postgresql.sql
-- 回滚：ALTER TABLE monitor_site_setting DROP COLUMN IF EXISTS currency_symbol, DROP COLUMN IF EXISTS currency;
-- ============================================================================

BEGIN;

ALTER TABLE monitor_site_setting ADD COLUMN IF NOT EXISTS currency        VARCHAR(10);
ALTER TABLE monitor_site_setting ADD COLUMN IF NOT EXISTS currency_symbol VARCHAR(16);

COMMENT ON COLUMN monitor_site_setting.currency        IS '站点币种代码（大写，如 INR、CNY）；为空用 dashboard.currency';
COMMENT ON COLUMN monitor_site_setting.currency_symbol IS '站点币种符号（如 ₹、¥）；为空按币种代码取常用符号，认不出用币种代码';

UPDATE monitor_site_setting
   SET uds_site = 'JITO', update_by = 'admin', update_time = localtimestamp
 WHERE site_name IS NOT NULL AND upper(site_code) = 'JITO' AND uds_site = 'SITE-B';

COMMIT;

-- 自检：站点的 UDS 标识、时区、币种（为空 = 按配置 / 推导）
SELECT site_code, site_name, uds_site, timezone, currency, currency_symbol FROM monitor_site_setting WHERE site_name IS NOT NULL ORDER BY sort_no, site_code;


-- ############################################################################
-- ## 19. alter_monitor_site_setting_zone_currency_postgresql.sql
-- ############################################################################

-- ============================================================================
-- 站点时区、币种以站点表为准（2026-10-29）· PostgreSQL（dashboard_db）· 可重复执行
--
-- dashboard 各接口（运营总览、总览板块、指标汇总、明细查询、导出）返回的 timezone / currency，以及「今天 / 当前整点 /
-- 区间」的计算，改为按当前站点（X-Site-Code）取 monitor_site_setting.timezone / currency（后端内存缓存，不查库）；
-- UDS 该站点的数据按同一时区统计。默认站点的时区也存在站点表里，后台「站点管理」可改。
--
-- 本脚本把还没填的站点时区、币种补成上线前实际在用的值（application.yml 的默认值 Asia/Kolkata / INR），
-- 上线前后各接口返回不变；后台改过的不动。
--   * 生产的 DASHBOARD_TIMEZONE / DASHBOARD_CURRENCY 不是默认值时，先把下面两处值改成生产值再执行。
--   * 某站点在 UDS 不是按 IST 统计的，执行后在站点管理里改成它的实际时区。
--
-- 执行顺序：在 ⑯ alter_monitor_site_setting_site、⑱ alter_monitor_site_setting_currency 之后。
-- 执行：psql -X -v ON_ERROR_STOP=1 -d dashboard_db -f sql/alter_monitor_site_setting_zone_currency_postgresql.sql
-- 回滚：不需要（空值与补上的值效果相同：为空时后端也按 dashboard.timezone / dashboard.currency）
-- ============================================================================

BEGIN;

UPDATE monitor_site_setting
   SET timezone = 'Asia/Kolkata', update_by = 'admin', update_time = localtimestamp
 WHERE site_name IS NOT NULL AND coalesce(trim(timezone), '') = '';

UPDATE monitor_site_setting
   SET currency = 'INR', update_by = 'admin', update_time = localtimestamp
 WHERE site_name IS NOT NULL AND coalesce(trim(currency), '') = '';

COMMIT;

-- 自检：每个站点都有时区、币种（应为 0 行）
SELECT site_code, site_name, timezone, currency FROM monitor_site_setting
 WHERE site_name IS NOT NULL AND (coalesce(trim(timezone), '') = '' OR coalesce(trim(currency), '') = '');
-- 站点一览
SELECT site_code, site_name, is_default, timezone, currency, currency_symbol FROM monitor_site_setting
 WHERE site_name IS NOT NULL ORDER BY sort_no, site_code;


-- ############################################################################
-- ## 20. alter_metric_card_avg_time_divide_postgresql.sql
-- ############################################################################

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


-- ############################################################################
-- ## 21. alter_monitor_sample_bet_users_postgresql.sql
-- ############################################################################

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


-- ############################################################################
-- ## 22. alter_monitor_event_stop_reason_postgresql.sql
-- ############################################################################

-- ============================================================================
-- 监控管理 · 事件停止原因（MON-28 / MON-29）· PostgreSQL（dashboard_db）。可重复执行。
--
-- 2026-10-27 与产品确认：修改规则的阈值、检测周期或注意升级后，异常中的事件标为「已停止判定」，按新规则重新起算
-- （不再按新阈值直接结束旧事件、也不会按新升级时长立刻升级推送）。
-- monitor_event + stop_reason：pause 暂停 / delete 删除 / update 修改判定条件；之前停止的事件为空（悬停按暂停 / 删除写）。
-- 「持續」列悬停：update →「10:40 規則修改，之後按新規則重新判定」。
-- ============================================================================
BEGIN;

ALTER TABLE monitor_event ADD COLUMN IF NOT EXISTS stop_reason VARCHAR(10);
ALTER TABLE monitor_event DROP CONSTRAINT IF EXISTS ck_monitor_event_stop_reason;
ALTER TABLE monitor_event ADD CONSTRAINT ck_monitor_event_stop_reason
    CHECK (stop_reason IS NULL OR stop_reason IN ('pause', 'delete', 'update'));
COMMENT ON COLUMN monitor_event.stop_reason IS '事件停止原因（status = stopped）：pause 规则暂停 / delete 规则删除 / update 修改了阈值、检测周期或注意升级';

-- 老数据：规则已删除的补 delete，其余已停止的补 pause
UPDATE monitor_event e
   SET stop_reason = CASE WHEN r.del_flag = '2' THEN 'delete' ELSE 'pause' END
  FROM monitor_rule r
 WHERE r.rule_id = e.rule_id AND r.site_code = e.site_code
   AND e.status = 'stopped' AND e.stop_reason IS NULL;

COMMIT;

SELECT stop_reason, count(*) FROM monitor_event WHERE status = 'stopped' GROUP BY 1;


-- ############################################################################
-- ## 23. fix_monitor_event_level_peak_postgresql.sql
-- ############################################################################

-- ============================================================================
-- 监控管理 · 修正已被「降级」的事件等级（MON-01 / MON-04）· PostgreSQL（dashboard_db）。可重复执行。
--
-- 修复前，事件到过严重后只要有一个周期回到注意范围，monitor_event.level 就被改成 WARN（peak_level 仍记着 CRIT）。
-- 新代码只升不降；本脚本把历史数据改回：到过严重的事件 level = CRIT。
-- 判定基准（threshold.against）、提醒条、指标卡都按 level 显示，随之恢复为严重线。
-- 不改 escalated、通知记录和处理状态；当前值（MON-03）历史上已被覆盖为最近一期，无法还原，新事件起按触发周期记录。
-- 和新 jar 一起执行（先停旧 jar 的判定也可以不停：旧 jar 可能再次降级，新 jar 上线后再执行一次即可）。
-- ============================================================================
BEGIN;

-- 执行前看一下会改哪些
SELECT event_id, site_code, rule_id, metric_code, level, peak_level, status, trigger_hour
  FROM monitor_event
 WHERE event_type = 'alert' AND peak_level = 'CRIT' AND level <> 'CRIT'
 ORDER BY site_code, event_id;

UPDATE monitor_event
   SET level = 'CRIT',
       update_time = (now() AT TIME ZONE 'Asia/Kolkata')
 WHERE event_type = 'alert' AND peak_level = 'CRIT' AND level <> 'CRIT';

COMMIT;


-- ############################################################################
-- ## 汇总自检（只查询）
-- ############################################################################

-- 应为 8 张：monitor_event / hour_check / lark_chat / notify_log / notify_template / rule / rule_state / site_setting
SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema() AND table_name LIKE 'monitor\_%' ORDER BY 1;
-- 应为 20 / 7
SELECT count(*) FILTER (WHERE monitorable) AS monitorable, count(*) FILTER (WHERE gauge_min IS NOT NULL) AS gauge FROM dashboard_metric_card;
-- 应含 muted、paused
SELECT pg_get_constraintdef(oid) AS notify_check FROM pg_constraint WHERE conname = 'ck_monitor_event_notify';
-- 菜单：监控管理 8 行 + 站点管理 2 行 + 通知模板 2 行 + 手动触发 1 行 + 指标管理 4 行
SELECT menu_id, menu_name, parent_id, perms FROM sys_menu
 WHERE menu_id BETWEEN 160 AND 167 OR perms LIKE 'system:site:%' OR perms LIKE 'system:notifyTpl:%'
    OR perms = 'monitor:rule:test' OR perms LIKE 'system:metric:%' ORDER BY menu_id;
-- 指标名称已是繁体：⑮ 的自检应为 0 行（见上）；名称备份表应有已接入指标的行数
SELECT count(*) AS name_backup_rows FROM dashboard_metric_card_name_bak_20261027;
-- 卡片图形不再有 BAR：0
SELECT count(*) AS bar_left FROM dashboard_metric_card WHERE chart_type = 'BAR';
-- 指标字典：28 行全部有口径（⑰ 之后为繁体「口徑：」），UDS 字段已填（net / ngr / pay / dT / wT 由后端组装，没有字段）
SELECT count(*) AS total, count(*) FILTER (WHERE remark LIKE '口径：%' OR remark LIKE '口徑：%') AS with_definition,
       count(*) FILTER (WHERE coalesce(uds_hour_field, uds_day_field, uds_amount_field) IS NOT NULL) AS with_uds_field
  FROM dashboard_metric_card;
-- 站点表：⑯ / ⑱ 的列都在（site_name、uds_site、timezone、is_default、sort_no、currency、currency_symbol 共 7 行）
SELECT column_name FROM information_schema.columns
 WHERE table_name = 'monitor_site_setting'
   AND column_name IN ('site_name', 'uds_site', 'timezone', 'is_default', 'sort_no', 'currency', 'currency_symbol') ORDER BY 1;
-- ㉒ 事件停止原因列存在（1 行）
SELECT column_name FROM information_schema.columns WHERE table_name = 'monitor_event' AND column_name = 'stop_reason';
-- sys_role.site_code 存在
SELECT column_name FROM information_schema.columns WHERE table_name = 'sys_role' AND column_name = 'site_code';
