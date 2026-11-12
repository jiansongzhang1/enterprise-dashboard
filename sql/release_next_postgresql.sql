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
--   ⑦ 2026-11-09  个人自定义指标集（个人视图）一期        新表 dashboard_view_slot / _slot_item / _preset / dashboard_user_view；
--                                                       推广分析 3 个配置位（核心指标卡、渠道表现表格列、行维度）的可选项、默认与预置组合
--   ⑧ 2026-11-10  推广分析 · 概览指标补 UDS 取数对照        16 个 promo 指标的 source_table / source_field 与 remark 里的对照状态（无结构变化）
--   ⑨ 2026-11-10  系统管理 · API Key（系统对接）          新表 sys_api_key + 按钮 system:apikey:list/add/edit；数据平台用 X-Api-Key 调 /notification/lark/send
--   ⑩ 2026-11-10  个人视图 · 行维度去掉顺序限制            promo.channel.rows 的 rules 清空（推廣渠道不再要求排在推廣商之下）
--   ⑪ 2026-11-10  站点管理 · 实时站点                      monitor_site_setting.realtime（站点管理可编辑；getInfo sites[]、运营总览 realtime 返回）
--   ⑫ 2026-11-11  站点管理 · 数据起始日                    monitor_site_setting.data_start_date（站点管理可编辑；getInfo sites[].dataStartDate 返回）
--   ⑬ 2026-11-12  推广分析 · 去掉行维度「推廣關係」、淨充值改为存提差   promo.channel.rows 删 relation；promoNet 的取数对照与说明（无结构变化）
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
('promoNet',     '淨充值',     'Net deposit',         'inv', 'hour', FALSE, 'LINE', 'MONEY', NULL, 'up',   TRUE, 1050, 'NONE', 'SUM',     FALSE, FALSE, FALSE, '1', 'admin', CURRENT_TIMESTAMP, '推广分析 net：＝存款總額 deposit_amount（2026-11-09 已确认；与运营总览的存提差 net 不同）'),
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
-- ⑦ 2026-11-09 · 个人自定义指标集（个人视图）一期（新 JAR 启动前执行；没执行时视图接口 404、取数接口按原来的默认，不影响启动）
--   1. 定义表（研发 / 管理员维护，改了 1 分钟内热加载）：
--        dashboard_view_slot       配置位：页面上一处可自定义的列表（上下限、同组相邻、顺序规则、是否叠加站点排列）
--        dashboard_view_slot_item  可选项与全站默认（default_sort 不为空 = 默认选中，按它排序）
--        dashboard_view_preset     预置组合（如表格列的「標準」「投放日報」；is_default = 就是全站默认）
--   2. dashboard_user_view：个人视图，用户 + 站点 + 配置位唯一；等于默认且没有小状态时不存（管理员改默认，没自定义的人自动跟上）
--   3. 初始数据：推广分析 promo.overview.cards（16 选 1–10，默认 10 张，同推广分析页 is_default）、
--      promo.channel.columns（38 列 5 组，顺序完全由用户定、不要求同组相邻；標準 / 投放日報）、promo.channel.rows（5 个维度选 1–4 层，推廣渠道在推廣商之下）
--   回滚：DROP TABLE IF EXISTS dashboard_user_view, dashboard_view_preset, dashboard_view_slot_item, dashboard_view_slot;
-- ----------------------------------------------------------------------------
BEGIN;

CREATE TABLE IF NOT EXISTS dashboard_view_slot (
    slot_code         VARCHAR(64)   PRIMARY KEY,
    page_code         VARCHAR(32)   NOT NULL,
    name              VARCHAR(50)   NOT NULL,
    kind              VARCHAR(16)   NOT NULL,
    min_items         SMALLINT      NOT NULL DEFAULT 1,
    max_items         SMALLINT,
    group_adjacent    BOOLEAN       NOT NULL DEFAULT FALSE,
    rules             TEXT,
    site_layout_page  VARCHAR(32),
    sort_no           INTEGER       NOT NULL DEFAULT 0,
    status            CHAR(1)       NOT NULL DEFAULT '0',
    create_by         VARCHAR(64)   DEFAULT '',
    create_time       TIMESTAMP     NOT NULL DEFAULT localtimestamp,
    update_time       TIMESTAMP     NOT NULL DEFAULT localtimestamp,
    CONSTRAINT ck_view_slot_kind   CHECK (kind IN ('METRICS', 'COLUMNS', 'DIMENSIONS')),
    CONSTRAINT ck_view_slot_status CHECK (status IN ('0', '1')),
    CONSTRAINT ck_view_slot_range  CHECK (min_items >= 0 AND (max_items IS NULL OR max_items >= min_items))
);
COMMENT ON TABLE  dashboard_view_slot                  IS '个人视图 · 配置位：页面上一处可自定义的列表';
COMMENT ON COLUMN dashboard_view_slot.slot_code        IS '如 promo.overview.cards；前端用它读写';
COMMENT ON COLUMN dashboard_view_slot.page_code        IS '所属页面（PROMO / OVERVIEW / SUMMARY）；读写需要该页面的查看权限';
COMMENT ON COLUMN dashboard_view_slot.kind             IS 'METRICS 指标卡 / COLUMNS 表格列 / DIMENSIONS 行维度路径';
COMMENT ON COLUMN dashboard_view_slot.max_items        IS '最多选几个；为空 = 不限';
COMMENT ON COLUMN dashboard_view_slot.group_adjacent   IS '同组的项必须相邻（表格列）';
COMMENT ON COLUMN dashboard_view_slot.rules            IS '顺序规则 JSON：[{"before":"agent","after":"channel","message":"…"}]';
COMMENT ON COLUMN dashboard_view_slot.site_layout_page IS '叠加哪个页面的站点排列（OVERVIEW / SUMMARY）；为空不叠加';
COMMENT ON COLUMN dashboard_view_slot.status           IS '0 启用 / 1 停用（停用后 GET 不返回、保存 404，个人记录保留）';

