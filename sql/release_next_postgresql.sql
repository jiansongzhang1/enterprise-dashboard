-- ============================================================================
-- 下一次上线 · 增量 SQL（dashboard_db）· PostgreSQL · 可重复执行
--
-- 基线：2026-11-02 线上库已包含此前全部表结构与数据（监控一期、多站点、订阅简报、菜单权限等），
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
--   ① 2026-11-02  Yata Copilot · 订阅简报 AI 摘要    dashboard_subscription.ai_enabled / ai_model，新表 copilot_usage_log
--   ② 2026-11-04  站点指标配置 · 两套默认展示指标集    dashboard_page_metric_site.display_type（DEFAULT / REPORT），唯一键加 display_type
--   ③ 2026-11-04  推广分析 · 预算与消耗（FY-56）菜单     目录「推广分析」+ 页面「预算与消耗」promo:budget:view + 按钮 promo:budget:import（无新表）
--   ④ 2026-11-04  系统管理 · 配置中心                     新表 config_center（唯一键 配置编码 + 站点）+ 菜单 system:configCenter:list/add/edit/remove
--   ⑤ 2026-11-05  推广分析 · 概览指标与可歸因开关        dashboard_metric_card.attributable；推广分析分组 inv / flow 与 16 个指标（仅登记）；
--                                                       页面 PROMO（dashboard_page_metric 的 CHECK 放开）；菜单「推广分析」dashboard:promo:view
--   ⑥ 2026-11-05  管理后台 · AI 助手                      按钮权限 copilot:chat:use（系统管理下，无新表；对话不落库，次数记在 copilot_usage_log）
--
-- 上线记录：
--   （尚未上线）
-- ============================================================================


-- ----------------------------------------------------------------------------
-- ① 2026-11-02 · Yata Copilot · 订阅简报 AI 摘要（新 JAR 启动前执行）
--   1. dashboard_subscription 加 ai_enabled（卡片带 AI 摘要，默认关）、ai_model（模型 id，为空用 copilot.default-model）
--   2. copilot_usage_log：每次调用模型记一行（功能、站点、模型、结果、token 用量、耗时），用于对账和每日次数上限
--   回滚：ALTER TABLE dashboard_subscription DROP COLUMN IF EXISTS ai_model, DROP COLUMN IF EXISTS ai_enabled;
--         DROP TABLE IF EXISTS copilot_usage_log;
-- ----------------------------------------------------------------------------
BEGIN;

ALTER TABLE dashboard_subscription ADD COLUMN IF NOT EXISTS ai_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE dashboard_subscription ADD COLUMN IF NOT EXISTS ai_model   VARCHAR(64);
COMMENT ON COLUMN dashboard_subscription.ai_enabled IS '卡片带 AI 摘要（Yata Copilot）；生成失败时照常发送、不带摘要';
COMMENT ON COLUMN dashboard_subscription.ai_model   IS 'AI 模型 id（如 claude-haiku-5-5）；为空用 copilot.default-model';

CREATE TABLE IF NOT EXISTS copilot_usage_log (
    id              BIGSERIAL       PRIMARY KEY,
    feature         VARCHAR(32)     NOT NULL,
    site_code       VARCHAR(32),
    ref_id          VARCHAR(64),
    model           VARCHAR(64)     NOT NULL,
    status          VARCHAR(16)     NOT NULL,
    input_tokens    INTEGER,
    output_tokens   INTEGER,
    latency_ms      INTEGER,
    error           VARCHAR(500),
    operator        VARCHAR(64),
    create_time     TIMESTAMP       NOT NULL DEFAULT localtimestamp,
    CONSTRAINT ck_copilot_usage_status CHECK (status IN ('SUCCESS', 'FAILED', 'REJECTED'))
);
CREATE INDEX IF NOT EXISTS idx_copilot_usage_feature_time ON copilot_usage_log (feature, create_time);

COMMENT ON TABLE  copilot_usage_log           IS 'AI 调用记录：一次调用模型一行（对账、每日次数上限）';
COMMENT ON COLUMN copilot_usage_log.feature   IS 'BRIEF 订阅简报摘要（以后 IMPORT Excel 导入等）';
COMMENT ON COLUMN copilot_usage_log.ref_id    IS '业务 ID：订阅 ID；预览为 preview';
COMMENT ON COLUMN copilot_usage_log.status    IS 'SUCCESS 采用 / FAILED 调用失败 / REJECTED 返回了但未通过后端校验（如出现数据里没有的数字）';

