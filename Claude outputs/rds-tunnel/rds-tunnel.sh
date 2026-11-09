#!/bin/bash
# cmp1 RDS 本地长连接：本机 127.0.0.1:15432 → cmp1-rds-01:5432（经 SSM 跳板 i-023b0797f3441654e）
# 断线自动重连；Ctrl+C 退出。用法：rds-tunnel（加 alias 后）或 bash rds-tunnel.sh
REGION=ap-southeast-1
TARGET=i-023b0797f3441654e
RDS_HOST=cmp1-rds-01.cb4uiiyq0dcp.ap-southeast-1.rds.amazonaws.com
REMOTE_PORT=5432
LOCAL_PORT=15432
RETRY_SECONDS=5

export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

for cmd in aws session-manager-plugin; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "缺少 $cmd：aws 用 'brew install awscli'，插件用 'brew install --cask session-manager-plugin'"
    exit 1
  fi
done

if lsof -nP -iTCP:"$LOCAL_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "本机端口 $LOCAL_PORT 已被占用（可能隧道已经在运行）："
  lsof -nP -iTCP:"$LOCAL_PORT" -sTCP:LISTEN
  exit 1
fi

trap 'echo; echo "已退出隧道"; exit 0' INT TERM

while true; do
  if ! aws sts get-caller-identity --region "$REGION" >/dev/null 2>&1; then
    echo "$(date '+%F %T') AWS 凭证无效或已过期（SSO 用户先执行 aws sso login），${RETRY_SECONDS}s 后重试…"
    sleep "$RETRY_SECONDS"
    continue
  fi
  echo "$(date '+%F %T') 建立隧道 127.0.0.1:$LOCAL_PORT → $RDS_HOST:$REMOTE_PORT（看到 Waiting for connections... 即可用，不要关闭此窗口）"
  aws ssm start-session \
    --region "$REGION" \
    --target "$TARGET" \
    --document-name AWS-StartPortForwardingSessionToRemoteHost \
    --parameters "{\"host\":[\"$RDS_HOST\"],\"portNumber\":[\"$REMOTE_PORT\"],\"localPortNumber\":[\"$LOCAL_PORT\"]}"
  echo "$(date '+%F %T') 隧道断开，${RETRY_SECONDS}s 后自动重连（Ctrl+C 退出）"
  sleep "$RETRY_SECONDS"
done