CREATE TABLE IF NOT EXISTS dashboard_view_slot_item (
    slot_code     VARCHAR(64)   NOT NULL,
    item_code     VARCHAR(64)   NOT NULL,
    item_name     VARCHAR(50),
    group_code    VARCHAR(32),
    group_name    VARCHAR(50),
    sort_no       INTEGER       NOT NULL DEFAULT 0,
    default_sort  INTEGER,
    status        CHAR(1)       NOT NULL DEFAULT '0',
    update_time   TIMESTAMP     NOT NULL DEFAULT localtimestamp,
    PRIMARY KEY (slot_code, item_code),
    CONSTRAINT ck_view_item_status CHECK (status IN ('0', '1'))
);
COMMENT ON TABLE  dashboard_view_slot_item              IS '个人视图 · 配置位的可选项与全站默认';
COMMENT ON COLUMN dashboard_view_slot_item.item_code    IS '项编码，与取数接口一致（推广分析：spend、reg …；列 lpPV …；维度 agent …）';
COMMENT ON COLUMN dashboard_view_slot_item.item_name    IS '显示名；为空取项来源（指标字典 / 推广分析列定义）的名称';
COMMENT ON COLUMN dashboard_view_slot_item.default_sort IS '全站默认里的顺序；为空 = 默认不选（配置位有 is_default 的预置组合时以组合为准）';
COMMENT ON COLUMN dashboard_view_slot_item.status       IS '0 启用 / 1 停用（停用即对所有人下线）';

CREATE TABLE IF NOT EXISTS dashboard_view_preset (
    slot_code    VARCHAR(64)   NOT NULL,
    preset_code  VARCHAR(32)   NOT NULL,
    name         VARCHAR(50)   NOT NULL,
    tip          VARCHAR(200),
    items        TEXT          NOT NULL,
    is_default   BOOLEAN       NOT NULL DEFAULT FALSE,
    sort_no      INTEGER       NOT NULL DEFAULT 0,
    update_time  TIMESTAMP     NOT NULL DEFAULT localtimestamp,
    PRIMARY KEY (slot_code, preset_code)
);
COMMENT ON TABLE  dashboard_view_preset            IS '个人视图 · 预置组合（如表格列的標準、投放日報）';
COMMENT ON COLUMN dashboard_view_preset.items      IS '按顺序的项编码，逗号分隔';
COMMENT ON COLUMN dashboard_view_preset.is_default IS '是否就是该配置位的全站默认；为 true 时优先于 slot_item.default_sort';

CREATE TABLE IF NOT EXISTS dashboard_user_view (
    id           BIGSERIAL     PRIMARY KEY,
    user_id      BIGINT        NOT NULL,
    site_code    VARCHAR(32)   NOT NULL,
    slot_code    VARCHAR(64)   NOT NULL,
    mode         VARCHAR(8)    NOT NULL DEFAULT 'DEFAULT',
    preset_code  VARCHAR(32),
    items        TEXT,
    last_custom  TEXT,
    attrs        TEXT,
    update_time  TIMESTAMP     NOT NULL DEFAULT localtimestamp,
    CONSTRAINT uk_user_view UNIQUE (user_id, site_code, slot_code),
    CONSTRAINT ck_user_view_mode CHECK (mode IN ('DEFAULT', 'PRESET', 'CUSTOM'))
);
COMMENT ON TABLE  dashboard_user_view             IS '个人视图：某人在某站点、某配置位上的选择（只对自己生效）';
COMMENT ON COLUMN dashboard_user_view.user_id     IS 'sys_user.user_id，取自登录令牌';
COMMENT ON COLUMN dashboard_user_view.site_code   IS '站点编码（大写），取自请求头 X-Site-Code；每个站点各存一份';
COMMENT ON COLUMN dashboard_user_view.mode        IS 'DEFAULT 跟随默认（只存了小状态）/ CUSTOM 自己挑的 / PRESET 跟随某个预置组合';
COMMENT ON COLUMN dashboard_user_view.items       IS 'mode = CUSTOM 时的项编码 JSON 数组，按显示顺序';
COMMENT ON COLUMN dashboard_user_view.last_custom IS '最近一次自定义（JSON 数组），切到预置组合后仍可切回';
COMMENT ON COLUMN dashboard_user_view.attrs       IS '小状态 JSON 对象（如 {"selected":"spend","spark":true}），服务端只限 4 KB、不解读';

