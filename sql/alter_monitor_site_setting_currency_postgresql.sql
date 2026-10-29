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
