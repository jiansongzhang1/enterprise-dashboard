# 本地测试 curl 集（可直接导入 Postman）

Postman 导入方式：**Import → Raw text → 粘贴单条 curl → Continue**。
一次只能导入一条，批量请用下面第 6 节的环境变量配合 Runner。

本地 context-path 是 `/`，生产是 `/prod-api`，注意区分。

---

## 0. 环境变量

```bash
export BASE=http://localhost:8080          # 生产: https://d3ajrxvlugsarb.cloudfront.net/prod-api
export MGMT=http://localhost:8081          # actuator 独立端口
export USERNAME=admin
export PASSWORD=admin123
export TOTP=123456                         # Google Authenticator 当前 6 位码
```

Postman 环境变量建同名的 `base` / `token` 即可，下文 `$BASE` 对应 `{{base}}`。

---

## 1. 登录取 token

```bash
curl --location "$BASE/login" \
  --header 'Content-Type: application/json' \
  --data "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\",\"code\":\"$TOTP\"}"
```

```bash
# 自动提取到环境变量
export TOKEN=$(curl -s -X POST "$BASE/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\",\"code\":\"$TOTP\"}" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin).get("token",""))')
echo "${TOKEN:0:40}..."
```

Postman 里在 Tests 标签加一行自动存 token：

```javascript
pm.environment.set("token", pm.response.json().token);
```

---

## 2. 健康检查

```bash
curl --location "$MGMT/actuator/health"
```

---

## 3. 指标汇总 `/dashboard/metrics/summary`

### 3.1 今日 · 小时粒度 · 环比

```bash
curl --location "$BASE/dashboard/metrics/summary" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{
    "granularity": "HOUR",
    "compareType": "PREV_PERIOD",
    "metricCodes": ["reg","ftd","ftdr","dep","wd","net"],
    "pageNum": 1,
    "pageSize": 20
  }'
```

重点看：`context.spanTo` 是否截断到整点、`context.asOf` 与 `updatedAt` 是否都有值、`availableGranularities` 是否合理。

### 3.2 近 30 天 · 请求 HOUR 应自动降级

```bash
curl --location "$BASE/dashboard/metrics/summary" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{"from":"2026-08-30","to":"2026-09-28","granularity":"HOUR"}'
```

预期：`granularity` 变成 `DAY`，`warnings` 含 `GRANULARITY_DOWNGRADED`。

### 3.3 自定义区间 + 自定义对比（等长）

```bash
curl --location "$BASE/dashboard/metrics/summary" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{
    "from": "2026-09-01",
    "to": "2026-09-12",
    "granularity": "DAY",
    "compareType": "CUSTOM",
    "compareFrom": "2026-08-20",
    "compareTo": "2026-08-31"
  }'
```

### 3.4 边界：对比区间与主区间重叠（应报错）

```bash
curl --location "$BASE/dashboard/metrics/summary" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{ "from": "2026-09-01", "to": "2026-09-12",
    "compareType": "CUSTOM", "compareFrom": "2026-09-10", "compareTo": "2026-09-15"
  }'
```

预期：`code` 非 200，msg 含「对比区间不能与主区间重叠」。

### 3.5 边界：早于站点上线日（应报错）

```bash
curl --location "$BASE/dashboard/metrics/summary" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{"from":"2026-01-01","to":"2026-01-31"}'
```

### 3.6 安全：注入式排序列（应静默回退，返回 200）

```bash
curl --location "$BASE/dashboard/metrics/summary" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{"sortColumn":"dep; DROP TABLE sys_user--"}'
```

---

## 4. 明细查询

### 4.1 会员明细 · 全部列

```bash
curl --location "$BASE/dashboard/records/member" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{"viewScheme":"ALL","pageNum":1,"pageSize":20}'
```

### 4.2 会员明细 · 模拟从首存指标下钻

```bash
curl --location "$BASE/dashboard/records/member" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{
    "slotFrom": "2026-09-13 09:00",
    "slotTo": "2026-09-13 10:00",
    "sourceMetricCode": "ftd",
    "timeField": "FTD_TIME",
    "viewScheme": "FTD",
    "hasFirstDeposit": true
  }'
```

预期：`columns` 只剩 key + ftd 两组，`context.spanFrom/To` 等于 slot。

### 4.3 交易明细 · 存款成功单

```bash
curl --location "$BASE/dashboard/records/transaction" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{"type":"DEPOSIT","status":"succ","pageNum":1,"pageSize":20}'
```

预期：`summaryNote` 为「仅计成功单」。

### 4.4 交易明细 · 方向与状态不匹配（应空结果而非报错）

```bash
curl --location "$BASE/dashboard/records/transaction" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{"type":"DEPOSIT","status":"auditing"}'
```

### 4.5 投注明细 · 按厂商筛选

```bash
curl --location "$BASE/dashboard/records/bet" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{"vendor":"JILI","sortColumn":"betAmount","sortDirection":"desc"}'
```

---

## 5. CSV 导出（`export_csv: true`）

命令行下载，`-OJ` 会用响应头里的文件名落盘：

```bash
curl --location "$BASE/dashboard/records/member" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{"viewScheme":"ALL","export_csv":true}' \
  -OJ
```

指定文件名并查看响应头：

```bash
curl --location "$BASE/dashboard/metrics/summary" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{"granularity":"HOUR","export_csv":true}' \
  -D - -o 指标汇总.csv
```