-- 配置位
INSERT INTO dashboard_view_slot (slot_code, page_code, name, kind, min_items, max_items, group_adjacent, rules, site_layout_page, sort_no, create_by) VALUES
('promo.overview.cards',  'PROMO', '核心指標', 'METRICS',    1, 10,   FALSE, '[]', NULL, 10, 'admin'),
('promo.channel.columns', 'PROMO', '表格列',   'COLUMNS',    1, NULL, FALSE, '[]', NULL, 20, 'admin'),
('promo.channel.rows',    'PROMO', '行維度',   'DIMENSIONS', 1, 4,    FALSE, '[]', NULL, 30, 'admin')
ON CONFLICT (slot_code) DO NOTHING;

-- 核心指标卡：编码同概览接口 metrics；名称取指标字典（item_name 为空）；默认 10 张同推广分析页 is_default
INSERT INTO dashboard_view_slot_item (slot_code, item_code, item_name, group_code, group_name, sort_no, default_sort) VALUES
('promo.overview.cards', 'spend',   NULL, 'inv',  '投放',     10,  10),
('promo.overview.cards', 'budget',  NULL, 'inv',  '投放',     20,  20),
('promo.overview.cards', 'cpa',     NULL, 'inv',  '投放',     30,  30),
('promo.overview.cards', 'ftdCost', NULL, 'inv',  '投放',     40,  40),
('promo.overview.cards', 'net',     NULL, 'inv',  '投放',     50,  50),
('promo.overview.cards', 'roi',     NULL, 'inv',  '投放',     60,  60),
('promo.overview.cards', 'lpUV',    NULL, 'flow', '流量轉化', 70,  NULL),
('promo.overview.cards', 'hUV',     NULL, 'flow', '流量轉化', 80,  NULL),
('promo.overview.cards', 'dl',      NULL, 'flow', '流量轉化', 90,  NULL),
('promo.overview.cards', 'act',     NULL, 'flow', '流量轉化', 100, NULL),
('promo.overview.cards', 'reg',     NULL, 'flow', '流量轉化', 110, 110),
('promo.overview.cards', 'regR',    NULL, 'flow', '流量轉化', 120, 120),
('promo.overview.cards', 'ftd',     NULL, 'flow', '流量轉化', 130, 130),
('promo.overview.cards', 'ftdR',    NULL, 'flow', '流量轉化', 140, 140),
('promo.overview.cards', 'ftdAmt',  NULL, 'flow', '流量轉化', 150, NULL),
('promo.overview.cards', 'rd',      NULL, 'flow', '流量轉化', 160, NULL)
ON CONFLICT (slot_code, item_code) DO NOTHING;

