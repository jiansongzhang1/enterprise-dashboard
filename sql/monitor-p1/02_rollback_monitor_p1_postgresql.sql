-- ============================================================================
-- 监控管理 · 一期 · 回滚 SQL（dashboard_db）· 2026-10-27
--
-- 撤销 01_release_monitor_p1_postgresql.sql 做的全部改动，数据库回到执行 01 之前的状态：
--   1. 删除菜单与授权：监控管理 160–167（monitor:event:* / monitor:rule:*）、手动触发测试告警 monitor:rule:test、
--      系统管理 · 通知模板 system:notifyTpl:*、站点管理 system:site:*、指标管理 system:metric:*
--   2. 删除 8 张 monitor_* 表（规则、事件、发送记录、通知模板、Lark 群缓存、站点设置等，数据一并删除）
--   3. dashboard_metric_card 按 01 第 0.5 节的备份恢复：去掉新增列（监控属性、uds_*_field），加回旧告警字段
--      alert_* / tolerance_*，全部数据（口径 remark、source_table / source_field、chart_type 等）、CHECK 约束、索引、
--      默认值与非空、注释都恢复成执行 01 之前的样子，并删掉后台「指标管理」登记的新行
--   4. sys_role.site_code：上线前没有这一列时删除（角色上设置的站点一并丢失）
--   5. 删除 01 建的备份表：dashboard_metric_card_bak_monitor_p1、dashboard_metric_card_bak_20261026、dashboard_metric_card_name_bak_20261027、dashboard_metric_card_remark_bak_20261028、
--      dashboard_metric_card_chart_bak_20261023、monitor_p1_rollback_info
--
-- 不能恢复的：01 第 ⑨ 段删除的旧版菜单 168–172（monitor:group:*、monitor:admin:edit）。
--
-- 依赖：01 第 0.5 节的备份表 dashboard_metric_card_bak_monitor_p1。没有（01 是 10-27 之前的版本执行的）时直接报错，什么都不改。
-- 整个脚本一个事务，任何一步失败全部撤销。
--
-- 防误操作：必须显式确认才会执行（生产先 pg_dump 全库再执行）：
--   PGOPTIONS='-c monitor.rollback_confirm=yes' psql -X -v ON_ERROR_STOP=1 -d dashboard_db -f 02_rollback_monitor_p1_postgresql.sql
-- 回滚后要用旧 JAR（新 JAR 依赖这些表和列，启动会失败）。
-- ============================================================================

BEGIN;

-- ---------- 0. 前置检查 ----------
DO $$
BEGIN
    IF coalesce(current_setting('monitor.rollback_confirm', true), '') <> 'yes' THEN
        RAISE EXCEPTION '未确认：用 PGOPTIONS=''-c monitor.rollback_confirm=yes'' 执行本脚本（会删除监控管理的表、菜单和数据）';
    END IF;
    IF to_regclass('dashboard_metric_card_bak_monitor_p1') IS NULL THEN
        RAISE EXCEPTION '缺少备份表 dashboard_metric_card_bak_monitor_p1（01 第 0.5 节），无法恢复 dashboard_metric_card，什么都没改';
    END IF;
    IF to_regclass('monitor_p1_rollback_info') IS NULL
       OR NOT EXISTS (SELECT 1 FROM monitor_p1_rollback_info WHERE item = 'captured') THEN
        RAISE EXCEPTION '缺少上线前状态记录 monitor_p1_rollback_info（01 第 0.5 节），什么都没改';
    END IF;
END $$;

-- ---------- 1. 菜单与授权 ----------
CREATE TEMP TABLE _p1_menu ON COMMIT DROP AS
SELECT menu_id FROM sys_menu
 WHERE perms LIKE 'monitor:event:%' OR perms LIKE 'monitor:rule:%'
    OR perms LIKE 'system:notifyTpl:%' OR perms LIKE 'system:site:%' OR perms LIKE 'system:metric:%'
    OR (menu_id = 160 AND coalesce(route_name, '') = 'monitor');
DELETE FROM sys_role_menu WHERE menu_id IN (SELECT menu_id FROM _p1_menu);
DELETE FROM sys_menu_i18n WHERE menu_id IN (SELECT menu_id FROM _p1_menu);
DELETE FROM sys_menu      WHERE menu_id IN (SELECT menu_id FROM _p1_menu);

-- ---------- 2. monitor_* 表 ----------
DROP TABLE IF EXISTS monitor_notify_log      CASCADE;
DROP TABLE IF EXISTS monitor_notify_template CASCADE;
DROP TABLE IF EXISTS monitor_event           CASCADE;
DROP TABLE IF EXISTS monitor_rule_state      CASCADE;
DROP TABLE IF EXISTS monitor_rule            CASCADE;
DROP TABLE IF EXISTS monitor_hour_check      CASCADE;
DROP TABLE IF EXISTS monitor_lark_chat       CASCADE;
DROP TABLE IF EXISTS monitor_site_setting    CASCADE;

