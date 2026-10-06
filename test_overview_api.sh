#!/usr/bin/env bash
# 运营总览接口 POST /dashboard/metrics/overview 的联调测试
#
#   ./test_overview_api.sh <TOTP验证码>
#   TOKEN=<已有token> ./test_overview_api.sh
#   BASE_URL=https://xxx/prod-api SLOT_FROM='2026-09-29 00:00' SLOT_TO='2026-09-30 00:00' ./test_overview_api.sh 123456
#
# 覆盖三件事: 查询核心指标 / 参数与边界 / 页面导出
#
# 断言一律看响应体的 code, 不看 HTTP 状态码 ——
# RuoYi 的 ServletUtils.renderString 固定写 HTTP 200, 鉴权失败也是 200,
# 只有导出成功那一次返回的是文件流, 没有 code 可看, 改看响应头.

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
USERNAME="${USERNAME:-admin}"
PASSWORD="${PASSWORD:-admin123}"
TOTP="${TOTP:-${1:-}}"
TOKEN="${TOKEN:-}"

# 默认查昨天一整天的小时粒度: 今天的数据水位可能还没到, 一整天的昨天一定齐
SLOT_FROM="${SLOT_FROM:-$(date -v-1d '+%Y-%m-%d 00:00' 2>/dev/null || date -d yesterday '+%Y-%m-%d 00:00')}"
SLOT_TO="${SLOT_TO:-$(date '+%Y-%m-%d 00:00')}"

PASS=0
FAIL=0
OUTDIR="${OUTDIR:-/tmp/overview-test}"
mkdir -p "$OUTDIR"

hr()   { printf '\n\033[1;34m%s\033[0m\n' "── $* ──────────────────────────────"; }
ok()   { printf '  \033[0;32m✓ %s\033[0m\n' "$*"; PASS=$((PASS+1)); }
bad()  { printf '  \033[0;31m✗ %s\033[0m\n' "$*"; FAIL=$((FAIL+1)); }
note() { printf '  \033[0;90m%s\033[0m\n' "$*"; }

# assert <描述> <python表达式>  —— 表达式里 d 是响应 JSON
assert() {
  local desc="$1" expr="$2"
  local result
  result=$(python3 - "$BODY" "$expr" <<'PY'
import json,sys
path, expr = sys.argv[1], sys.argv[2]
try:
    d = json.load(open(path, encoding='utf-8'))
except Exception as e:
    print("PARSE_ERROR " + str(e)); sys.exit()
try:
    v = eval(expr, {"d": d, "len": len, "all": all, "any": any, "sum": sum,
                    "abs": abs, "isinstance": isinstance, "float": float,
                    "list": list, "dict": dict, "str": str, "set": set})
    print("PASS" if v else "FAIL")
except Exception as e:
    print("ERROR " + type(e).__name__ + ": " + str(e))
PY
)
  case "$result" in
    PASS) ok "$desc" ;;
    FAIL) bad "$desc" ;;
    *)    bad "$desc  [$result]" ;;
  esac
}

# post <名称> <body> [期望的业务code, 默认200] -> 结果写进 $BODY
post() {
  local name="$1" body="$2" want="${3:-200}"
  BODY="$OUTDIR/$(echo "$name" | tr -cd 'a-zA-Z0-9_').json"
  local hdrs="$OUTDIR/h.txt"
  curl -s -o "$BODY" -D "$hdrs" \
    -X POST "$BASE_URL/dashboard/metrics/${API_PATH:-overview}" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "$body" >/dev/null
  printf '\n\033[1m▸ %s\033[0m\n' "$name"
  local trace
  trace=$(grep -i '^x-trace-id:' "$hdrs" | tr -d '\r' | awk '{print $2}')
  [ -n "$trace" ] && note "X-Trace-Id: $trace"
  [ "$want" = "-" ] || assert "业务 code = $want" "d.get('code') == $want"
}