-- 渠道表现 · 表格列：编码同 /dashboard/promo/channels/table 的 columns；名称取列定义；默认 = 預置組合「標準」
INSERT INTO dashboard_view_slot_item (slot_code, item_code, item_name, group_code, group_name, sort_no, default_sort) VALUES
('promo.channel.columns', 'lpPV',     NULL, 'traffic',    '流量',       10,  NULL),
('promo.channel.columns', 'lpUV',     NULL, 'traffic',    '流量',       20,  NULL),
('promo.channel.columns', 'hPV',      NULL, 'traffic',    '流量',       30,  NULL),
('promo.channel.columns', 'hUV',      NULL, 'traffic',    '流量',       40,  NULL),
('promo.channel.columns', 'dl',       NULL, 'traffic',    '流量',       50,  NULL),
('promo.channel.columns', 'act',      NULL, 'traffic',    '流量',       60,  NULL),
('promo.channel.columns', 'reg',      NULL, 'conversion', '轉化',       70,  NULL),
('promo.channel.columns', 'regR',     NULL, 'conversion', '轉化',       80,  NULL),
('promo.channel.columns', 'ftd',      NULL, 'conversion', '轉化',       90,  NULL),
('promo.channel.columns', 'ftdR',     NULL, 'conversion', '轉化',       100, NULL),
('promo.channel.columns', 'depR',     NULL, 'conversion', '轉化',       110, NULL),
('promo.channel.columns', 'ftdAmt',   NULL, 'conversion', '轉化',       120, NULL),
('promo.channel.columns', 'rd',       NULL, 'conversion', '轉化',       130, NULL),
('promo.channel.columns', 'rdAmt',    NULL, 'conversion', '轉化',       140, NULL),
('promo.channel.columns', 'rdR',      NULL, 'conversion', '轉化',       150, NULL),
('promo.channel.columns', 'spend',    NULL, 'spend',      '投放',       160, NULL),
('promo.channel.columns', 'budget',   NULL, 'spend',      '投放',       170, NULL),
('promo.channel.columns', 'uvCost',   NULL, 'spend',      '投放',       180, NULL),
('promo.channel.columns', 'cpa',      NULL, 'spend',      '投放',       190, NULL),
('promo.channel.columns', 'ftdCost',  NULL, 'spend',      '投放',       200, NULL),
('promo.channel.columns', 'roi',      NULL, 'spend',      '投放',       210, NULL),
('promo.channel.columns', 'roas7',    NULL, 'retention',  '留存與回收', 220, NULL),
('promo.channel.columns', 'roas30',   NULL, 'retention',  '留存與回收', 230, NULL),
('promo.channel.columns', 'regRet1',  NULL, 'retention',  '留存與回收', 240, NULL),
('promo.channel.columns', 'regRet3',  NULL, 'retention',  '留存與回收', 250, NULL),
('promo.channel.columns', 'regRet7',  NULL, 'retention',  '留存與回收', 260, NULL),
('promo.channel.columns', 'regRet15', NULL, 'retention',  '留存與回收', 270, NULL),
('promo.channel.columns', 'regRet30', NULL, 'retention',  '留存與回收', 280, NULL),
('promo.channel.columns', 'depU',     NULL, 'value',      '價值',       290, NULL),
('promo.channel.columns', 'dep',      NULL, 'value',      '價值',       300, NULL),
('promo.channel.columns', 'wdU',      NULL, 'value',      '價值',       310, NULL),
('promo.channel.columns', 'wd',       NULL, 'value',      '價值',       320, NULL),
('promo.channel.columns', 'net',      NULL, 'value',      '價值',       330, NULL),
('promo.channel.columns', 'arppu',    NULL, 'value',      '價值',       340, NULL),
('promo.channel.columns', 'betU',     NULL, 'value',      '價值',       350, NULL),
('promo.channel.columns', 'bet',      NULL, 'value',      '價值',       360, NULL),
('promo.channel.columns', 'vbet',     NULL, 'value',      '價值',       370, NULL),
('promo.channel.columns', 'ggr',      NULL, 'value',      '價值',       380, NULL)
ON CONFLICT (slot_code, item_code) DO NOTHING;

-- 預置組合：標準（全站默认）、投放日報（同投放日报导出的 17 列，按组靠拢）
INSERT INTO dashboard_view_preset (slot_code, preset_code, name, tip, items, is_default, sort_no) VALUES
('promo.channel.columns', 'standard', '標準',     '常用的流量、轉化、投放與價值列',
 'lpUV,hUV,reg,regR,ftd,ftdR,ftdAmt,spend,cpa,ftdCost,roi,dep,net', TRUE, 10),
('promo.channel.columns', 'daily',    '投放日報', '與投放日報導出同一組指標',
 'spend,uvCost,cpa,roi,lpUV,hUV,dl,act,dep,wd,depU,wdU,arppu,reg,ftd,ftdR,depR', FALSE, 20)
ON CONFLICT (slot_code, preset_code) DO NOTHING;

-- 行维度：编码同 rows；默认 推廣商 › 推廣渠道
INSERT INTO dashboard_view_slot_item (slot_code, item_code, item_name, group_code, group_name, sort_no, default_sort) VALUES
('promo.channel.rows', 'agent',    NULL, 'dim', '維度', 10, 10),
('promo.channel.rows', 'channel',  NULL, 'dim', '維度', 20, 20),
('promo.channel.rows', 'terminal', NULL, 'dim', '維度', 30, NULL),
('promo.channel.rows', 'relation', NULL, 'dim', '維度', 40, NULL),
('promo.channel.rows', 'date',     NULL, 'dim', '維度', 50, NULL)
ON CONFLICT (slot_code, item_code) DO NOTHING;

COMMIT;

-- ⑦ 自检：配置位 3 个；可选项 16 / 38 / 5；预置组合 2 个；个人视图表已建
SELECT s.slot_code, s.name, s.min_items, s.max_items, s.group_adjacent,
       (SELECT count(*) FROM dashboard_view_slot_item i WHERE i.slot_code = s.slot_code) AS items,
       (SELECT count(*) FROM dashboard_view_slot_item i WHERE i.slot_code = s.slot_code AND i.default_sort IS NOT NULL) AS defaults,
       (SELECT count(*) FROM dashboard_view_preset p WHERE p.slot_code = s.slot_code) AS presets
FROM dashboard_view_slot s ORDER BY s.sort_no;
SELECT to_regclass('dashboard_user_view') AS user_view_table;


