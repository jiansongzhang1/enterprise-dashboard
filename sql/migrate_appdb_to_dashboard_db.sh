#!/usr/bin/env bash
# =============================================================================
# appdb → dashboard_db 迁移（在 EC2 上执行，SSM 登录）
#
#   SRC_DB = appdb          现有库，数据原样复制到 DST_DB，执行过程中只读不写
#   DST_DB = dashboard_db   新库 = appdb 的完整副本（用户、角色、菜单、日志、看板配置全部保留）
#   TMP_DB = init_tmp       用仓库 SQL 从零建的干净库，只用来和 DST_DB 比对表结构，比完即可删除
#
# 分步执行，每步看完输出再执行下一步：
#   ./migrate_appdb_to_dashboard_db.sh check      # 0. 环境与连通性检查，不改任何东西
#   ./migrate_appdb_to_dashboard_db.sh copy       # 1. 备份 appdb 并恢复到 dashboard_db，核对每张表行数
#   ./migrate_appdb_to_dashboard_db.sh build-tmp  # 2. 用仓库 SQL 建 init_tmp
#   ./migrate_appdb_to_dashboard_db.sh diff       # 3. 比对 init_tmp 与 dashboard_db 的表结构 / 索引 / 约束 / 权限标识
#   ./migrate_appdb_to_dashboard_db.sh upgrade    # 4. 在 dashboard_db 上补跑增量脚本：新增列 / 表、权限菜单（均可重复执行）
#   ./migrate_appdb_to_dashboard_db.sh metrics    # 5. 重建看板指标配置（dashboard_metric_card / group / page_metric）
#   ./migrate_appdb_to_dashboard_db.sh verify     # 6. 校验 dashboard_db：列、表、权限菜单、指标配置条数
#   ./migrate_appdb_to_dashboard_db.sh drop-tmp   # 7. 删除 init_tmp
#
# 环境变量（密码不要写进本文件）：
#   PGHOST PGUSER PGPASSWORD    必填
#   SRC_DB TMP_DB DST_DB        默认 appdb / init_tmp / dashboard_db
#   SQL_DIR                     仓库 sql 目录在 EC2 上的位置，默认 /opt/fivetech/sql
#   WORK_DIR                    备份与报告目录，默认 /opt/fivetech/migrate
# =============================================================================
set -euo pipefail

: "${PGHOST:?请先 export PGHOST}"
: "${PGUSER:?请先 export PGUSER}"
: "${PGPASSWORD:?请先 export PGPASSWORD}"
export PGPORT="${PGPORT:-5432}"
export PGSSLMODE="${PGSSLMODE:-require}"
SRC_DB="${SRC_DB:-appdb}"
TMP_DB="${TMP_DB:-init_tmp}"
DST_DB="${DST_DB:-dashboard_db}"
SQL_DIR="${SQL_DIR:-/opt/fivetech/sql}"
WORK_DIR="${WORK_DIR:-/opt/fivetech/migrate}"
STAMP="$(date +%Y%m%d_%H%M%S)"

for n in "$SRC_DB" "$TMP_DB" "$DST_DB"; do
  [[ "$n" =~ ^[a-z_][a-z0-9_]*$ ]] || { echo "库名不合法：$n"; exit 1; }
done
[[ "$SRC_DB" != "$DST_DB" && "$SRC_DB" != "$TMP_DB" && "$TMP_DB" != "$DST_DB" ]] \
  || { echo "SRC_DB / TMP_DB / DST_DB 不能重名"; exit 1; }
mkdir -p "$WORK_DIR"

q()  { psql -X -q -v ON_ERROR_STOP=1 -At -d "$1" -c "$2"; }      # 取值
run(){ psql -X -q -v ON_ERROR_STOP=1 --single-transaction -d "$1" -f "$2"; }
db_exists() { [[ "$(q postgres "SELECT 1 FROM pg_database WHERE datname='$1'")" == "1" ]]; }