# ---------- 0. 登录 ----------
hr "0. 登录"
if [ -z "$TOKEN" ]; then
  if [ -z "$TOTP" ]; then
    echo "用法: ./test_overview_api.sh <6位验证码>   或   TOKEN=<token> ./test_overview_api.sh"; exit 1
  fi
  TOKEN=$(curl -s -X POST "$BASE_URL/login" -H "Content-Type: application/json" \
    -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\",\"code\":\"$TOTP\"}" \
    | python3 -c "import json,sys;print(json.load(sys.stdin).get('token',''))")
  [ -n "$TOKEN" ] || { bad "登录失败"; exit 1; }
  ok "登录成功"
else
  note "使用传入的 TOKEN"
fi
note "查询区间 $SLOT_FROM ~ $SLOT_TO (左闭右开)"

# =====================================================================
hr "1. 查询核心指标 blocks=[METRICS]"
# =====================================================================
post "metrics" "{
  \"slotFrom\":\"$SLOT_FROM\", \"slotTo\":\"$SLOT_TO\",
  \"granularity\":\"HOUR\", \"compareType\":\"PREV_PERIOD\",
  \"blocks\":[\"METRICS\"], \"includeSeries\":true
}"

M="d['data']['blocks']['METRICS']"
assert "返回 METRICS 块" "set(d['data']['blocks']) == {'METRICS'}"
assert "取数没有降级 (blockErrors 为空)" "not d['data'].get('blockErrors')"
assert "指标卡 20 张 (21 个指标去掉不上墙的 login)" "$M['total'] == 20"
assert "核心指标 8 个" "$M['coreCount'] == 8"
assert "每张卡都有 code/label/valueFormat" \
  "all(c.get('code') and c.get('label') and c.get('valueFormat') for c in $M['items'])"
assert "series 长度恒等于 slot.points (前端按下标对齐标签)" \
  "all(len(c.get('series') or []) == d['data']['slot']['points'] for c in $M['items'])"
assert "slot.labels 长度也等于 points" \
  "len(d['data']['slot']['labels']) == d['data']['slot']['points']"
assert "PCT 指标走百分点: 有 deltaPt 就没有 deltaPct" \
  "all(not (c.get('deltaPt') is not None and c.get('deltaPct') is not None) for c in $M['items'])"
assert "比率已 ×100 落在 0~100 (未归一化会是 0.xx)" \
  "all(c.get('value') is None or 0 <= float(c['value']) <= 100 for c in $M['items'] if c['valueFormat'] == 'PCT')"
assert "时长已 ÷60, 单位是分钟而不是秒 (<600)" \
  "all(c.get('value') is None or float(c['value']) < 600 for c in $M['items'] if c['valueFormat'] == 'MIN')"
assert "监控未上线, alert 一律 NOT_CONFIGURED" \
  "all((c.get('alert') or {}).get('state') == 'NOT_CONFIGURED' for c in $M['items'])"
assert "monitoring.configured = false" "d['data']['monitoring']['configured'] is False"
assert "活跃人数合计不是序列加总 (合计 <= 序列和)" \
  "all(c.get('value') is None or float(c['value']) <= sum(float(v) for v in (c.get('series') or []) if v is not None) for c in $M['items'] if c['code']=='active')"
assert "币种与时区已回填" "d['data']['currency'] and d['data']['timezone']"
assert "updateFrequency = hour" "d['data']['updateFrequency'] == 'hour'"
assert "对比期已解析且不与主区间重叠" \
  "d['data']['compare']['to'] <= d['data']['slot']['from']"
python3 -c "
import json;d=json.load(open('$BODY'))
b=d['data']['blocks']['METRICS']['items']
print('  前 5 张卡:', ', '.join('%s=%s' % (c['code'], c.get('value')) for c in b[:5]))
print('  asOf =', d['data'].get('asOf'), ' empty =', d['data'].get('empty'))
"