-- ----------------------------------------------------------------------------
-- 新的改动从这里往下追加（⑧ 起，编号顺延）
-- ----------------------------------------------------------------------------

-- ----------------------------------------------------------------------------
-- ⑧ 2026-11-10 · 推广分析 · 16 个概览指标补 UDS 取数对照（《推广分析 · 指标与 UDS 数据集字段映射》2026-11-05 @Daniel，
--   按 2026-11-10 的实现与确认修订；新 JAR 上线后执行即可，顺序不限）
--   只改 dashboard_metric_card 里 promo 前缀的 16 行，只影响「系统管理 › 指标管理」的显示，不影响推广分析页取数：
--     source_table / source_field = UDS 数据集与字段，只写纯标识符（指标管理保存时会校验：表只允许字母、数字、_ 和 .，
--     字段只允许字母、数字、_）；算出来的指标（CPA、转化率等）和没有来源的留空，公式写在 remark；
--     remark 第二行写对照状态（UDS：可映射 / 需确认 / 缺源）与原因。
--   不填 uds_hour_field 等四列：那是运营总览的线上配置对照，填了指标管理会显示「與配置不一致」。
--   只更新还没写过对照状态（remark 里没有「UDS：」）的行，或上游表 / 字段不合规的行（修正旧版写入的中文说明）；
--   指标管理里手工改过的不覆盖，可重复执行。
--   回滚：UPDATE dashboard_metric_card SET source_table = NULL, source_field = NULL WHERE metric_code LIKE 'promo%' AND status = '1';
--         （remark 回滚为 ⑤ 的原文，见 ⑤ 的 INSERT）
-- ----------------------------------------------------------------------------
BEGIN;

UPDATE dashboard_metric_card c
   SET source_table = v.source_table,
       source_field = v.source_field,
       remark       = v.remark,
       update_by    = 'admin',
       update_time  = CURRENT_TIMESTAMP
  FROM (VALUES
    ('promoSpend',   'import_yata_promo_cost',   'cost_amount',
     E'推广分析 spend：运营导入的消耗合计（全部数据），按 biz_date × 推广商 × 推广渠道，站点默认币种，次日导入\nUDS：可映射'),
    ('promoBudget',  'import_yata_promo_budget', 'budget_amount',
     E'推广分析 budget：运营导入的预算合计，按日（含今天）\nUDS：可映射'),
    ('promoCpa',     NULL,                       NULL,
     E'推广分析 cpa：有效格子的消耗 ÷ 有效格子的注册人数（import_yata_promo_cost.cost_amount ÷ dashboard_market_hour_v1.reg）\nUDS：缺源（行为数据没有推广渠道维度，算不出有效格子）'),
    ('promoFtdCost', NULL,                       NULL,
     E'推广分析 ftdCost：有效格子的消耗 ÷ 有效格子的首存人数（cost_amount ÷ ftd_players）\nUDS：缺源（同 CPA）'),
    ('promoNet',     'dashboard_market_hour_v1', 'deposit_amount',
     E'推广分析 net：＝存款總額（2026-11-09 已确认；与运营总览的存提差 net 不同）\nUDS：可映射（精确数据集）'),
    ('promoRoi',     NULL,                       NULL,
     E'推广分析 roi：有效格子的淨充值 ÷ 有效格子的消耗 − 1（deposit_amount ÷ cost_amount − 1）\nUDS：缺源（同 CPA）'),
    ('promoLpUV',    'dashboard_market_hour_v1', 'lp_uv',
     E'推广分析 lpUV：落地页去重访问人数\nUDS：需确认（字段是 IP 在推荐码下的首次访问次数、不分终端；页面要按用户 ID / 匿名标识去重的人数，产品确认前不展示）'),
    ('promoHUV',     NULL,                       NULL,
     E'推广分析 hUV：主页去重访问人数（含 Web 与 APP 内打开）\nUDS：缺源（缺主站访问事件与访客身份）'),
    ('promoDl',      'dashboard_market_hour_v1', 'lp_download',
     E'推广分析 dl：点击下载 APK 的次数\nUDS：需确认（没有按钮点击事件；用下载请求数 lp_download 还是下载完成数 lp_download_ok 待产品定，确认前不展示）'),
    ('promoAct',     'dashboard_market_hour_v1', 'android_first_open',
     E'推广分析 act：Android 安装后首次启动的人数\nUDS：需确认（页面现取 android_first_open：APK 首次打开数，是设备数不是人数，没有终端拆分，只在「全部」与 Android 有值；口径待定。另有按推荐码的 lp_install，与它同源，不可相加）'),
    ('promoReg',     'dashboard_market_hour_v1', 'reg',
     E'推广分析 reg：按注册时的推广渠道与终端归属\nUDS：可映射（分终端用 web_ / android_ / ios_ / unknown_ 前缀；按推广商 / 推广渠道拆分缺源）'),
    ('promoRegR',    NULL,                       NULL,
     E'推广分析 regR：注册人数 ÷ 入口 UV（按渠道入口类型取 UV-落地頁或 UV-主頁）\nUDS：缺源（主站入口缺 UV-主頁；落地页入口的 lp_uv 口径需确认）'),
    ('promoFtd',     'dashboard_market_hour_v1', 'ftd_players',
     E'推广分析 ftd：按注册终端，按到账时间 bill_date\nUDS：可映射（分终端加前缀）'),
    ('promoFtdR',    NULL,                       NULL,
     E'推广分析 ftdR：首存人数 ÷ 注册人数（dashboard_market_hour_v1 的 ftd_players ÷ reg，同一条查询）\nUDS：可映射'),
    ('promoFtdAmt',  'dashboard_market_hour_v1', 'ftd_amount',
     E'推广分析 ftdAmt：首存金额，商户默认币种\nUDS：可映射'),
    ('promoRd',      'dashboard_market_hour_v1', 'redeposit_users',
     E'推广分析 rd：首存后再次存款；多天按日累加\nUDS：可映射（精确数据集；不用宽表 redeposit_players：它要求六个代码维度全部固定）')
  ) AS v(metric_code, source_table, source_field, remark)
 WHERE c.metric_code = v.metric_code
   AND (position('UDS：' IN coalesce(c.remark, '')) = 0
        OR (c.source_table IS NOT NULL AND c.source_table !~ '^[A-Za-z_][A-Za-z0-9_.]*$')
        OR (c.source_field IS NOT NULL AND c.source_field !~ '^[A-Za-z_][A-Za-z0-9_]*$'));

