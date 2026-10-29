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