COMMIT;

-- ① 自检：应返回 ai_enabled、ai_model 两行，usage_table 不为空
SELECT column_name FROM information_schema.columns
 WHERE table_name = 'dashboard_subscription' AND column_name IN ('ai_enabled', 'ai_model') ORDER BY 1;
SELECT to_regclass('copilot_usage_log') AS usage_table;
-- ① 对账（按需手动执行）：本月按模型汇总 token
-- SELECT model, count(*) AS calls, sum(input_tokens) AS input_tokens, sum(output_tokens) AS output_tokens
--   FROM copilot_usage_log WHERE create_time >= date_trunc('month', current_date) GROUP BY model;


-- ----------------------------------------------------------------------------
-- ② 2026-11-04 · 站点管理 › 指標配置 · 两套默认展示指标集（新 JAR 启动前执行）
--   dashboard_page_metric_site 加 display_type：同一站点同一页面最多两套指标顺序与显示
--     DEFAULT 默认指标集（运营总览 / 指标汇总取数用这一套；已有数据全部归为 DEFAULT，行为不变）
--     REPORT  报表指标集（目前只做配置，不影响取数）
--   唯一键 uk_page_metric_site 由 (site_code, page_code, metric_code) 改为 (site_code, page_code, display_type, metric_code)
--   回滚（先删 REPORT 行，否则唯一键建不回去）：
--     DELETE FROM dashboard_page_metric_site WHERE display_type <> 'DEFAULT';
--     ALTER TABLE dashboard_page_metric_site DROP CONSTRAINT IF EXISTS ck_page_metric_site_display,
--       DROP CONSTRAINT IF EXISTS uk_page_metric_site, ADD CONSTRAINT uk_page_metric_site UNIQUE (site_code, page_code, metric_code),
--       DROP COLUMN IF EXISTS display_type;
-- ----------------------------------------------------------------------------
BEGIN;

ALTER TABLE dashboard_page_metric_site ADD COLUMN IF NOT EXISTS display_type VARCHAR(16) NOT NULL DEFAULT 'DEFAULT';

ALTER TABLE dashboard_page_metric_site DROP CONSTRAINT IF EXISTS ck_page_metric_site_display;
ALTER TABLE dashboard_page_metric_site ADD CONSTRAINT ck_page_metric_site_display CHECK (display_type IN ('DEFAULT', 'REPORT'));

ALTER TABLE dashboard_page_metric_site DROP CONSTRAINT IF EXISTS uk_page_metric_site;
ALTER TABLE dashboard_page_metric_site ADD CONSTRAINT uk_page_metric_site UNIQUE (site_code, page_code, display_type, metric_code);

COMMENT ON COLUMN dashboard_page_metric_site.display_type IS '指标集：DEFAULT 默认（页面取数用）/ REPORT 报表（暂只做配置）；同一站点同一页面每套各自一份顺序与显示';

COMMIT;

-- ② 自检：display_type 列存在；唯一键含 display_type；已有行都是 DEFAULT
SELECT column_name, data_type, column_default FROM information_schema.columns
 WHERE table_name = 'dashboard_page_metric_site' AND column_name = 'display_type';
SELECT pg_get_constraintdef(oid) AS uk FROM pg_constraint WHERE conname = 'uk_page_metric_site';
SELECT display_type, count(*) FROM dashboard_page_metric_site GROUP BY display_type;