COMMIT;

-- ⑧ 自检：16 行；上游表 / 字段都是纯标识符或为空；淨充值为「可映射」、激活为「需确认」
SELECT metric_code, source_table, source_field, split_part(remark, E'\n', 2) AS uds_status
  FROM dashboard_metric_card WHERE metric_code LIKE 'promo%' ORDER BY sort_no;


-- ============================================================================
-- ⑨ 2026-11-10 · 系统管理 · API Key（系统对接用；新 JAR 启动前执行，没执行时带 X-Api-Key 的请求返回 401，不影响其他功能）
--   1. 新表 sys_api_key：只存 Key 的 SHA-256 与前 12 位，明文只在新建时返回一次。每个 Key 绑定一个后台账号（按它的权限放行），
--      再限定能访问的接口（必须在 application.yml security.api-key.paths 里，目前只有 /notification/lark/send）、来源 IP、到期时间。
--   2. 系统管理（menu_id = 1）下加按钮：API Key 查看 system:apikey:list / 新建 system:apikey:add / 作废 system:apikey:edit，授给 role 1。
--   回滚：DROP TABLE IF EXISTS sys_api_key;
--         DELETE FROM sys_role_menu / sys_menu_i18n / sys_menu WHERE perms LIKE 'system:apikey:%' 对应的 menu_id
-- ============================================================================
BEGIN;

CREATE TABLE IF NOT EXISTS sys_api_key (
    key_id          BIGSERIAL       PRIMARY KEY,
    key_name        VARCHAR(64)     NOT NULL,
    key_prefix      VARCHAR(16)     NOT NULL,
    key_hash        CHAR(64)        NOT NULL,
    user_id         BIGINT          NOT NULL,
    allowed_paths   VARCHAR(500)    NOT NULL,
    allowed_ips     VARCHAR(500),
    expire_time     TIMESTAMP,
    status          CHAR(1)         NOT NULL DEFAULT '0',
    last_used_time  TIMESTAMP,
    last_used_ip    VARCHAR(64),
    create_by       VARCHAR(64)     NOT NULL DEFAULT '',
    create_time     TIMESTAMP       NOT NULL DEFAULT localtimestamp,
    update_by       VARCHAR(64)     NOT NULL DEFAULT '',
    update_time     TIMESTAMP       NOT NULL DEFAULT localtimestamp,
    remark          VARCHAR(500),
    CONSTRAINT uk_api_key_hash UNIQUE (key_hash),
    CONSTRAINT ck_api_key_status CHECK (status IN ('0', '1'))
);
COMMENT ON TABLE  sys_api_key               IS '系统对接用的 API Key（请求头 X-Api-Key）';
COMMENT ON COLUMN sys_api_key.key_name      IS '对接方名称，如「数据平台 · 数据更新通知」';
COMMENT ON COLUMN sys_api_key.key_prefix    IS '明文前 12 位（ftk_ + 8 位），列表与日志里认 Key 用，不能用来调用';
COMMENT ON COLUMN sys_api_key.key_hash      IS '明文的 SHA-256（十六进制）；明文不落库';
COMMENT ON COLUMN sys_api_key.user_id       IS '绑定的后台账号：按它的角色权限放行，操作日志记在它名下';
COMMENT ON COLUMN sys_api_key.allowed_paths IS '能访问的接口，逗号分隔；须在 security.api-key.paths 里';
COMMENT ON COLUMN sys_api_key.allowed_ips   IS '允许的来源 IP，逗号分隔；为空不限';
COMMENT ON COLUMN sys_api_key.expire_time   IS '到期时间；为空永不过期';
COMMENT ON COLUMN sys_api_key.status        IS '0 启用 / 1 已作废（作废立即失效，不能恢复）';

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), 'API Key 查看', 1, 101, '', '', '', '',
       1, 0, 'F', '0', '0', 'system:apikey:list', '#', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '系统对接用的 API Key 列表（只显示前缀）'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:apikey:list');

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), 'API Key 新建', 1, 102, '', '', '', '',
       1, 0, 'F', '0', '0', 'system:apikey:add', '#', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '新建 API Key（明文只显示一次）'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:apikey:add');

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, "query", route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
    create_time, update_by, update_time, remark
)
SELECT (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu), 'API Key 作废', 1, 103, '', '', '', '',
       1, 0, 'F', '0', '0', 'system:apikey:edit', '#', 'admin',
       CURRENT_TIMESTAMP, '', NULL, '作废 API Key（立即失效）'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:apikey:edit');

