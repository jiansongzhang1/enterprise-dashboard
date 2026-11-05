#!/usr/bin/env bash
# ============================================================================
# 监控管理接口自动化测试（newman 跑 monitor-rules.postman_collection.json）
#
# 用法：
#   ./run-monitor-api-test.sh                      # 用默认测试环境
#   ./run-monitor-api-test.sh -e 某个.postman_environment.json
#   ./run-monitor-api-test.sh --folder "1. 新增与查询"   # 只跑某一组（可多次 --folder）
#   ./run-monitor-api-test.sh -c monitor-new-apis.postman_collection.json   # 换集合（新接口补测）
#
# 前置：
#   1. 本机装了 Node.js（newman 用 npx 临时拉，不需要全局安装）
#   2. 测试库已执行 monitor_batch1_test_data.sql 第 1 段（造 oc_postman_ok / oc_postman_dead 群缓存）
#      第 9 组会实时拉 Lark 群，把 oc_postman_ok 置成 dead，下次跑前要再执行一次第 1 段。
#
# 密码、GA 验证码、普通用户 token 都在运行时输入，只写进 600 权限的临时文件，退出即删，不落报告。
# ============================================================================
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
COLLECTION="$DIR/monitor-rules.postman_collection.json"
ENV_FILE=""
FOLDERS=()
NEWMAN_VER="6.2.1"
HTMLEXTRA_VER="1.23.1"
TEST_BASE_URL="https://d3ajrxvlugsarb.cloudfront.net/prod-api"

while [[ $# -gt 0 ]]; do
  case "$1" in
    -e|--env)    ENV_FILE="$2"; shift 2 ;;
    -c|--collection) COLLECTION="$2"; [[ "$COLLECTION" = /* ]] || COLLECTION="$DIR/$COLLECTION"; shift 2 ;;
    --folder)    FOLDERS+=("$2"); shift 2 ;;
    -h|--help)   sed -n '2,18p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

command -v node >/dev/null || { echo "需要 Node.js：brew install node" >&2; exit 1; }
[[ -f "$COLLECTION" ]] || { echo "找不到集合：$COLLECTION" >&2; exit 1; }

# 默认环境：doc/ 下指向测试 CloudFront 的那份（文件名叫 prod，实际 baseUrl 是测试环境）
if [[ -z "$ENV_FILE" ]]; then
  ENV_FILE="$DIR/../Fivetech-Dashboard-prod.postman_environment.json"
fi
[[ -f "$ENV_FILE" ]] || { echo "找不到环境文件：$ENV_FILE" >&2; exit 1; }

read_env() { node -e '
  const e=require(process.argv[1]); const v=(e.values||[]).find(x=>x.key===process.argv[2]);
  process.stdout.write(v&&v.value!=null?String(v.value):"")' "$ENV_FILE" "$1"; }

BASE_URL="$(read_env baseUrl)"; BASE_URL="${BASE_URL:-$TEST_BASE_URL}"
ENV_USER="$(read_env username)"

# 防误跑生产：集合会建/删规则
if [[ "$BASE_URL" == *"yata.fivetech.co.jp"* ]]; then
  echo "baseUrl 是生产（$BASE_URL），本脚本只用于测试环境，已停止。" >&2; exit 1
fi

echo "环境文件：$ENV_FILE"
echo "baseUrl ：$BASE_URL"
read -r -p "用户名 [${ENV_USER:-admin}]：" USERNAME; USERNAME="${USERNAME:-${ENV_USER:-admin}}"
read -r -s -p "密码：" PASSWORD; echo
read -r -s -p "普通用户 token（测第 6 组用，可直接回车跳过）：" USER_TOKEN; echo
read -r -p "确认测试库已执行 monitor_batch1_test_data.sql 第 1 段？[y/N] " OK
[[ "$OK" =~ ^[yY]$ ]] || { echo "先在测试库执行第 1 段造数据再跑。" >&2; exit 1; }
# GA 码 30 秒有效，最后输入，紧接着就登录
read -r -p "GA 验证码（6 位，未绑定 GA 直接回车）：" TOTP

TMP_ENV="$(mktemp -t monitor-env.XXXXXX)"
chmod 600 "$TMP_ENV"
trap 'rm -f "$TMP_ENV"' EXIT

BASE_URL="$BASE_URL" USERNAME="$USERNAME" PASSWORD="$PASSWORD" TOTP="$TOTP" USER_TOKEN="$USER_TOKEN" \
node -e '
  const fs=require("fs"); const e=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));
  const set={baseUrl:process.env.BASE_URL, username:process.env.USERNAME, password:process.env.PASSWORD,
             totp:process.env.TOTP, token:"", userToken:process.env.USER_TOKEN};
  e.values=(e.values||[]).filter(v=>!(v.key in set));
  for (const [k,v] of Object.entries(set)) e.values.push({key:k,value:v,type:"default",enabled:true});
  fs.writeFileSync(process.argv[2], JSON.stringify(e));' "$ENV_FILE" "$TMP_ENV"
unset PASSWORD TOTP USER_TOKEN

STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$DIR/reports/$STAMP"
mkdir -p "$OUT"
[[ -f "$DIR/reports/.gitignore" ]] || echo "*" > "$DIR/reports/.gitignore"

FOLDER_ARGS=()
for f in "${FOLDERS[@]+"${FOLDERS[@]}"}"; do FOLDER_ARGS+=(--folder "$f"); done

set +e
npx -y -p "newman@$NEWMAN_VER" -p "newman-reporter-htmlextra@$HTMLEXTRA_VER" \
  newman run "$COLLECTION" \
    -e "$TMP_ENV" \
    "${FOLDER_ARGS[@]+"${FOLDER_ARGS[@]}"}" \
    --timeout-request 30000 \
    --reporters cli,junit,htmlextra \
    --reporter-junit-export "$OUT/junit.xml" \
    --reporter-htmlextra-export "$OUT/report.html" \
    --reporter-htmlextra-title "监控管理接口测试 $STAMP" \
    --reporter-htmlextra-skipHeaders "Authorization" \
    --reporter-htmlextra-hideRequestBody "0.1 登录（获取 token）" \
    --reporter-htmlextra-hideResponseBody "0.1 登录（获取 token）"
RC=$?
set -e

echo
echo "报告：$OUT/report.html"
[[ $RC -eq 0 ]] && echo "全部通过" || echo "有失败用例（exit $RC），看报告里的 Failed 页签"
echo "提醒：第 9 组会把 oc_postman_ok 置为 dead，下次跑前重新执行测试数据第 1 段。"
exit $RC
