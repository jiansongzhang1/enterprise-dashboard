# 监控管理 · Lark 群实时拉取（GET /monitor/lark/chats）部署与验证

2026-10-21。属于一期「Lark 批次」的第一部分：LarkClient + 实时群列表与缓存。发送、重试、失效检测在后续。

## 本批内容

- `GET /monitor/lark/chats`：规则抽屉打开「通知」下拉时调用，后端实时调 Lark `GET /open-apis/im/v1/chats`（翻完所有页，page_size 100），刷新本站 `monitor_lark_chat`，返回：

  ```json
  { "code": 200, "data": {
      "fetched": true, "fetchedAt": "2026-10-21 13:05",
      "chats": [
        { "chatId": "oc_5d71b8...", "name": "金流告警群", "status": "ok",   "refCount": 3 },
        { "chatId": "oc_92c4ea...", "name": "市場活動群", "status": "dead", "refCount": 1 } ] } }
  ```

  - 拉取成功：拉到的群 upsert 为 ok（更新群名、last_seen_at），之前有、这次没拉到的置 dead；机器人被拉回同一个群后自动恢复 ok。
  - 拉取失败（超时、5xx、限流、未识别的错误码）：返回上次结果，`fetched = false`，`fetchedAt` 为上次成功时间（从没成功过为 null）。
  - 取 token 失败、应用缺少 `im:chat:readonly` 权限、服务端没配凭证：`4802「Lark 應用憑證無效」`，具体原因只在后端日志。
  - ok 的群全部返回；dead 的群只在被规则（含暂停的，不含已删除的）引用时返回；`refCount` = 引用它的规则数。按群名自然排序（「2 群」在「10 群」前）。
  - 同一站点 30 秒内重复调用直接返回上次结果（`MONITOR_LARK_CHATS_CACHE_SECONDS`，0 = 每次实时拉取）。
  - 权限：`monitor:rule:add` 或 `monitor:rule:edit`；站点取请求头 `X-Site-Code`。
- 拉取后，`POST /monitor/rules` 的 `notifyChatId` 校验（必须在本站群缓存里、未失效）就有了真实数据来源，不再需要手工插 `monitor_lark_chat`。

## 代码变更

| 位置 | 说明 |
|---|---|
| `fivetech-common/.../common/lark/LarkClient.java` 等（新） | `LarkClient`（取 token、列群、发文本）、`LarkResult`、`LarkErrorCategory`、`LarkErrorCodes`（错误码对照表）、`LarkChat`。放在 common：监控在 system，framework 依赖 system，放 framework 会循环依赖 |
| `fivetech-framework/.../notification/LarkNotificationService.java` | 改为调用 LarkClient，对外行为不变（`/notification/lark` 发送接口照旧） |
| `fivetech-system/.../monitor/config/MonitorProperties.java` | 新增 `monitor.lark.app-id / app-secret / chats-cache-seconds` |
| `fivetech-system/.../monitor/service/IMonitorLarkChatService`、`impl/MonitorLarkChatServiceImpl`、`impl/MonitorLarkChatWriter`、`MonitorNaturalOrder`（新） | 拉取、刷新缓存（一个事务）、失败兜底、自然排序 |
| `MonitorLarkChatMapper.java / .xml` | 新增 `selectLastSeenAt`、`upsertSeenChats`（last_seen_at 不回退）、`markUnseenDead`、`selectChatOptions`（带 refCount），都按站点过滤 |
| `fivetech-admin/.../monitoring/MonitorLarkController.java`（新） | `GET /monitor/lark/chats` |
| `application.yml` | `monitor.lark.*` |
| `LarkErrorCodes` / `LarkResult.isChatGone()`、`IMonitorLarkChatService.markDeadIfChatGone`、`MonitorLarkChatMapper.markChatDead` | 发送返回 230002 / 232009 / 230034 时把群置为失效 |
| 单元测试（新） | `LarkClientTest`（本机 HttpServer 模拟 Lark：翻页、token 缓存与失效重取、错误分类、发送）、`MonitorLarkChatServiceTest`、`MonitorNaturalOrderTest` |
| `doc/monitor/monitor-rules.postman_collection.json` | 新增第 9 组 |
| `doc/monitor/monitor_batch1_test_data.sql` | 注释：第 9 组会把假群置为失效 |

