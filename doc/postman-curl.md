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

### 3.1 今日 · 小时粒度

```bash
curl --location "$BASE/dashboard/metrics/summary" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{
    "granularity": "HOUR",
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

### 3.3 自定义区间 · 日粒度

```bash
curl --location "$BASE/dashboard/metrics/summary" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{"from": "2026-09-01", "to": "2026-09-12", "granularity": "DAY"}'
```

> 指标汇总不做对比期，compareType / compareFrom / compareTo 已移除，传了也会被忽略。

### 3.4 边界：早于站点上线日（应报错）

```bash
curl --location "$BASE/dashboard/metrics/summary" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{"from":"2026-01-01","to":"2026-01-31"}'
```

### 3.5 安全：注入式排序列（应静默回退，返回 200）

```bash
curl --location "$BASE/dashboard/metrics/summary" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{"sortColumn":"dep; DROP TABLE sys_user--"}'
```

---

## 4. 明细查询

### 4.1 会员明细 · 默认（不限时间，返回 27 列定义）

```bash
curl --location "$BASE/dashboard/records/member" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{"pageNum":1,"pageSize":20}'
```

### 4.2 会员明细 · 完整入参（对齐原型 MVP-V1.0）

```bash
curl --location "$BASE/dashboard/records/member" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{
    "keyword": "",
    "registerTimeFrom": "2026-09-13 09:00",
    "registerTimeTo": "2026-09-13 10:00",
    "firstDepositTimeFrom": null,
    "firstDepositTimeTo": null,
    "lastBetTimeFrom": null,
    "lastBetTimeTo": null,
    "status": "ok",
    "userType": "real",
    "level": "VIP3",
    "country": "IN",
    "registerChannel": "LP-01 / organic",
    "cumulativeDepositMin": 10000,
    "cumulativeDepositMax": 500000,
    "sourceMetricCode": "reg",
    "sortColumn": "registerTime",
    "sortDirection": "desc",
    "pageNum": 1,
    "pageSize": 20
  }'
```

- 三个时间条件各自独立、可叠加，都是 `yyyy-MM-dd HH:mm`、左闭右开；都不传 = 不限时间。
- 从指标下钻：注册类 → `registerTime*`，首存类（ftd / ftdA）→ `firstDepositTime*`，活跃人数 → `lastBetTime*`。
- 枚举：`status` ok/pend/frozen/self/banned；`userType` real/trial/test/agent；`level` VIP1–VIP18；`country` IN/NP/BD/LK/PK。

预期：`columns` 只剩 key + ftd 两组，`context.spanFrom/To` 等于 slot。

### 4.3 存款明细 · 成功单

```bash
curl --location "$BASE/dashboard/records/deposit" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{"status":"succ","pageNum":1,"pageSize":20}'
```

预期：`columns` 9 列；`summary` 为 `{}`、`summaryNote` 为 `null`（订单表混币种，不出合计）。

### 4.4 存款明细 · 下钻时间片

```bash
curl --location "$BASE/dashboard/records/deposit" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{"slotFrom":"2026-09-13 09:00","slotTo":"2026-09-13 10:00","sourceMetricCode":"dep","status":"succ"}'
```

### 4.5 提款明细 · 待审核

```bash
curl --location "$BASE/dashboard/records/withdraw" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{"status":"auditing","auditStatus":"pending","sortColumn":"createTime","sortDirection":"desc"}'
```

预期：`columns` 17 列，最后一列 `auditNote`；USDT 订单的 `bankName/bankCode/bankCountry` 为 `null`。

### 4.6 投注明细 · 平台 + 类型 + 金额区间

```bash
curl --location "$BASE/dashboard/records/bet" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{"vendorCode":"JILI","gameType":"slots","game":"Super Ace","settleStatus":"done","betAmountMin":100,"betAmountMax":5000,"sortColumn":"betAmount","sortDirection":"desc"}'
```

预期：`columns` 14 列，默认按 `betTime desc`；未结算注单 `payout`、`winLoss` 为 `null`。

---

## 5. 导出 XLSX（`export_csv: true`，字段名为兼容历史保留，也可传 `export: true`）

命令行下载，`-OJ` 会用响应头里的文件名落盘：

```bash
curl --location "$BASE/dashboard/records/member" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{"columns":["status","registerTime","cumulativeDepositAmount"],"export_csv":true}' \
  -OJ
```

指定文件名并查看响应头：

```bash
curl --location "$BASE/dashboard/metrics/summary" \
  --header "Authorization: Bearer $TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{"granularity":"HOUR","export_csv":true}' \
  -D - -o 指标汇总.xlsx
```

检查要点：

```bash
file 指标汇总.xlsx                   # 应为 Microsoft Excel 2007+
unzip -l 指标汇总.xlsx | grep sheet  # 应有两张 sheet：数据、口径说明
```

> Postman 里用右侧 **Send** 下拉的 **Send and Download**，否则 XLSX 会以乱码形式显示在响应区。

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
