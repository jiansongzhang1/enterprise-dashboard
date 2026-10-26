# 监控管理 · Lark 告警发送 · 部署与验证

2026-10-22。一期「Lark 批次」后半部分：把判定引擎写好的 `monitor_notify_log` pending 记录真正发到 Lark 群。

## 增量脚本补充

已部署过的库按顺序执行（psql -f）：`sql/alter_monitor_event_mute_postgresql.sql`（若未执行）→ `sql/alter_monitor_event_notify_paused_postgresql.sql`（事件通知状态 CHECK 增加 paused）。

## 做了什么

| 项 | 说明 |
|---|---|
| 发送任务 | `MonitorEngineScheduler#sendNotifications`，每分钟第 30 秒（IST）一轮，Redis 锁 `monitor:lock:notify`（55 秒），和判定共用 `monitor.engine.enabled` 开关 |
| 投递 | `MonitorNotifySender`：每个站点每轮最多 20 条，先到期的先发；每轮时间预算 40 秒，超过的留到下一轮。文本消息，`receive_id_type=chat_id` |
| 成功 | 记录 `sent`；事件 `notify_status=sent`、`notified_at`、`notify_count+1`；规则 `last_notified_at`（**静默期从这里开始算**） |
| 超时 / 限流 / 5xx / 未识别错误码 | 1 / 5 / 30 分钟后重试，共重试 3 次；仍失败 → 记录 `failed`，事件显示「发送失败」 |
| 机器人不在群里、群已解散、无权限 | 直接 `failed`；230002 / 232009 / 230034 时把该群置为失效 |
| 凭证无效（App ID/Secret 错、应用停用、缺权限） | 本轮该站点的发送记录全部 `failed`（error_msg「Lark 機器人憑證失效，未發送（…）」），事件 `notify_status = paused`，前端显示「僅記錄」；不再请求 Lark；告警到研发值班「監控告警暫停發送：Lark 機器人憑證失效」（ErrorAlertService → Telegram，按原因去重） |
| 没配置 App ID/Secret | 发送记录 `failed`（「本站尚未接入 Lark 機器人，未發送」），事件 `notify_status = unset`（未配置） |
| 规则在发送前被暂停或删除（事件 stopped） | 不发送，记录 `failed`（error_msg「規則已暫停或刪除，取消發送」），事件不动 |
| 判定与发送并发 | 判定更新事件时不再回写旧的 `notify_status`（为 null 时保留库里的值），避免把发送任务刚写的 sent / failed 覆盖回 pending |

新增文件：`MonitorNotifySender`、`MonitorNotifyWriter`、`MonitorOpsAlerter`（system 里的接口）、`MonitorOpsAlerterImpl`（framework）、`MonitorNotifyMapper`（java + xml）、`MonitorPendingNotify`、`MonitorNotifySenderTest`。
不需要改表。

## 配置

| 环境变量 / 配置 | 默认 | 说明 |
|---|---|---|
| `MONITOR_ENGINE_ENABLED` | false | 判定和发送一起开关 |
| `MONITOR_LARK_APP_ID` / `MONITOR_LARK_APP_SECRET` | 沿用 `LARK_APP_ID` / `LARK_APP_SECRET` | 应用需要 `im:message:send_as_bot`（发消息）和 `im:chat:readonly`（拉群） |
| `MONITOR_NOTIFY_CRON` | `30 * * * * *` | 发送频率 |
| `monitor.engine.notify-batch-size / notify-budget-seconds / notify-lock-seconds` | 20 / 40 / 55 | 一般不用改 |
| `MONITOR_EVENT_PAGE_URL` | 空 | 告警消息里「查看」链接，空则不带 |

## 部署

```bash
mvn -pl fivetech-admin -am clean package    # 含 MonitorNotifySenderTest、MonitorMapperScopeTest
```

重启后日志应能看到发送任务每分钟一轮（没有待发送记录时不打日志）。

## 验证

1. 机器人拉进一个测试群，前端新建规则时选这个群（打开下拉会实时拉取）。
2. 造一条会立刻触发的规则：下限类，注意 / 严重阈值都设得**高于**当前实际值，持续次数 1，启用。
3. 等下一个完整小时判定（最晚该小时结束后的 :50），然后 1 分钟内群里应收到告警。

```sql
-- 发送记录：pending → sent；失败看 error_msg / retry_count / next_retry_at
select id, event_id, chat_id, status, retry_count, next_retry_at, sent_at, success, error_msg
  from monitor_notify_log order by id desc limit 10;

-- 事件通知状态
select event_id, rule_id, level, status, notify_status, notified_at, notify_count
  from monitor_event where event_type = 'alert' order by event_id desc limit 10;

-- 规则最近发送时间（静默期）
select rule_id, rule_no, mute_hours, last_notified_at from monitor_rule where del_flag = '0' order by rule_no;
```

4. 只想验证发送通道、不想等判定：手工插一条 pending 记录（chat_id 换成测试群），1 分钟内会发出去。

```sql
insert into monitor_notify_log (site_code, msg_type, chat_id, platform, status, content, retry_count, next_retry_at)
values ('SITE-IN', 'alert', 'oc_换成测试群', 'lark', 'pending', '【測試】監控告警發送通道', 0, localtimestamp);
```

5. 失败路径：
   - 把机器人移出测试群再插一条 → `failed`，`error_msg` 含 `code=230002`，`monitor_lark_chat` 里该群变 `dead`。
   - 凭证改错重启 → 发送记录 `failed`，事件 `notify_status = paused`（事件列表显示「僅記錄」，悬停「Lark 機器人憑證失效，未發送」），研发值班 Telegram 收到「監控告警暫停發送：Lark 機器人憑證失效」。

## 注意

- 测完把第 2 步的测试规则删掉，不然每过一个静默期会在群里重复提醒一次（严重持续时按设计重复提醒）。
- Postman 联调用的假群 `oc_postman_ok` 发不出去（机器人不在群里），会按目标不可用直接 failed 并置为失效。联调期间如果有规则挂在假群上、又刚好触发，日志里会看到对应的 WARN，属正常。
- 一期每条严重告警单独一条消息，不合并；注意级别只记录不发送；恢复不发通知。

## 2026-10-23：通知模板（数据库配置，FY-30 格式，不再重复提醒）

1. 执行 `sql/add_monitor_notify_template_postgresql.sql`（可重复执行；执行过旧版也直接执行）：建表、加列（`monitor_notify_log.template_id / template_version / notify_type`）、建菜单。脚本里不写模板正文，执行时不会再提示输入参数。
2. 部署并重启后端：启动时自动写入两条全站默认模板（ALERT_NEW、ALERT_ESCALATED，FY-30 格式）；还是旧版预置内容的自动换成新格式，有人改过的不动。日志 `[monitor] 通知模板：初始化 / 升级全站默认模板 N 条`。
3. 如果之前执行旧脚本时在客户端里填过参数（模板内容被替换坏了），先删掉再重启：
   `DELETE FROM monitor_notify_template WHERE site_code IS NULL AND update_by = 'system';`
4. 发布前端，「系统管理 · 通知模板」里点「測試發送」看效果。

FY-30 的「查看監控詳情」深链接一期不做，消息里没有这一行。同一事件只在新触发严重（ALERT_NEW）、注意升级为严重（ALERT_ESCALATED）时各发一次，持续严重不再提醒（FY-30 R2）。发送用 Lark 富文本（post），正文里的网址、`[文字](网址)` 都能点开。