-- ----------------------------------------------------------------------------
-- ③ 2026-11-04 · 推广分析 · 预算与消耗（FY-56）菜单与权限（新 JAR 上线后执行即可，顺序不限）
--   目录「推广分析」（path promo）› 页面「预算与消耗」（component promo/budget/index，权限 promo:budget:view：查看、下载模板）
--   按钮「导入」promo:budget:import（上传校验；数据存 UDS，写入暂未接入，本系统不建表、不存文件）
--   授给超级管理员角色（role_id = 1）；其他角色在「角色管理」里勾选。
--   回滚：DELETE FROM sys_role_menu WHERE menu_id IN (SELECT menu_id FROM sys_menu WHERE perms LIKE 'promo:budget:%' OR (path = 'promo' AND parent_id = 0 AND menu_type = 'M'));
--         DELETE FROM sys_menu_i18n WHERE menu_id IN (同上);  DELETE FROM sys_menu WHERE menu_id IN (同上);（先删按钮、页面，再删目录）
-- ----------------------------------------------------------------------------
BEGIN;

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '推广分析', 0, 9, 'promo', NULL, '', '',
       1, 0, 'M', '0', '0', '', 'chart', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '推广分析（FY-46）'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = 'promo' AND parent_id = 0 AND menu_type = 'M');

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '预算与消耗', d.menu_id, 1, 'budget', 'promo/budget/index', '', '',
       1, 0, 'C', '0', '0', 'promo:budget:view', 'money', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '按日 × 推广渠道的消耗与预算；只能通过文件导入（FY-56）'
FROM sys_menu d
WHERE d.path = 'promo' AND d.parent_id = 0 AND d.menu_type = 'M'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'promo:budget:view');

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '导入', p.menu_id, 1, '', '', '', '',
       1, 0, 'F', '0', '0', 'promo:budget:import', '#', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '导入消耗 / 预算（.xlsx、.csv）'
FROM sys_menu p
WHERE p.perms = 'promo:budget:view'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'promo:budget:import');

INSERT INTO sys_menu_i18n (menu_id, locale, menu_name, remark)
SELECT m.menu_id, v.locale, v.menu_name, v.remark
FROM sys_menu m
JOIN (VALUES
    ('promo:budget:view',   'zh-CN', '预算与消耗', '按日 × 推广渠道的消耗与预算'),
    ('promo:budget:view',   'zh-TW', '預算與消耗', '按日 × 推廣渠道的消耗與預算'),
    ('promo:budget:view',   'en-US', 'Budget & Spend', 'Daily spend and budget by channel'),
    ('promo:budget:import', 'zh-CN', '导入', '导入消耗 / 预算'),
    ('promo:budget:import', 'zh-TW', '匯入', '匯入消耗 / 預算'),
    ('promo:budget:import', 'en-US', 'Import', 'Import spend / budget')
) AS v(perms, locale, menu_name, remark) ON v.perms = m.perms
WHERE NOT EXISTS (SELECT 1 FROM sys_menu_i18n i WHERE i.menu_id = m.menu_id AND i.locale = v.locale);

INSERT INTO sys_menu_i18n (menu_id, locale, menu_name, remark)
SELECT m.menu_id, v.locale, v.menu_name, v.remark
FROM sys_menu m
JOIN (VALUES
    ('zh-CN', '推广分析', '推广分析'),
    ('zh-TW', '推廣分析', '推廣分析'),
    ('en-US', 'Promotion', 'Promotion analytics')
) AS v(locale, menu_name, remark) ON TRUE
WHERE m.path = 'promo' AND m.parent_id = 0 AND m.menu_type = 'M'
  AND NOT EXISTS (SELECT 1 FROM sys_menu_i18n i WHERE i.menu_id = m.menu_id AND i.locale = v.locale);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id
FROM sys_menu m
WHERE (m.perms IN ('promo:budget:view', 'promo:budget:import') OR (m.path = 'promo' AND m.parent_id = 0 AND m.menu_type = 'M'))
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.role_id = 1)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id);

COMMIT;

-- ③ 自检：应有 3 行（目录、页面、按钮）；重新登录后台后出现「推广分析 › 预算与消耗」
SELECT menu_id, menu_name, parent_id, menu_type, path, component, perms FROM sys_menu
 WHERE perms LIKE 'promo:budget:%' OR (path = 'promo' AND parent_id = 0 AND menu_type = 'M') ORDER BY menu_id;


