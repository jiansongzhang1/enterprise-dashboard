-- ============================================================================
-- 系统管理 · 订阅简报（2026-11-01）· PostgreSQL（dashboard_db）· 可重复执行
--
-- 每天在站点时区的某个整点，把运营总览某个范围（今日 / 昨日走 dashboard_overview_hour_v2，近 7 / 30 日走
-- dashboard_overview_day_v2）的勾选指标整理成 Lark 卡片，发到 Lark 群或个人（按用户邮箱）。用 monitor.lark 的机器人。
--   * dashboard_subscription：订阅；删除为逻辑删除（del_flag = '2'）；last_slot 防止同一发送时刻重复发送（多实例）
--   * dashboard_subscription_log：发送记录，一次发送 × 一个接收者一行（定时 / 立即发送 / 测试发送）
--   * 菜单：系统管理 › 订阅简报（system:subscription:list），按钮 新增 / 修改 / 删除（:add / :edit / :remove），授给超级管理员
--
-- 执行：psql -X -v ON_ERROR_STOP=1 -d dashboard_db -f sql/add_dashboard_subscription_postgresql.sql
-- 回滚：删除两张表和 system:subscription:* 菜单（见 sql/monitor-p1/02_rollback_monitor_p1_postgresql.sql）
-- ============================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS dashboard_subscription (
    id              BIGSERIAL       PRIMARY KEY,
    site_code       VARCHAR(32)     NOT NULL,
    name            VARCHAR(50)     NOT NULL,
    description     VARCHAR(200),
    range_code      VARCHAR(16)     NOT NULL,
    metrics         VARCHAR(1000)   NOT NULL,
    send_hour       SMALLINT        NOT NULL,
    receivers       TEXT            NOT NULL DEFAULT '[]',
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE,
    last_slot       VARCHAR(16),
    last_run_at     TIMESTAMP,
    last_status     VARCHAR(16),
    last_error      VARCHAR(500),
    del_flag        CHAR(1)         NOT NULL DEFAULT '0',
    create_by       VARCHAR(64)     DEFAULT '',
    create_time     TIMESTAMP       DEFAULT localtimestamp,
    update_by       VARCHAR(64)     DEFAULT '',
    update_time     TIMESTAMP,
    CONSTRAINT ck_dashboard_subscription_range CHECK (range_code IN ('TODAY', 'YESTERDAY', 'LAST_7D', 'LAST_30D')),
    CONSTRAINT ck_dashboard_subscription_hour CHECK (send_hour BETWEEN 0 AND 23),
    CONSTRAINT ck_dashboard_subscription_status CHECK (last_status IS NULL OR last_status IN ('SUCCESS', 'PARTIAL', 'FAILED'))
);
CREATE INDEX IF NOT EXISTS idx_dashboard_subscription_site ON dashboard_subscription (site_code) WHERE del_flag = '0';

COMMENT ON TABLE  dashboard_subscription             IS '订阅简报：每天定时把运营总览某个范围的指标整理成 Lark 卡片发到群或个人';
COMMENT ON COLUMN dashboard_subscription.site_code   IS '站点编码（monitor_site_setting.site_code），建立后不能改';
COMMENT ON COLUMN dashboard_subscription.name        IS '名称（卡片标题），同站点不重复';
COMMENT ON COLUMN dashboard_subscription.description IS '说明（卡片正文，选填）';
COMMENT ON COLUMN dashboard_subscription.range_code  IS '统计范围：TODAY 今日截至发送整点 / YESTERDAY 昨日 / LAST_7D 近 7 日 / LAST_30D 近 30 日（不含当天）';
COMMENT ON COLUMN dashboard_subscription.metrics     IS '指标编码，逗号分隔，按站点运营总览顺序';
COMMENT ON COLUMN dashboard_subscription.send_hour   IS '每日发送整点（站点时区，0–23）';
COMMENT ON COLUMN dashboard_subscription.receivers   IS '接收者 JSON：[{"type":"chat","id":"oc_…","name":"群名"},{"type":"user","id":"用户ID","name":"邮箱"}]';
COMMENT ON COLUMN dashboard_subscription.last_slot   IS '最近一次定时发送的发送时刻（yyyy-MM-dd HH:mm，站点时区），同一时刻只发一次';
COMMENT ON COLUMN dashboard_subscription.last_status IS '最近一次发送结果：SUCCESS / PARTIAL（部分接收者失败）/ FAILED';