不需要执行 SQL：`monitor_lark_chat` 在 `monitor_schema_postgresql.sql` 里已建好。

## 部署步骤

1. 客户在 Lark 开放平台的自建应用开通 `im:chat:readonly`（拉群列表）和 `im:message:send_as_bot`（后续发告警）并发布新版本，把机器人拉进要用的群。
2. 服务器 `/etc/fivetech.env` 配凭证（值通过安全渠道获取，不进代码、git、聊天和文档）：

   ```bash
   MONITOR_LARK_APP_ID=<APP_ID>
   MONITOR_LARK_APP_SECRET=<SECRET>
   # 可选：同一站点多少秒内复用上次拉取结果，默认 30；0 = 每次实时拉取
   # MONITOR_LARK_CHATS_CACHE_SECONDS=30
   ```

   不配 `MONITOR_LARK_*` 时沿用已有的 `LARK_APP_ID / LARK_APP_SECRET`。与 `LARK_ENABLED` 无关。
3. 正常发版、重启。

## 验证

### 本地已做的检查

- 用生产 jar 里的全部依赖编译 common / system / framework / admin 全部源码（`--release 17`）：通过。
- 单元测试 26 个全部通过（含 `MonitorMapperScopeTest`，新增查询都引用了 `monitorScopeFilter`）。
- 新 Mapper 语句用 MyBatis + PostgreSQL 16 实跑 12 项：刷新与置失效、只被已删除规则引用的失效群不返回、refCount 含暂停规则且不跨站点、较早拉取晚提交不回退群名、拉回同一个群自动恢复、JITO 与 SITE-IN 同 chat_id 互不影响。

### 部署后

```bash
# token 用登录接口拿到的值，不要贴到聊天或文档里
curl -s -H "Authorization: Bearer <TOKEN>" -H "X-Site-Code: SITE-IN" \
  <BASE_URL>/monitor/lark/chats | jq   # BASE_URL 与 Postman 环境的 baseUrl 相同
```

- `fetched: true`，`chats` 里能看到机器人所在的群；把机器人拉进一个新群后（等 30 秒或把缓存秒数设为 0）再调，能看到新群。
- 返回 4802：看后端日志「Lark 应用凭证无效或缺少权限」那一行：`tokenFailure=true` 一般是 App ID / Secret 不对或应用已停用；`larkCode=99991672` 是没开 `im:chat:readonly` 或开了没发布新版本。
- 用拉到的 `chatId` 新建规则，不再报「通知群不存在，請重新選擇」。
- Postman：`monitor-rules.postman_collection.json` 第 9 组。

## 已知限制 / 待确认

- 一期一个 Lark 应用服务所有站点；各站点各自拉取、各自缓存（同一个群在每个站点各一条记录）。
- `LarkErrorCodes` 里的错误码按 Lark 文档整理，上线后遇到未识别的 code 会按「可重试」处理并打 WARN 日志，届时补进对照表。
- 群超过 5000 个（50 页）按拉取失败处理，返回上次结果。
- 发送消息时识别群不可用（已实现）：Lark 发送接口返回 230002（机器人不在群里 / 被踢出）、232009（群已解散）、230034（chat_id 无效）时，
  调 `IMonitorLarkChatService.markDeadIfChatGone(scope, chatId, result)` 把本站缓存里的群置为失效；被禁言（230035）等群还在的情况不动。
  发送返回码就能判断，不需要每天定时刷新群状态；打开群下拉（本接口）时也会整体刷新一次。错误码依据 Lark 官方「发送消息」接口错误码表（2026-10-21 核对）。
- 告警发送任务（每分钟投递、重试）在 Lark 批次后半部分，上线时在发送失败处调用上面的方法。