-- ----------------------------------------------------------------------------
-- ④ 2026-11-04 · 系统管理 · 配置中心（新 JAR 启动前执行）
--   config_center：按「配置编码 + 站点」存业务配置，配置值为 JSON；站点为空串 = 全站通用（站点没有自己的配置时用它）。
--   第一个用到的编码：PROMO_CHANNELS（推广分析 · 预算与消耗的推广商 / 推广渠道字典，原 application.yml 的 promo.channels 不再使用）。
--   站点改编码时随其他带 site_code 的表一起级联更新（按 information_schema 自动找到本表；全站通用的空串不受影响）。
--   菜单：系统管理 › 配置中心（system/configCenter/index），按钮 新增 / 修改 / 删除；授给超级管理员角色（role_id = 1）。
--   回滚：DELETE FROM sys_role_menu / sys_menu_i18n / sys_menu 中 perms LIKE 'system:configCenter:%' 的行（先按钮后页面）；
--         DROP TABLE IF EXISTS config_center;
-- ----------------------------------------------------------------------------
BEGIN;

CREATE TABLE IF NOT EXISTS config_center (
    id              BIGSERIAL       PRIMARY KEY,
    config_code     VARCHAR(64)     NOT NULL,
    site_code       VARCHAR(32)     NOT NULL DEFAULT '',
    page_name       VARCHAR(64)     NOT NULL,
    description     VARCHAR(500)    NOT NULL,
    config_value    TEXT            NOT NULL,
    create_by       VARCHAR(64)     DEFAULT '',
    create_time     TIMESTAMP       NOT NULL DEFAULT localtimestamp,
    update_by       VARCHAR(64)     DEFAULT '',
    update_time     TIMESTAMP       NOT NULL DEFAULT localtimestamp,
    CONSTRAINT uk_config_center UNIQUE (config_code, site_code),
    CONSTRAINT ck_config_center_code CHECK (config_code ~ '^[A-Z][A-Z0-9_]{1,63}$')
);

COMMENT ON TABLE  config_center              IS '配置中心：按配置编码 + 站点存业务配置（JSON），后台「系统管理 › 配置中心」维护';
COMMENT ON COLUMN config_center.config_code  IS '配置编码（大写字母、数字、下划线），如 PROMO_CHANNELS';
COMMENT ON COLUMN config_center.site_code    IS '站点编码（monitor_site_setting.site_code）；空串 = 全站通用';
COMMENT ON COLUMN config_center.page_name    IS '所属页面，如「推廣分析 · 預算與消耗」';
COMMENT ON COLUMN config_center.description  IS '含义';
COMMENT ON COLUMN config_center.config_value IS '配置值（JSON）；代码里登记的编码保存时另按结构校验';

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '配置中心', 1, 13, 'configCenter', 'system/configCenter/index', '', '',
       1, 0, 'C', '0', '0', 'system:configCenter:list', 'edit', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '按配置编码 + 站点维护业务配置（config_center）'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:configCenter:list');

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) FROM sys_menu) + v.order_num, v.menu_name, p.menu_id, v.order_num, '', '', '', '',
       1, 0, 'F', '0', '0', v.perms, '#', 'admin',
       CURRENT_TIMESTAMP, '', NULL, v.remark
FROM sys_menu p
CROSS JOIN (VALUES
    (1, '新增配置', 'system:configCenter:add',    '新增配置'),
    (2, '修改配置', 'system:configCenter:edit',   '修改配置'),
    (3, '删除配置', 'system:configCenter:remove', '删除配置')
) AS v(order_num, menu_name, perms, remark)
WHERE p.perms = 'system:configCenter:list'
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.perms = v.perms);

INSERT INTO sys_menu_i18n (menu_id, locale, menu_name, remark)
SELECT m.menu_id, v.locale, v.menu_name, v.remark
FROM sys_menu m
JOIN (VALUES
    ('system:configCenter:list',   'zh-CN', '配置中心', '按配置编码 + 站点维护业务配置'),
    ('system:configCenter:list',   'zh-TW', '配置中心', '按配置編碼 + 站點維護業務配置'),
    ('system:configCenter:list',   'en-US', 'Config Center', 'Business configs by code and site'),
    ('system:configCenter:add',    'zh-CN', '新增配置', '新增配置'),
    ('system:configCenter:add',    'zh-TW', '新增配置', '新增配置'),
    ('system:configCenter:add',    'en-US', 'Add Config', 'Add config'),
    ('system:configCenter:edit',   'zh-CN', '修改配置', '修改配置'),
    ('system:configCenter:edit',   'zh-TW', '修改配置', '修改配置'),
    ('system:configCenter:edit',   'en-US', 'Edit Config', 'Edit config'),
    ('system:configCenter:remove', 'zh-CN', '删除配置', '删除配置'),
    ('system:configCenter:remove', 'zh-TW', '刪除配置', '刪除配置'),
    ('system:configCenter:remove', 'en-US', 'Delete Config', 'Delete config')
) AS v(perms, locale, menu_name, remark) ON v.perms = m.perms
WHERE NOT EXISTS (SELECT 1 FROM sys_menu_i18n i WHERE i.menu_id = m.menu_id AND i.locale = v.locale);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id
FROM sys_menu m
WHERE m.perms IN ('system:configCenter:list', 'system:configCenter:add', 'system:configCenter:edit', 'system:configCenter:remove')
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.role_id = 1)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id);