CREATE TABLE IF NOT EXISTS dashboard_subscription_log (
    id              BIGSERIAL       PRIMARY KEY,
    subscription_id BIGINT          NOT NULL,
    site_code       VARCHAR(32)     NOT NULL,
    trigger_type    VARCHAR(16)     NOT NULL,
    period          VARCHAR(64),
    receiver_type   VARCHAR(8)      NOT NULL,
    receiver_id     VARCHAR(64),
    receiver_name   VARCHAR(128),
    status          VARCHAR(16)     NOT NULL,
    error           VARCHAR(500),
    message_id      VARCHAR(64),
    operator        VARCHAR(64),
    create_time     TIMESTAMP       DEFAULT localtimestamp,
    CONSTRAINT ck_dashboard_subscription_log_trigger CHECK (trigger_type IN ('SCHEDULE', 'MANUAL', 'TEST')),
    CONSTRAINT ck_dashboard_subscription_log_status CHECK (status IN ('SUCCESS', 'FAILED'))
);
CREATE INDEX IF NOT EXISTS idx_dashboard_subscription_log_sub ON dashboard_subscription_log (subscription_id, id DESC);

COMMENT ON TABLE  dashboard_subscription_log              IS '订阅简报发送记录：一次发送 × 一个接收者一行';
COMMENT ON COLUMN dashboard_subscription_log.trigger_type IS 'SCHEDULE 定时 / MANUAL 立即发送 / TEST 测试发送（只发给操作人）';
COMMENT ON COLUMN dashboard_subscription_log.period       IS '统计周期文字';
COMMENT ON COLUMN dashboard_subscription_log.receiver_type IS 'chat 群 / user 个人';

-- ---------- 菜单：系统管理 · 订阅简报 ----------
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '订阅简报', 1, 12, 'subscription', 'system/subscription/index', '', '',
       1, 0, 'C', '0', '0', 'system:subscription:list', 'email', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '每天定时把运营总览指标发到 Lark 群或个人'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:subscription:list');

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '新增订阅', p.menu_id, 1, '', '', '', '',
       1, 0, 'F', '0', '0', 'system:subscription:add', '#', 'admin', CURRENT_TIMESTAMP, '', NULL, '新增订阅简报'
FROM sys_menu p
WHERE p.perms = 'system:subscription:list'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:subscription:add');
-- 一条 INSERT 里 MAX(menu_id) + 1 只算一次，所以三个按钮分开插
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '修改订阅', p.menu_id, 2, '', '', '', '',
       1, 0, 'F', '0', '0', 'system:subscription:edit', '#', 'admin', CURRENT_TIMESTAMP, '', NULL, '修改、启停、立即发送、测试发送'
FROM sys_menu p
WHERE p.perms = 'system:subscription:list'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:subscription:edit');
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '删除订阅', p.menu_id, 3, '', '', '', '',
       1, 0, 'F', '0', '0', 'system:subscription:remove', '#', 'admin', CURRENT_TIMESTAMP, '', NULL, '删除订阅简报'
FROM sys_menu p
WHERE p.perms = 'system:subscription:list'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:subscription:remove');

INSERT INTO sys_menu_i18n (menu_id, locale, menu_name, remark)
SELECT m.menu_id, v.locale, v.menu_name, v.remark
FROM sys_menu m
JOIN (VALUES
    ('system:subscription:list',   'zh-CN', '订阅简报', '每天定时把运营总览指标发到 Lark 群或个人'),
    ('system:subscription:list',   'zh-TW', '訂閱簡報', '每天定時把營運總覽指標發到 Lark 群或個人'),
    ('system:subscription:list',   'en-US', 'Subscriptions', 'Daily overview briefing to Lark groups or people'),
    ('system:subscription:add',    'zh-CN', '新增订阅', '新增订阅简报'),
    ('system:subscription:add',    'zh-TW', '新增訂閱', '新增訂閱簡報'),
    ('system:subscription:add',    'en-US', 'Add Subscription', 'Create a subscription'),
    ('system:subscription:edit',   'zh-CN', '修改订阅', '修改、启停、立即发送、测试发送'),
    ('system:subscription:edit',   'zh-TW', '修改訂閱', '修改、啟停、立即發送、測試發送'),
    ('system:subscription:edit',   'en-US', 'Edit Subscription', 'Edit, pause, send now, test send'),
    ('system:subscription:remove', 'zh-CN', '删除订阅', '删除订阅简报'),
    ('system:subscription:remove', 'zh-TW', '刪除訂閱', '刪除訂閱簡報'),
    ('system:subscription:remove', 'en-US', 'Delete Subscription', 'Delete a subscription')
) AS v(perms, locale, menu_name, remark) ON v.perms = m.perms
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu_i18n i WHERE i.menu_id = m.menu_id AND i.locale = v.locale
);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id
FROM sys_menu m
WHERE m.perms LIKE 'system:subscription:%'
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.role_id = 1)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id);

COMMIT;

-- 自检：两张表（2 行）；菜单 4 行（订阅简报 + 3 个按钮）
SELECT table_name FROM information_schema.tables
 WHERE table_schema = current_schema() AND table_name IN ('dashboard_subscription', 'dashboard_subscription_log') ORDER BY 1;
SELECT menu_id, menu_name, parent_id, menu_type, perms FROM sys_menu WHERE perms LIKE 'system:subscription:%' ORDER BY menu_id;
