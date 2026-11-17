-- ============================================================================
-- 下一次上线 · 增量 SQL（dashboard_db）· PostgreSQL · 可重复执行
--
-- 基线：①–⑬（2026-11-02 ~ 2026-11-12）已全部上线，已从本文件移除（见 git 历史）；
--       之后所有数据库改动只追加到本文件末尾，按段编号；上线后在「上线记录」里登记日期。
--
-- 写法约定：
--   * 每段自带 BEGIN/COMMIT，段首注明：编号、日期、需求、改了什么、回滚语句；
--   * 必须可重复执行（IF NOT EXISTS / ON CONFLICT DO NOTHING / DO $$ 判断），中途出错修正后从头再跑；
--   * 只追加，不改已上线的段；要改已上线的结构，追加新段。
--
-- 执行（用 psql，不要用图形客户端整段执行；不要加 --single-transaction）：
--   psql -X -v ON_ERROR_STOP=1 -d dashboard_db -f sql/release_next_postgresql.sql
--
-- 段落目录：
--   ⑭ 2026-11-13  订阅管理 · 下一期（V0.4 原型）            dashboard_subscription 加频率 / 视图 / 负责人；新表 dashboard_subscription_run；
--                                                       log 加 run_id / result / reason_code / attempts；旧订阅备份后删除；菜单 dashboard:subscription:*
--   ⑮ 2026-11-13  监控 · 按规则发告警                      按钮 monitor:rule:alert；已有 API Key 的接口范围换成 /monitor/rules/*/alert
--   ⑯ 2026-11-13  指标管理 · 所属范围                      dashboard_metric_card.page_scope（OVERVIEW / PROMO / RISK，空 = 所有页面）
--   ⑰ 2026-11-13  菜单分组：站点板块 / 游戏板块             sys_menu.group_code（site / game）
--   ⑱ 2026-11-16  监控 · 订单规则                          monitor_rule 加 rule_type / rule_name / level / config / version 等；序列 monitor_rule_version_seq
--   ⑲ 2026-11-16  操作日志 · 记录查询操作                  字典 sys_oper_type 加「查询」（dict_value = 10，对应 BusinessType.QUERY）
--   ⑳ 2026-11-16  监控 · 订单告警事件                      新表 monitor_order_event：/monitor/rules/{ruleId}/alert 带订单号、金额、当日累计笔数时写入
--   ㉑ 2026-11-16  监控 · 规则可选多个通知群                monitor_rule 加 notify_chats（jsonb，最多 5 个群），按 notify_chat_id 回填
--   ㉒ 2026-11-16  风控分析                                指标管理加 ext 与页面目录范围（RISK_RANK / RISK_GAME / MONITOR_COND）；风控概览页 RISK 与新指标 wdr / rtp；
--                                                       排行榜 31 列、游戏分析 20 列；新表 risk_my_board；配置中心 RISK_*；个人视图配置位；菜单 dashboard:risk:*
--   ㉓ 2026-11-16  监控 · 条件规则                          新表 monitor_rule_condition；monitor_event 加 rule_type / detail（metric_code 可空）；
--                                                       monitor_rule 加 edit_time / edit_by；monitor_rule_state 加 detail；条件规则字段目录 27 项（MONITOR_COND）
--
-- 上线记录：
--   ①–⑬ 已上线
--   ⑭–㉔ 尚未上线
-- ============================================================================


-- ----------------------------------------------------------------------------
-- ⑭ 2026-11-13 · 订阅管理 · 下一期（V0.4 原型「訂閱規則｜發送歷史」，设计文档《订阅简报 · 设计文档》）
--   新 JAR 启动前执行（列表、定时任务会读新列）。
--   1. dashboard_subscription：
--      - 发送频率 freq_type（DAY / WEEK / HOURS）、freq_dow（週几 1–7）、freq_every（N 1–12）、freq_to（「到」整点）；
--        send_hour 仍是每日、每週的发送整点，以及每 N 小時的「從」；
--      - 视图（发起时营运总览的选择，建立后不可改）：view_gran、view_cmp（PREV / CUSTOM）、view_from / view_to（固定日期）、
--        view_cmp_from / view_cmp_to（自訂对比）；range_code 增加 CUSTOM（固定日期）；
--      - 负责人 owner_user_id、来源页面 source_page；last_status 增加 SKIPPED（已跳過）。
--   2. 新表 dashboard_subscription_run：一次定时 / 立即发送一行（测试发送不记录），逐群结果在 log（run_id）。
--   3. dashboard_subscription_log 加 run_id、result（OK / PART / FAIL / SKIP）、reason_code（原因代码）、attempts（含自动重试的次数）。
--   4. 旧订阅不迁移（2026-11-16 确认）：先整表备份到 dashboard_subscription_bak_20261116 / dashboard_subscription_log_bak_20261116，
--      再删掉全部订阅与发送记录，上线后在营运总览重新建立。只在第一次执行时删（备份表已存在就跳过），重复执行不会删掉新建的订阅。
--      执行前先看下面「删除前清单」。
--   5. 菜单：订阅管理 dashboard:subscription:list，按钮 add / edit / remove / admin（管理员：可修改任何人的订阅、接管），授给 role 1。
--   回滚：
--     DELETE FROM dashboard_subscription_log; DELETE FROM dashboard_subscription;   -- 先删上线后新建的
--     INSERT INTO dashboard_subscription     SELECT * FROM dashboard_subscription_bak_20261116;       -- 新列在末尾，取默认值
--     INSERT INTO dashboard_subscription_log SELECT * FROM dashboard_subscription_log_bak_20261116;
--     DROP TABLE IF EXISTS dashboard_subscription_run;
--     （新加的列不影响旧 JAR，可以保留；range_code = 'CUSTOM' 的订阅在旧 JAR 下会取数失败，回滚前先暂停）
-- ----------------------------------------------------------------------------

-- ⑭ 删除前清单：现有订阅（id、站点、名称、建立人、是否启用）与发送记录数
SELECT id, site_code, name, create_by, enabled FROM dashboard_subscription WHERE del_flag = '0' ORDER BY site_code, id;
SELECT count(*) AS log_rows FROM dashboard_subscription_log;

BEGIN;

-- 旧订阅：整表备份后删除（备份表已存在 = 已经删过，跳过）
DO $$
BEGIN
    IF to_regclass('dashboard_subscription_bak_20261116') IS NULL THEN
        CREATE TABLE dashboard_subscription_bak_20261116     AS SELECT * FROM dashboard_subscription;
        CREATE TABLE dashboard_subscription_log_bak_20261116 AS SELECT * FROM dashboard_subscription_log;
        DELETE FROM dashboard_subscription_log;
        DELETE FROM dashboard_subscription;
    END IF;
END $$;

ALTER TABLE dashboard_subscription ADD COLUMN IF NOT EXISTS freq_type     VARCHAR(8)  NOT NULL DEFAULT 'DAY';
ALTER TABLE dashboard_subscription ADD COLUMN IF NOT EXISTS freq_dow      SMALLINT;
ALTER TABLE dashboard_subscription ADD COLUMN IF NOT EXISTS freq_every    SMALLINT;
ALTER TABLE dashboard_subscription ADD COLUMN IF NOT EXISTS freq_to       SMALLINT;
ALTER TABLE dashboard_subscription ADD COLUMN IF NOT EXISTS view_gran     VARCHAR(8);
ALTER TABLE dashboard_subscription ADD COLUMN IF NOT EXISTS view_cmp      VARCHAR(8)  NOT NULL DEFAULT 'PREV';
ALTER TABLE dashboard_subscription ADD COLUMN IF NOT EXISTS view_from     DATE;
ALTER TABLE dashboard_subscription ADD COLUMN IF NOT EXISTS view_to       DATE;
ALTER TABLE dashboard_subscription ADD COLUMN IF NOT EXISTS view_cmp_from DATE;
ALTER TABLE dashboard_subscription ADD COLUMN IF NOT EXISTS view_cmp_to   DATE;
ALTER TABLE dashboard_subscription ADD COLUMN IF NOT EXISTS source_page   VARCHAR(16) NOT NULL DEFAULT 'ov';
ALTER TABLE dashboard_subscription ADD COLUMN IF NOT EXISTS owner_user_id BIGINT;

ALTER TABLE dashboard_subscription DROP CONSTRAINT IF EXISTS ck_dashboard_subscription_range;
ALTER TABLE dashboard_subscription ADD CONSTRAINT ck_dashboard_subscription_range
    CHECK (range_code IN ('TODAY', 'YESTERDAY', 'LAST_7D', 'LAST_30D', 'CUSTOM'));
ALTER TABLE dashboard_subscription DROP CONSTRAINT IF EXISTS ck_dashboard_subscription_status;
ALTER TABLE dashboard_subscription ADD CONSTRAINT ck_dashboard_subscription_status
    CHECK (last_status IS NULL OR last_status IN ('SUCCESS', 'PARTIAL', 'FAILED', 'SKIPPED'));
ALTER TABLE dashboard_subscription DROP CONSTRAINT IF EXISTS ck_dashboard_subscription_freq;
ALTER TABLE dashboard_subscription ADD CONSTRAINT ck_dashboard_subscription_freq CHECK (
    freq_type IN ('DAY', 'WEEK', 'HOURS')
    AND (freq_dow IS NULL OR freq_dow BETWEEN 1 AND 7)
    AND (freq_every IS NULL OR freq_every BETWEEN 1 AND 12)
    AND (freq_to IS NULL OR freq_to BETWEEN 0 AND 23));

COMMENT ON COLUMN dashboard_subscription.freq_type     IS '发送频率：DAY 每日 / WEEK 每週 / HOURS 每 N 小時；send_hour 为每日、每週的整点，每 N 小時的「從」';
COMMENT ON COLUMN dashboard_subscription.freq_dow      IS '每週：週几（1=週一 … 7=週日）';
COMMENT ON COLUMN dashboard_subscription.freq_every    IS '每 N 小時：N（1–12）';
COMMENT ON COLUMN dashboard_subscription.freq_to       IS '每 N 小時：「到」的整点（含）';
COMMENT ON COLUMN dashboard_subscription.view_gran     IS '视图粒度 HOUR / DAY / WEEK（发起时营运总览的选择，建立后不可改）；为空按统计范围';
COMMENT ON COLUMN dashboard_subscription.view_cmp      IS '视图对比：PREV 上一週期 / CUSTOM 自訂（view_cmp_from – view_cmp_to）';
COMMENT ON COLUMN dashboard_subscription.view_from     IS '固定日期（range_code = CUSTOM）的开始日';
COMMENT ON COLUMN dashboard_subscription.view_to       IS '固定日期的结束日（含）';
COMMENT ON COLUMN dashboard_subscription.source_page   IS '来源页面，本期只有 ov（营运总览）';
COMMENT ON COLUMN dashboard_subscription.owner_user_id IS '负责人（sys_user.user_id）：默认建立人，可转交；只有负责人和管理员能修改';

-- 推送批次
CREATE TABLE IF NOT EXISTS dashboard_subscription_run (
    id              BIGSERIAL     PRIMARY KEY,
    subscription_id BIGINT        NOT NULL,
    site_code       VARCHAR(32)   NOT NULL,
    trigger_type    VARCHAR(16)   NOT NULL,
    plan_at         TIMESTAMP,
    sent_at         TIMESTAMP,
    period          VARCHAR(64),
    compare_period  VARCHAR(64),
    result          VARCHAR(8)    NOT NULL DEFAULT 'RUNNING',
    reason_code     VARCHAR(16),
    error           VARCHAR(500),
    receiver_total  INT           NOT NULL DEFAULT 0,
    sent            INT           NOT NULL DEFAULT 0,
    failed          INT           NOT NULL DEFAULT 0,
    ai_status       VARCHAR(8),
    ai_error        VARCHAR(200),
    snapshot_status VARCHAR(8),
    snapshot_error  VARCHAR(500),
    operator        VARCHAR(64),
    started_at      TIMESTAMP     DEFAULT localtimestamp,
    finished_at     TIMESTAMP,
    CONSTRAINT ck_dashboard_subscription_run_trigger CHECK (trigger_type IN ('SCHEDULE', 'MANUAL')),
    CONSTRAINT ck_dashboard_subscription_run_result CHECK (result IN ('RUNNING', 'OK', 'PART', 'FAIL', 'SKIP'))
);
CREATE INDEX IF NOT EXISTS idx_dashboard_subscription_run_sub  ON dashboard_subscription_run (subscription_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_dashboard_subscription_run_site ON dashboard_subscription_run (site_code, sent_at DESC);
CREATE INDEX IF NOT EXISTS idx_dashboard_subscription_run_running ON dashboard_subscription_run (started_at) WHERE result = 'RUNNING';

COMMENT ON TABLE  dashboard_subscription_run                 IS '订阅推送批次：一次定时 / 立即发送一行（测试发送不记录），逐群结果在 dashboard_subscription_log.run_id；不限保留期';
COMMENT ON COLUMN dashboard_subscription_run.plan_at         IS '排程时刻（站点时区）；立即发送为点击时的整点';
COMMENT ON COLUMN dashboard_subscription_run.sent_at         IS '实际发送时间（站点时区）；已跳過写排程时刻';
COMMENT ON COLUMN dashboard_subscription_run.result          IS 'RUNNING 发送中 / OK 成功 / PART 部分失敗 / FAIL 失敗 / SKIP 已跳過；各群结果相同即该结果，否则 PART';
COMMENT ON COLUMN dashboard_subscription_run.reason_code     IS '整次失败的原因代码：data 取数失败 / shot 截图失败（本次所有群都未发送）';
COMMENT ON COLUMN dashboard_subscription_run.started_at      IS '开始时间（服务器时间）；超过 10 分钟仍是 RUNNING 由定时任务记为失败（發送中斷）';

ALTER TABLE dashboard_subscription_log ADD COLUMN IF NOT EXISTS run_id      BIGINT;
ALTER TABLE dashboard_subscription_log ADD COLUMN IF NOT EXISTS result      VARCHAR(8);
ALTER TABLE dashboard_subscription_log ADD COLUMN IF NOT EXISTS reason_code VARCHAR(16);
ALTER TABLE dashboard_subscription_log ADD COLUMN IF NOT EXISTS attempts    SMALLINT;
CREATE INDEX IF NOT EXISTS idx_dashboard_subscription_log_run ON dashboard_subscription_log (run_id);
CREATE INDEX IF NOT EXISTS idx_dashboard_subscription_log_receiver ON dashboard_subscription_log (receiver_id) WHERE run_id IS NOT NULL;
COMMENT ON COLUMN dashboard_subscription_log.run_id      IS '推送批次（dashboard_subscription_run.id）；2026-11-13 之前的记录为空，不进发送历史';
COMMENT ON COLUMN dashboard_subscription_log.result      IS 'OK 成功 / PART 部分失敗 / FAIL 失敗 / SKIP 已跳過';
COMMENT ON COLUMN dashboard_subscription_log.reason_code IS '原因代码：gone / moved / timeout / limit / server / rejected / data / shot / img / card / cred / off';
COMMENT ON COLUMN dashboard_subscription_log.attempts    IS '调用 Lark 的次数（超时、限流、5xx 自动重试 2 次）';

-- 菜单：订阅管理（站点页面）+ 按钮
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '订阅管理', 0, 60, 'subscription', 'dashboard/subscription/index', '', '',
       1, 0, 'C', '0', '0', 'dashboard:subscription:list', 'email', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '订阅规则与发送历史；在营运总览点「订阅」新增'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'dashboard:subscription:list');

-- 一条 INSERT 里 MAX(menu_id) + 1 只算一次，所以按钮分开插
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, "query", route_name, is_frame, is_cache,
                      menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '新增订阅', p.menu_id, 1, '', '', '', '', 1, 0,
       'F', '0', '0', 'dashboard:subscription:add', '#', 'admin', CURRENT_TIMESTAMP, '', NULL, '在营运总览点「订阅」新增'
FROM sys_menu p WHERE p.perms = 'dashboard:subscription:list'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'dashboard:subscription:add');
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, "query", route_name, is_frame, is_cache,
                      menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '修改订阅', p.menu_id, 2, '', '', '', '', 1, 0,
       'F', '0', '0', 'dashboard:subscription:edit', '#', 'admin', CURRENT_TIMESTAMP, '', NULL, '修改、启停、立即发送、测试发送（仅负责人与管理员）'
FROM sys_menu p WHERE p.perms = 'dashboard:subscription:list'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'dashboard:subscription:edit');
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, "query", route_name, is_frame, is_cache,
                      menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '删除订阅', p.menu_id, 3, '', '', '', '', 1, 0,
       'F', '0', '0', 'dashboard:subscription:remove', '#', 'admin', CURRENT_TIMESTAMP, '', NULL, '删除后不再发送，发送历史保留'
FROM sys_menu p WHERE p.perms = 'dashboard:subscription:list'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'dashboard:subscription:remove');
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, "query", route_name, is_frame, is_cache,
                      menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '订阅管理员', p.menu_id, 4, '', '', '', '', 1, 0,
       'F', '0', '0', 'dashboard:subscription:admin', '#', 'admin', CURRENT_TIMESTAMP, '', NULL, '可修改任何人的订阅、接管负责人'
FROM sys_menu p WHERE p.perms = 'dashboard:subscription:list'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'dashboard:subscription:admin');

INSERT INTO sys_menu_i18n (menu_id, locale, menu_name, remark)
SELECT m.menu_id, v.locale, v.menu_name, v.remark
FROM sys_menu m
JOIN (VALUES
    ('dashboard:subscription:list',   'zh-CN', '订阅管理',   '订阅规则与发送历史'),
    ('dashboard:subscription:list',   'zh-TW', '訂閱管理',   '訂閱規則與發送歷史'),
    ('dashboard:subscription:list',   'en-US', 'Subscriptions', 'Subscription rules and send history'),
    ('dashboard:subscription:add',    'zh-CN', '新增订阅',   '在营运总览点「订阅」新增'),
    ('dashboard:subscription:add',    'zh-TW', '新增訂閱',   '在營運總覽點「訂閱」新增'),
    ('dashboard:subscription:add',    'en-US', 'Add Subscription', 'Subscribe from the overview page'),
    ('dashboard:subscription:edit',   'zh-CN', '修改订阅',   '修改、启停、立即发送、测试发送'),
    ('dashboard:subscription:edit',   'zh-TW', '修改訂閱',   '修改、啟停、立即發送、測試發送'),
    ('dashboard:subscription:edit',   'en-US', 'Edit Subscription', 'Edit, pause, send now, test send'),
    ('dashboard:subscription:remove', 'zh-CN', '删除订阅',   '删除后不再发送'),
    ('dashboard:subscription:remove', 'zh-TW', '刪除訂閱',   '刪除後不再發送'),
    ('dashboard:subscription:remove', 'en-US', 'Delete Subscription', 'Stop sending; history is kept'),
    ('dashboard:subscription:admin',  'zh-CN', '订阅管理员', '可修改任何人的订阅、接管负责人'),
    ('dashboard:subscription:admin',  'zh-TW', '訂閱管理員', '可修改任何人的訂閱、接管負責人'),
    ('dashboard:subscription:admin',  'en-US', 'Subscription Admin', 'Edit any subscription, take over ownership')
) AS v(perms, locale, menu_name, remark) ON v.perms = m.perms
WHERE NOT EXISTS (SELECT 1 FROM sys_menu_i18n i WHERE i.menu_id = m.menu_id AND i.locale = v.locale);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id FROM sys_menu m
WHERE m.perms LIKE 'dashboard:subscription:%'
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.role_id = 1)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id);

COMMIT;

-- ⑭ 自检：订阅表新列 12 个；批次表已建；log 新列 4 个；备份表已建；订阅已清空（上线后新建的除外）；菜单 5 行
SELECT count(*) AS new_columns FROM information_schema.columns
 WHERE table_name = 'dashboard_subscription'
   AND column_name IN ('freq_type', 'freq_dow', 'freq_every', 'freq_to', 'view_gran', 'view_cmp', 'view_from', 'view_to',
                       'view_cmp_from', 'view_cmp_to', 'source_page', 'owner_user_id');
SELECT to_regclass('dashboard_subscription_run') AS run_table;
SELECT count(*) AS log_columns FROM information_schema.columns
 WHERE table_name = 'dashboard_subscription_log' AND column_name IN ('run_id', 'result', 'reason_code', 'attempts');
SELECT (SELECT count(*) FROM dashboard_subscription_bak_20261116) AS backup_subs,
       (SELECT count(*) FROM dashboard_subscription_log_bak_20261116) AS backup_logs,
       (SELECT count(*) FROM dashboard_subscription) AS subs_now;
SELECT menu_id, menu_name, parent_id, menu_type, perms FROM sys_menu WHERE perms LIKE 'dashboard:subscription:%' ORDER BY menu_id;


-- ----------------------------------------------------------------------------
-- ⑮ 2026-11-13 · 监控 · 按规则发告警（外部判定服务用；新 JAR 启动前后执行都可以）
--   接口：POST /monitor/rules/{ruleId}/alert，body { "message": "…" }；把消息发到规则配置的 Lark 群（开头自动加「【站点名】」）。
--   认证：请求头 X-Api-Key（security.api-key.paths 改为 /monitor/rules/*/alert）或登录 token；站点取请求头 X-Site-Code。
--   1. 告警规则菜单下加按钮「按规则发告警」monitor:rule:alert，授给 role 1；
--   2. 已有 API Key 的接口范围由 /notification/lark/send 换成 /monitor/rules/*/alert（/notification/lark/send 不再认 API Key，登录后仍可用）。
--      Key 绑定的账号还要有 monitor:rule:alert，并且在要发的站点有角色：在「角色管理」里给它所在的角色勾上（本段不改其他角色）。
--   回滚：UPDATE sys_api_key SET allowed_paths = '/notification/lark/send' WHERE allowed_paths = '/monitor/rules/*/alert';
--         DELETE FROM sys_role_menu / sys_menu_i18n / sys_menu WHERE perms = 'monitor:rule:alert' 对应的 menu_id
-- ----------------------------------------------------------------------------
BEGIN;

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '按规则发告警', p.parent_id, 9, '', '', '', '',
       1, 0, 'F', '0', '0', 'monitor:rule:alert', '#', 'admin',
       CURRENT_TIMESTAMP, '', NULL, 'POST /monitor/rules/{ruleId}/alert：把消息发到规则配置的 Lark 群（外部判定服务用，支持 API Key）'