COMMIT;

-- ④ 自检：表已建好；菜单 4 行（1 个页面 + 3 个按钮）
SELECT to_regclass('config_center') AS config_center_table;
SELECT menu_id, menu_name, parent_id, menu_type, perms FROM sys_menu WHERE perms LIKE 'system:configCenter:%' ORDER BY menu_id;


-- ----------------------------------------------------------------------------
-- ⑤ 2026-11-05 · 推广分析（FY-46）· 概览指标登记 + 指标管理「可歸因」开关 + 页面权限（新 JAR 启动前执行）
--   1. dashboard_metric_card 加 attributable（可歸因：概览选中该卡并带 attribution 时返回变化归因，默认关；指标管理里开关）
--   2. 分组 inv 投放 / flow 流量轉化（Q6）；16 个推广分析概览指标，编码加 promo 前缀（与运营总览的 reg / ftd / net 等区分，
--      接口里仍用 spend / reg / ftd …，后端换算）；status = '1' 仅登记（UDS 未接入，不进运营总览注册表、不能建告警规则）
--   3. 页面 PROMO：dashboard_page_metric 的页面 CHECK 加 PROMO；推广分析页 is_default = 默认显示的卡（暂不支持按站点调整顺序）
--   4. 菜单：目录「推广分析」下加页面「推广分析」（promo/analysis/index，权限 dashboard:promo:view：看页面、取数、导出），授给 role 1
--   回滚：DELETE FROM dashboard_page_metric WHERE page_code = 'PROMO';
--         DELETE FROM dashboard_metric_card WHERE metric_code LIKE 'promo%' AND status = '1'; DELETE FROM dashboard_metric_group WHERE group_code IN ('inv','flow');
--         ALTER TABLE dashboard_metric_card DROP COLUMN IF EXISTS attributable;（CHECK 改回见各自原脚本）；
--         DELETE FROM sys_role_menu / sys_menu_i18n / sys_menu WHERE perms = 'dashboard:promo:view' 对应的 menu_id
-- ----------------------------------------------------------------------------
BEGIN;

ALTER TABLE dashboard_metric_card ADD COLUMN IF NOT EXISTS attributable BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN dashboard_metric_card.attributable IS '可歸因：推广分析概览选中该卡并请求 attribution 时返回按推广商 / 推广渠道 / 终端的变化归因';

-- 页面 CHECK 放开 PROMO（先删后加，可重复执行）
ALTER TABLE dashboard_page_metric DROP CONSTRAINT IF EXISTS ck_page_code;
ALTER TABLE dashboard_page_metric ADD CONSTRAINT ck_page_code CHECK (page_code IN ('OVERVIEW','SUMMARY','PROMO'));
ALTER TABLE dashboard_page_metric DROP CONSTRAINT IF EXISTS ck_page_attrs;
ALTER TABLE dashboard_page_metric ADD CONSTRAINT ck_page_attrs CHECK (
    (page_code = 'OVERVIEW' AND is_default = TRUE)
    OR (page_code = 'SUMMARY' AND emphasis = 'NORMAL')
    OR page_code = 'PROMO'
);
COMMENT ON COLUMN dashboard_page_metric.page_code IS 'OVERVIEW 运营总览指标墙 / SUMMARY 指标汇总表 / PROMO 推广分析概览';

INSERT INTO dashboard_metric_group (group_code, group_name, group_name_en, sort_no, remark) VALUES
('inv', '投放',     'Spend',              110, '推广分析概览'),
('flow', '流量轉化', 'Traffic & conversion', 120, '推广分析概览')
ON CONFLICT (group_code) DO NOTHING;