检查要点：

```bash
file 指标汇总.csv                    # 应为 UTF-8 (with BOM)
head -c 3 指标汇总.csv | xxd         # 前三字节应是 ef bb bf
head -20 指标汇总.csv                # 前面是口径说明区，空行后才是表头
```

> Postman 里用右侧 **Send** 下拉的 **Send and Download**，否则 CSV 会以乱码形式显示在响应区。

导出权限未授时应报错（默认要求 `dashboard:export:csv`）：

```bash
curl --location "$BASE/dashboard/records/bet" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{"export_csv":true}'
```

---

## 6. UDS 数据平台直连（抓真实响应结构用）

这两条需要在**能访问 172.29.106.105 的机器**上执行。
拿到返回后贴回来，可以把 `UdsClient.parseRows()` 的容错解析改成精确解析。

### 6.1 元数据

```bash
curl --location 'http://172.29.106.105:18082/v1/meta/datasets/bet_daily' \
  --header 'Content-Type: application/json' \
  --header 'X-Uds-Principal: fivetech-dashboard' \
  --header 'X-Uds-Tenant: poc' \
  --header 'X-Uds-Roles: merchant_ops' \
  --header 'X-Uds-Merchant-Codes: M001,M002'
```

### 6.2 按天查询

```bash
curl --location 'http://172.29.106.105:18082/v1/query' \
  --header 'Content-Type: application/json' \
  --header 'X-Uds-Principal: fivetech-dashboard' \
  --header 'X-Uds-Tenant: poc' \
  --header 'X-Uds-Roles: merchant_ops' \
  --header 'X-Uds-Merchant-Codes: M001,M002' \
  --header 'X-Request-Id: local-test-001' \
  --data '{
    "dataset": "bet_daily",
    "dimensions": ["merchant_code"],
    "metrics": ["bet_amount","ggr_rate"],
    "time": { "dimension":"dt", "granularity":"DAY", "from":"2026-09-08", "to":"2026-09-14" },
    "options": { "channel": "ONLINE" }
  }'
```

### 6.3 验证 `to` 是闭区间还是开区间（重要）

同一个区间查两次，比较返回行数：

```bash
# 7 天
curl -s --location 'http://172.29.106.105:18082/v1/query' \
  --header 'Content-Type: application/json' \
  --header 'X-Uds-Principal: fivetech-dashboard' --header 'X-Uds-Tenant: poc' \
  --header 'X-Uds-Roles: merchant_ops' --header 'X-Uds-Merchant-Codes: M001,M002' \
  --data '{"dataset":"bet_daily","dimensions":[],"metrics":["bet_amount"],
           "time":{"dimension":"dt","granularity":"DAY","from":"2026-09-08","to":"2026-09-14"}}'

# 1 天
curl -s --location 'http://172.29.106.105:18082/v1/query' \
  --header 'Content-Type: application/json' \
  --header 'X-Uds-Principal: fivetech-dashboard' --header 'X-Uds-Tenant: poc' \
  --header 'X-Uds-Roles: merchant_ops' --header 'X-Uds-Merchant-Codes: M001,M002' \
  --data '{"dataset":"bet_daily","dimensions":[],"metrics":["bet_amount"],
           "time":{"dimension":"dt","granularity":"DAY","from":"2026-09-08","to":"2026-09-08"}}'
```

第一条返回 7 行 = 闭区间；6 行 = 开区间。第二条返回 1 行 = 闭区间；0 行 = 开区间。
**这个如果搞反，每次查询都会差一天且不会报错。**

### 6.4 区间合计怎么发（待确认）

试几种写法，看哪种返回「整区间一行」：

```bash
# 猜测 A：granularity = ALL
--data '{"dataset":"bet_daily","dimensions":[],"metrics":["bet_amount"],
         "time":{"dimension":"dt","granularity":"ALL","from":"2026-09-08","to":"2026-09-14"}}'

# 猜测 B：省略 granularity
--data '{"dataset":"bet_daily","dimensions":[],"metrics":["bet_amount"],
         "time":{"dimension":"dt","from":"2026-09-08","to":"2026-09-14"}}'

# 猜测 C：granularity = TOTAL
--data '{"dataset":"bet_daily","dimensions":[],"metrics":["bet_amount"],
         "time":{"dimension":"dt","granularity":"TOTAL","from":"2026-09-08","to":"2026-09-14"}}'
```

哪种可行就把 `dashboard.gateway.uds.total-granularity` 设成对应值。

### 6.5 切到 UDS 网关后跑通本系统

```bash
export UDS_BASE_URL=http://172.29.106.105:18082
export UDS_TENANT=poc
export UDS_MERCHANT_CODES=M001,M002
# 重启后端，再跑第 3 节的用例，这次应该有真实数值
```

---

## 7. 鉴权与 traceId

```bash
# 无 token：HTTP 仍是 200，业务 code=401
curl -i --location "$BASE/dashboard/metrics/summary" \
  --header 'Content-Type: application/json' \
  --data '{}'

# traceId 透传：应原样返回
curl -i --location "$BASE/dashboard/records/bet" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --header 'X-Trace-Id: mytest123' \
  --data '{}' | grep -i x-trace-id

# 非法 traceId：应被替换为新生成的
curl -i --location "$BASE/dashboard/records/bet" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --header 'X-Trace-Id: bad value with spaces' \
  --data '{}' | grep -i x-trace-id
```
