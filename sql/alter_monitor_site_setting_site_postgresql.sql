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