note "只要核心 8 个: 传 metrics 列表"
post "metrics_subset" "{
  \"slotFrom\":\"$SLOT_FROM\", \"slotTo\":\"$SLOT_TO\", \"granularity\":\"HOUR\",
  \"blocks\":[\"METRICS\"], \"metrics\":[\"reg\",\"ftd\",\"dep\"], \"includeSeries\":false
}"
assert "按请求返回 3 张卡" "$M['total'] == 3"
assert "includeSeries=false 时不返回序列" \
  "all(not c.get('series') for c in $M['items'])"

note "未知指标编码应报 4003, 不静默丢弃"
post "metrics_unknown" "{
  \"slotFrom\":\"$SLOT_FROM\", \"slotTo\":\"$SLOT_TO\",
  \"blocks\":[\"METRICS\"], \"metrics\":[\"reg\",\"no_such_metric\"]
}" 4003
assert "提示里点名了错的那个指标" "'no_such_metric' in (d.get('msg') or '')"

# =====================================================================
hr "2. 参数与边界"
# =====================================================================
note "blocks 不传 = 首屏, 默认返回指标墙"
post "blocks_default" "{
  \"slotFrom\":\"$SLOT_FROM\", \"slotTo\":\"$SLOT_TO\", \"granularity\":\"HOUR\"
}"
assert "省略 blocks 时默认返回 METRICS" \
  "set(d['data']['blocks']) == {'METRICS'}"

note "单个字符串也接受: blocks 传 'METRICS'"
post "blocks_string" "{
  \"slotFrom\":\"$SLOT_FROM\", \"slotTo\":\"$SLOT_TO\", \"block\":\"METRICS\"
}"
assert "字符串形式解析成功, 且只返回这一块" \
  "set(d['data']['blocks']) == {'METRICS'}"

note "长区间应自动降级粒度而不是报错"
post "downgrade" "{
  \"slotFrom\":\"2026-06-01 00:00\", \"slotTo\":\"2026-09-01 00:00\",
  \"granularity\":\"HOUR\", \"blocks\":[\"METRICS\"]
}"
assert "粒度被降级并写进 notices" \
  "any(n['code'] == 'GRANULARITY_DOWNGRADED' for n in d['data']['notices'])"
assert "降级后 slot.granularity 不再是 HOUR" "d['data']['slot']['granularity'] != 'HOUR'"

note "左闭右开: from == to 必须报错, 而不是当成 1 个点"
post "empty_range" "{
  \"slotFrom\":\"$SLOT_FROM\", \"slotTo\":\"$SLOT_FROM\", \"blocks\":[\"METRICS\"]
}" 4001
assert "提示说明了左闭右开" "'slotTo' in (d.get('msg') or '') or '左闭右开' in (d.get('msg') or '')"

# =====================================================================
hr "3. 页面导出 export=true"
# =====================================================================
XLSX="$OUTDIR/overview.xlsx"
HDRS="$OUTDIR/export-headers.txt"
curl -s -o "$XLSX" -D "$HDRS" \
  -X POST "$BASE_URL/dashboard/metrics/overview" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{
    \"slotFrom\":\"$SLOT_FROM\", \"slotTo\":\"$SLOT_TO\", \"granularity\":\"HOUR\",
    \"blocks\":[\"METRICS\"], \"export\":true, \"exportFormat\":\"XLSX\"
  }" >/dev/null

printf '\n\033[1m▸ 导出 xlsx\033[0m\n'
CT=$(grep -i '^content-type:' "$HDRS" | tr -d '\r' | head -1)
CD=$(grep -i '^content-disposition:' "$HDRS" | tr -d '\r' | head -1)
SIZE=$(wc -c < "$XLSX" | tr -d ' ')

if echo "$CT" | grep -qi 'spreadsheetml.sheet'; then ok "Content-Type 是 xlsx"
else bad "Content-Type 不对: ${CT:-空}"; head -c 300 "$XLSX"; echo; fi