create_db_like_src() {
  # 编码与排序规则照抄源库，避免恢复后字符串比较 / 排序与原库不一致
  local enc coll ctype
  enc="$(q postgres "SELECT pg_encoding_to_char(encoding) FROM pg_database WHERE datname='$SRC_DB'")"
  coll="$(q postgres "SELECT datcollate FROM pg_database WHERE datname='$SRC_DB'")"
  ctype="$(q postgres "SELECT datctype FROM pg_database WHERE datname='$SRC_DB'")"
  echo "创建 $1（ENCODING=$enc LC_COLLATE=$coll LC_CTYPE=$ctype）"
  q postgres "CREATE DATABASE \"$1\" WITH TEMPLATE template0 ENCODING '$enc' LC_COLLATE '$coll' LC_CTYPE '$ctype'"
}

# ---------------------------------------------------------------------------
cmd_check() {
  echo "== 客户端"; psql --version; pg_dump --version; pg_restore --version
  echo "== 连通性 $PGHOST:$PGPORT（sslmode=$PGSSLMODE）"
  local server; server="$(q postgres "SHOW server_version")"; echo "服务端版本：$server"
  local smaj cmaj
  smaj="${server%%.*}"; cmaj="$(pg_dump --version | grep -oE '[0-9]+' | head -1)"
  if (( cmaj < smaj )); then
    echo "✗ pg_dump 主版本 $cmaj 低于服务端 $smaj，dump 会失败。Amazon Linux 2023：sudo dnf install -y postgresql${smaj}"; exit 1
  fi
  echo "== 库"
  q postgres "SELECT datname, pg_size_pretty(pg_database_size(datname)) FROM pg_database WHERE NOT datistemplate ORDER BY 1"
  db_exists "$SRC_DB" || { echo "✗ 源库 $SRC_DB 不存在"; exit 1; }
  db_exists "$DST_DB" && echo "⚠ 目标库 $DST_DB 已存在：copy 步骤会拒绝执行" || echo "✓ 目标库 $DST_DB 不存在，可以创建"
  echo "== 当前连到 $SRC_DB 的会话（迁移窗口内应只剩本脚本）"
  q postgres "SELECT usename, application_name, client_addr, state FROM pg_stat_activity WHERE datname='$SRC_DB'"
  echo "== 当前用户能否建库"
  q postgres "SELECT rolcreatedb OR rolsuper FROM pg_roles WHERE rolname=current_user"
  echo "== SQL 目录 $SQL_DIR"
  for f in ry_20260819_postgresql.sql dashboard_metric_card_postgresql.sql dashboard_page_metric_postgresql.sql \
           monitor_schema_postgresql.sql alter_monitor_engine_postgresql.sql add_monitor_menu_postgresql.sql; do
    [[ -f "$SQL_DIR/$f" ]] && echo "✓ $f" || echo "✗ 缺少 $f（build-tmp 需要）"
  done
}

# ---------------------------------------------------------------------------
cmd_copy() {
  db_exists "$SRC_DB" || { echo "✗ 源库 $SRC_DB 不存在"; exit 1; }
  if db_exists "$DST_DB"; then echo "✗ 目标库 $DST_DB 已存在，拒绝覆盖。确认可删后手工 DROP DATABASE 再执行"; exit 1; fi

  local dump="$WORK_DIR/${SRC_DB}_${STAMP}.dump"
  echo "== 1/3 备份 $SRC_DB → $dump"
  pg_dump -d "$SRC_DB" -Fc --no-owner --no-acl -f "$dump"
  ls -lh "$dump"

  echo "== 2/3 恢复到 $DST_DB"
  create_db_like_src "$DST_DB"
  # --no-owner/--no-acl：对象全部归当前用户（$PGUSER），RDS 上没有 superuser，按原 owner 恢复会报错
  pg_restore -d "$DST_DB" --no-owner --no-acl --exit-on-error --single-transaction "$dump"

  echo "== 3/3 核对每张表行数（$SRC_DB vs $DST_DB）"
  local bad=0 t a b
  while read -r t; do
    [[ -z "$t" ]] && continue
    a="$(q "$SRC_DB" "SELECT count(*) FROM public.\"$t\"")"
    b="$(q "$DST_DB" "SELECT count(*) FROM public.\"$t\"" 2>/dev/null || echo MISSING)"
    if [[ "$a" == "$b" ]]; then printf '  ✓ %-32s %s\n' "$t" "$a"; else printf '  ✗ %-32s %s → %s\n' "$t" "$a" "$b"; bad=1; fi
  done < <(q "$SRC_DB" "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY 1")
  (( bad == 0 )) && echo "✓ 行数全部一致" || { echo "✗ 有表行数不一致：迁移窗口内 $SRC_DB 是否还有写入？"; exit 1; }
  echo "备份文件保留在 $dump，回滚或复核时使用"
}