INSERT INTO dashboard_metric_card (
    metric_code, metric_name, metric_name_en, group_code, update_frequency, single_day_only, chart_type,
    value_format, value_decimals, direction, show_as_card, sort_no, calc_type, agg_type,
    monitorable, allow_negative, attributable, status, create_by, create_time, remark
) VALUES
('promoSpend',   '消耗',       'Spend',               'inv', 'day',  FALSE, 'LINE', 'MONEY', NULL, 'flat', TRUE, 1010, 'NONE', 'SUM',     FALSE, FALSE, FALSE, '1', 'admin', CURRENT_TIMESTAMP, '推广分析 spend：运营导入的消耗合计（全部数据），按日'),
('promoBudget',  '預算',       'Budget',              'inv', 'day',  FALSE, 'LINE', 'MONEY', NULL, 'flat', TRUE, 1020, 'NONE', 'SUM',     FALSE, FALSE, FALSE, '1', 'admin', CURRENT_TIMESTAMP, '推广分析 budget：运营导入的预算合计，按日'),
('promoCpa',     'CPA',        'CPA',                 'inv', 'day',  FALSE, 'LINE', 'MONEY', 2,    'down', TRUE, 1030, 'NONE', 'FORMULA', FALSE, FALSE, FALSE, '1', 'admin', CURRENT_TIMESTAMP, '推广分析 cpa：有效格子的消耗 ÷ 有效格子的注册人数'),
('promoFtdCost', '首存成本',   'FTD cost',            'inv', 'day',  FALSE, 'LINE', 'MONEY', 2,    'down', TRUE, 1040, 'NONE', 'FORMULA', FALSE, FALSE, FALSE, '1', 'admin', CURRENT_TIMESTAMP, '推广分析 ftdCost：有效格子的消耗 ÷ 有效格子的首存人数'),
('promoNet',     '淨充值',     'Net deposit',         'inv', 'hour', FALSE, 'LINE', 'MONEY', NULL, 'up',   TRUE, 1050, 'NONE', 'SUM',     FALSE, FALSE, FALSE, '1', 'admin', CURRENT_TIMESTAMP, '推广分析 net：暂定＝存款總額（与运营总览的存提差 net 不同）'),
('promoRoi',     'D0 ROI',     'D0 ROI',              'inv', 'day',  FALSE, 'LINE', 'PCT',   NULL, 'up',   TRUE, 1060, 'NONE', 'FORMULA', FALSE, TRUE,  FALSE, '1', 'admin', CURRENT_TIMESTAMP, '推广分析 roi：有效格子的淨充值 ÷ 有效格子的消耗 − 1'),
('promoLpUV',    'UV-落地頁',  'Landing page UV',     'flow', 'hour', FALSE, 'LINE', 'INT',   NULL, 'up',   TRUE, 1110, 'NONE', 'SUM',     FALSE, FALSE, FALSE, '1', 'admin', CURRENT_TIMESTAMP, '推广分析 lpUV：落地页去重访问人数'),
('promoHUV',     'UV-主頁',    'Home page UV',        'flow', 'hour', FALSE, 'LINE', 'INT',   NULL, 'up',   TRUE, 1120, 'NONE', 'SUM',     FALSE, FALSE, FALSE, '1', 'admin', CURRENT_TIMESTAMP, '推广分析 hUV：主页去重访问人数（含 Web 与 APP 内打开）'),
('promoDl',      '下載點擊',   'Download clicks',     'flow', 'hour', FALSE, 'LINE', 'INT',   NULL, 'up',   TRUE, 1130, 'NONE', 'SUM',     FALSE, FALSE, FALSE, '1', 'admin', CURRENT_TIMESTAMP, '推广分析 dl：点击下载 APK 的次数'),
('promoAct',     '激活',       'Activations',         'flow', 'hour', FALSE, 'LINE', 'INT',   NULL, 'up',   TRUE, 1140, 'NONE', 'SUM',     FALSE, FALSE, FALSE, '1', 'admin', CURRENT_TIMESTAMP, '推广分析 act：Android 安装后首次启动的人数'),
('promoReg',     '註冊人數',   'Registrations',       'flow', 'hour', FALSE, 'LINE', 'INT',   NULL, 'up',   TRUE, 1150, 'NONE', 'SUM',     FALSE, FALSE, FALSE, '1', 'admin', CURRENT_TIMESTAMP, '推广分析 reg：按注册时的推广渠道与终端归属'),
('promoRegR',    '註冊轉化率', 'Registration rate',   'flow', 'hour', FALSE, 'LINE', 'PCT',   NULL, 'up',   TRUE, 1160, 'NONE', 'FORMULA', FALSE, FALSE, FALSE, '1', 'admin', CURRENT_TIMESTAMP, '推广分析 regR：注册人数 ÷ 入口 UV（按渠道入口类型取 UV-落地頁或 UV-主頁）'),
('promoFtd',     '首存人數',   'FTD users',           'flow', 'hour', FALSE, 'LINE', 'INT',   NULL, 'up',   TRUE, 1170, 'NONE', 'SUM',     FALSE, FALSE, FALSE, '1', 'admin', CURRENT_TIMESTAMP, '推广分析 ftd'),
('promoFtdR',    '首存轉化率', 'FTD rate',            'flow', 'hour', FALSE, 'LINE', 'PCT',   NULL, 'up',   TRUE, 1180, 'NONE', 'FORMULA', FALSE, FALSE, FALSE, '1', 'admin', CURRENT_TIMESTAMP, '推广分析 ftdR：首存人数 ÷ 注册人数'),
('promoFtdAmt',  '首存金額',   'FTD amount',          'flow', 'hour', FALSE, 'LINE', 'MONEY', NULL, 'up',   TRUE, 1190, 'NONE', 'SUM',     FALSE, FALSE, FALSE, '1', 'admin', CURRENT_TIMESTAMP, '推广分析 ftdAmt'),
('promoRd',      '復存人數',   'Repeat depositors',   'flow', 'hour', FALSE, 'LINE', 'INT',   NULL, 'up',   TRUE, 1200, 'NONE', 'SUM',     FALSE, FALSE, FALSE, '1', 'admin', CURRENT_TIMESTAMP, '推广分析 rd：首存后再次存款；多天按日累加')
ON CONFLICT (metric_code) DO NOTHING;