INSERT INTO sys_menu_i18n (menu_id, locale, menu_name, remark)
SELECT m.menu_id, v.locale, v.menu_name, v.remark
FROM sys_menu m
JOIN (VALUES
    ('system:apikey:list', 'zh-CN', 'API Key 查看', '系统对接用的 API Key 列表'),
    ('system:apikey:list', 'zh-TW', 'API Key 查看', '系統對接用的 API Key 列表'),
    ('system:apikey:list', 'en-US', 'View API keys', 'API keys for system integration'),
    ('system:apikey:add',  'zh-CN', 'API Key 新建', '新建 API Key'),
    ('system:apikey:add',  'zh-TW', 'API Key 新建', '新建 API Key'),
    ('system:apikey:add',  'en-US', 'Create API key', 'Create an API key'),
    ('system:apikey:edit', 'zh-CN', 'API Key 作废', '作废 API Key'),
    ('system:apikey:edit', 'zh-TW', 'API Key 作廢', '作廢 API Key'),
    ('system:apikey:edit', 'en-US', 'Revoke API key', 'Revoke an API key')
) AS v(perms, locale, menu_name, remark) ON v.perms = m.perms
WHERE NOT EXISTS (SELECT 1 FROM sys_menu_i18n i WHERE i.menu_id = m.menu_id AND i.locale = v.locale);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id
FROM sys_menu m
WHERE m.perms IN ('system:apikey:list', 'system:apikey:add', 'system:apikey:edit')
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.role_id = 1)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id);

COMMIT;

-- ⑨ 自检：表已建；3 个按钮
SELECT to_regclass('sys_api_key') AS api_key_table;
SELECT menu_id, menu_name, parent_id, menu_type, perms FROM sys_menu WHERE perms LIKE 'system:apikey:%' ORDER BY menu_id;


-- ----------------------------------------------------------------------------
-- ⑩ 2026-11-10 · 个人视图 · 推广分析行维度去掉「推廣渠道須在推廣商之下」
--   ⑦ 给 promo.channel.rows 配了顺序规则（channel 不能排在 agent 前面），产品要求去掉：rules 清空为 []。
--   ⑦ 的 INSERT 已同步改为 '[]'，新库执行 ⑦ 即可；已执行过 ⑦ 的库执行本段。
--   update_time 一变，后端约 1 分钟内自动重新加载视图定义，不用重启；用户已保存的视图不受影响。
--   回滚：UPDATE dashboard_view_slot SET rules = '[{"before":"agent","after":"channel","message":"推廣渠道須在推廣商之下"}]',
--                update_time = localtimestamp WHERE slot_code = 'promo.channel.rows';
-- ----------------------------------------------------------------------------
UPDATE dashboard_view_slot
   SET rules = '[]', update_time = localtimestamp
 WHERE slot_code = 'promo.channel.rows' AND coalesce(rules, '') <> '[]';

-- ⑩ 自检：rules = []
SELECT slot_code, rules FROM dashboard_view_slot WHERE slot_code = 'promo.channel.rows';


-- ----------------------------------------------------------------------------
-- ⑪ 2026-11-10 · 站点管理 · 实时站点（新 JAR 启动前执行：启动时加载站点表会读这一列）
--   realtime = true：UDS 提供该站点当前小时的实时数据（分钟级），页头显示「實時更新」；默认 false（每小时更新）。
--   站点管理新增 / 修改时编辑（body.realtime = true / false），站点列表、登录 getInfo 的 sites[]、运营总览 overview 都返回 realtime。
--   回滚：ALTER TABLE monitor_site_setting DROP COLUMN IF EXISTS realtime;
-- ----------------------------------------------------------------------------
ALTER TABLE monitor_site_setting ADD COLUMN IF NOT EXISTS realtime BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN monitor_site_setting.realtime IS '实时站点：UDS 提供当前小时的实时数据（分钟级），页头显示「實時更新」；false 为每小时更新';