# ---------------------------------------------------------------------------
cmd_build_tmp() {
  if db_exists "$TMP_DB"; then
    echo "✗ $TMP_DB 已存在。它只是比对用的临时库，可先执行 drop-tmp 再重建"; exit 1
  fi
  create_db_like_src "$TMP_DB"
  # 顺序固定：metric_card 先建，page_metric 外键引用它；监控管理（一期）的表和菜单放最后，菜单依赖 ry 脚本里的 sys_menu_i18n
  for f in ry_20260819_postgresql.sql dashboard_metric_card_postgresql.sql dashboard_page_metric_postgresql.sql \
           monitor_schema_postgresql.sql alter_monitor_engine_postgresql.sql add_monitor_menu_postgresql.sql \
           add_metric_manage_menu_postgresql.sql; do
    echo "== 执行 $f"; run "$TMP_DB" "$SQL_DIR/$f"
  done
  echo "✓ $TMP_DB 已按仓库脚本建好"
}

# ---------------------------------------------------------------------------
snapshot() {
  local db="$1" out="$2"
  {
    echo "### 列"
    q "$db" "SELECT table_name||'.'||column_name||' '||data_type||' null='||is_nullable||' default='||COALESCE(column_default,'')
             FROM information_schema.columns WHERE table_schema='public' ORDER BY table_name, column_name"
    echo "### 索引"
    q "$db" "SELECT tablename||' '||indexname||' :: '||regexp_replace(indexdef,'^CREATE (UNIQUE )?INDEX \S+ ON ','')
             FROM pg_indexes WHERE schemaname='public' ORDER BY tablename, indexname"
    echo "### 约束"
    q "$db" "SELECT conrelid::regclass||' '||conname||' :: '||pg_get_constraintdef(oid)
             FROM pg_constraint WHERE connamespace='public'::regnamespace ORDER BY 1"
    echo "### 权限标识 sys_menu.perms"
    q "$db" "SELECT DISTINCT perms FROM sys_menu WHERE COALESCE(perms,'')<>'' ORDER BY 1"
  } > "$out"
}

cmd_diff() {
  db_exists "$TMP_DB" || { echo "✗ $TMP_DB 不存在，先执行 build-tmp"; exit 1; }
  db_exists "$DST_DB" || { echo "✗ $DST_DB 不存在，先执行 copy"; exit 1; }
  local a="$WORK_DIR/schema_${TMP_DB}.txt" b="$WORK_DIR/schema_${DST_DB}.txt" r="$WORK_DIR/diff_${STAMP}.txt"
  snapshot "$TMP_DB" "$a"; snapshot "$DST_DB" "$b"
  # diff 有差异时返回 1，不能让 set -e 当成失败
  diff -u "$a" "$b" > "$r" || true
  if [[ -s "$r" ]]; then
    echo "差异报告：$r"
    echo "  「-」开头 = 仓库脚本有、$DST_DB 缺（通常要补跑对应的增量脚本）"
    echo "  「+」开头 = $DST_DB 有、仓库脚本没有（历史遗留或增量脚本加的菜单，逐条确认）"
    echo; cat "$r"
  else
    echo "✓ 表结构、索引、约束、权限标识完全一致"
  fi
}

