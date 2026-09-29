#!/usr/bin/env bash
# 仪表板四个查询接口的冒烟与边界测试
#
# 用法:
#   ./test_dashboard_api.sh <TOTP验证码>
#   BASE_URL=https://xxx/prod-api USERNAME=admin PASSWORD=xxx ./test_dashboard_api.sh 123456
#   TOKEN=<已有token> ./test_dashboard_api.sh          # 跳过登录
#
# 注意: 生产环境 context-path 是 /prod-api, BASE_URL 要带上

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
USERNAME="${USERNAME:-admin}"
PASSWORD="${PASSWORD:-admin123}"
TOTP="${TOTP:-${1:-}}"
TOKEN="${TOKEN:-}"

PASS=0
FAIL=0

# ---------- 工具 ----------
if command -v jq >/dev/null 2>&1; then
  pretty() { jq . 2>/dev/null || cat; }
  jget()   { jq -r "$1" 2>/dev/null; }
else
  pretty() { python3 -m json.tool 2>/dev/null || cat; }
  jget()   { python3 -c "
import json,sys
try:
    d=json.load(sys.stdin)
except Exception:
    print(''); sys.exit()
for k in '$1'.strip('.').split('.'):
    if isinstance(d,dict): d=d.get(k)
    else: d=None
    if d is None: break
print('' if d is None else d)
"; }
fi

hr()    { printf '\n\033[1;34m%s\033[0m\n' "── $* ──────────────────────────────────"; }
ok()    { printf '  \033[0;32m✓ %s\033[0m\n' "$*"; PASS=$((PASS+1)); }
bad()   { printf '  \033[0;31m✗ %s\033[0m\n' "$*"; FAIL=$((FAIL+1)); }
note()  { printf '  \033[0;90m%s\033[0m\n' "$*"; }

# 发请求: req <名称> <路径> <body> <期望http状态> [期望code]
req() {
  local name="$1" path="$2" body="$3" want_http="${4:-200}" want_code="${5:-}"
  local hdr out http trace code

  hdr=$(mktemp); out=$(mktemp)
  http=$(curl -s -o "$out" -D "$hdr" -w '%{http_code}' \
    -X POST "$BASE_URL$path" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "$body")

  trace=$(grep -i '^x-trace-id:' "$hdr" | tr -d '\r' | awk '{print $2}')
  code=$(cat "$out" | jget '.code')

  printf '\n\033[1m▸ %s\033[0m  [HTTP %s]\n' "$name" "$http"
  [ -n "$trace" ] && note "X-Trace-Id: $trace" || bad "响应头缺少 X-Trace-Id (TraceIdFilter 未生效?)"

  if [ "$http" = "$want_http" ]; then ok "HTTP 状态符合预期 ($want_http)"
  else bad "HTTP 期望 $want_http 实际 $http"; fi

  if [ -n "$want_code" ]; then
    if [ "$code" = "$want_code" ]; then ok "业务 code = $want_code"
    else bad "业务 code 期望 $want_code 实际 ${code:-空}"; fi
  fi

  cat "$out" | pretty | head -60
  rm -f "$hdr" "$out"
}

# ---------- 0. 登录取 token ----------
hr "0. 登录"
if [ -z "$TOKEN" ]; then
  if [ -z "$TOTP" ]; then
    echo "缺少 TOTP 验证码。用法: ./test_dashboard_api.sh <6位验证码>"
    echo "或直接: TOKEN=<已有token> ./test_dashboard_api.sh"
    exit 1
  fi
  LOGIN=$(curl -s -X POST "$BASE_URL/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\",\"code\":\"$TOTP\"}")
  TOKEN=$(echo "$LOGIN" | jget '.token')
  if [ -z "$TOKEN" ]; then
    bad "登录失败"; echo "$LOGIN" | pretty; exit 1
  fi
  ok "登录成功, token 长度 ${#TOKEN}"
else
  note "使用传入的 TOKEN"
fi

# ---------- 1. 健康检查 ----------
hr "1. 健康检查 (actuator, 独立端口)"
MGMT="${MGMT_URL:-http://localhost:8081}"
HEALTH=$(curl -s -m 5 "$MGMT/actuator/health")
if echo "$HEALTH" | grep -q '"status":"UP"'; then ok "health = UP"
else bad "health 异常或不可达: $HEALTH"; fi

# ---------- 2. 指标汇总 ----------
hr "2. 指标汇总 POST /dashboard/metrics/summary"

req "默认今日(不传 from/to) / 小时粒度 / 环比" \
  "/dashboard/metrics/summary" \
  '{"granularity":"HOUR","compareType":"PREV_PERIOD",
    "metricCodes":["reg","ftd","ftdr","dep","wd","net"],"pageNum":1,"pageSize":20}' \
  200 200