-- 推广分析概览：16 个可选，is_default = 打开页面默认显示的 10 个（Q7 默认做法：消耗、预算、CPA、首存成本、淨充值、D0 ROI、注册人数、注册转化率、首存人数、首存转化率；不分大小卡）
INSERT INTO dashboard_page_metric (page_code, metric_code, sort_no, is_default, emphasis, create_by, remark) VALUES
('PROMO', 'promoSpend',   10,  TRUE,  'NORMAL', 'admin', '消耗'),
('PROMO', 'promoBudget',  20,  TRUE,  'NORMAL', 'admin', '預算'),
('PROMO', 'promoCpa',     30,  TRUE,  'NORMAL', 'admin', 'CPA'),
('PROMO', 'promoFtdCost', 40,  TRUE,  'NORMAL', 'admin', '首存成本'),
('PROMO', 'promoNet',     50,  TRUE,  'NORMAL', 'admin', '淨充值'),
('PROMO', 'promoRoi',     60,  TRUE,  'NORMAL', 'admin', 'D0 ROI'),
('PROMO', 'promoLpUV',    70,  FALSE, 'NORMAL', 'admin', 'UV-落地頁'),
('PROMO', 'promoHUV',     80,  FALSE, 'NORMAL', 'admin', 'UV-主頁'),
('PROMO', 'promoDl',      90,  FALSE, 'NORMAL', 'admin', '下載點擊'),
('PROMO', 'promoAct',     100, FALSE, 'NORMAL', 'admin', '激活'),
('PROMO', 'promoReg',     110, TRUE,  'NORMAL', 'admin', '註冊人數'),
('PROMO', 'promoRegR',    120, TRUE,  'NORMAL', 'admin', '註冊轉化率'),
('PROMO', 'promoFtd',     130, TRUE,  'NORMAL', 'admin', '首存人數'),
('PROMO', 'promoFtdR',    140, TRUE,  'NORMAL', 'admin', '首存轉化率'),
('PROMO', 'promoFtdAmt',  150, FALSE, 'NORMAL', 'admin', '首存金額'),
('PROMO', 'promoRd',      160, FALSE, 'NORMAL', 'admin', '復存人數')
ON CONFLICT (page_code, metric_code) DO NOTHING;