FROM sys_menu p
WHERE p.perms = 'monitor:rule:list'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'monitor:rule:alert');

INSERT INTO sys_menu_i18n (menu_id, locale, menu_name, remark)
SELECT m.menu_id, v.locale, v.menu_name, v.remark
FROM sys_menu m
         JOIN (VALUES
                   ('zh-CN', '按规则发告警', '把消息发到规则配置的 Lark 群'),
                   ('zh-TW', '按規則發告警', '把訊息發到規則配置的 Lark 群'),
                   ('en-US', 'Send Rule Alert', 'Send a message to the rule''s Lark chat')
) AS v(locale, menu_name, remark) ON TRUE
WHERE m.perms = 'monitor:rule:alert'
  AND NOT EXISTS (SELECT 1 FROM sys_menu_i18n i WHERE i.menu_id = m.menu_id AND i.locale = v.locale);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id
FROM sys_menu m
WHERE m.perms = 'monitor:rule:alert'
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.role_id = 1)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id);

UPDATE sys_api_key
SET allowed_paths = '/monitor/rules/*/alert', update_time = CURRENT_TIMESTAMP
WHERE allowed_paths = '/notification/lark/send';

COMMIT;

-- ⑮ 自检：按钮 1 行；没有 Key 还停在 /notification/lark/send
SELECT menu_id, menu_name, parent_id, perms FROM sys_menu WHERE perms = 'monitor:rule:alert';
SELECT count(*) AS still_lark_send FROM sys_api_key WHERE allowed_paths LIKE '%/notification/lark/send%';


-- ----------------------------------------------------------------------------
-- ⑯ 2026-11-13 · 指标管理 · 所属范围（新 JAR 启动前执行：指标管理列表会读这一列）
--   dashboard_metric_card.page_scope：指标用在哪个页面——OVERVIEW 运营总览 / PROMO 推广分析 / RISK 风控分析；
--   为空（NULL）= 适用于所有页面。在「系统管理 · 指标管理」里编辑。已有的 promo 前缀指标（推广分析概览，见 ⑤）填 PROMO，其余留空。
--   回滚：ALTER TABLE dashboard_metric_card DROP CONSTRAINT IF EXISTS ck_metric_card_page_scope;
--         ALTER TABLE dashboard_metric_card DROP COLUMN IF EXISTS page_scope;
-- ----------------------------------------------------------------------------
BEGIN;

ALTER TABLE dashboard_metric_card ADD COLUMN IF NOT EXISTS page_scope VARCHAR(16);
COMMENT ON COLUMN dashboard_metric_card.page_scope IS '所属范围：OVERVIEW 运营总览 / PROMO 推广分析 / RISK 风控分析；为空 = 适用于所有页面';

ALTER TABLE dashboard_metric_card DROP CONSTRAINT IF EXISTS ck_metric_card_page_scope;
-- 取值同 ㉒（三个页面目录 RISK_RANK / RISK_GAME / MONITOR_COND 由 ㉒ 使用；写在这里是为了整份文件重复执行时不被 ⑯ 卡住）
ALTER TABLE dashboard_metric_card ADD CONSTRAINT ck_metric_card_page_scope
    CHECK (page_scope IS NULL OR page_scope IN ('OVERVIEW', 'PROMO', 'RISK', 'RISK_RANK', 'RISK_GAME', 'MONITOR_COND'));

UPDATE dashboard_metric_card
SET page_scope = 'PROMO', update_time = CURRENT_TIMESTAMP
WHERE metric_code LIKE 'promo%' AND page_scope IS NULL;

COMMIT;

-- ⑯ 自检：各范围的指标数（空 = 所有页面）
SELECT COALESCE(page_scope, '（所有页面）') AS page_scope, count(*) FROM dashboard_metric_card GROUP BY page_scope ORDER BY 1;


-- ----------------------------------------------------------------------------
-- ⑰ 2026-11-13 · 菜单分组：站点板块 / 游戏板块（新 JAR 启动前执行：菜单查询会读这一列）
--   sys_menu.group_code：site 站点板块（默认，现有全部菜单）/ game 游戏板块（老板视角）。
--   /getRouters 的每个路由节点输出 group_code（与 component 平级），前端按它把菜单分到两个视角的侧栏。
--   游戏板块的菜单建好后把 group_code 改为 game：在「系统管理 · 菜单管理」里选「遊戲」，或
--     UPDATE sys_menu SET group_code = 'game', update_time = CURRENT_TIMESTAMP WHERE menu_id IN (…游戏板块的目录、页面与按钮…);
--   回滚：ALTER TABLE sys_menu DROP CONSTRAINT IF EXISTS ck_sys_menu_group_code;
--         ALTER TABLE sys_menu DROP COLUMN IF EXISTS group_code;
-- ----------------------------------------------------------------------------
BEGIN;

ALTER TABLE sys_menu ADD COLUMN IF NOT EXISTS group_code VARCHAR(16) NOT NULL DEFAULT 'site';
COMMENT ON COLUMN sys_menu.group_code IS '菜单分组（视角）：site 站点板块（默认）/ game 游戏板块；/getRouters 输出 group_code';

ALTER TABLE sys_menu DROP CONSTRAINT IF EXISTS ck_sys_menu_group_code;
ALTER TABLE sys_menu ADD CONSTRAINT ck_sys_menu_group_code CHECK (group_code IN ('site', 'game'));

COMMIT;

-- ⑰ 自检：现有菜单都是 site
SELECT group_code, count(*) FROM sys_menu GROUP BY group_code ORDER BY 1;


-- ----------------------------------------------------------------------------
-- ⑱ 2026-11-16 · 监控 · 订单规则（新建 / 编辑，对外查询接口 /open/monitor/order-rules）
--   新 JAR 启动前执行：规则查询会读这些列。设计文档《新一期需求分析与后端设计》5.1、5.3、第 6 节。
--   三类规则共用 monitor_rule，用 rule_type 区分：METRIC 指标规则（已有数据）/ COND 条件规则（下一步）/ ORDER 订单规则。
--   订单规则的时点、金额口径、门槛、渠道范围、每日上限存在 config（jsonb）；metric_code、judge_type、min_sample_key 为空。
--   version 取自序列 monitor_rule_version_seq：每次新建、修改、启停、删除都取新值，只增不减（事务回滚也不复用），
--   供 /open/monitor/order-rules 的 sinceVersion 增量同步（以后 Kafka 事件也用它）。
--   指标规则原有的 CHECK（判定方式、严重线）改为只对 METRIC 生效；判定任务、运营总览、指标规则列表只读 METRIC。
--   回滚（会删掉订单规则）：
--     DELETE FROM monitor_rule WHERE rule_type <> 'METRIC';
--     ALTER TABLE monitor_rule DROP CONSTRAINT IF EXISTS ck_monitor_rule_type;
--     ALTER TABLE monitor_rule DROP CONSTRAINT IF EXISTS ck_monitor_rule_metric_cols;
--     ALTER TABLE monitor_rule DROP CONSTRAINT IF EXISTS ck_monitor_rule_order;
--     （ck_monitor_rule_judge / ck_monitor_rule_crit 按 sql/monitor_schema_postgresql.sql 原样重建，再把三列改回 NOT NULL）
--     DROP INDEX IF EXISTS idx_monitor_rule_version;
--     ALTER TABLE monitor_rule DROP COLUMN IF EXISTS version, DROP COLUMN IF EXISTS config, DROP COLUMN IF EXISTS currency,
--       DROP COLUMN IF EXISTS notify_enabled, DROP COLUMN IF EXISTS level, DROP COLUMN IF EXISTS description,
--       DROP COLUMN IF EXISTS rule_name, DROP COLUMN IF EXISTS rule_type;
--     DROP SEQUENCE IF EXISTS monitor_rule_version_seq;
-- ----------------------------------------------------------------------------
BEGIN;

ALTER TABLE monitor_rule ADD COLUMN IF NOT EXISTS rule_type      VARCHAR(16) NOT NULL DEFAULT 'METRIC';
ALTER TABLE monitor_rule ADD COLUMN IF NOT EXISTS rule_name      VARCHAR(60);
ALTER TABLE monitor_rule ADD COLUMN IF NOT EXISTS description    VARCHAR(400);
ALTER TABLE monitor_rule ADD COLUMN IF NOT EXISTS level          VARCHAR(8);
ALTER TABLE monitor_rule ADD COLUMN IF NOT EXISTS notify_enabled BOOLEAN;
ALTER TABLE monitor_rule ADD COLUMN IF NOT EXISTS currency       VARCHAR(8);
ALTER TABLE monitor_rule ADD COLUMN IF NOT EXISTS config         JSONB;

CREATE SEQUENCE IF NOT EXISTS monitor_rule_version_seq;
ALTER TABLE monitor_rule ADD COLUMN IF NOT EXISTS version BIGINT;
UPDATE monitor_rule SET version = nextval('monitor_rule_version_seq') WHERE version IS NULL;
ALTER TABLE monitor_rule ALTER COLUMN version SET DEFAULT nextval('monitor_rule_version_seq');
ALTER TABLE monitor_rule ALTER COLUMN version SET NOT NULL;
ALTER SEQUENCE monitor_rule_version_seq OWNED BY monitor_rule.version;

-- 订单 / 条件规则没有这三列
ALTER TABLE monitor_rule ALTER COLUMN metric_code    DROP NOT NULL;
ALTER TABLE monitor_rule ALTER COLUMN judge_type     DROP NOT NULL;
ALTER TABLE monitor_rule ALTER COLUMN min_sample_key DROP NOT NULL;

ALTER TABLE monitor_rule DROP CONSTRAINT IF EXISTS ck_monitor_rule_type;
ALTER TABLE monitor_rule ADD CONSTRAINT ck_monitor_rule_type CHECK (rule_type IN ('METRIC', 'COND', 'ORDER'));

ALTER TABLE monitor_rule DROP CONSTRAINT IF EXISTS ck_monitor_rule_judge;
ALTER TABLE monitor_rule ADD CONSTRAINT ck_monitor_rule_judge
    CHECK (rule_type <> 'METRIC' OR judge_type IN ('lo', 'hi', 'band', 'drop'));

ALTER TABLE monitor_rule DROP CONSTRAINT IF EXISTS ck_monitor_rule_crit;
ALTER TABLE monitor_rule ADD CONSTRAINT ck_monitor_rule_crit CHECK (
    rule_type <> 'METRIC'
        OR (judge_type IN ('lo', 'drop') AND crit_low  IS NOT NULL)
        OR (judge_type = 'hi'            AND crit_high IS NOT NULL)
        OR (judge_type = 'band'          AND crit_low  IS NOT NULL AND crit_high IS NOT NULL)
    );

ALTER TABLE monitor_rule DROP CONSTRAINT IF EXISTS ck_monitor_rule_metric_cols;
ALTER TABLE monitor_rule ADD CONSTRAINT ck_monitor_rule_metric_cols
    CHECK (rule_type <> 'METRIC' OR (metric_code IS NOT NULL AND judge_type IS NOT NULL AND min_sample_key IS NOT NULL));

ALTER TABLE monitor_rule DROP CONSTRAINT IF EXISTS ck_monitor_rule_order;
ALTER TABLE monitor_rule ADD CONSTRAINT ck_monitor_rule_order CHECK (
    rule_type <> 'ORDER'
        OR (rule_name IS NOT NULL AND level IN ('CRIT', 'WARN') AND notify_enabled IS NOT NULL
        AND currency IS NOT NULL AND config IS NOT NULL)
    );

CREATE INDEX IF NOT EXISTS idx_monitor_rule_version ON monitor_rule (site_code, rule_type, version);