if echo "$CD" | grep -qi 'attachment'; then ok "Content-Disposition: attachment (浏览器会弹下载)"
else bad "缺少 attachment: ${CD:-空}"; fi

if echo "$CD" | grep -q "filename\*=UTF-8''"; then ok "中文文件名走 RFC 5987, 另有 ASCII 兜底"
else bad "文件名没有 RFC 5987 形式, 中文会乱码"; fi

if [ "$SIZE" -gt 2000 ]; then ok "文件大小 $SIZE 字节"
else bad "文件过小($SIZE 字节), 多半是被错误体顶掉了"; head -c 300 "$XLSX"; echo; fi

# xlsx 本质是 zip, 用 unzip -l 就能看出 sheet 数量, 不必装 openpyxl
if command -v unzip >/dev/null 2>&1; then
  SHEETS=$(unzip -l "$XLSX" 2>/dev/null | grep -c 'xl/worksheets/sheet')
  if [ "$SHEETS" -ge 3 ]; then ok "文件可解压, 含 $SHEETS 张 sheet (主要指标/时间序列/口径说明)"
  else bad "sheet 数量异常: $SHEETS"; fi
else
  note "没有 unzip, 跳过结构检查"
fi

if python3 -c "import openpyxl" 2>/dev/null; then
  python3 - "$XLSX" <<'PY'
import sys, openpyxl
wb = openpyxl.load_workbook(sys.argv[1])
print('  sheet:', ', '.join(wb.sheetnames))
ws = wb['主要指标']
print('  主要指标 %d 行 × %d 列' % (ws.max_row, ws.max_column))
# 数值必须是数字而不是字符串, 否则 Excel 里没法求和排序
vals = [c.value for c in ws['E'][3:] if c.value is not None]
bad = [v for v in vals if isinstance(v, str)]
print('  ✓ 当期值全是数字类型' if not bad else '  ✗ 有 %d 个值写成了字符串: %s' % (len(bad), bad[:3]))
PY
else
  note "没有 openpyxl (pip3 install openpyxl 可做更细的检查), 跳过内容检查"
fi

note "导出格式已统一为 XLSX, 不再测试 CSV"

note "无 token 导出应被拦在鉴权, 不能落一个文件"
NOAUTH=$(curl -s -X POST "$BASE_URL/dashboard/metrics/overview" \
  -H "Content-Type: application/json" \
  -d "{\"slotFrom\":\"$SLOT_FROM\",\"slotTo\":\"$SLOT_TO\",\"export\":true}" \
  | python3 -c "import json,sys;print(json.load(sys.stdin).get('code',''))" 2>/dev/null)
if [ "$NOAUTH" = "401" ]; then ok "无 token 业务 code = 401"
else bad "无 token 期望 401, 实际 ${NOAUTH:-空}"; fi

# =====================================================================
hr "4. 其余板块 rankingboard / cohort / reg-channels (用户与价值一期不做)"
# =====================================================================
# 拆解数据集（dashboard.gateway.uds.breakdowns）未配置时：结构完整、items 为空、notices 含 DATA_NOT_CONNECTED
DAY_FROM="${SLOT_FROM:0:10}"; DAY_TO="${SLOT_TO:0:10}"
[ "$DAY_FROM" = "$DAY_TO" ] && DAY_TO=$(python3 -c "import datetime,sys;print((datetime.date.fromisoformat('$DAY_FROM')+datetime.timedelta(days=1)).isoformat())")

API_PATH=rankingboard post "rankingboard" "{
  \"granularity\":\"HOUR\", \"slotFrom\":\"$SLOT_FROM\", \"slotTo\":\"$SLOT_TO\", \"topN\":10
}"
assert "有 bonus 与 games 两块" "'bonus' in d['data'] and 'games' in d['data']"
assert "updateFrequency = hour" "d['data']['updateFrequency'] == 'hour'"
assert "赠金最多 6 项, 占比合计约 100%" \
  "len(d['data']['bonus']['items']) <= 6 and (not d['data']['bonus']['items'] or abs(sum(float(i['share']) for i in d['data']['bonus']['items']) - 100) < 0.6)"