note "重点看: context.spanTo 是否截断到整点 / availableGranularities / values 全为 null(占位实现)"

req "近30天 / 日粒度 / 默认核心指标集" \
  "/dashboard/metrics/summary" \
  '{"from":"2026-08-30","to":"2026-09-28","granularity":"DAY"}' \
  200 200
note "重点看: metricCodes 省略时是否返回核心指标集"

req "近30天 / 请求 HOUR 粒度 → 应自动降级" \
  "/dashboard/metrics/summary" \
  '{"from":"2026-08-30","to":"2026-09-28","granularity":"HOUR"}' \
  200 200
note "重点看: warnings 是否含 GRANULARITY_DOWNGRADED, granularity 是否变成 DAY"

req "自定义区间 + 自定义对比(等长)" \
  "/dashboard/metrics/summary" \
  '{"from":"2026-09-01","to":"2026-09-12","granularity":"DAY",
    "compareType":"CUSTOM","compareFrom":"2026-08-20","compareTo":"2026-08-31"}' \
  200 200

req "按指标列排序" \
  "/dashboard/metrics/summary" \
  '{"granularity":"HOUR","sortColumn":"dep","sortDirection":"asc"}' \
  200 200

# ---------- 3. 会员明细 ----------
hr "3. 会员明细 POST /dashboard/records/member"

req "全部列 / 今日" \
  "/dashboard/records/member" \
  '{"viewScheme":"ALL","pageNum":1,"pageSize":20}' \
  200 200
note "重点看: columns 应返回全部 18 列"

req "首存视角 + 按首存时间筛 (模拟从 ftd 指标下钻)" \
  "/dashboard/records/member" \
  '{"slotFrom":"2026-09-13 09:00","slotTo":"2026-09-13 10:00",
    "sourceMetricCode":"ftd","timeField":"FTD_TIME","viewScheme":"FTD",
    "hasFirstDeposit":true}' \
  200 200
note "重点看: columns 应只剩 key + ftd 两组; context.spanFrom/To 应等于 slot"

req "价值视角 + 分层筛选" \
  "/dashboard/records/member" \
  '{"from":"2026-08-30","to":"2026-09-28","viewScheme":"VALUE","tier":"high","stage":"active"}' \
  200 200

req "全量区间 + 关键字搜索" \
  "/dashboard/records/member" \
  '{"from":"2026-03-01","to":"2026-09-28","keyword":"+91","pageSize":10}' \
  200 200
note "重点看: 关键字搜索结果; 区间只由 from/to 决定"

# ---------- 4. 交易明细 ----------
hr "4. 交易明细 POST /dashboard/records/transaction"

req "存款 / 成功单 / 今日" \
  "/dashboard/records/transaction" \
  '{"type":"DEPOSIT","status":"succ","pageNum":1,"pageSize":20}' \
  200 200
note "重点看: summaryNote 应为「仅计成功单」"

req "提款 / 待审核" \
  "/dashboard/records/transaction" \
  '{"type":"WITHDRAW","status":"auditing","auditStatus":"pending"}' \
  200 200

req "存提都要 + 按金额排序" \
  "/dashboard/records/transaction" \
  '{"from":"2026-09-22","to":"2026-09-28","sortColumn":"amount","sortDirection":"desc"}' \
  200 200

req "切换到完成时间口径" \
  "/dashboard/records/transaction" \
  '{"timeField":"FINISH"}' \
  200 200

# ---------- 5. 投注明细 ----------
hr "5. 投注明细 POST /dashboard/records/bet"

req "今日全部" \
  "/dashboard/records/bet" \
  '{"pageNum":1,"pageSize":20}' \
  200 200

req "按厂商筛选 + 按投注额排序" \
  "/dashboard/records/bet" \
  '{"vendor":"JILI","sortColumn":"betAmount","sortDirection":"desc"}' \
  200 200