COMMENT ON COLUMN monitor_rule.rule_type      IS '规则类型：METRIC 指标规则 / COND 条件规则 / ORDER 订单规则；编号 rule_no 三类共用';
COMMENT ON COLUMN monitor_rule.rule_name      IS '规则名称（条件规则、订单规则），最多 30 字；指标规则为空，显示指标名';
COMMENT ON COLUMN monitor_rule.description    IS '规则说明，最多 200 字';
COMMENT ON COLUMN monitor_rule.level          IS '等级（条件规则、订单规则）：CRIT 严重 / WARN 注意';
COMMENT ON COLUMN monitor_rule.notify_enabled IS '是否发送通知（条件规则、订单规则）；开启时 notify_chat_id 必填';
COMMENT ON COLUMN monitor_rule.currency       IS '保存时的站点币种；站点币种变了时详情提示复核门槛';
COMMENT ON COLUMN monitor_rule.config         IS '订单规则：{orderType, triggerAt, amountMode, amountThreshold, channelScope, dailyCap}';
COMMENT ON COLUMN monitor_rule.version        IS '版本号，取自 monitor_rule_version_seq，每次新建、修改、启停、删除取新值；对外查询 sinceVersion 增量同步用';

COMMIT;

-- ⑱ 可选：已有的 API Key 也能拉订单规则（发告警和拉规则用同一个 Key 时执行；绑定账号还要有 monitor:rule:query 和站点角色）
-- UPDATE sys_api_key
--    SET allowed_paths = allowed_paths || ',/open/monitor/order-rules/**', update_time = CURRENT_TIMESTAMP
--  WHERE allowed_paths LIKE '%/monitor/rules/*/alert%' AND allowed_paths NOT LIKE '%/open/monitor/order-rules%';

-- ⑱ 自检：按类型的规则数；version 都有值；新约束 5 个
SELECT rule_type, count(*), min(version), max(version) FROM monitor_rule GROUP BY rule_type ORDER BY 1;
SELECT count(*) AS no_version FROM monitor_rule WHERE version IS NULL;
SELECT conname FROM pg_constraint
WHERE conrelid = 'monitor_rule'::regclass
  AND conname IN ('ck_monitor_rule_type', 'ck_monitor_rule_judge', 'ck_monitor_rule_crit', 'ck_monitor_rule_metric_cols', 'ck_monitor_rule_order')
ORDER BY 1;


-- ----------------------------------------------------------------------------
-- ⑲ 2026-11-16 · 操作日志 · 记录查询操作（新 JAR 启动前后执行都可以）
--   数据查询接口（运营总览、指标汇总、推广分析、明细查询、监控事件与规则等）带 @Log(businessType = QUERY, isSaveResponseData = false)，
--   只记请求参数、不记返回内容。sys_oper_log.business_type 存的是 BusinessType 的序号，QUERY = 10，
--   字典 sys_oper_type 补 dict_value = 10「查询」，操作日志页才显示得出类型、能按它筛选。
--   字典有缓存：执行后在「系统管理 · 字典管理」点「刷新缓存」，或重启后生效。
--   回滚：DELETE FROM sys_dict_data WHERE dict_type = 'sys_oper_type' AND dict_value = '10';
-- ----------------------------------------------------------------------------
BEGIN;

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status,
                           create_by, create_time, update_by, update_time, remark)
SELECT 10, '查询', '10', 'sys_oper_type', '', 'info', 'N', '0',
       'admin', CURRENT_TIMESTAMP, '', NULL, '查询操作（只记请求参数，不记返回内容）'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type = 'sys_oper_type' AND dict_value = '10');

COMMIT;

-- ⑲ 自检：sys_oper_type 有 0–10 共 11 项，10 = 查询
SELECT dict_value, dict_label FROM sys_dict_data WHERE dict_type = 'sys_oper_type' ORDER BY dict_value::int;


-- ----------------------------------------------------------------------------
-- ⑳ 2026-11-16 · 监控 · 订单告警事件（新 JAR 启动前执行：订单规则调 /monitor/rules/{ruleId}/alert 时会写这张表）
--   外部判定服务判定一笔提款订单命中订单规则后，调 POST /monitor/rules/{ruleId}/alert，body 里带
--   orderNo（订单号）、amount（本笔金额）、cumCount（当日累计笔数，当日累计规则必填）、cumAmount（当日累计金额，选填）；
--   多笔合并用 orders[]。Yata 一笔订单存一行，再发到规则配置的每个群，每个群的结果记在 notify_chats 里。
--   订单审核状态、用户信息不存，「訂單告警」列表展示时实时查 UDS。
--   (site_code, rule_id, order_no) 唯一：同一笔订单重复上报不新增、不重复发送。
--   回滚：DROP TABLE IF EXISTS monitor_order_event;（会丢掉已记录的订单告警）
-- ----------------------------------------------------------------------------
BEGIN;

CREATE TABLE IF NOT EXISTS monitor_order_event (
    event_id            BIGSERIAL       PRIMARY KEY,
    site_code           VARCHAR(32)     NOT NULL,
    rule_id             BIGINT          NOT NULL,
    order_no            VARCHAR(64)     NOT NULL,
    amount              NUMERIC(18,2)   NOT NULL,
    cum_count           INTEGER,
    cum_amount          NUMERIC(18,2),
    trigger_time        TIMESTAMP       NOT NULL,
    level               VARCHAR(8)      NOT NULL,
    trigger_point       VARCHAR(16),
    amount_mode         VARCHAR(16),
    currency            VARCHAR(8),
    notify_status       VARCHAR(16)     NOT NULL,
    notify_reason       VARCHAR(16),
    notify_chats        JSONB           NOT NULL DEFAULT '[]'::jsonb,
    notified_at         TIMESTAMP,
    handle_status       VARCHAR(10)     NOT NULL DEFAULT 'pending',
    handle_note         VARCHAR(500),
    handle_by           VARCHAR(64),
    handle_time         TIMESTAMP,
    report_by           VARCHAR(64),
    create_time         TIMESTAMP       NOT NULL,
    CONSTRAINT uk_monitor_order_event UNIQUE (site_code, rule_id, order_no),
    CONSTRAINT ck_monitor_order_event_amount CHECK (amount > 0 AND (cum_amount IS NULL OR cum_amount >= amount)),
    CONSTRAINT ck_monitor_order_event_cum CHECK (cum_count IS NULL OR cum_count >= 1),
    CONSTRAINT ck_monitor_order_event_notify CHECK (notify_status IN ('PENDING', 'SENT', 'PARTIAL', 'FAILED', 'LOG_ONLY')
        AND (notify_reason IS NULL OR notify_reason IN ('CAPPED', 'NOTIFY_OFF'))),
    CONSTRAINT ck_monitor_order_event_handle CHECK (handle_status IN ('pending', 'done'))
);

CREATE INDEX IF NOT EXISTS idx_monitor_order_event_time   ON monitor_order_event (site_code, trigger_time DESC);
CREATE INDEX IF NOT EXISTS idx_monitor_order_event_handle ON monitor_order_event (site_code, handle_status, trigger_time DESC);
CREATE INDEX IF NOT EXISTS idx_monitor_order_event_rule   ON monitor_order_event (site_code, rule_id, trigger_time DESC);

COMMENT ON TABLE  monitor_order_event                 IS '订单告警事件：订单规则命中的提款订单，一笔一行（外部判定服务经 /monitor/rules/{ruleId}/alert 上报）';
COMMENT ON COLUMN monitor_order_event.order_no        IS '提款订单号；(site_code, rule_id, order_no) 唯一，重复上报不新增';
COMMENT ON COLUMN monitor_order_event.amount          IS '本笔金额（规则币种）';
COMMENT ON COLUMN monitor_order_event.cum_count       IS '账号当日累计笔数（含本笔）；只有当日累计规则有';
COMMENT ON COLUMN monitor_order_event.cum_amount      IS '账号当日累计金额（含本笔）；只有当日累计规则有，选填';
COMMENT ON COLUMN monitor_order_event.trigger_time    IS '触发时间 = 收到上报的时间，按统计时区存';
COMMENT ON COLUMN monitor_order_event.level           IS '命中时规则的等级快照 CRIT / WARN；trigger_point、amount_mode、currency 同为快照';
COMMENT ON COLUMN monitor_order_event.notify_status   IS 'PENDING 发送中 / SENT 全部群已发送 / PARTIAL 部分群发送失败 / FAILED 全部失败 / LOG_ONLY 只记录';
COMMENT ON COLUMN monitor_order_event.notify_reason   IS '只记录的原因：CAPPED 判定服务按每日上限不发（send=false）/ NOTIFY_OFF 规则关闭了通知';
COMMENT ON COLUMN monitor_order_event.notify_chats    IS '每个群的发送结果 [{chatId, name, status: SENT/FAILED, messageId, error}]；同一次上报合并发送的多笔共用消息';
COMMENT ON COLUMN monitor_order_event.handle_status   IS '处理状态 pending 未处理 / done 已处理（「訂單告警」页签标记）';
COMMENT ON COLUMN monitor_order_event.report_by       IS '上报的账号（API Key 绑定的账号或登录账号）';

COMMIT;

-- ⑳ 自检：约束（主键、唯一、4 个检查）与 3 个索引
SELECT conname FROM pg_constraint WHERE conrelid = 'monitor_order_event'::regclass ORDER BY 1;
SELECT indexname FROM pg_indexes WHERE tablename = 'monitor_order_event' ORDER BY 1;


-- ----------------------------------------------------------------------------
-- ㉑ 2026-11-16 · 监控 · 规则可选多个通知群（新 JAR 启动前执行：保存规则、判定发送都会读写 notify_chats）
--   原型 2026-11-13 第五版：指标规则、条件规则、订单规则的通知群都改为多选，最多 5 个；同一条告警以相同内容分别发到每个群。
--   monitor_rule 加 notify_chats：[{"chatId":"oc_x","name":"保存时的群名"}, …]，按选择顺序；
--   notify_chat_id / notify_chat_name 继续写第一个群，旧的查询与回滚不受影响。已有规则按 notify_chat_id 回填一个群。
--   发送记录 monitor_notify_log 本来就是「一次发送 × 一个群」一行，不用改；异常事件「通知送達」按群汇总。
--   回滚：ALTER TABLE monitor_rule DROP CONSTRAINT IF EXISTS ck_monitor_rule_chats; ALTER TABLE monitor_rule DROP COLUMN IF EXISTS notify_chats;
--         （回滚后每条规则只发第一个群）
-- ----------------------------------------------------------------------------
BEGIN;

ALTER TABLE monitor_rule ADD COLUMN IF NOT EXISTS notify_chats JSONB NOT NULL DEFAULT '[]'::jsonb;

UPDATE monitor_rule
   SET notify_chats = jsonb_build_array(jsonb_build_object('chatId', notify_chat_id, 'name', notify_chat_name))
 WHERE notify_chat_id IS NOT NULL AND notify_chat_id <> ''
   AND notify_chats = '[]'::jsonb;

ALTER TABLE monitor_rule DROP CONSTRAINT IF EXISTS ck_monitor_rule_chats;
ALTER TABLE monitor_rule ADD  CONSTRAINT ck_monitor_rule_chats
    CHECK (jsonb_typeof(notify_chats) = 'array' AND jsonb_array_length(notify_chats) <= 5);

COMMENT ON COLUMN monitor_rule.notify_chats IS '通知群（最多 5 个）[{chatId, name}]，按选择顺序；notify_chat_id / notify_chat_name 同步写第一个群';

COMMIT;

-- ㉑ 自检：有群的规则都已回填；第一个群与 notify_chat_id 一致
SELECT count(*) AS not_backfilled FROM monitor_rule
 WHERE notify_chat_id IS NOT NULL AND notify_chat_id <> '' AND notify_chats = '[]'::jsonb;
SELECT count(*) AS first_mismatch FROM monitor_rule
 WHERE jsonb_array_length(notify_chats) > 0 AND notify_chats->0->>'chatId' IS DISTINCT FROM notify_chat_id;

-- ----------------------------------------------------------------------------
-- ㉒ 2026-11-16 · 风控分析（概览、排行榜、我的榜单、游戏分析）（新 JAR 启动前执行：指标管理、注册表会读 ext 与新范围）
--   1. 指标管理：dashboard_metric_card 加 ext（jsonb，页面专属属性）；所属范围加三个「页面目录」：
--      RISK_RANK 风控排行榜的列与名单条件 / RISK_GAME 游戏分析的列 / MONITOR_COND 条件规则的字段目录（不进运营总览注册表）。
--      列的名称、格式、UDS 字段、分组、聚合方式、计算公式、能否排行 / 做条件都在「指标管理」里改，代码不写死。
--   2. 风控概览（scene = RISK）：页面 RISK（dashboard_page_metric / _site 的 CHECK 放开）；新指标 提存比 wdr、RTP rtp
--      （指标管理里配公式 DIVIDE，后端按公式算，不改 yml）；15 个页面指标，CORE = 默认核心 10 个。
--      待审核笔数 / 等待时长（wPendN / wPendT）先登记（仅登记），UDS 给出字段后接入并挂到 RISK 页。
--   3. 排行榜 31 列（28 个可选列 + 3 个辅助列）、游戏分析 20 列，登记为已接入；UDS 字段名为占位（remark 标「待 UDS 确认」），
--      数据集名用环境变量 RISK_UDS_*_DATASET 配置，为空时接口返回 dataPending。
--   4. 新表 risk_my_board（我的榜单）；配置中心 RISK_RANK_THRESHOLDS / RISK_RANK_BOARDS / RISK_GAME_DICT（全站通用默认值）；
--      个人视图配置位 risk.overview.cards / risk.rank.cards / risk.rank.cols.{榜单} / risk.game.path / risk.game.cols；
--      菜单「风控分析」dashboard:risk:view + 按钮 dashboard:risk:export / dashboard:risk:cond:edit，授给 role 1。
--   回滚：DROP TABLE IF EXISTS risk_my_board;
--         DELETE FROM dashboard_view_slot_item WHERE slot_code LIKE 'risk.%'; DELETE FROM dashboard_view_slot WHERE slot_code LIKE 'risk.%';
--         DELETE FROM dashboard_page_metric WHERE page_code = 'RISK'; DELETE FROM dashboard_page_metric_site WHERE page_code = 'RISK';
--         DELETE FROM dashboard_metric_card WHERE page_scope IN ('RISK_RANK', 'RISK_GAME') OR metric_code IN ('wdr', 'rtp', 'wPendN', 'wPendT');
--         DELETE FROM config_center WHERE config_code IN ('RISK_RANK_THRESHOLDS', 'RISK_RANK_BOARDS', 'RISK_GAME_DICT');
--         DELETE FROM sys_role_menu / sys_menu_i18n / sys_menu WHERE perms LIKE 'dashboard:risk:%'（及目录 path = 'risk'）
--         （ext 列与 CHECK 可以保留，不影响旧 JAR）
-- ----------------------------------------------------------------------------
BEGIN;

-- 1. 指标管理：页面专属属性 + 所属范围
ALTER TABLE dashboard_metric_card ADD COLUMN IF NOT EXISTS ext JSONB;
COMMENT ON COLUMN dashboard_metric_card.ext IS '页面专属属性（RISK_RANK / RISK_GAME / MONITOR_COND）：接口编码、分组、聚合方式、计算公式、能否排行 / 做条件、支持的维度等；指标管理里编辑';
ALTER TABLE dashboard_metric_card DROP CONSTRAINT IF EXISTS ck_metric_card_page_scope;
ALTER TABLE dashboard_metric_card ADD CONSTRAINT ck_metric_card_page_scope
    CHECK (page_scope IS NULL OR page_scope IN ('OVERVIEW', 'PROMO', 'RISK', 'RISK_RANK', 'RISK_GAME', 'MONITOR_COND'));
ALTER TABLE dashboard_metric_card DROP CONSTRAINT IF EXISTS ck_metric_card_ext;
ALTER TABLE dashboard_metric_card ADD CONSTRAINT ck_metric_card_ext
    CHECK (ext IS NULL OR (jsonb_typeof(ext) = 'object' AND coalesce(page_scope, '') IN ('RISK_RANK', 'RISK_GAME', 'MONITOR_COND')));
COMMENT ON COLUMN dashboard_metric_card.page_scope IS '所属范围：OVERVIEW 运营总览 / PROMO 推广分析 / RISK 风控分析；页面目录 RISK_RANK 风控排行榜 / RISK_GAME 游戏分析 / MONITOR_COND 条件规则；为空 = 适用于所有页面';