assert "游戏榜按投注额降序且不超过 topN" \
  "len(d['data']['games']['items']) <= 10 and all(float(a['betAmount'] or 0) >= float(b['betAmount'] or 0) for a,b in zip(d['data']['games']['items'], d['data']['games']['items'][1:]))"
assert "未接入的数据在 notices 里说明, 不是静默空" \
  "d['data']['games']['items'] or any(n['code'] in ('DATA_NOT_CONNECTED','DATA_NOT_READY','DATA_LAGGING') for n in d['data']['notices'])"

API_PATH=rankingboard post "rankingboard_topN_51" "{
  \"granularity\":\"HOUR\", \"slotFrom\":\"$SLOT_FROM\", \"slotTo\":\"$SLOT_TO\", \"topN\":51
}" 4004

API_PATH=rankingboard post "rankingboard_compare_ignored" "{
  \"granularity\":\"HOUR\", \"slotFrom\":\"$SLOT_FROM\", \"slotTo\":\"$SLOT_TO\", \"compareType\":\"PREV_PERIOD\"
}"
assert "传了对比参数进 PARAM_IGNORED" "any(n['code']=='PARAM_IGNORED' for n in d['data']['notices'])"

API_PATH=cohort post "cohort" "{
  \"slotFrom\":\"$DAY_FROM\", \"slotTo\":\"$DAY_TO\", \"type\":\"BOTH\"
}"
assert "updateFrequency = day" "d['data']['updateFrequency'] == 'day'"
assert "固定 9 列 D+1…D+30" "d['data']['columns'] == ['D+1','D+2','D+3','D+4','D+5','D+6','D+7','D+15','D+30']"
assert "每行 values 长度 = 9" \
  "all(len(r['values']) == 9 for t in (d['data']['retention'], d['data']['ltv']) for r in t['rows'])"
assert "最后一个分群日 (昨天) 的 D+1 还没有观察值, 应为 null" \
  "not d['data']['retention']['rows'] or d['data']['retention']['rows'][-1]['cohortDate'] < d['data']['asOf'][:10] and (d['data']['retention']['rows'][-1]['cohortDate'] != (__import__('datetime').date.fromisoformat(d['data']['asOf'][:10]) - __import__('datetime').timedelta(days=1)).isoformat() or d['data']['retention']['rows'][-1]['values'][0] is None)"

API_PATH=cohort post "cohort_export_both" "{
  \"slotFrom\":\"$DAY_FROM\", \"slotTo\":\"$DAY_TO\", \"type\":\"BOTH\", \"export\":true
}" 4005

API_PATH=reg-channels post "reg_channels" "{
  \"slotFrom\":\"$SLOT_FROM\", \"slotTo\":\"$SLOT_TO\"
}"
assert "updateFrequency = hour" "d['data']['updateFrequency'] == 'hour'"
assert "三个渠道分组都返回" "{g['code'] for g in d['data']['groups']} >= {'ad','ag','og'}"
assert "分组人数之和 = totalRegistrations" \
  "d['data']['totalRegistrations'] is None or sum(g['registrations'] or 0 for g in d['data']['groups']) == d['data']['totalRegistrations']"
assert "渠道按注册人数降序" \
  "all(a['registrations'] >= b['registrations'] for a,b in zip(d['data']['channels'], d['data']['channels'][1:]))"

# ---------- 汇总 ----------
hr "结果"
printf '  通过 \033[0;32m%d\033[0m  失败 \033[0;31m%d\033[0m\n' "$PASS" "$FAIL"
note "响应体与导出文件留在 $OUTDIR"
[ "$FAIL" -eq 0 ] || exit 1
