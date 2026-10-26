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