-- 2. 页面 RISK
ALTER TABLE dashboard_page_metric DROP CONSTRAINT IF EXISTS ck_page_code;
ALTER TABLE dashboard_page_metric ADD CONSTRAINT ck_page_code CHECK (page_code IN ('OVERVIEW','SUMMARY','PROMO','RISK'));
ALTER TABLE dashboard_page_metric DROP CONSTRAINT IF EXISTS ck_page_attrs;
ALTER TABLE dashboard_page_metric ADD CONSTRAINT ck_page_attrs CHECK (
    (page_code = 'OVERVIEW' AND is_default = TRUE)
    OR (page_code = 'SUMMARY' AND emphasis = 'NORMAL')
    OR page_code IN ('PROMO', 'RISK')
);
COMMENT ON COLUMN dashboard_page_metric.page_code IS 'OVERVIEW 运营总览指标墙 / SUMMARY 指标汇总表 / PROMO 推广分析概览 / RISK 风控分析概览（emphasis = CORE 为默认核心指标）';
DO $$
BEGIN
    IF to_regclass('dashboard_page_metric_site') IS NOT NULL THEN
        ALTER TABLE dashboard_page_metric_site DROP CONSTRAINT IF EXISTS ck_page_metric_site_page;
        ALTER TABLE dashboard_page_metric_site ADD CONSTRAINT ck_page_metric_site_page CHECK (page_code IN ('OVERVIEW', 'SUMMARY', 'RISK'));
    END IF;
END $$;

INSERT INTO dashboard_metric_group (group_code, group_name, group_name_en, sort_no, remark) VALUES
('risk',     '風控排行榜', 'Risk ranking',  210, '风控分析 · 排行榜的列与名单条件（page_scope = RISK_RANK）'),
('riskGame', '遊戲分析',   'Game analysis', 220, '风控分析 · 游戏分析的列（page_scope = RISK_GAME）'),
('cond',     '條件規則',   'Rule fields',   230, '监控 · 条件规则的字段目录（page_scope = MONITOR_COND）')
ON CONFLICT (group_code) DO NOTHING;

-- 风控概览的新指标：提存比、RTP 用公式（后端按 calc_type 计算，不改 yml）；待审核两项先登记
INSERT INTO dashboard_metric_card (
    metric_code, metric_name, metric_name_en, group_code, update_frequency, single_day_only, chart_type,
    value_format, value_decimals, direction, show_as_card, sort_no, calc_type, left_code, right_code, agg_type,
    monitorable, allow_negative, attributable, page_scope, status, create_by, create_time, remark
)
SELECT v.* FROM (VALUES
('wdr',    '提存比',             'Withdraw / deposit', 'eg2', 'hour', FALSE, 'LINE', 'PCT', NULL::smallint, 'down', TRUE, 2210, 'DIVIDE', 'wd',  'dep', 'FORMULA',
 FALSE, FALSE, FALSE, 'RISK', '0', 'admin', CURRENT_TIMESTAMP, '提款 ÷ 存款；比率，变化归因只写说明，去处：排行榜 · 提款榜'),
('rtp',    'RTP',                'RTP',                'eg4', 'hour', FALSE, 'LINE', 'PCT', NULL::smallint, 'down', TRUE, 2410, 'DIVIDE', 'pay', 'bet', 'FORMULA',
 FALSE, FALSE, FALSE, 'RISK', '0', 'admin', CURRENT_TIMESTAMP, '派彩 ÷ 有效投注；比率，变化归因只写说明，去处：游戏分析'),
('wPendN', '待審核筆數',         'Pending withdrawals', 'eg3', 'hour', FALSE, 'LINE', 'INT', NULL::smallint, 'down', TRUE, 2310, 'NONE', NULL, NULL, 'SUM',
 FALSE, FALSE, FALSE, 'RISK', '1', 'admin', CURRENT_TIMESTAMP, '快照：时间范围结束时仍在审核中、当天提交的提款；待 UDS 提供字段后接入'),
('wPendT', '待審核平均等待時長', 'Pending wait time',   'eg3', 'hour', FALSE, 'LINE', 'MIN', NULL::smallint, 'down', TRUE, 2320, 'NONE', NULL, NULL, 'SUM',
 FALSE, FALSE, FALSE, 'RISK', '1', 'admin', CURRENT_TIMESTAMP, '快照：同上的平均等待时长；待 UDS 提供字段后接入')
) AS v(metric_code, metric_name, metric_name_en, group_code, update_frequency, single_day_only, chart_type,
       value_format, value_decimals, direction, show_as_card, sort_no, calc_type, left_code, right_code, agg_type,
       monitorable, allow_negative, attributable, page_scope, status, create_by, create_time, remark)
WHERE EXISTS (SELECT 1 FROM dashboard_metric_group g WHERE g.group_code = v.group_code)
ON CONFLICT (metric_code) DO NOTHING;

-- 存款人数 depU、提款笔数 wOk（出款成功笔数）、派彩 pay 原来只作派生指标的操作数（隐藏原子量），
-- 风控概览要单独显示，改为可展示（不影响运营总览：运营总览只显示挂在 OVERVIEW 页上的指标）。
-- 不改的话注册表启动校验会报「页面 RISK 挂了隐藏原子量」。回滚：UPDATE ... SET show_as_card = FALSE WHERE metric_code IN ('depU', 'wOk', 'pay');
UPDATE dashboard_metric_card SET show_as_card = TRUE, update_time = CURRENT_TIMESTAMP
 WHERE metric_code IN ('depU', 'wOk', 'pay') AND show_as_card = FALSE;

-- 风控概览页：15 个指标（复用运营总览已有的指标），CORE = 默认核心 10 个
INSERT INTO dashboard_page_metric (page_code, metric_code, sort_no, is_default, emphasis, create_by, remark)
SELECT 'RISK', v.code, v.sort_no, TRUE, v.emphasis, 'admin', v.remark
FROM (VALUES
    ('reg', 10, 'NORMAL', '註冊人數'), ('dep', 20, 'CORE', '存款總額'), ('depU', 30, 'NORMAL', '存款人數（只支持單日）'),
    ('ftd', 40, 'NORMAL', '首存人數'), ('wd', 50, 'CORE', '提款總額'), ('wOk', 60, 'NORMAL', '提款筆數（出款成功）'),
    ('net', 70, 'CORE', '存提差'), ('wdr', 80, 'CORE', '提存比'), ('dOkR', 90, 'CORE', '存款成功率'),
    ('bet', 100, 'CORE', '有效投注'), ('pay', 110, 'NORMAL', '派彩'), ('ggr', 120, 'CORE', 'GGR'),
    ('rtp', 130, 'CORE', 'RTP'), ('killR', 140, 'CORE', '殺率'), ('ngr', 150, 'CORE', 'NGR')
) AS v(code, sort_no, emphasis, remark)
WHERE EXISTS (SELECT 1 FROM dashboard_metric_card c WHERE c.metric_code = v.code AND c.status = '0')
ON CONFLICT (page_code, metric_code) DO NOTHING;

