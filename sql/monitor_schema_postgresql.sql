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