# ---------- 6. 边界与安全 ----------
hr "6. 边界与安全 (比正常路径更值得看)"

req "对比区间与主区间重叠 → 应报错" \
  "/dashboard/metrics/summary" \
  '{"from":"2026-09-01","to":"2026-09-12",
    "compareType":"CUSTOM","compareFrom":"2026-09-10","compareTo":"2026-09-15"}' \
  200 500
note "期望: 业务 code 非 200, msg 含「对比区间不能与主区间重叠」"

req "早于站点上线日 → 应报错" \
  "/dashboard/metrics/summary" \
  '{"from":"2026-01-01","to":"2026-01-31"}' \
  200 500
note "期望: msg 含「不能早于站点上线日 2026-03-01」"

req "对比区间长度不等 → 只警告不拦截" \
  "/dashboard/metrics/summary" \
  '{"from":"2026-09-01","to":"2026-09-12","granularity":"DAY",
    "compareType":"CUSTOM","compareFrom":"2026-08-01","compareTo":"2026-08-05"}' \
  200 200
note "期望: code=200 且 warnings 含 COMPARE_LENGTH_MISMATCH"

req "SQL 注入式排序列 → 应静默回退, 不报错不拼查询" \
  "/dashboard/records/transaction" \
  '{"sortColumn":"amount; DROP TABLE sys_user--"}' \
  200 200
note "期望: code=200, 服务端按 createTime 排序"

req "未知指标编码 → 应被丢弃, 其余列正常" \
  "/dashboard/metrics/summary" \
  '{"metricCodes":["reg","nonexistent_metric","dep"]}' \
  200 200
note "期望: columns 只有 time/reg/dep, 没有 nonexistent_metric"

req "只传 from 不传 to → 应报错" \
  "/dashboard/metrics/summary" \
  '{"from":"2026-09-01"}' \
  200 500
note "期望: msg 含「from 与 to 需同时传入」"

req "pageSize 超上限 → 参数校验拦截" \
  "/dashboard/records/bet" \
  '{"pageSize":9999}' \
  200 500

# ---------- 7. 鉴权 ----------
# 注意: RuoYi 的 ServletUtils.renderString 固定写 HTTP 200,
# 鉴权结果在响应体的 code 字段 (401 未认证 / 403 无权限), 不能断言 HTTP 状态码
hr "7. 鉴权"
NOAUTH=$(curl -s -X POST "$BASE_URL/dashboard/metrics/summary" \
  -H "Content-Type: application/json" -d '{}' | jget '.code')
if [ "$NOAUTH" = "401" ]; then ok "无 token 业务 code = 401"
else bad "无 token 期望 code 401, 实际 ${NOAUTH:-空}"; fi

BADTOKEN=$(curl -s -X POST "$BASE_URL/dashboard/metrics/summary" \
  -H "Authorization: Bearer invalid.token.here" \
  -H "Content-Type: application/json" -d '{}' | jget '.code')
if [ "$BADTOKEN" = "401" ]; then ok "无效 token 业务 code = 401"
else bad "无效 token 期望 code 401, 实际 ${BADTOKEN:-空}"; fi

# ---------- 8. traceId 透传 ----------
hr "8. traceId 透传"
MYTRACE="smoketest$(date +%s)"
GOT=$(curl -s -D - -o /dev/null -X POST "$BASE_URL/dashboard/records/bet" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "X-Trace-Id: $MYTRACE" -d '{}' \
  | grep -i '^x-trace-id:' | tr -d '\r' | awk '{print $2}')
if [ "$GOT" = "$MYTRACE" ]; then ok "自定义 traceId 原样透传: $GOT"
else bad "traceId 未透传, 期望 $MYTRACE 实际 $GOT"; fi

BADTRACE=$(curl -s -D - -o /dev/null -X POST "$BASE_URL/dashboard/records/bet" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "X-Trace-Id: bad value with spaces" -d '{}' \
  | grep -i '^x-trace-id:' | tr -d '\r' | awk '{print $2}')
if [ -n "$BADTRACE" ] && [ "$BADTRACE" != "bad" ]; then ok "非法 traceId 被替换为新生成: $BADTRACE"
else bad "非法 traceId 未被拦截: $BADTRACE"; fi

# ---------- 汇总 ----------
hr "结果"
printf '  通过 \033[0;32m%d\033[0m  失败 \033[0;31m%d\033[0m\n\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