-- ---------- 3. dashboard_metric_card 按备份恢复 ----------
DO $$
DECLARE
    t     regclass := 'dashboard_metric_card'::regclass;
    bak   regclass := 'dashboard_metric_card_bak_monitor_p1'::regclass;
    c     record;
    sets  text;
    cols  text;
BEGIN
    -- 3.1 去掉上线前没有、或定义被 01 改过的 CHECK 约束（ck_judge_type、ck_gauge、ck_monitorable、ck_chart_type），没动过的保留
    FOR c IN
        SELECT k.conname FROM pg_constraint k
         WHERE k.conrelid = t AND k.contype = 'c'
           AND NOT EXISTS (SELECT 1 FROM monitor_p1_rollback_info i
                            WHERE i.item = 'check:' || k.conname AND i.value = pg_get_constraintdef(k.oid))
    LOOP
        EXECUTE format('ALTER TABLE dashboard_metric_card DROP CONSTRAINT %I', c.conname);
    END LOOP;

    -- 3.2 删掉备份里没有的列（monitorable、judge_type、…、uds_*_field）
    FOR c IN
        SELECT a.attname FROM pg_attribute a
         WHERE a.attrelid = t AND a.attnum > 0 AND NOT a.attisdropped
           AND NOT EXISTS (SELECT 1 FROM pg_attribute b
                            WHERE b.attrelid = bak AND b.attname = a.attname AND b.attnum > 0 AND NOT b.attisdropped)
    LOOP
        EXECUTE format('ALTER TABLE dashboard_metric_card DROP COLUMN %I', c.attname);
        RAISE NOTICE '删除列 dashboard_metric_card.%', c.attname;
    END LOOP;

    -- 3.3 加回备份里有、现在没有的列（alert_enabled、alert_min、…），类型取自备份
    FOR c IN
        SELECT b.attname, format_type(b.atttypid, b.atttypmod) AS typ FROM pg_attribute b
         WHERE b.attrelid = bak AND b.attnum > 0 AND NOT b.attisdropped
           AND NOT EXISTS (SELECT 1 FROM pg_attribute a
                            WHERE a.attrelid = t AND a.attname = b.attname AND a.attnum > 0 AND NOT a.attisdropped)
         ORDER BY b.attnum
    LOOP
        EXECUTE format('ALTER TABLE dashboard_metric_card ADD COLUMN %I %s', c.attname, c.typ);
        RAISE NOTICE '加回列 dashboard_metric_card.%', c.attname;
    END LOOP;

    -- 3.4 后台「指标管理」登记的新行：备份里没有的删掉
    EXECUTE format('DELETE FROM dashboard_metric_card c WHERE NOT EXISTS (SELECT 1 FROM %s b WHERE b.metric_code = c.metric_code)', bak);

    -- 3.5 按 metric_code 用备份值覆盖全部列（id 除外）
    SELECT string_agg(format('%I = b.%I', b.attname, b.attname), ', ' ORDER BY b.attnum),
           string_agg(format('%I', b.attname), ', ' ORDER BY b.attnum)
      INTO sets, cols
      FROM pg_attribute b
     WHERE b.attrelid = bak AND b.attnum > 0 AND NOT b.attisdropped AND b.attname NOT IN ('id', 'metric_code');
    EXECUTE format('UPDATE dashboard_metric_card c SET %s FROM %s b WHERE b.metric_code = c.metric_code', sets, bak);
    -- 备份里有、现在被删掉的行补回来
    EXECUTE format('INSERT INTO dashboard_metric_card (metric_code, %s) SELECT b.metric_code, %s FROM %s b '
                   'WHERE NOT EXISTS (SELECT 1 FROM dashboard_metric_card c WHERE c.metric_code = b.metric_code)',
                   cols, (SELECT string_agg('b.' || x, ', ') FROM unnest(string_to_array(cols, ', ')) x), bak);

    -- 3.6 列默认值与非空：按上线前
    FOR c IN SELECT substr(item, 8) AS col, value::json AS v FROM monitor_p1_rollback_info WHERE item LIKE 'column:%' LOOP
        CONTINUE WHEN NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = t AND attname = c.col AND NOT attisdropped);
        IF c.v->>'default' IS NULL THEN
            EXECUTE format('ALTER TABLE dashboard_metric_card ALTER COLUMN %I DROP DEFAULT', c.col);
        ELSE
            EXECUTE format('ALTER TABLE dashboard_metric_card ALTER COLUMN %I SET DEFAULT %s', c.col, c.v->>'default');
        END IF;
        IF (c.v->>'notnull')::boolean THEN
            EXECUTE format('ALTER TABLE dashboard_metric_card ALTER COLUMN %I SET NOT NULL', c.col);
        ELSE
            EXECUTE format('ALTER TABLE dashboard_metric_card ALTER COLUMN %I DROP NOT NULL', c.col);
        END IF;
    END LOOP;

    -- 3.7 CHECK 约束：上线前有、现在没有的按原定义加回（ck_chart_type 含 BAR、ck_source、ck_alert_* 等）
    FOR c IN SELECT substr(item, 7) AS name, value AS def FROM monitor_p1_rollback_info WHERE item LIKE 'check:%' ORDER BY item LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = t AND conname = c.name) THEN
            EXECUTE format('ALTER TABLE dashboard_metric_card ADD CONSTRAINT %I %s', c.name, c.def);
        END IF;
    END LOOP;

    -- 3.8 索引：上线前有、现在没有的加回（idx_metric_card_alert）
    FOR c IN SELECT substr(item, 7) AS name, value AS def FROM monitor_p1_rollback_info WHERE item LIKE 'index:%' LOOP
        IF to_regclass(quote_ident(c.name)) IS NULL THEN
            EXECUTE c.def;
            RAISE NOTICE '加回索引 %', c.name;
        END IF;
    END LOOP;

    -- 3.9 表与列注释
    FOR c IN SELECT item, value FROM monitor_p1_rollback_info WHERE item LIKE 'comment:%' LOOP
        IF c.item = 'comment:table' THEN
            EXECUTE format('COMMENT ON TABLE dashboard_metric_card IS %L', c.value);
        ELSIF EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = t AND attname = substr(c.item, 9) AND NOT attisdropped) THEN
            EXECUTE format('COMMENT ON COLUMN dashboard_metric_card.%I IS %L', substr(c.item, 9), c.value);
        END IF;
    END LOOP;