-- 3. 排行榜的列与名单条件字段（接口编码见 ext.apiCode；指标编码加 rk 前缀，与运营总览的指标区分）
INSERT INTO dashboard_metric_card (
    metric_code, metric_name, group_code, update_frequency, single_day_only, chart_type, value_format, direction, show_as_card, sort_no,
    calc_type, agg_type, monitorable, allow_negative, attributable, page_scope, uds_day_field, ext, status, create_by, create_time, remark
) VALUES
('rkDep', '存款', 'risk', 'day', FALSE, 'LINE', 'MONEY', 'up', TRUE, 3000, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', 'dep_amount', '{"apiCode": "dep", "group": "FUND", "source": "USER_DAY", "condition": "RANGE", "common": true}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('rkDepN', '存款筆數', 'risk', 'day', FALSE, 'LINE', 'INT', 'up', TRUE, 3010, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', 'dep_count', '{"apiCode": "depN", "group": "FUND", "source": "USER_DAY"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('rkWd', '提款', 'risk', 'day', FALSE, 'LINE', 'MONEY', 'up', TRUE, 3020, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', 'wd_amount', '{"apiCode": "wd", "group": "FUND", "source": "USER_DAY", "condition": "RANGE", "common": true}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('rkWdN', '提款筆數', 'risk', 'day', FALSE, 'LINE', 'INT', 'up', TRUE, 3030, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', 'wd_count', '{"apiCode": "wdN", "group": "FUND", "source": "USER_DAY"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('rkNet', '存提差', 'risk', 'day', FALSE, 'LINE', 'MONEY', 'down', TRUE, 3040, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', NULL, '{"apiCode": "net", "group": "FUND", "source": "CALC", "calc": {"op": "SUBTRACT", "left": "dep", "right": "wd"}, "riskDir": "asc", "condition": "RANGE"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '由其他列计算（ext.calc）'),
('rkTurnX', '打碼倍數', 'risk', 'day', FALSE, 'LINE', 'MULTIPLE', 'down', TRUE, 3050, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', NULL, '{"apiCode": "turnX", "group": "FUND", "source": "CALC", "calc": {"op": "DIVIDE", "left": "bet", "right": "dep"}, "riskDir": "asc", "condition": "RANGE", "common": true}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '由其他列计算（ext.calc）'),
('rkBonus', '優惠', 'risk', 'day', FALSE, 'LINE', 'MONEY', 'up', TRUE, 3060, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', 'bonus_amount', '{"apiCode": "bonus", "group": "FUND", "source": "USER_DAY"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('rkBonusR', '優惠佔存款比', 'risk', 'day', FALSE, 'LINE', 'PCT', 'up', TRUE, 3070, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', NULL, '{"apiCode": "bonusR", "group": "FUND", "source": "CALC", "calc": {"op": "DIVIDE", "left": "bonus", "right": "dep"}}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '由其他列计算（ext.calc）'),
('rkBet', '有效投注', 'risk', 'day', FALSE, 'LINE', 'MONEY', 'up', TRUE, 3080, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', 'bet_amount', '{"apiCode": "bet", "group": "GAME", "source": "USER_GAME", "condition": "RANGE", "common": true}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('rkBetN', '投注筆數', 'risk', 'day', FALSE, 'LINE', 'INT', 'up', TRUE, 3090, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', 'bet_count', '{"apiCode": "betN", "group": "GAME", "source": "USER_GAME"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('rkWinLoss', '玩家輸贏', 'risk', 'day', FALSE, 'LINE', 'MONEY', 'up', TRUE, 3100, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', NULL, '{"apiCode": "winLoss", "group": "GAME", "source": "CALC", "calc": {"op": "SUBTRACT", "left": "pay", "right": "bet"}, "condition": "RANGE", "common": true}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '由其他列计算（ext.calc）'),
('rkRtp', 'RTP', 'risk', 'day', FALSE, 'LINE', 'PCT', 'up', TRUE, 3110, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', NULL, '{"apiCode": "rtp", "group": "GAME", "source": "CALC", "calc": {"op": "DIVIDE", "left": "pay", "right": "bet"}, "condition": "RANGE", "common": true}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '由其他列计算（ext.calc）'),
('rkMaxPayout', '單筆最大派彩', 'risk', 'day', FALSE, 'LINE', 'MONEY', 'up', TRUE, 3120, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', 'max_payout', '{"apiCode": "maxPayout", "group": "GAME", "source": "USER_GAME", "agg": "MAX", "tieBreak": {"ratioOf": "maxPayoutBet", "timeOf": "winTime"}, "condition": "RANGE", "common": true}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('rkPay', '派彩', 'risk', 'day', FALSE, 'LINE', 'MONEY', 'up', TRUE, 3130, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', 'payout_amount', '{"apiCode": "pay", "group": "GAME", "source": "USER_GAME"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('rkProfitGame', '盈利遊戲', 'risk', 'day', FALSE, 'LINE', 'INT', 'up', TRUE, 3140, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', 'game', '{"apiCode": "profitGame", "group": "GAME", "source": "USER_GAME", "kind": "TEXT", "agg": "ARGMAX", "argOf": "gWinLoss", "positive": true, "tieBy": "bet", "condition": "SELECT"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '维度字段（risk.fields）'),
('rkWinGame', '中獎遊戲', 'risk', 'day', FALSE, 'LINE', 'INT', 'up', TRUE, 3150, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', 'game', '{"apiCode": "winGame", "group": "GAME", "source": "USER_GAME", "kind": "TEXT", "agg": "FOLLOW", "follow": "maxPayout", "condition": "SELECT"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '维度字段（risk.fields）'),
('rkWinTime', '中獎時間', 'risk', 'day', FALSE, 'LINE', 'INT', 'up', TRUE, 3160, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', 'max_payout_settle_time', '{"apiCode": "winTime", "group": "GAME", "source": "USER_GAME", "kind": "TIME", "agg": "FOLLOW", "follow": "maxPayout"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('rkMaxPayoutBet', '該筆投注額', 'risk', 'day', FALSE, 'LINE', 'MONEY', 'up', TRUE, 3170, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', 'max_payout_bet', '{"apiCode": "maxPayoutBet", "group": "GAME", "source": "USER_GAME", "agg": "FOLLOW", "follow": "maxPayout", "rankable": false}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('rkPayoutX', '派彩倍數', 'risk', 'day', FALSE, 'LINE', 'MULTIPLE', 'up', TRUE, 3180, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', NULL, '{"apiCode": "payoutX", "group": "GAME", "source": "CALC", "calc": {"op": "DIVIDE", "left": "maxPayout", "right": "maxPayoutBet"}}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '由其他列计算（ext.calc）'),
('rkChannel', '推廣渠道', 'risk', 'day', FALSE, 'LINE', 'INT', 'flat', TRUE, 3190, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', 'channelId', '{"apiCode": "channel", "group": "ACCOUNT", "source": "DIM", "kind": "TEXT", "rankable": false}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '维度字段（risk.fields）'),
('rkParent', '上級帳號', 'risk', 'day', FALSE, 'LINE', 'INT', 'flat', TRUE, 3200, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', 'parent', '{"apiCode": "parent", "group": "ACCOUNT", "source": "DIM", "kind": "TEXT", "rankable": false, "condition": "SELECT"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '维度字段（risk.fields）'),
('rkRegTime', '註冊時間', 'risk', 'day', FALSE, 'LINE', 'INT', 'flat', TRUE, 3210, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', 'regTime', '{"apiCode": "regTime", "group": "ACCOUNT", "source": "DIM", "kind": "TIME", "rankable": true, "riskDir": "desc"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '维度字段（risk.fields）'),
('rkFirstDepTime', '首存時間', 'risk', 'day', FALSE, 'LINE', 'INT', 'flat', TRUE, 3220, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', 'first_dep_time', '{"apiCode": "firstDepTime", "group": "HISTORY", "source": "SNAPSHOT", "kind": "TIME", "rankable": true, "riskDir": "desc"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('rkFirstDepAmt', '首存金額', 'risk', 'day', FALSE, 'LINE', 'MONEY', 'up', TRUE, 3230, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', 'first_dep_amount', '{"apiCode": "firstDepAmt", "group": "HISTORY", "source": "SNAPSHOT", "agg": "LAST"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('rkHistDep', '累計存款', 'risk', 'day', FALSE, 'LINE', 'MONEY', 'up', TRUE, 3240, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', 'hist_dep_amount', '{"apiCode": "histDep", "group": "HISTORY", "source": "SNAPSHOT", "agg": "LAST"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('rkHistWd', '累計提款', 'risk', 'day', FALSE, 'LINE', 'MONEY', 'up', TRUE, 3250, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', 'hist_wd_amount', '{"apiCode": "histWd", "group": "HISTORY", "source": "SNAPSHOT", "agg": "LAST"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('rkHistNet', '累計存提差', 'risk', 'day', FALSE, 'LINE', 'MONEY', 'down', TRUE, 3260, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', NULL, '{"apiCode": "histNet", "group": "HISTORY", "source": "CALC", "calc": {"op": "SUBTRACT", "left": "histDep", "right": "histWd"}, "riskDir": "asc"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '由其他列计算（ext.calc）'),
('rkHistWinLoss', '累計玩家輸贏', 'risk', 'day', FALSE, 'LINE', 'MONEY', 'up', TRUE, 3270, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', 'hist_win_loss', '{"apiCode": "histWinLoss", "group": "HISTORY", "source": "SNAPSHOT", "agg": "LAST"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('rkGWinLoss', '遊戲輸贏（按遊戲匯總用）', 'risk', 'day', FALSE, 'LINE', 'MONEY', 'up', TRUE, 3280, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', 'win_loss', '{"apiCode": "gWinLoss", "group": "GAME", "source": "USER_GAME", "column": false, "rankable": false}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('rkTopGame', '投注最多的遊戲', 'risk', 'day', FALSE, 'LINE', 'INT', 'flat', TRUE, 3290, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', 'game', '{"apiCode": "topGame", "group": "GAME", "source": "USER_GAME", "kind": "TEXT", "agg": "ARGMAX", "argOf": "bet", "column": false, "rankable": false}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '维度字段（risk.fields）'),
('rkRegDays', '註冊天數', 'risk', 'day', FALSE, 'LINE', 'INT', 'flat', TRUE, 3300, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_RANK', NULL, '{"apiCode": "regDays", "group": "ACCOUNT", "source": "CALC", "calc": {"op": "DAYS_SINCE", "left": "regTime"}, "riskDir": "asc", "condition": "RANGE", "common": true, "column": false}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '由其他列计算（ext.calc）')
ON CONFLICT (metric_code) DO NOTHING;

-- 游戏分析的 20 列（ext.standard = 默认「标准」8 列）
INSERT INTO dashboard_metric_card (
    metric_code, metric_name, group_code, update_frequency, single_day_only, chart_type, value_format, direction, show_as_card, sort_no,
    calc_type, agg_type, monitorable, allow_negative, attributable, page_scope, uds_day_field, ext, status, create_by, create_time, remark
) VALUES
('gmBet', '有效投注', 'riskGame', 'day', FALSE, 'LINE', 'MONEY', 'up', TRUE, 4000, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_GAME', 'bet_amount', '{"apiCode": "bet", "group": "BET", "standard": true}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('gmBetN', '注單數', 'riskGame', 'day', FALSE, 'LINE', 'INT', 'up', TRUE, 4010, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_GAME', 'bet_count', '{"apiCode": "betN", "group": "BET"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('gmBetU', '投注人數', 'riskGame', 'day', FALSE, 'LINE', 'INT', 'up', TRUE, 4020, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_GAME', 'bet_users', '{"apiCode": "betU", "group": "BET", "standard": true}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('gmPay', '派彩金額', 'riskGame', 'day', FALSE, 'LINE', 'MONEY', 'up', TRUE, 4030, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_GAME', 'payout_amount', '{"apiCode": "pay", "group": "WINLOSS", "standard": true}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('gmGgr', 'GGR', 'riskGame', 'day', FALSE, 'LINE', 'MONEY', 'up', TRUE, 4040, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_GAME', 'ggr', '{"apiCode": "ggr", "group": "WINLOSS", "standard": true}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('gmWinLoss', '會員輸贏', 'riskGame', 'day', FALSE, 'LINE', 'MONEY', 'up', TRUE, 4050, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_GAME', NULL, '{"apiCode": "winLoss", "group": "WINLOSS", "calc": {"op": "SUBTRACT", "left": "pay", "right": "bet"}}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '由其他列计算（ext.calc）'),
('gmRtp', 'RTP', 'riskGame', 'day', FALSE, 'LINE', 'PCT', 'down', TRUE, 4060, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_GAME', NULL, '{"apiCode": "rtp", "group": "WINLOSS", "standard": true, "calc": {"op": "DIVIDE", "left": "pay", "right": "bet"}}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '由其他列计算（ext.calc）'),
('gmKill', '殺率', 'riskGame', 'day', FALSE, 'LINE', 'PCT', 'up', TRUE, 4070, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_GAME', NULL, '{"apiCode": "kill", "group": "WINLOSS", "standard": true, "calc": {"op": "DIVIDE", "left": "ggr", "right": "bet"}}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '由其他列计算（ext.calc）'),
('gmProfitU', '盈利總人數', 'riskGame', 'day', FALSE, 'LINE', 'INT', 'flat', TRUE, 4080, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_GAME', 'profit_users', '{"apiCode": "profitU", "group": "USER"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('gmLossU', '虧損總人數', 'riskGame', 'day', FALSE, 'LINE', 'INT', 'flat', TRUE, 4090, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_GAME', 'loss_users', '{"apiCode": "lossU", "group": "USER"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('gmProfitUR', '盈利人數佔比', 'riskGame', 'day', FALSE, 'LINE', 'PCT', 'down', TRUE, 4100, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_GAME', NULL, '{"apiCode": "profitUR", "group": "USER", "standard": true, "calc": {"op": "DIVIDE", "left": "profitU", "right": "betU"}}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '由其他列计算（ext.calc）'),
('gmAvgWinLoss', '人均輸贏', 'riskGame', 'day', FALSE, 'LINE', 'MONEY', 'down', TRUE, 4110, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_GAME', NULL, '{"apiCode": "avgWinLoss", "group": "USER", "standard": true, "calc": {"op": "DIVIDE", "left": "winLoss", "right": "betU"}}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '由其他列计算（ext.calc）'),
('gmCashBet', '本金投注', 'riskGame', 'day', FALSE, 'LINE', 'MONEY', 'up', TRUE, 4120, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_GAME', 'cash_bet_amount', '{"apiCode": "cashBet", "group": "SPLIT"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('gmCashPay', '本金派彩', 'riskGame', 'day', FALSE, 'LINE', 'MONEY', 'up', TRUE, 4130, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_GAME', 'cash_payout_amount', '{"apiCode": "cashPay", "group": "SPLIT"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('gmCashWinLoss', '本金輸贏', 'riskGame', 'day', FALSE, 'LINE', 'MONEY', 'up', TRUE, 4140, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_GAME', NULL, '{"apiCode": "cashWinLoss", "group": "SPLIT", "calc": {"op": "SUBTRACT", "left": "cashPay", "right": "cashBet"}}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '由其他列计算（ext.calc）'),
('gmCashRtp', '本金 RTP', 'riskGame', 'day', FALSE, 'LINE', 'PCT', 'down', TRUE, 4150, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_GAME', NULL, '{"apiCode": "cashRtp", "group": "SPLIT", "calc": {"op": "DIVIDE", "left": "cashPay", "right": "cashBet"}}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '由其他列计算（ext.calc）'),
('gmBonusBet', '優惠投注', 'riskGame', 'day', FALSE, 'LINE', 'MONEY', 'up', TRUE, 4160, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_GAME', 'bonus_bet_amount', '{"apiCode": "bonusBet", "group": "SPLIT"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('gmBonusPay', '優惠派彩', 'riskGame', 'day', FALSE, 'LINE', 'MONEY', 'up', TRUE, 4170, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_GAME', 'bonus_payout_amount', '{"apiCode": "bonusPay", "group": "SPLIT"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('gmBonusWinLoss', '優惠輸贏', 'riskGame', 'day', FALSE, 'LINE', 'MONEY', 'up', TRUE, 4180, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_GAME', NULL, '{"apiCode": "bonusWinLoss", "group": "SPLIT", "calc": {"op": "SUBTRACT", "left": "bonusPay", "right": "bonusBet"}}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '由其他列计算（ext.calc）'),
('gmBonusRtp', '優惠 RTP', 'riskGame', 'day', FALSE, 'LINE', 'PCT', 'down', TRUE, 4190, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'RISK_GAME', NULL, '{"apiCode": "bonusRtp", "group": "SPLIT", "calc": {"op": "DIVIDE", "left": "bonusPay", "right": "bonusBet"}}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '由其他列计算（ext.calc）')
ON CONFLICT (metric_code) DO NOTHING;

-- 4. 我的榜单
CREATE TABLE IF NOT EXISTS risk_my_board (
    board_id     BIGSERIAL     PRIMARY KEY,
    user_id      BIGINT        NOT NULL,
    site_code    VARCHAR(32)   NOT NULL,
    board_name   VARCHAR(16)   NOT NULL,
    conditions   JSONB         NOT NULL DEFAULT '[]'::jsonb,
    rank_col     VARCHAR(32)   NOT NULL,
    rank_dir     VARCHAR(4)    NOT NULL DEFAULT 'desc',
    order_num    INTEGER       NOT NULL DEFAULT 1,
    show_card    BOOLEAN       NOT NULL DEFAULT FALSE,
    del_flag     CHAR(1)       NOT NULL DEFAULT '0',
    create_by    VARCHAR(64)   DEFAULT '',
    create_time  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by    VARCHAR(64)   DEFAULT '',
    update_time  TIMESTAMP,
    CONSTRAINT ck_risk_my_board_dir   CHECK (rank_dir IN ('asc', 'desc')),
    CONSTRAINT ck_risk_my_board_conds CHECK (jsonb_typeof(conditions) = 'array'),
    CONSTRAINT ck_risk_my_board_del   CHECK (del_flag IN ('0', '2'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_risk_my_board_name ON risk_my_board (user_id, upper(site_code), lower(board_name)) WHERE del_flag = '0';
CREATE INDEX IF NOT EXISTS idx_risk_my_board_user ON risk_my_board (user_id, site_code, order_num) WHERE del_flag = '0';
COMMENT ON TABLE  risk_my_board IS '风控分析 · 我的榜单：名单条件加排行字段保存为一张榜，按「用户 + 站点」隔离，逻辑删除';
COMMENT ON COLUMN risk_my_board.conditions IS '名单条件（结构同 /dashboard/risk/rank/list 的 conditions），数值按保存时固定';
COMMENT ON COLUMN risk_my_board.rank_col   IS '排行字段（指标管理 RISK_RANK 列的接口编码）';
COMMENT ON COLUMN risk_my_board.show_card  IS '是否显示为卡片';
COMMENT ON COLUMN risk_my_board.del_flag   IS '0 正常 / 2 已删除';

-- 配置中心：全站通用默认值（与代码里的默认相同；站点要不同时在配置中心另加一条本站的）
INSERT INTO config_center (config_code, site_code, page_name, description, config_value, create_by, create_time)
SELECT v.code, '', '風控分析', v.description, v.value, 'admin', CURRENT_TIMESTAMP
FROM (VALUES
    ('RISK_RANK_THRESHOLDS', '排行榜的榜單條件（全站共用，只作用於榜單本身）', '{
  "values": {
    "bigOne": 50000,
    "newDays": 7,
    "winMin": 20000,
    "rtpBet": 20000,
    "lowTurn": 1
  },
  "limits": {
    "bigOne": {
      "min": 1000,
      "max": 100000000
    },
    "newDays": {
      "min": 1,
      "max": 90
    },
    "winMin": {
      "min": 100,
      "max": 100000000
    },
    "rtpBet": {
      "min": 100,
      "max": 100000000
    },
    "lowTurn": {
      "min": 0,
      "max": 100
    }
  },
  "names": {
    "bigOne": "大額提款榜 · 單筆提款 ≥",
    "newDays": "新號榜 · 註冊 ≤（天）",
    "winMin": "大額中獎榜 · 單筆派彩 ≥",
    "rtpBet": "高 RTP 榜 · 有效投注 ≥",
    "lowTurn": "低打碼提款榜 · 打碼倍數 ≤"
  }
}'),
    ('RISK_RANK_BOARDS',     '排行榜的榜單設置：入榜條件、排行字段、佔比口徑、默認列、默認卡片、佔比檔位、我的榜單上限、風險類型', '{
  "myBoardLimit": 10,
  "bands": [
    1,
    5,
    10
  ],
  "defaultCards": [
    "pnl",
    "wd",
    "new",
    "low",
    "win",
    "bigw"
  ],
  "riskTypes": [
    {
      "code": "newWin",
      "name": "新號盈利",
      "board": "new"
    },
    {
      "code": "lowTurn",
      "name": "低打碼提款",
      "board": "low"
    },
    {
      "code": "highRtp",
      "name": "高 RTP",
      "board": "rtp"
    },
    {
      "code": "bigWin",
      "name": "大額中獎",
      "board": "win"
    }
  ],
  "boards": [
    {
      "code": "pnl",
      "name": "盈利榜",
      "entry": [
        {
          "field": "winLoss",
          "gt": 0
        }
      ],
      "rankCol": "winLoss",
      "shareMode": "SHARE",
      "shareBase": "ENTRY",
      "cols": [
        "bet",
        "rtp",
        "net"
      ]
    },
    {
      "code": "wd",
      "name": "提款榜",
      "entry": [
        {
          "field": "wd",
          "gt": 0
        }
      ],
      "rankCol": "wd",
      "shareMode": "SHARE",
      "shareBase": "ALL",
      "cols": [
        "dep",
        "wdN",
        "net",
        "turnX"
      ]
    },
    {
      "code": "new",
      "name": "新號榜",
      "entry": [
        {
          "field": "regDays",
          "max": "$newDays"
        },
        {
          "field": "winLoss",
          "gt": 0
        }
      ],
      "rankCol": "winLoss",
      "shareMode": "SHARE",
      "shareBase": "ENTRY",
      "cols": [
        "dep",
        "wd",
        "parent"
      ]
    },
    {
      "code": "win",
      "name": "大額中獎榜",
      "entry": [
        {
          "field": "maxPayout",
          "min": "$winMin"
        }
      ],
      "rankCol": "maxPayout",
      "shareMode": "MULTIPLE_BUCKET",
      "bucketCol": "payoutX",
      "buckets": [
        {
          "label": "≥ 500 倍",
          "min": 500
        },
        {
          "label": "100–500 倍",
          "min": 100,
          "max": 500
        },
        {
          "label": "< 100 倍",
          "max": 100
        }
      ],
      "cols": [
        "winGame",
        "winTime",
        "maxPayoutBet",
        "payoutX"
      ]
    },
    {
      "code": "rtp",
      "name": "高 RTP 榜",
      "entry": [
        {
          "field": "bet",
          "min": "$rtpBet"
        }
      ],
      "rankCol": "rtp",
      "shareMode": "RTP_DIFF",
      "cols": [
        "bet",
        "winLoss",
        "profitGame"
      ]
    },
    {
      "code": "low",
      "name": "低打碼提款榜",
      "entry": [
        {
          "field": "wd",
          "gt": 0
        },
        {
          "field": "turnX",
          "max": "$lowTurn"
        }
      ],
      "rankCol": "wd",
      "shareMode": "SHARE",
      "shareBase": "ALL",
      "cols": [
        "dep",
        "turnX",
        "bet"
      ]
    },
    {
      "code": "bigw",
      "name": "大額提款榜",
      "kind": "ORDER",
      "threshold": "$bigOne",
      "shareMode": "SHARE",
      "features": [
        {
          "code": "new",
          "name": "新號",
          "field": "regDays",
          "max": "$newDays"
        },
        {
          "code": "low",
          "name": "低打碼",
          "field": "turnX",
          "max": "$lowTurn"
        }
      ],
      "cols": [
        "dep",
        "winLoss",
        "turnX"
      ]
    },
    {
      "code": "all",
      "name": "全部用戶",
      "entry": [
        {
          "any": [
            {
              "field": "dep",
              "gt": 0
            },
            {
              "field": "bet",
              "gt": 0
            }
          ]
        }
      ],
      "rankCol": "winLoss",
      "shareMode": "NONE",
      "card": false,
      "cols": [
        "dep",
        "wd",
        "bet"
      ]
    }
  ]
}'),
    ('RISK_GAME_DICT',       '遊戲字典：遊戲平台 › 品牌 / 類型 › 遊戲（編碼與 UDS 一致）', '{ "platforms": [], "games": [] }')
) AS v(code, description, value)
WHERE NOT EXISTS (SELECT 1 FROM config_center c WHERE c.config_code = v.code AND c.site_code = '');

-- 个人视图配置位（可选项的名称与是否还在跟着指标管理、配置中心走：RiskViewItemSource）
INSERT INTO dashboard_view_slot (slot_code, page_code, name, kind, min_items, max_items, group_adjacent, rules, site_layout_page, sort_no, create_by) VALUES
('risk.overview.cards', 'RISK', '核心指標', 'METRICS', 1, 10, FALSE, '[]', NULL, 10, 'admin'),
('risk.rank.cards', 'RISK', '榜單卡片', 'COLUMNS', 1, 9, FALSE, '[]', NULL, 20, 'admin'),
('risk.game.path', 'RISK', '遊戲分析 · 行維度', 'DIMENSIONS', 1, 5, FALSE, '[]', NULL, 90, 'admin'),
('risk.game.cols', 'RISK', '遊戲分析 · 列', 'COLUMNS', 1, NULL, FALSE, '[]', NULL, 95, 'admin'),
('risk.rank.cols.pnl', 'RISK', '盈利榜 · 列', 'COLUMNS', 0, NULL, FALSE, '[]', NULL, 30, 'admin'),
('risk.rank.cols.wd', 'RISK', '提款榜 · 列', 'COLUMNS', 0, NULL, FALSE, '[]', NULL, 31, 'admin'),
('risk.rank.cols.new', 'RISK', '新號榜 · 列', 'COLUMNS', 0, NULL, FALSE, '[]', NULL, 32, 'admin'),
('risk.rank.cols.low', 'RISK', '低打碼提款榜 · 列', 'COLUMNS', 0, NULL, FALSE, '[]', NULL, 33, 'admin'),
('risk.rank.cols.win', 'RISK', '大額中獎榜 · 列', 'COLUMNS', 0, NULL, FALSE, '[]', NULL, 34, 'admin'),
('risk.rank.cols.bigw', 'RISK', '大額提款榜 · 列', 'COLUMNS', 0, NULL, FALSE, '[]', NULL, 35, 'admin'),
('risk.rank.cols.rtp', 'RISK', '高 RTP 榜 · 列', 'COLUMNS', 0, NULL, FALSE, '[]', NULL, 36, 'admin'),
('risk.rank.cols.all', 'RISK', '全部用戶 · 列', 'COLUMNS', 0, NULL, FALSE, '[]', NULL, 37, 'admin')
ON CONFLICT (slot_code) DO NOTHING;

-- 风控概览核心指标：可选项 = RISK 页的指标，默认 = CORE
INSERT INTO dashboard_view_slot_item (slot_code, item_code, item_name, group_code, group_name, sort_no, default_sort)
SELECT 'risk.overview.cards', p.metric_code, NULL, c.group_code, NULL, p.sort_no, CASE WHEN p.emphasis = 'CORE' THEN p.sort_no END
FROM dashboard_page_metric p JOIN dashboard_metric_card c ON c.metric_code = p.metric_code
WHERE p.page_code = 'RISK'
ON CONFLICT (slot_code, item_code) DO NOTHING;

-- 榜单卡片：系统榜单，默认 6 张（我的榜单是否显示为卡片存在 risk_my_board.show_card；占比档位存在这个配置位的 attrs.bands）
INSERT INTO dashboard_view_slot_item (slot_code, item_code, item_name, group_code, group_name, sort_no, default_sort) VALUES
('risk.rank.cards', 'pnl', '盈利榜', 'board', '榜單', 10, 10),
('risk.rank.cards', 'wd', '提款榜', 'board', '榜單', 20, 20),
('risk.rank.cards', 'new', '新號榜', 'board', '榜單', 30, 30),
('risk.rank.cards', 'low', '低打碼提款榜', 'board', '榜單', 40, 40),
('risk.rank.cards', 'win', '大額中獎榜', 'board', '榜單', 50, 50),
('risk.rank.cards', 'bigw', '大額提款榜', 'board', '榜單', 60, 60),
('risk.rank.cards', 'rtp', '高 RTP 榜', 'board', '榜單', 70, NULL)
ON CONFLICT (slot_code, item_code) DO NOTHING;

-- 每张榜单的列设置：可选项 = 指标管理里的排行榜列（ext.column 不为 false），默认 = 榜单设置的默认列；
-- 以后在指标管理加了列，要在这里补一行可选项（同一写法再执行一次即可）
INSERT INTO dashboard_view_slot_item (slot_code, item_code, item_name, group_code, group_name, sort_no, default_sort)
SELECT s.slot_code, c.ext->>'apiCode', NULL, c.ext->>'group', NULL, c.sort_no, d.default_sort
FROM dashboard_view_slot s
JOIN dashboard_metric_card c ON c.page_scope = 'RISK_RANK' AND coalesce(c.ext->>'column', 'true') <> 'false'
LEFT JOIN (VALUES
    ('risk.rank.cols.pnl', 'bet', 10),
    ('risk.rank.cols.pnl', 'rtp', 20),
    ('risk.rank.cols.pnl', 'net', 30),
    ('risk.rank.cols.wd', 'dep', 10),
    ('risk.rank.cols.wd', 'wdN', 20),
    ('risk.rank.cols.wd', 'net', 30),
    ('risk.rank.cols.wd', 'turnX', 40),
    ('risk.rank.cols.new', 'dep', 10),
    ('risk.rank.cols.new', 'wd', 20),
    ('risk.rank.cols.new', 'parent', 30),
    ('risk.rank.cols.win', 'winGame', 10),
    ('risk.rank.cols.win', 'winTime', 20),
    ('risk.rank.cols.win', 'maxPayoutBet', 30),
    ('risk.rank.cols.win', 'payoutX', 40),
    ('risk.rank.cols.rtp', 'bet', 10),
    ('risk.rank.cols.rtp', 'winLoss', 20),
    ('risk.rank.cols.rtp', 'profitGame', 30),
    ('risk.rank.cols.low', 'dep', 10),
    ('risk.rank.cols.low', 'turnX', 20),
    ('risk.rank.cols.low', 'bet', 30),
    ('risk.rank.cols.bigw', 'dep', 10),
    ('risk.rank.cols.bigw', 'winLoss', 20),
    ('risk.rank.cols.bigw', 'turnX', 30),
    ('risk.rank.cols.all', 'dep', 10),
    ('risk.rank.cols.all', 'wd', 20),
    ('risk.rank.cols.all', 'bet', 30)
) AS d(slot_code, item_code, default_sort) ON d.slot_code = s.slot_code AND d.item_code = c.ext->>'apiCode'
WHERE s.slot_code LIKE 'risk.rank.cols.%' AND s.slot_code NOT IN ('risk.rank.cols.chrisk', 'risk.rank.cols.chriskDrill')
ON CONFLICT (slot_code, item_code) DO NOTHING;

-- 游戏分析：行维度（默认 平台 › 品牌 › 游戏）、列（默认 standard 8 列）
INSERT INTO dashboard_view_slot_item (slot_code, item_code, item_name, group_code, group_name, sort_no, default_sort) VALUES
('risk.game.path', 'platform', '遊戲平台', 'dim', '維度', 10, 10),
('risk.game.path', 'brand',    '遊戲品牌', 'dim', '維度', 20, 20),
('risk.game.path', 'gameType', '遊戲類型', 'dim', '維度', 30, NULL),
('risk.game.path', 'game',     '遊戲',     'dim', '維度', 40, 30),
('risk.game.path', 'date',     '日期',     'dim', '維度', 50, NULL)
ON CONFLICT (slot_code, item_code) DO NOTHING;
INSERT INTO dashboard_view_slot_item (slot_code, item_code, item_name, group_code, group_name, sort_no, default_sort)
SELECT 'risk.game.cols', c.ext->>'apiCode', NULL, c.ext->>'group', NULL, c.sort_no,
       CASE WHEN (c.ext->>'standard')::boolean THEN c.sort_no END
FROM dashboard_metric_card c WHERE c.page_scope = 'RISK_GAME'
ON CONFLICT (slot_code, item_code) DO NOTHING;

-- 菜单：目录「风控分析」（站点板块，推广分析之后）+ 页面 + 按钮
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, "query", route_name, is_frame, is_cache,
                      menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '风控分析', 0,
       COALESCE((SELECT order_num + 1 FROM sys_menu WHERE path = 'promo' AND parent_id = 0 AND menu_type = 'M' LIMIT 1), 5),
       'risk', NULL, '', '', 1, 0, 'M', '0', '0', '', 'monitor', 'admin', CURRENT_TIMESTAMP, '', NULL, '风控分析：概览、排行榜、游戏分析'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = 'risk' AND parent_id = 0 AND menu_type = 'M');
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, "query", route_name, is_frame, is_cache,
                      menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '风控分析', d.menu_id, 1, 'analysis', 'risk/analysis/index', '', '',
       1, 0, 'C', '0', '0', 'dashboard:risk:view', 'chart', 'admin', CURRENT_TIMESTAMP, '', NULL, '概览、排行榜、游戏分析；我的榜单'
FROM sys_menu d WHERE d.path = 'risk' AND d.parent_id = 0 AND d.menu_type = 'M'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'dashboard:risk:view');
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, "query", route_name, is_frame, is_cache,
                      menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '风控导出', p.menu_id, 1, '', '', '', '', 1, 0,
       'F', '0', '0', 'dashboard:risk:export', '#', 'admin', CURRENT_TIMESTAMP, '', NULL, '导出概览、完整名单、游戏分析'
FROM sys_menu p WHERE p.perms = 'dashboard:risk:view'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'dashboard:risk:export');
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, "query", route_name, is_frame, is_cache,
                      menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '修改榜单条件', p.menu_id, 2, '', '', '', '', 1, 0,
       'F', '0', '0', 'dashboard:risk:cond:edit', '#', 'admin', CURRENT_TIMESTAMP, '', NULL, '风控负责人：改榜单条件（全站共用）'
FROM sys_menu p WHERE p.perms = 'dashboard:risk:view'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'dashboard:risk:cond:edit');

INSERT INTO sys_menu_i18n (menu_id, locale, menu_name, remark)
SELECT m.menu_id, v.locale, v.menu_name, v.remark
FROM sys_menu m
JOIN (VALUES
    ('dashboard:risk:view',      'zh-CN', '风控分析',     '概览、排行榜、游戏分析'),
    ('dashboard:risk:view',      'zh-TW', '風控分析',     '概覽、排行榜、遊戲分析'),
    ('dashboard:risk:view',      'en-US', 'Risk Analytics', 'Overview, rankings, game analysis'),
    ('dashboard:risk:export',    'zh-CN', '风控导出',     '导出概览、完整名单、游戏分析'),
    ('dashboard:risk:export',    'zh-TW', '風控匯出',     '匯出概覽、完整名單、遊戲分析'),
    ('dashboard:risk:export',    'en-US', 'Export',       'Export overview, rankings, game analysis'),
    ('dashboard:risk:cond:edit', 'zh-CN', '修改榜单条件', '全站共用'),
    ('dashboard:risk:cond:edit', 'zh-TW', '修改榜單條件', '全站共用'),
    ('dashboard:risk:cond:edit', 'en-US', 'Edit board thresholds', 'Shared by everyone')
) AS v(perms, locale, menu_name, remark) ON v.perms = m.perms
WHERE NOT EXISTS (SELECT 1 FROM sys_menu_i18n i WHERE i.menu_id = m.menu_id AND i.locale = v.locale);
INSERT INTO sys_menu_i18n (menu_id, locale, menu_name, remark)
SELECT m.menu_id, v.locale, v.menu_name, ''
FROM sys_menu m
JOIN (VALUES ('zh-CN', '风控分析'), ('zh-TW', '風控分析'), ('en-US', 'Risk Analytics')) AS v(locale, menu_name) ON TRUE
WHERE m.path = 'risk' AND m.parent_id = 0 AND m.menu_type = 'M'
  AND NOT EXISTS (SELECT 1 FROM sys_menu_i18n i WHERE i.menu_id = m.menu_id AND i.locale = v.locale);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id FROM sys_menu m
WHERE (m.perms LIKE 'dashboard:risk:%' OR (m.path = 'risk' AND m.parent_id = 0 AND m.menu_type = 'M'))
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.role_id = 1)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id);

COMMIT;

-- ㉒ 自检：ext 列；风控概览 15 个指标（CORE 10）；排行榜 31 列、游戏分析 20 列；配置位 12 个；配置中心 3 条；菜单 4 行
SELECT column_name FROM information_schema.columns WHERE table_name = 'dashboard_metric_card' AND column_name = 'ext';
SELECT count(*) AS risk_page, count(*) FILTER (WHERE emphasis = 'CORE') AS risk_core FROM dashboard_page_metric WHERE page_code = 'RISK';
SELECT page_scope, count(*) FROM dashboard_metric_card WHERE page_scope IN ('RISK', 'RISK_RANK', 'RISK_GAME') GROUP BY page_scope ORDER BY 1;
SELECT s.slot_code, (SELECT count(*) FROM dashboard_view_slot_item i WHERE i.slot_code = s.slot_code) AS items,
       (SELECT count(*) FROM dashboard_view_slot_item i WHERE i.slot_code = s.slot_code AND i.default_sort IS NOT NULL) AS defaults
FROM dashboard_view_slot s WHERE s.slot_code LIKE 'risk.%' ORDER BY s.sort_no;
SELECT config_code, site_code FROM config_center WHERE config_code LIKE 'RISK_%' ORDER BY 1;
SELECT menu_id, menu_name, parent_id, menu_type, perms FROM sys_menu WHERE (path = 'risk' AND parent_id = 0) OR perms LIKE 'dashboard:risk:%' ORDER BY menu_id;

-- ----------------------------------------------------------------------------
-- ㉓ 2026-11-16 · 监控 · 条件规则（新 JAR 启动前执行：条件规则的读写、检测任务、事件列表都会用到新列）
--   1. monitor_rule.edit_time / edit_by：最近一次修改配置的时间与人（启停不改），告警规则列表按它排序并显示修改人；
--      回填为 update_time / update_by（没有时取创建）
--   2. monitor_rule_condition：条件规则的条件，每条规则 1–5 行（条件关系、窗口、连续次数在 monitor_rule.config）
--   3. monitor_event.rule_type / detail；metric_code 改为可空（条件规则的事件没有单一指标）
--   4. monitor_rule_state.detail：条件规则最近一次检测的说明（如基准不足的条件）
--   5. 条件规则的字段目录 27 项（dashboard_metric_card，page_scope = MONITOR_COND，group_code = cond）：
--      名称、格式、UDS 取数字段、支持的维度、比率的分子分母、样本口径都在「指标管理」里改；UDS 字段名待 UDS 确认
--   回滚：DROP TABLE IF EXISTS monitor_rule_condition; DELETE FROM dashboard_metric_card WHERE page_scope = 'MONITOR_COND';
--         新增列保留不影响旧 JAR（旧 JAR 不写条件规则；metric_code 恢复 NOT NULL 前须先删 rule_type = 'COND' 的事件）
-- ----------------------------------------------------------------------------
BEGIN;

ALTER TABLE monitor_rule ADD COLUMN IF NOT EXISTS edit_time TIMESTAMP;
COMMENT ON COLUMN monitor_rule.edit_time IS '最近一次修改配置的时间（启停不改），告警规则列表按它排序';
UPDATE monitor_rule SET edit_time = coalesce(update_time, create_time) WHERE edit_time IS NULL;
ALTER TABLE monitor_rule ADD COLUMN IF NOT EXISTS edit_by VARCHAR(64);
COMMENT ON COLUMN monitor_rule.edit_by IS '最近一次修改配置的人（登录名，启停不改），与 edit_time 配对';
UPDATE monitor_rule SET edit_by = coalesce(nullif(update_by, ''), create_by) WHERE edit_by IS NULL;

ALTER TABLE monitor_rule DROP CONSTRAINT IF EXISTS ck_monitor_rule_cond;
ALTER TABLE monitor_rule ADD CONSTRAINT ck_monitor_rule_cond CHECK (
    rule_type <> 'COND' OR (rule_name IS NOT NULL AND level IN ('CRIT', 'WARN') AND notify_enabled IS NOT NULL AND config IS NOT NULL));

CREATE TABLE IF NOT EXISTS monitor_rule_condition (
    rule_id         BIGINT          NOT NULL,
    seq             SMALLINT        NOT NULL,
    field_code      VARCHAR(32)     NOT NULL,
    scope           JSONB           NOT NULL DEFAULT '{}'::jsonb,
    how             VARCHAR(8)      NOT NULL,
    op              VARCHAR(8)      NOT NULL,
    unit            VARCHAR(8),
    threshold_low   NUMERIC(20,4),
    threshold_high  NUMERIC(20,4),
    min_sample      NUMERIC(20,4),
    CONSTRAINT pk_monitor_rule_condition PRIMARY KEY (rule_id, seq),
    CONSTRAINT ck_monitor_rule_condition_seq CHECK (seq BETWEEN 1 AND 10),
    CONSTRAINT ck_monitor_rule_condition_scope CHECK (jsonb_typeof(scope) = 'object'),
    CONSTRAINT ck_monitor_rule_condition_how CHECK (how IN ('CUR', 'BASE', 'YD')),
    CONSTRAINT ck_monitor_rule_condition_op CHECK (
        (how = 'CUR' AND op IN ('GT', 'GE', 'LT', 'LE', 'OUT') AND unit IS NULL)
        OR (how IN ('BASE', 'YD') AND op IN ('UP', 'DOWN', 'CHG') AND unit IN ('PCT', 'ABS'))),
    CONSTRAINT ck_monitor_rule_condition_out CHECK (
        (op = 'OUT' AND threshold_low IS NOT NULL AND threshold_high IS NOT NULL AND threshold_low < threshold_high)
        OR (op <> 'OUT' AND threshold_low IS NOT NULL AND threshold_high IS NULL)),
    CONSTRAINT ck_monitor_rule_condition_sample CHECK (min_sample IS NULL OR min_sample >= 0)
);
COMMENT ON TABLE monitor_rule_condition IS '监控 · 条件规则的条件（每条规则 1–5 行）';
COMMENT ON COLUMN monitor_rule_condition.field_code IS '字段接口编码（指标管理 MONITOR_COND 行的 ext.apiCode）';
COMMENT ON COLUMN monitor_rule_condition.scope IS '范围：维度 → 一个值，如 {"ch":"g:1001","term":"Android"}；{} = 不限';
COMMENT ON COLUMN monitor_rule_condition.how IS 'CUR 当前值 / BASE 较基准（近 4 周同星期同时段平均）/ YD 较昨日同时段';
COMMENT ON COLUMN monitor_rule_condition.threshold_high IS '只有 op = OUT（超出区间）时有值';

ALTER TABLE monitor_event ADD COLUMN IF NOT EXISTS rule_type VARCHAR(10) NOT NULL DEFAULT 'METRIC';
ALTER TABLE monitor_event ADD COLUMN IF NOT EXISTS detail JSONB;
ALTER TABLE monitor_event ALTER COLUMN metric_code DROP NOT NULL;
ALTER TABLE monitor_event DROP CONSTRAINT IF EXISTS ck_monitor_event_rule_type;
ALTER TABLE monitor_event ADD CONSTRAINT ck_monitor_event_rule_type CHECK (
    rule_type IN ('METRIC', 'COND') AND (rule_type <> 'METRIC' OR metric_code IS NOT NULL));
COMMENT ON COLUMN monitor_event.rule_type IS 'METRIC 指标规则 / COND 条件规则（订单规则的命中在 monitor_order_event）';
COMMENT ON COLUMN monitor_event.detail IS '条件规则：各条件触发时（triggered）与最近一期（latest）的值、基准、结果';

ALTER TABLE monitor_rule_state ADD COLUMN IF NOT EXISTS detail JSONB;
COMMENT ON COLUMN monitor_rule_state.detail IS '条件规则最近一次检测的说明，如 {"noBase":[1,3],"from":"2026-11-20"}';

INSERT INTO dashboard_metric_card (
    metric_code, metric_name, group_code, update_frequency, single_day_only, chart_type, value_format, direction, show_as_card, sort_no,
    calc_type, agg_type, monitorable, allow_negative, attributable, page_scope, uds_hour_field, ext, status, create_by, create_time, remark
) VALUES
('cdReg', '註冊人數', 'cond', 'hour', FALSE, 'LINE', 'INT', 'up', TRUE, 5000, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'MONITOR_COND', 'reg_cnt', '{"apiCode": "reg", "group": "user", "dims": ["ch", "term"], "defaultHow": "BASE", "defaultOp": "UP"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('cdFtd', '首存人數', 'cond', 'hour', FALSE, 'LINE', 'INT', 'up', TRUE, 5010, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'MONITOR_COND', 'ftd_cnt', '{"apiCode": "ftd", "group": "user", "dims": ["ch", "term"], "defaultHow": "BASE", "defaultOp": "DOWN"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('cdFtdr', '首存轉化率', 'cond', 'hour', FALSE, 'LINE', 'PCT', 'up', TRUE, 5020, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'MONITOR_COND', NULL, '{"apiCode": "ftdr", "group": "user", "dims": ["ch", "term"], "defaultHow": "CUR", "defaultOp": "LT", "ratio": {"numerator": "ftd_cnt", "denominator": "reg_cnt"}, "sample": {"key": "reg", "name": "註冊人數", "unit": "人", "field": "reg_cnt"}}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '首存人數 ÷ 註冊人數；UDS 字段待 UDS 确认'),
('cdActive', '活躍人數', 'cond', 'hour', FALSE, 'LINE', 'INT', 'up', TRUE, 5030, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'MONITOR_COND', 'active_cnt', '{"apiCode": "active", "group": "user", "dims": ["ch", "term"], "additive": false, "defaultHow": "BASE", "defaultOp": "DOWN"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '多小時取各小時平均（小時去重人數不能相加）；UDS 字段待 UDS 确认'),
('cdDep', '存款總額', 'cond', 'hour', FALSE, 'LINE', 'MONEY', 'up', TRUE, 5040, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'MONITOR_COND', 'dep_amount', '{"apiCode": "dep", "group": "fund", "dims": ["ch", "pay", "term"], "defaultHow": "BASE", "defaultOp": "DOWN"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('cdDOkR', '存款成功率', 'cond', 'hour', FALSE, 'LINE', 'PCT', 'up', TRUE, 5050, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'MONITOR_COND', NULL, '{"apiCode": "dOkR", "group": "fund", "dims": ["ch", "pay", "term"], "defaultHow": "CUR", "defaultOp": "LT", "ratio": {"numerator": "dep_ok_cnt", "denominator": "dep_try_cnt"}, "sample": {"key": "dTry", "name": "存款訂單數", "unit": "筆", "field": "dep_try_cnt"}}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '成功訂單數 ÷ 存款訂單數；UDS 字段待 UDS 确认'),
('cdDTry', '存款訂單數', 'cond', 'hour', FALSE, 'LINE', 'INT', 'up', TRUE, 5060, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'MONITOR_COND', 'dep_try_cnt', '{"apiCode": "dTry", "group": "fund", "dims": ["ch", "pay", "term"], "defaultHow": "BASE", "defaultOp": "CHG"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '含未成功的訂單；UDS 字段待 UDS 确认'),
('cdDT', '平均到賬時長', 'cond', 'hour', FALSE, 'LINE', 'MIN', 'down', TRUE, 5070, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'MONITOR_COND', NULL, '{"apiCode": "dT", "group": "fund", "dims": ["pay", "term"], "defaultHow": "CUR", "defaultOp": "GT", "ratio": {"numerator": "dep_minutes_sum", "denominator": "dep_ok_cnt", "scale": 1}, "sample": {"key": "dOk", "name": "成功存款筆數", "unit": "筆", "field": "dep_ok_cnt"}}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '到賬分鐘數合計 ÷ 成功筆數；UDS 字段待 UDS 确认'),
('cdWd', '提款總額', 'cond', 'hour', FALSE, 'LINE', 'MONEY', 'down', TRUE, 5080, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'MONITOR_COND', 'wd_amount', '{"apiCode": "wd", "group": "fund", "dims": ["ch", "term"], "defaultHow": "BASE", "defaultOp": "UP"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('cdNet', '存提差', 'cond', 'hour', FALSE, 'LINE', 'MONEY', 'up', TRUE, 5090, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'MONITOR_COND', 'net_amount', '{"apiCode": "net", "group": "fund", "dims": ["ch", "term"], "defaultHow": "BASE", "defaultOp": "DOWN"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '存款總額 − 提款總額；UDS 字段待 UDS 确认'),
('cdWdr', '提存比', 'cond', 'hour', FALSE, 'LINE', 'PCT', 'down', TRUE, 5100, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'MONITOR_COND', NULL, '{"apiCode": "wdr", "group": "fund", "dims": ["ch", "term"], "defaultHow": "CUR", "defaultOp": "GT", "ratio": {"numerator": "wd_amount", "denominator": "dep_amount"}}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '提款總額 ÷ 存款總額；UDS 字段待 UDS 确认'),
('cdWdN', '提款筆數', 'cond', 'hour', FALSE, 'LINE', 'INT', 'down', TRUE, 5110, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'MONITOR_COND', 'wd_cnt', '{"apiCode": "wdN", "group": "fund", "dims": ["ch", "term"], "defaultHow": "BASE", "defaultOp": "UP"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('cdBigN', '大額提款筆數', 'cond', 'hour', FALSE, 'LINE', 'INT', 'down', TRUE, 5120, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'MONITOR_COND', 'big_wd_cnt', '{"apiCode": "bigN", "group": "fund", "dims": ["ch", "term"], "defaultHow": "CUR", "defaultOp": "GT"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '單筆 ≥ ₹10,000 的提款筆數（門檻由 UDS 統計口徑決定）；UDS 字段待 UDS 确认'),
('cdWOkR', '出款成功率', 'cond', 'hour', FALSE, 'LINE', 'PCT', 'up', TRUE, 5130, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'MONITOR_COND', NULL, '{"apiCode": "wOkR", "group": "fund", "dims": [], "defaultHow": "CUR", "defaultOp": "LT", "ratio": {"numerator": "wd_ok_cnt", "denominator": "wd_done_cnt"}, "sample": {"key": "wDone", "name": "已處理提款筆數", "unit": "筆", "field": "wd_done_cnt"}}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '出款成功筆數 ÷ 已處理筆數；UDS 字段待 UDS 确认'),
('cdWT', '平均出款時長', 'cond', 'hour', FALSE, 'LINE', 'MIN', 'down', TRUE, 5140, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'MONITOR_COND', NULL, '{"apiCode": "wT", "group": "fund", "dims": [], "defaultHow": "CUR", "defaultOp": "GT", "ratio": {"numerator": "wd_minutes_sum", "denominator": "wd_ok_cnt", "scale": 1}}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '出款分鐘數合計 ÷ 出款成功筆數；UDS 字段待 UDS 确认'),
('cdWPendT', '待審核平均等待時長', 'cond', 'hour', FALSE, 'LINE', 'MIN', 'down', TRUE, 5150, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'MONITOR_COND', 'w_pend_minutes_avg', '{"apiCode": "wPendT", "group": "fund", "dims": [], "snapshot": true, "defaultHow": "CUR", "defaultOp": "GT"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '整點快照；UDS 字段待 UDS 确认'),
('cdWPendN', '待審核筆數', 'cond', 'hour', FALSE, 'LINE', 'INT', 'down', TRUE, 5160, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'MONITOR_COND', 'w_pend_cnt', '{"apiCode": "wPendN", "group": "fund", "dims": [], "snapshot": true, "defaultHow": "CUR", "defaultOp": "GT"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '整點快照；UDS 字段待 UDS 确认'),
('cdArppu', 'ARPPU', 'cond', 'hour', FALSE, 'LINE', 'MONEY', 'up', TRUE, 5170, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'MONITOR_COND', NULL, '{"apiCode": "arppu", "group": "fund", "dims": ["ch"], "defaultHow": "BASE", "defaultOp": "DOWN", "ratio": {"numerator": "dep_amount", "denominator": "dep_users"}, "sample": {"key": "depU", "name": "存款人數", "unit": "人", "field": "dep_users"}}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '存款總額 ÷ 存款人數；UDS 字段待 UDS 确认'),
('cdFtdA', '平均首存金額', 'cond', 'hour', FALSE, 'LINE', 'MONEY', 'up', TRUE, 5180, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'MONITOR_COND', NULL, '{"apiCode": "ftdA", "group": "fund", "dims": ["ch"], "defaultHow": "BASE", "defaultOp": "DOWN", "ratio": {"numerator": "ftd_amount", "denominator": "ftd_cnt"}, "sample": {"key": "ftd", "name": "首存人數", "unit": "人", "field": "ftd_cnt"}}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '首存金額 ÷ 首存人數；UDS 字段待 UDS 确认'),
('cdBet', '投注', 'cond', 'hour', FALSE, 'LINE', 'MONEY', 'up', TRUE, 5190, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'MONITOR_COND', 'bet_amount', '{"apiCode": "bet", "group": "game", "dims": ["pf", "gty", "game", "ch", "term"], "defaultHow": "BASE", "defaultOp": "CHG"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '有效投注；UDS 字段待 UDS 确认'),
('cdGgr', 'GGR', 'cond', 'hour', FALSE, 'LINE', 'MONEY', 'up', TRUE, 5200, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'MONITOR_COND', 'ggr', '{"apiCode": "ggr", "group": "game", "dims": ["pf", "gty", "game", "ch", "term"], "defaultHow": "BASE", "defaultOp": "DOWN"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '投注 − 派彩；UDS 字段待 UDS 确认'),
('cdKillR', '殺率', 'cond', 'hour', FALSE, 'LINE', 'PCT', 'up', TRUE, 5210, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'MONITOR_COND', NULL, '{"apiCode": "killR", "group": "game", "dims": ["pf", "gty", "game", "ch", "term"], "defaultHow": "CUR", "defaultOp": "LT", "ratio": {"numerator": "ggr", "denominator": "bet_amount"}, "sample": {"key": "betN", "name": "注單數", "unit": "筆", "field": "bet_cnt"}}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'GGR ÷ 投注；UDS 字段待 UDS 确认'),
('cdRtp', 'RTP', 'cond', 'hour', FALSE, 'LINE', 'PCT', 'down', TRUE, 5220, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'MONITOR_COND', NULL, '{"apiCode": "rtp", "group": "game", "dims": ["pf", "gty", "game", "ch", "term"], "defaultHow": "CUR", "defaultOp": "GT", "ratio": {"numerator": "payout_amount", "denominator": "bet_amount"}, "sample": {"key": "betN", "name": "注單數", "unit": "筆", "field": "bet_cnt"}}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '派彩 ÷ 投注；UDS 字段待 UDS 确认'),
('cdNgr', 'NGR', 'cond', 'hour', FALSE, 'LINE', 'MONEY', 'up', TRUE, 5230, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'MONITOR_COND', 'ngr', '{"apiCode": "ngr", "group": "game", "dims": [], "defaultHow": "BASE", "defaultOp": "DOWN"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'GGR − 贈金；UDS 字段待 UDS 确认'),
('cdBonus', '贈金', 'cond', 'hour', FALSE, 'LINE', 'MONEY', 'down', TRUE, 5240, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'MONITOR_COND', 'bonus_amount', '{"apiCode": "bonus", "group": "game", "dims": [], "defaultHow": "BASE", "defaultOp": "UP"}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, 'UDS 字段待 UDS 确认'),
('cdBonusR', '贈金佔比', 'cond', 'hour', FALSE, 'LINE', 'PCT', 'down', TRUE, 5250, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'MONITOR_COND', NULL, '{"apiCode": "bonusR", "group": "game", "dims": [], "defaultHow": "CUR", "defaultOp": "GT", "ratio": {"numerator": "bonus_amount", "denominator": "dep_amount"}}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '贈金 ÷ 存款總額；UDS 字段待 UDS 确认'),
('cdTurnX', '打碼倍數', 'cond', 'hour', FALSE, 'LINE', 'MULTIPLE', 'up', TRUE, 5260, 'NONE', 'SUM', FALSE, TRUE, FALSE, 'MONITOR_COND', NULL, '{"apiCode": "turnX", "group": "game", "dims": [], "defaultHow": "CUR", "defaultOp": "LT", "ratio": {"numerator": "bet_amount", "denominator": "dep_amount", "scale": 1}}'::jsonb, '0', 'admin', CURRENT_TIMESTAMP, '投注 ÷ 存款總額；UDS 字段待 UDS 确认')
ON CONFLICT (metric_code) DO NOTHING;

COMMIT;

-- ㉓ 自检：新表与新列；条件规则字段目录 27 项
SELECT to_regclass('monitor_rule_condition') AS condition_table;
SELECT table_name, column_name FROM information_schema.columns
 WHERE (table_name, column_name) IN (('monitor_rule', 'edit_time'), ('monitor_rule', 'edit_by'), ('monitor_event', 'rule_type'), ('monitor_event', 'detail'),
                                     ('monitor_rule_state', 'detail'))
 ORDER BY 1, 2;
SELECT count(*) AS cond_fields FROM dashboard_metric_card WHERE page_scope = 'MONITOR_COND';

-- ----------------------------------------------------------------------------
-- ㉔ 2026-11-17 · 风控分析 · 排行榜对齐《榜单逻辑总览》（原型第 96 版）（新 JAR 启动前执行）
--   1. RISK_RANK_BOARDS 全站默认升级：渠道风险榜 chrisk、卡片 3 个数 stats、大额中奖 / 高RTP 卡片中间改为游戏分布、
--      大额提款榜的名单条件 filters（去掉风险特征）、高RTP 风险类型加 RTP ≥ 100%、各榜默认列补 註冊時間 / 推廣渠道。
--      只升级还是 ㉒ 写入的旧版本（不含 chrisk）；站点自己改过的配置不动，需按新结构手工补。
--   2. 个人视图配置位：渠道风险榜的列（渠道列）、下钻名单的列、个人风险类型（没设过 = 跟随全局默认）。
--   3. 用户类榜单列设置的默认列按新配置重排；大额提款榜默认只留打碼倍數。
--   回滚：把 RISK_RANK_BOARDS 改回 ㉒ 的值；DELETE FROM dashboard_view_slot_item WHERE slot_code IN ('risk.rank.cols.chrisk',
--         'risk.rank.cols.chriskDrill', 'risk.rank.riskTypes') OR (slot_code = 'risk.rank.cards' AND item_code = 'chrisk');
--         DELETE FROM dashboard_view_slot WHERE slot_code IN ('risk.rank.cols.chrisk', 'risk.rank.cols.chriskDrill', 'risk.rank.riskTypes');
-- ----------------------------------------------------------------------------
BEGIN;

UPDATE config_center
   SET config_value = '{
  "myBoardLimit": 10,
  "bands": [
    1,
    5,
    10
  ],
  "defaultCards": [
    "pnl",
    "wd",
    "new",
    "low",
    "win",
    "bigw"
  ],
  "shareCols": [
    "dep",
    "wd",
    "bet",
    "pay",
    "bonus",
    "maxPayout"
  ],
  "sharePositiveCols": [
    "winLoss"
  ],
  "ratioCols": [
    "rtp",
    "turnX",
    "bonusR"
  ],
  "riskTypes": [
    {
      "code": "newWin",
      "name": "新號盈利",
      "board": "new"
    },
    {
      "code": "lowTurn",
      "name": "低打碼提款",
      "board": "low"
    },
    {
      "code": "highRtp",
      "name": "高RTP",
      "board": "rtp",
      "and": [
        {
          "field": "rtp",
          "min": 1
        }
      ]
    },
    {
      "code": "bigWin",
      "name": "大額中獎",
      "board": "win"
    }
  ],
  "channelRiskDefault": [
    "newWin",
    "lowTurn",
    "bigWin"
  ],
  "boards": [
    {
      "code": "pnl",
      "name": "盈利榜",
      "entry": [
        {
          "field": "winLoss",
          "gt": 0
        }
      ],
      "rankCol": "winLoss",
      "shareMode": "SHARE",
      "shareBase": "ENTRY",
      "cols": [
        "bet",
        "rtp",
        "net",
        "regTime",
        "channel"
      ],
      "stats": [
        {
          "code": "entry",
          "label": "入榜"
        },
        {
          "code": "sum",
          "label": "合計"
        },
        {
          "code": "activeShare",
          "label": "佔活躍用戶"
        }
      ]
    },
    {
      "code": "wd",
      "name": "提款榜",
      "entry": [
        {
          "field": "wd",
          "gt": 0
        }
      ],
      "rankCol": "wd",
      "shareMode": "SHARE",
      "shareBase": "ALL",
      "cols": [
        "wdN",
        "dep",
        "net",
        "turnX",
        "regTime",
        "channel"
      ],
      "stats": [
        {
          "code": "entry",
          "label": "入榜"
        },
        {
          "code": "sum",
          "label": "合計"
        },
        {
          "code": "ratio",
          "num": "wd",
          "den": "dep",
          "label": "提存比"
        }
      ]
    },
    {
      "code": "new",
      "name": "新號榜",
      "entry": [
        {
          "field": "regDays",
          "max": "$newDays"
        },
        {
          "field": "winLoss",
          "gt": 0
        }
      ],
      "rankCol": "winLoss",
      "shareMode": "SHARE",
      "shareBase": "ENTRY",
      "cols": [
        "dep",
        "wd",
        "parent",
        "regTime",
        "channel"
      ],
      "stats": [
        {
          "code": "entry",
          "label": "入榜"
        },
        {
          "code": "sum",
          "label": "合計"
        },
        {
          "code": "shareOfPositive",
          "col": "winLoss",
          "label": "佔全部盈利"
        }
      ]
    },
    {
      "code": "win",
      "name": "大額中獎榜",
      "entry": [
        {
          "field": "maxPayout",
          "min": "$winMin"
        }
      ],
      "rankCol": "maxPayout",
      "shareMode": "GAME_DIST",
      "distCol": "winGame",
      "cols": [
        "winGame",
        "winTime",
        "maxPayoutBet",
        "payoutX",
        "regTime",
        "channel"
      ],
      "stats": [
        {
          "code": "entry",
          "label": "入榜"
        },
        {
          "code": "max",
          "label": "最高派彩"
        },
        {
          "code": "maxOf",
          "col": "payoutX",
          "label": "最高倍數"
        }
      ]
    },
    {
      "code": "rtp",
      "name": "高RTP榜",
      "entry": [
        {
          "field": "bet",
          "min": "$rtpBet"
        }
      ],
      "rankCol": "rtp",
      "shareMode": "GAME_DIST",
      "distCol": "profitGame",
      "cols": [
        "bet",
        "winLoss",
        "profitGame",
        "regTime",
        "channel"
      ],
      "stats": [
        {
          "code": "entry",
          "label": "入榜"
        },
        {
          "code": "countAtLeast",
          "col": "rtp",
          "value": 1,
          "label": "RTP ≥ 100% 的人數"
        },
        {
          "code": "sumOf",
          "col": "winLoss",
          "label": "輸贏合計"
        }
      ]
    },
    {
      "code": "low",
      "name": "低打碼提款榜",
      "entry": [
        {
          "field": "wd",
          "gt": 0
        },
        {
          "field": "turnX",
          "max": "$lowTurn"
        }
      ],
      "rankCol": "wd",
      "shareMode": "SHARE",
      "shareBase": "ALL",
      "cols": [
        "dep",
        "turnX",
        "wdN",
        "regTime",
        "channel"
      ],
      "stats": [
        {
          "code": "entry",
          "label": "入榜"
        },
        {
          "code": "sum",
          "label": "合計"
        },
        {
          "code": "shareOfAll",
          "col": "wd",
          "label": "佔全部提款"
        }
      ]
    },
    {
      "code": "bigw",
      "name": "大額提款榜",
      "kind": "ORDER",
      "threshold": "$bigOne",
      "shareMode": "SHARE",
      "filters": [
        "regDays",
        "turnX",
        "dep",
        "winLoss"
      ],
      "cols": [
        "turnX"
      ],
      "colOptions": [
        "turnX",
        "dep",
        "winLoss"
      ],
      "stats": [
        {
          "code": "entry",
          "label": "大額筆數"
        },
        {
          "code": "sum",
          "label": "金額"
        },
        {
          "code": "shareOfAll",
          "col": "wd",
          "label": "佔全部提款"
        }
      ]
    },
    {
      "code": "chrisk",
      "name": "渠道風險榜",
      "kind": "CHANNEL",
      "rankCol": "riskWd",
      "cols": [
        "ftdUsers",
        "depAmt",
        "avgDep",
        "activeUsers",
        "riskUsers",
        "riskRate",
        "riskWd",
        "riskTypeCounts",
        "winLoss"
      ],
      "drillCols": [
        "dep",
        "turnX",
        "winLoss",
        "regTime"
      ],
      "drillRankCol": "wd",
      "stats": [
        {
          "code": "riskChannels",
          "label": "風險渠道"
        },
        {
          "code": "riskUsers",
          "label": "風險用戶"
        },
        {
          "code": "riskWd",
          "label": "風險用戶提款"
        }
      ],
      "channelCols": [
        {
          "code": "regUsers",
          "name": "註冊用戶",
          "group": "ACQ",
          "valueFormat": "INT",
          "rank": true,
          "condition": true,
          "fixed": true,
          "remark": "所選時間內註冊的用戶數（含沒有存款或投注的）"
        },
        {
          "code": "ftdUsers",
          "name": "首存人數",
          "group": "ACQ",
          "valueFormat": "INT",
          "rank": true,
          "condition": true,
          "remark": "所選時間內完成首存的用戶數（按首存時間，不看註冊時間）"
        },
        {
          "code": "ftdAmt",
          "name": "首存金額",
          "group": "ACQ",
          "valueFormat": "MONEY",
          "rank": true,
          "remark": "所選時間內完成首存的用戶，首存金額合計"
        },
        {
          "code": "activeUsers",
          "name": "活躍用戶",
          "group": "ACQ",
          "valueFormat": "INT",
          "rank": true,
          "remark": "所選時間內有存款或投注的用戶數"
        },
        {
          "code": "depUsers",
          "name": "存款人數",
          "group": "FUND",
          "valueFormat": "INT",
          "rank": true,
          "condition": true,
          "remark": "所選時間內有成功存款的用戶數"
        },
        {
          "code": "depAmt",
          "name": "存款金額",
          "group": "FUND",
          "valueFormat": "MONEY",
          "rank": true,
          "condition": true,
          "remark": "所選時間內的成功存款合計"
        },
        {
          "code": "avgDep",
          "name": "平均存款金額",
          "group": "FUND",
          "valueFormat": "MONEY",
          "rank": true,
          "condition": true,
          "remark": "存款金額 ÷ 存款人數"
        },
        {
          "code": "wd",
          "name": "提款",
          "group": "FUND",
          "valueFormat": "MONEY",
          "rank": true,
          "condition": true
        },
        {
          "code": "riskUsers",
          "name": "風險用戶",
          "group": "RISK",
          "valueFormat": "INT",
          "rank": true,
          "condition": true
        },
        {
          "code": "riskRate",
          "name": "風險用戶佔比",
          "group": "RISK",
          "valueFormat": "PCT",
          "rank": true,
          "condition": true,
          "remark": "風險用戶 ÷ 活躍用戶"
        },
        {
          "code": "riskWd",
          "name": "風險用戶提款",
          "group": "RISK",
          "valueFormat": "MONEY",
          "rank": true,
          "condition": true
        },
        {
          "code": "riskTypeCounts",
          "name": "各風險類型人數",
          "group": "RISK",
          "valueFormat": "INT"
        },
        {
          "code": "bet",
          "name": "有效投注",
          "group": "GAME",
          "valueFormat": "MONEY",
          "rank": true,
          "condition": true
        },
        {
          "code": "winLoss",
          "name": "玩家輸贏",
          "group": "GAME",
          "valueFormat": "MONEY",
          "rank": true,
          "condition": true
        }
      ]
    },
    {
      "code": "all",
      "name": "全部用戶",
      "scope": [
        {
          "any": [
            {
              "field": "dep",
              "gt": 0
            },
            {
              "field": "bet",
              "gt": 0
            }
          ]
        }
      ],
      "rankCol": "winLoss",
      "shareMode": "NONE",
      "card": false,
      "cols": [
        "dep",
        "wd",
        "bet",
        "regTime",
        "channel"
      ]
    }
  ]
}',
       update_by = 'admin', update_time = CURRENT_TIMESTAMP
 WHERE config_code = 'RISK_RANK_BOARDS' AND site_code = '' AND config_value NOT LIKE '%"chrisk"%';

INSERT INTO dashboard_view_slot (slot_code, page_code, name, kind, min_items, max_items, group_adjacent, rules, site_layout_page, sort_no, create_by) VALUES
('risk.rank.cols.chrisk',      'RISK', '渠道風險榜 · 列',            'COLUMNS', 0, NULL, FALSE, '[]', NULL, 38, 'admin'),
('risk.rank.cols.chriskDrill', 'RISK', '渠道風險榜 · 下鑽名單 · 列', 'COLUMNS', 0, NULL, FALSE, '[]', NULL, 39, 'admin'),
('risk.rank.riskTypes',        'RISK', '渠道風險榜 · 風險類型',      'COLUMNS', 1, 4,    FALSE, '[]', NULL, 40, 'admin')
ON CONFLICT (slot_code) DO NOTHING;

-- 卡片：渠道风险榜可加为卡片（不在默认 6 张里）
INSERT INTO dashboard_view_slot_item (slot_code, item_code, item_name, group_code, group_name, sort_no, default_sort) VALUES
('risk.rank.cards', 'chrisk', '渠道風險榜', 'board', '榜單', 80, NULL)
ON CONFLICT (slot_code, item_code) DO NOTHING;

-- 渠道风险榜的列（註冊用戶固定第一列，不在可选项里；「各風險類型人數」按所选风险类型展开）
INSERT INTO dashboard_view_slot_item (slot_code, item_code, item_name, group_code, group_name, sort_no, default_sort) VALUES
('risk.rank.cols.chrisk', 'ftdUsers', '首存人數', 'ACQ', NULL, 20, 10),
('risk.rank.cols.chrisk', 'ftdAmt', '首存金額', 'ACQ', NULL, 30, NULL),
('risk.rank.cols.chrisk', 'activeUsers', '活躍用戶', 'ACQ', NULL, 40, 40),
('risk.rank.cols.chrisk', 'depUsers', '存款人數', 'FUND', NULL, 50, NULL),
('risk.rank.cols.chrisk', 'depAmt', '存款金額', 'FUND', NULL, 60, 20),
('risk.rank.cols.chrisk', 'avgDep', '平均存款金額', 'FUND', NULL, 70, 30),
('risk.rank.cols.chrisk', 'wd', '提款', 'FUND', NULL, 80, NULL),
('risk.rank.cols.chrisk', 'riskUsers', '風險用戶', 'RISK', NULL, 90, 50),
('risk.rank.cols.chrisk', 'riskRate', '風險用戶佔比', 'RISK', NULL, 100, 60),
('risk.rank.cols.chrisk', 'riskWd', '風險用戶提款', 'RISK', NULL, 110, 70),
('risk.rank.cols.chrisk', 'riskTypeCounts', '各風險類型人數', 'RISK', NULL, 120, 80),
('risk.rank.cols.chrisk', 'bet', '有效投注', 'GAME', NULL, 130, NULL),
('risk.rank.cols.chrisk', 'winLoss', '玩家輸贏', 'GAME', NULL, 140, 90)
ON CONFLICT (slot_code, item_code) DO NOTHING;

-- 个人风险类型：可选项 = 系统 4 类；不设默认（没设过 = 跟随全局默认，见 RISK_RANK_THRESHOLDS.channelRiskTypes）
INSERT INTO dashboard_view_slot_item (slot_code, item_code, item_name, group_code, group_name, sort_no, default_sort) VALUES
('risk.rank.riskTypes', 'newWin',  '新號盈利',   'risk', '風險類型', 10, NULL),
('risk.rank.riskTypes', 'lowTurn', '低打碼提款', 'risk', '風險類型', 20, NULL),
('risk.rank.riskTypes', 'highRtp', '高RTP',      'risk', '風險類型', 30, NULL),
('risk.rank.riskTypes', 'bigWin',  '大額中獎',   'risk', '風險類型', 40, NULL)
ON CONFLICT (slot_code, item_code) DO NOTHING;

-- 下钻名单的列：可选项同用户类榜单（指标管理 RISK_RANK 列），默认 存款、打碼倍數、玩家輸贏、註冊時間
INSERT INTO dashboard_view_slot_item (slot_code, item_code, item_name, group_code, group_name, sort_no, default_sort)
SELECT 'risk.rank.cols.chriskDrill', c.ext->>'apiCode', NULL, c.ext->>'group', NULL, c.sort_no, d.default_sort
FROM dashboard_metric_card c
LEFT JOIN (VALUES
    ('risk.rank.cols.chriskDrill', 'dep', 10),
    ('risk.rank.cols.chriskDrill', 'turnX', 20),
    ('risk.rank.cols.chriskDrill', 'winLoss', 30),
    ('risk.rank.cols.chriskDrill', 'regTime', 40)
) AS d(slot_code, item_code, default_sort) ON d.item_code = c.ext->>'apiCode'
WHERE c.page_scope = 'RISK_RANK' AND coalesce(c.ext->>'column', 'true') <> 'false'
ON CONFLICT (slot_code, item_code) DO NOTHING;

-- 用户类榜单与大额提款榜的默认列（FY-66 表 3-1、FY-70）：先清掉旧默认，再按新顺序写
UPDATE dashboard_view_slot_item SET default_sort = NULL
 WHERE slot_code IN ('risk.rank.cols.pnl', 'risk.rank.cols.wd', 'risk.rank.cols.new', 'risk.rank.cols.win', 'risk.rank.cols.rtp',
                     'risk.rank.cols.low', 'risk.rank.cols.bigw', 'risk.rank.cols.all');
UPDATE dashboard_view_slot_item i SET default_sort = d.default_sort
FROM (VALUES
    ('risk.rank.cols.pnl', 'bet', 10),
    ('risk.rank.cols.pnl', 'rtp', 20),
    ('risk.rank.cols.pnl', 'net', 30),
    ('risk.rank.cols.pnl', 'regTime', 40),
    ('risk.rank.cols.pnl', 'channel', 50),
    ('risk.rank.cols.wd', 'wdN', 10),
    ('risk.rank.cols.wd', 'dep', 20),
    ('risk.rank.cols.wd', 'net', 30),
    ('risk.rank.cols.wd', 'turnX', 40),
    ('risk.rank.cols.wd', 'regTime', 50),
    ('risk.rank.cols.wd', 'channel', 60),
    ('risk.rank.cols.new', 'dep', 10),
    ('risk.rank.cols.new', 'wd', 20),
    ('risk.rank.cols.new', 'parent', 30),
    ('risk.rank.cols.new', 'regTime', 40),
    ('risk.rank.cols.new', 'channel', 50),
    ('risk.rank.cols.win', 'winGame', 10),
    ('risk.rank.cols.win', 'winTime', 20),
    ('risk.rank.cols.win', 'maxPayoutBet', 30),
    ('risk.rank.cols.win', 'payoutX', 40),
    ('risk.rank.cols.win', 'regTime', 50),
    ('risk.rank.cols.win', 'channel', 60),
    ('risk.rank.cols.rtp', 'bet', 10),
    ('risk.rank.cols.rtp', 'winLoss', 20),
    ('risk.rank.cols.rtp', 'profitGame', 30),
    ('risk.rank.cols.rtp', 'regTime', 40),
    ('risk.rank.cols.rtp', 'channel', 50),
    ('risk.rank.cols.low', 'dep', 10),
    ('risk.rank.cols.low', 'turnX', 20),
    ('risk.rank.cols.low', 'wdN', 30),
    ('risk.rank.cols.low', 'regTime', 40),
    ('risk.rank.cols.low', 'channel', 50),
    ('risk.rank.cols.all', 'dep', 10),
    ('risk.rank.cols.all', 'wd', 20),
    ('risk.rank.cols.all', 'bet', 30),
    ('risk.rank.cols.all', 'regTime', 40),
    ('risk.rank.cols.all', 'channel', 50),
    ('risk.rank.cols.bigw', 'turnX', 10)
) AS d(slot_code, item_code, default_sort)
WHERE i.slot_code = d.slot_code AND i.item_code = d.item_code;

COMMIT;

-- ㉔ 自检：配置已是新版本；新配置位 3 个；各榜默认列
SELECT config_code, site_code, config_value LIKE '%"chrisk"%' AS upgraded FROM config_center WHERE config_code = 'RISK_RANK_BOARDS' ORDER BY site_code;
SELECT s.slot_code, (SELECT count(*) FROM dashboard_view_slot_item i WHERE i.slot_code = s.slot_code) AS items,
       (SELECT string_agg(i.item_code, ',' ORDER BY i.default_sort) FROM dashboard_view_slot_item i
         WHERE i.slot_code = s.slot_code AND i.default_sort IS NOT NULL) AS defaults
FROM dashboard_view_slot s WHERE s.slot_code LIKE 'risk.rank.%' ORDER BY s.sort_no;