# ---------------------------------------------------------------------------
# 增量脚本：全部用 IF NOT EXISTS / WHERE NOT EXISTS 写成，可重复执行。顺序有依赖，不要调换：
#   系统通知(122) 先于 Lark(123)；指标汇总/明细(130~137) 先于运营总览路由(140~142)；140 先于 143~145
UPGRADE_FILES=(
  ry_20260819_ga_secret.sql
  add_notification_contacts_postgresql.sql
  add_account_activation_postgresql.sql
  add_password_history_postgresql.sql
  add_multilingual_menu_postgresql.sql
  add_system_notification_menu_postgresql.sql
  add_email_permission_postgresql.sql
  add_telegram_permission_postgresql.sql
  add_whatsapp_permission_postgresql.sql
  add_lark_permission_postgresql.sql
  add_online_user_postgresql.sql
  add_dashboard_query_permission_postgresql.sql
  alter_dashboard_split_deposit_withdraw_postgresql.sql
  add_dashboard_router_menu_postgresql.sql
  add_dashboard_overview_permission_postgresql.sql
  # 多站点一期：sys_role.site_code（登录、菜单、权限查询都会用到，缺了登录直接报 column r.site_code does not exist）
  alter_sys_role_site_postgresql.sql
  # 监控管理（一期）：7 张 monitor_* 表、菜单 160–167。dashboard_metric_card 的监控属性由 metrics 步骤按仓库脚本重建，
  # 不需要再跑 alter_metric_card_monitor_postgresql.sql（那个只给不重建指标表的已有环境用）
  monitor_schema_postgresql.sql
  # 判定引擎（一期第二批）：monitor_event.event_type 等新列；新建表脚本已包含，这里可重复执行
  alter_monitor_engine_postgresql.sql
  add_monitor_menu_postgresql.sql
  # 指标字典（2026-10-26）：dashboard_metric_card 新增 UDS 字段列、口径写入 remark；metrics 步骤按建表脚本重建时已包含，这里可重复执行
  alter_metric_card_dictionary_postgresql.sql
  # 系统管理 · 指标管理菜单（system:metric:*）
  add_metric_manage_menu_postgresql.sql
)

cmd_upgrade() {
  db_exists "$DST_DB" || { echo "✗ $DST_DB 不存在，先执行 copy"; exit 1; }
  local f
  for f in "${UPGRADE_FILES[@]}"; do
    [[ -f "$SQL_DIR/$f" ]] || { echo "✗ 缺少 $SQL_DIR/$f"; exit 1; }
  done
  for f in "${UPGRADE_FILES[@]}"; do
    echo "== $f"
    # 不加 --single-transaction：其中两个脚本自带 BEGIN/COMMIT；每个脚本本身可重复执行，失败后修正再跑即可
    psql -X -q -v ON_ERROR_STOP=1 -d "$DST_DB" -f "$SQL_DIR/$f"
  done
  echo "✓ 增量脚本执行完成"
}

# 看板指标配置是纯配置数据（无用户数据），直接按仓库脚本重建，包含 direction / single_day_only / update_frequency 等最新列。
# dashboard_page_metric 外键引用 dashboard_metric_card，必须先删它，否则 metric_card 脚本里的 DROP TABLE 会失败
cmd_metrics() {
  db_exists "$DST_DB" || { echo "✗ $DST_DB 不存在，先执行 copy"; exit 1; }
  psql -X -q -v ON_ERROR_STOP=1 --single-transaction -d "$DST_DB" \
    -c "DROP TABLE IF EXISTS dashboard_page_metric; DROP TABLE IF EXISTS dashboard_metric_group;" \
    -f "$SQL_DIR/dashboard_metric_card_postgresql.sql" \
    -f "$SQL_DIR/dashboard_page_metric_postgresql.sql"
  echo "✓ 看板指标配置已重建"
}