-- 菜单：推广分析页（目录由 ③ 建好）
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), '推广分析', d.menu_id, 0, 'analysis', 'promo/analysis/index', '', '',
       1, 0, 'C', '0', '0', 'dashboard:promo:view', 'chart', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '推广分析：概览、获客与留存、渠道表现、裂变层级（FY-46 / FY-59）'
FROM sys_menu d
WHERE d.path = 'promo' AND d.parent_id = 0 AND d.menu_type = 'M'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'dashboard:promo:view');

INSERT INTO sys_menu_i18n (menu_id, locale, menu_name, remark)
SELECT m.menu_id, v.locale, v.menu_name, v.remark
FROM sys_menu m
JOIN (VALUES
    ('zh-CN', '推广分析', '概览、获客与留存、渠道表现、裂变层级'),
    ('zh-TW', '推廣分析', '概覽、獲客與留存、渠道表現、裂變層級'),
    ('en-US', 'Promotion Analytics', 'Overview, acquisition & retention, channels, referral tree')
) AS v(locale, menu_name, remark) ON TRUE
WHERE m.perms = 'dashboard:promo:view'
  AND NOT EXISTS (SELECT 1 FROM sys_menu_i18n i WHERE i.menu_id = m.menu_id AND i.locale = v.locale);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id
FROM sys_menu m
WHERE m.perms = 'dashboard:promo:view'
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.role_id = 1)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id);

COMMIT;

-- ⑤ 自检：可歸因列已加；推广分析指标 16 个（status = 1）、页面关系 16 条（默认 10 个）；菜单 1 行
SELECT column_name, data_type, column_default FROM information_schema.columns
 WHERE table_name = 'dashboard_metric_card' AND column_name = 'attributable';
SELECT count(*) AS promo_cards FROM dashboard_metric_card WHERE metric_code LIKE 'promo%';
SELECT count(*) AS promo_page_rows, count(*) FILTER (WHERE is_default) AS promo_defaults FROM dashboard_page_metric WHERE page_code = 'PROMO';
SELECT menu_id, menu_name, parent_id, menu_type, perms FROM sys_menu WHERE perms = 'dashboard:promo:view';


-- ----------------------------------------------------------------------------
-- ⑥ 2026-11-05 · 管理后台 · AI 助手（右下角聊天）权限（新 JAR 上线后执行即可）
--   系统管理（menu_id = 1）下加按钮「AI 助手」copilot:chat:use；有这个权限的人在每个页面右下角看到 AI 助手。授给 role 1。
--   依赖 ① 的 copilot_usage_log（记次数，不记内容）。
--   回滚：DELETE FROM sys_role_menu / sys_menu_i18n / sys_menu WHERE perms = 'copilot:chat:use' 对应的 menu_id
-- ----------------------------------------------------------------------------
BEGIN;

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), 'AI 助手', 1, 99, '', '', '', '',
       1, 0, 'F', '0', '0', 'copilot:chat:use', '#', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '每个页面右下角的 AI 助手（带本站今日、昨日指标与告警）'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'copilot:chat:use');

INSERT INTO sys_menu_i18n (menu_id, locale, menu_name, remark)
SELECT m.menu_id, v.locale, v.menu_name, v.remark
FROM sys_menu m
JOIN (VALUES
    ('zh-CN', 'AI 助手', '每个页面右下角的 AI 助手'),
    ('zh-TW', 'AI 助手', '每個頁面右下角的 AI 助手'),
    ('en-US', 'AI Assistant', 'AI assistant on every page')
) AS v(locale, menu_name, remark) ON TRUE
WHERE m.perms = 'copilot:chat:use'
  AND NOT EXISTS (SELECT 1 FROM sys_menu_i18n i WHERE i.menu_id = m.menu_id AND i.locale = v.locale);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id
FROM sys_menu m
WHERE m.perms = 'copilot:chat:use'
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.role_id = 1)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id);

COMMIT;

-- ⑥ 自检：1 行
SELECT menu_id, menu_name, parent_id, menu_type, perms FROM sys_menu WHERE perms = 'copilot:chat:use';


-- ----------------------------------------------------------------------------
-- 新的改动从这里往下追加（⑦ …）
-- ----------------------------------------------------------------------------
