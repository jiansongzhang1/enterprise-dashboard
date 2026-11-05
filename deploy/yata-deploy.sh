#!/usr/bin/env bash
# Yata 后端一键部署 / 回滚
#
# 用法（在 EC2 上）：
#   sudo yata-deploy            # 从 S3 拉最新 jar → 备份 → 替换 → 重启 → 健康检查，失败自动回滚
#   sudo yata-deploy --force    # 即使 jar 与当前一致也重新部署
#   sudo yata-deploy rollback   # 手动回滚到上一个版本（fivetech-admin-pre.jar）
#   sudo yata-deploy status     # 查看服务状态和健康检查
#
# 可用环境变量覆盖默认值，例如：sudo S3_URI=s3://xxx/yyy.jar yata-deploy
set -euo pipefail

S3_URI="${S3_URI:-s3://dmo-data-yata-deploy/backend/fivetech-admin.jar}"
REGION="${REGION:-ap-southeast-1}"
APP_DIR="${APP_DIR:-/opt/fivetech/app}"
JAR_NAME="${JAR_NAME:-fivetech-admin.jar}"
SERVICE="${SERVICE:-fivetech}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8081/actuator/health}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-180}"     # 秒，等待服务变为 UP 的最长时间
KEEP_BACKUPS="${KEEP_BACKUPS:-5}"           # backups/ 下保留的历史版本数
LOG_FILE="${LOG_FILE:-/var/log/yata-deploy.log}"

JAR="$APP_DIR/$JAR_NAME"
PRE_JAR="$APP_DIR/${JAR_NAME%.jar}-pre.jar"
BACKUP_DIR="$APP_DIR/backups"

c_red=$'\e[31m'; c_grn=$'\e[32m'; c_ylw=$'\e[33m'; c_off=$'\e[0m'
log()  { printf '%s %s\n' "$(date '+%F %T')" "$*" | tee -a "$LOG_FILE" >&2; }
ok()   { log "${c_grn}✔ $*${c_off}"; }
warn() { log "${c_ylw}! $*${c_off}"; }
die()  { log "${c_red}✘ $*${c_off}"; exit 1; }

[[ $EUID -eq 0 ]] || { echo "请用 sudo 运行：sudo $0 $*" >&2; exit 1; }
touch "$LOG_FILE" 2>/dev/null || LOG_FILE=/dev/null

health_status() {
  # 输出 UP / DOWN / 空（不可达）
  curl -s -m 5 "$HEALTH_URL" 2>/dev/null \
    | python3 -c 'import sys,json; print(json.load(sys.stdin).get("status",""))' 2>/dev/null || true
}

wait_healthy() {
  local waited=0 st=""
  log "等待服务健康（最长 ${HEALTH_TIMEOUT}s）..."
  while (( waited < HEALTH_TIMEOUT )); do
    if ! systemctl is-active --quiet "$SERVICE" && (( waited > 10 )); then
      # 进程已退出（启动失败），不用再等
      systemctl is-failed --quiet "$SERVICE" && break
    fi
    st="$(health_status)"
    if [[ "$st" == "UP" ]]; then
      ok "健康检查 UP（用时 ${waited}s）：$(curl -s -m 5 "$HEALTH_URL")"
      return 0
    fi
    sleep 3; waited=$((waited + 3))
  done
  warn "健康检查未通过，最后状态：${st:-不可达}"
  return 1
}

show_errors() {
  log "最近的启动错误日志："
  journalctl -u "$SERVICE" -n 300 --no-pager 2>/dev/null \
    | grep -iE 'error|exception|caused by|APPLICATION FAILED' | tail -20 | tee -a "$LOG_FILE" >&2 || true
}

restart_service() {
  systemctl daemon-reload
  systemctl restart "$SERVICE"
}

install_jar() {  # $1 = 源 jar
  install -o root -g root -m 755 "$1" "$JAR"
}

do_rollback() {
  [[ -f "$PRE_JAR" ]] || die "找不到上一个版本 $PRE_JAR，无法回滚"
  warn "回滚到上一个版本：$PRE_JAR（sha256 $(sha256sum "$PRE_JAR" | cut -c1-12)）"
  install_jar "$PRE_JAR"
  restart_service
  if wait_healthy; then ok "回滚完成"; else show_errors; die "回滚后服务仍不健康，请人工处理"; fi
}

do_status() {
  systemctl status "$SERVICE" --no-pager | head -8
  echo "当前 jar：$(ls -l --time-style=+%F_%T "$JAR" | awk '{print $6}')  sha256 $(sha256sum "$JAR" | cut -c1-12)"
  echo "健康检查：$(curl -s -m 5 "$HEALTH_URL" || echo 不可达)"
}

do_deploy() {
  local force="${1:-}" tmp
  tmp="$(mktemp /tmp/fivetech-admin.XXXXXX.jar)"
  TMP_JAR="$tmp"
  trap 'rm -f "${TMP_JAR:-}"' EXIT

  log "===== 开始部署 $S3_URI ====="
  # 1. 下载
  aws s3 cp "$S3_URI" "$tmp" --region "$REGION" --only-show-errors || die "从 S3 下载失败"
  [[ -s "$tmp" && "$(head -c2 "$tmp")" == "PK" ]] || die "下载的文件不是有效的 jar"
  local new_sha cur_sha=""
  new_sha="$(sha256sum "$tmp" | awk '{print $1}')"
  [[ -f "$JAR" ]] && cur_sha="$(sha256sum "$JAR" | awk '{print $1}')"
  ok "下载完成：$(du -h "$tmp" | cut -f1)，sha256 ${new_sha:0:12}"
  if [[ "$new_sha" == "$cur_sha" && "$force" != "--force" ]]; then
    warn "S3 上的 jar 与当前运行版本相同，跳过部署（如需强制重启用 --force）"
    return 0
  fi

  # 2. 备份当前版本
  if [[ -f "$JAR" ]]; then
    mkdir -p "$BACKUP_DIR"
    cp -p "$JAR" "$PRE_JAR"
    cp -p "$JAR" "$BACKUP_DIR/${JAR_NAME%.jar}-$(date +%Y%m%d-%H%M%S).jar"
    ls -1t "$BACKUP_DIR"/*.jar 2>/dev/null | tail -n +$((KEEP_BACKUPS + 1)) | xargs -r rm -f
    ok "已备份当前版本 → $PRE_JAR（sha256 ${cur_sha:0:12}）"
  fi

  # 3. 替换并重启
  install_jar "$tmp"
  ok "已替换 $JAR"
  restart_service
  log "已重启 $SERVICE"

  # 4. 健康检查，失败自动回滚
  if wait_healthy; then
    ok "===== 部署成功 ====="
    systemctl status "$SERVICE" --no-pager | head -5
  else
    show_errors
    if [[ -f "$PRE_JAR" ]]; then do_rollback; die "新版本启动失败，已自动回滚到上一个版本"; fi
    die "新版本启动失败，且没有可回滚的版本"
  fi
}

case "${1:-deploy}" in
  deploy|--force) do_deploy "${1:-}" ;;
  rollback)       do_rollback ;;
  status)         do_status ;;
  -h|--help|help) sed -n '2,10p' "$0" ;;
  *)              echo "未知参数：$1（可用：deploy / --force / rollback / status）" >&2; exit 1 ;;
esac