END $$;

-- ---------- 4. sys_role.site_code ----------
DO $$
BEGIN
    IF coalesce((SELECT value FROM monitor_p1_rollback_info WHERE item = 'sys_role.site_code.existed'), 'false') <> 'true' THEN
        DROP INDEX IF EXISTS idx_sys_role_site_code;
        ALTER TABLE sys_role DROP COLUMN IF EXISTS site_code;
        RAISE NOTICE '已删除 sys_role.site_code（上线前没有这一列）';
    ELSE
        RAISE NOTICE 'sys_role.site_code 上线前就存在，保留';
    END IF;
END $$;

-- ---------- 5. 01 建的备份表 ----------
DROP TABLE IF EXISTS dashboard_metric_card_name_bak_20261027;
DROP TABLE IF EXISTS dashboard_metric_card_remark_bak_20261028;
-- 01 ⑯ 的站点存在 monitor_site_setting，随 monitor_* 表一起删除（旧 JAR 只认 application.yml 的站点；后台改过站点编码时，回滚前先把 yml 改成一致）
-- 旧版本建过的 sys_site 一并清理
DROP TABLE IF EXISTS sys_site;
DROP TABLE IF EXISTS dashboard_metric_card_chart_bak_20261023;
DROP TABLE IF EXISTS dashboard_metric_card_bak_20261026;
DROP TABLE IF EXISTS dashboard_metric_card_bak_monitor_p1;
DROP TABLE IF EXISTS monitor_p1_rollback_info;

COMMIT;

-- ---------- 自检（只查询） ----------
-- 应为 0 张
SELECT count(*) AS monitor_tables FROM information_schema.tables
 WHERE table_schema = current_schema() AND table_name LIKE 'monitor\_%';
-- 应为 0 行
SELECT count(*) AS p1_menus FROM sys_menu
 WHERE perms LIKE 'monitor:event:%' OR perms LIKE 'monitor:rule:%' OR perms LIKE 'system:notifyTpl:%'
    OR perms LIKE 'system:site:%' OR perms LIKE 'system:metric:%' OR menu_id = 160;
-- 上线前是旧版本时：有 alert_* 6 列，没有 monitorable / uds_hour_field
SELECT column_name FROM information_schema.columns
 WHERE table_schema = current_schema() AND table_name = 'dashboard_metric_card'
   AND column_name IN ('alert_enabled','alert_min','alert_max','tolerance_pct','tolerance_crit_multiple','alert_level',
                       'monitorable','uds_hour_field')
 ORDER BY 1;
SELECT count(*) AS total, count(*) FILTER (WHERE chart_type = 'BAR') AS bar FROM dashboard_metric_card;