-- ⑪ 自检：每个站点一行，realtime 默认 false；实时站点在「系统管理 › 站点管理」里打开，或：
--   UPDATE monitor_site_setting SET realtime = TRUE, update_time = localtimestamp WHERE upper(site_code) IN ('J9');
SELECT site_code, site_name, realtime FROM monitor_site_setting WHERE site_name IS NOT NULL ORDER BY sort_no, site_code;


-- ----------------------------------------------------------------------------
-- ⑫ 2026-11-11 · 站点管理 · 数据起始日（新 JAR 启动前执行：启动时加载站点表会读这一列）
--   data_start_date：站点数据从哪天开始（之前没有数据），前端日期选择从这天起；为空用 application.yml 的 dashboard.launch-date。
--   站点管理新增 / 修改时编辑（body.dataStartDate = yyyy-MM-dd，传空串清空），站点列表、登录 getInfo 的 sites[].dataStartDate 返回。
--   回滚：ALTER TABLE monitor_site_setting DROP COLUMN IF EXISTS data_start_date;
-- ----------------------------------------------------------------------------
ALTER TABLE monitor_site_setting ADD COLUMN IF NOT EXISTS data_start_date DATE;
COMMENT ON COLUMN monitor_site_setting.data_start_date IS '站点数据起始日（之前没有数据，前端日期选择从这天起）；为空用 dashboard.launch-date';

-- ⑫ 自检：每个站点一行，data_start_date 默认为空（用 dashboard.launch-date）；在「系统管理 › 站点管理」里设置，或：
--   UPDATE monitor_site_setting SET data_start_date = DATE '2026-03-01', update_time = localtimestamp WHERE upper(site_code) = 'H86';
SELECT site_code, site_name, data_start_date FROM monitor_site_setting WHERE site_name IS NOT NULL ORDER BY sort_no, site_code;


-- ----------------------------------------------------------------------------
-- ⑬ 2026-11-12 · 推广分析 · 去掉渠道表现行维度「推廣關係」；淨充值改为与存提差一致（新 JAR 上线时一起执行，顺序不限）
--   1. 推廣關係（relation）数据近期不能同步，渠道表现的行维度去掉它：删除可选项 promo.channel.rows / relation。
--      新 JAR 不再认 rows 里的 relation（4003）；用户已保存的视图里含 relation 的，后端读取时自动忽略，不用改数据。
--      视图定义按「行数 + max(update_time)」热加载，删除后约 1 分钟生效，不用重启。
--   2. 淨充值口径业务侧调整为「存款總額 − 提款總額」（与运营总览的存提差一致）：只更新指标管理里 promoNet 的取数对照与说明，
--      页面取数由代码决定（deposit_amount − withdraw_amount）。
--   回滚：INSERT INTO dashboard_view_slot_item (slot_code, item_code, item_name, group_code, group_name, sort_no, default_sort)
--           VALUES ('promo.channel.rows', 'relation', NULL, 'dim', '維度', 40, NULL) ON CONFLICT (slot_code, item_code) DO NOTHING;
--         UPDATE dashboard_metric_card SET remark = E'推广分析 net：＝存款總額（2026-11-09 已确认；与运营总览的存提差 net 不同）\nUDS：可映射（精确数据集）',
--                update_time = CURRENT_TIMESTAMP WHERE metric_code = 'promoNet';
-- ----------------------------------------------------------------------------
BEGIN;

DELETE FROM dashboard_view_slot_item WHERE slot_code = 'promo.channel.rows' AND item_code = 'relation';

UPDATE dashboard_view_slot SET update_time = localtimestamp WHERE slot_code = 'promo.channel.rows';

UPDATE dashboard_metric_card
   SET source_table = 'dashboard_market_hour_v1',
       source_field = 'deposit_amount',
       remark       = E'推广分析 net：存款總額 − 提款總額（deposit_amount − withdraw_amount，2026-11-12 起与运营总览的存提差一致）\nUDS：可映射（精确数据集）',
       update_by    = 'admin',
       update_time  = CURRENT_TIMESTAMP
 WHERE metric_code = 'promoNet'
   AND position('withdraw_amount' IN coalesce(remark, '')) = 0;

COMMIT;

-- ⑬ 自检：行维度剩 agent / channel / terminal / date 4 项；promoNet 的说明含 withdraw_amount
SELECT item_code, sort_no, default_sort FROM dashboard_view_slot_item WHERE slot_code = 'promo.channel.rows' ORDER BY sort_no;
SELECT metric_code, source_table, source_field, remark FROM dashboard_metric_card WHERE metric_code = 'promoNet';