cmd_verify() {
  db_exists "$DST_DB" || { echo "✗ $DST_DB 不存在"; exit 1; }
  local bad=0
  check() {  # 名称 SQL 期望值
    local got; got="$(q "$DST_DB" "$2" 2>/dev/null || echo ERROR)"
    if [[ "$got" == "$3" ]]; then printf '  ✓ %-42s %s\n' "$1" "$got"; else printf '  ✗ %-42s %s（期望 %s）\n' "$1" "$got" "$3"; bad=1; fi
  }
  echo "== 表与列"
  for t in sys_account_link sys_user_pwd_history sys_menu_i18n dashboard_metric_card dashboard_metric_group dashboard_page_metric \
           monitor_site_setting monitor_lark_chat monitor_rule monitor_rule_state monitor_event monitor_notify_log monitor_hour_check; do
    check "表 $t" "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename='$t'" 1
  done
  for c in sys_user.ga_secret sys_user.ga_status sys_user.notification_contacts sys_user.activate_time \
           dashboard_metric_card.update_frequency dashboard_metric_card.direction dashboard_metric_card.single_day_only \
           dashboard_metric_card.monitorable dashboard_metric_card.judge_type dashboard_metric_card.gauge_min \
           monitor_event.event_type monitor_event.last_period_end monitor_event.notify_count monitor_event.handle_reset monitor_event.stopped_at \
           monitor_rule_state.last_data_hour sys_role.site_code; do
    check "列 $c" "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='${c%%.*}' AND column_name='${c#*.}'" 1
  done
  echo "== 看板指标配置"
  check "dashboard_metric_card 行数"        "SELECT count(*) FROM dashboard_metric_card" 28
  check "dashboard_metric_group 行数"       "SELECT count(*) FROM dashboard_metric_group" 4
  check "OVERVIEW 指标数"                   "SELECT count(*) FROM dashboard_page_metric WHERE page_code='OVERVIEW'" 20
  check "SUMMARY 指标数"                    "SELECT count(*) FROM dashboard_page_metric WHERE page_code='SUMMARY'" 21
  check "CORE 大卡数"                       "SELECT count(*) FROM dashboard_page_metric WHERE emphasis='CORE'" 8
  check "SUMMARY 默认列数"                  "SELECT count(*) FROM dashboard_page_metric WHERE page_code='SUMMARY' AND is_default" 8
  check "仅单日指标（active / arppu）"      "SELECT string_agg(metric_code, ',' ORDER BY metric_code) FROM dashboard_metric_card WHERE single_day_only" "active,arppu"
  check "可监控指标数"                       "SELECT count(*) FROM dashboard_metric_card WHERE monitorable" 20
  check "有门槛条刻度的指标数"               "SELECT count(*) FROM dashboard_metric_card WHERE gauge_min IS NOT NULL" 7
  check "旧告警字段已删除"                   "SELECT count(*) FROM information_schema.columns WHERE table_name='dashboard_metric_card' AND column_name IN ('alert_enabled','alert_min','alert_max','tolerance_pct')" 0
  echo "== 路由与权限"
  check "顶级路由 overview/metrics/detail"  "SELECT string_agg(route_name, ',' ORDER BY menu_id) FROM sys_menu WHERE menu_id IN (140,141,142)" "overview,metrics,detail"
  check "旧目录 130 已删除"                 "SELECT count(*) FROM sys_menu WHERE menu_id=130" 0
  check "监控管理菜单 160–167"              "SELECT count(*) FROM sys_menu WHERE menu_id BETWEEN 160 AND 167" 8
  check "旧监控菜单 168–172 已清理"         "SELECT count(*) FROM sys_menu WHERE perms LIKE 'monitor:group:%' OR perms='monitor:admin:edit'" 0
  echo "  dashboard 权限标识："
  q "$DST_DB" "SELECT '    '||menu_id||' '||perms FROM sys_menu WHERE perms LIKE 'dashboard:%' ORDER BY menu_id"
  echo "  各角色拥有的 dashboard 权限数（admin 为超管，不依赖授权表）："
  q "$DST_DB" "SELECT '    '||r.role_key||' = '||count(m.menu_id) FROM sys_role r LEFT JOIN sys_role_menu rm ON rm.role_id=r.role_id
               LEFT JOIN sys_menu m ON m.menu_id=rm.menu_id AND (m.perms LIKE 'dashboard:%' OR m.menu_id BETWEEN 140 AND 145)
               WHERE r.del_flag='0' GROUP BY r.role_key ORDER BY 1"
  (( bad == 0 )) && echo "✓ 校验通过" || { echo "✗ 有校验项未通过"; exit 1; }
}

cmd_drop_tmp() {
  db_exists "$TMP_DB" || { echo "$TMP_DB 不存在，无需删除"; exit 0; }
  q postgres "DROP DATABASE \"$TMP_DB\""; echo "✓ 已删除 $TMP_DB"
}

case "${1:-}" in
  check)     cmd_check ;;
  copy)      cmd_copy ;;
  build-tmp) cmd_build_tmp ;;
  diff)      cmd_diff ;;
  upgrade)   cmd_upgrade ;;
  metrics)   cmd_metrics ;;
  verify)    cmd_verify ;;
  drop-tmp)  cmd_drop_tmp ;;
  *) sed -n '2,25p' "$0"; exit 1 ;;
esac
