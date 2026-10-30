# 监控管理 · 一期 · 数据库脚本（2026-10-27）

| 文件 | 作用 |
| --- | --- |
| `01_release_monitor_p1_postgresql.sql` | 上线：本次监控管理新增和修改的全部 DDL、数据、菜单（23 个脚本按顺序原样拼接 + 前置检查 + 回滚用备份 + 汇总自检）。可重复执行 |
| `02_rollback_monitor_p1_postgresql.sql` | 回滚：撤销 01 的全部改动，回到执行 01 之前的状态。需要显式确认，一个事务 |

## 01 包含的 17 段（顺序不能换）

| 顺序 | 来源脚本（`sql/` 下，内容未改） | 作用 |
| --- | --- | --- |
| ① | alter_sys_role_site | `sys_role.site_code`（角色带站点） |
| ② | alter_metric_card_monitor | 指标监控属性（20 个可监控、7 个门槛条）；**删除** 旧告警字段 `alert_*` / `tolerance_*` |
| ③ | monitor_schema | 7 张 monitor 表 + SITE-IN 站点设置 |
| ④ | alter_monitor_engine | 判定引擎列 |
| ⑤ | alter_monitor_site_package_network | 站点所属包网 |
| ⑥ | alter_monitor_event_mute | 通知状态 muted |
| ⑦ | alter_monitor_event_notify_paused | 通知状态 paused |
| ⑧ | add_monitor_notify_template | 通知模板表 + 菜单「通知模板」 |
| ⑨ | add_monitor_menu | 监控管理菜单 160–167 |
| ⑩ | add_system_site_menu | 菜单「站点管理」 |
| ⑪ | alter_metric_card_chart_line | 卡片图形 BAR → LINE |
| ⑫ | alter_metric_card_dictionary | 指标口径与 UDS 取数字段（新增 4 列） |
| ⑬ | add_metric_manage_menu | 菜单「指标管理」 |
| ⑭ | add_monitor_rule_test_permission | 按钮「手动触发测试告警」 |
| ⑮ | alter_metric_card_name_traditional | 已接入指标名称改为繁体、以库为准（后台「指标管理」可改名称 / 分组 / 格式；备份表 dashboard_metric_card_name_bak_20261027） |
| ⑯ | alter_monitor_site_setting_site | 站点以库为准：monitor_site_setting 兼作站点表（site_name 不为空的行是站点；后端首次启动从 yml 导入）+ 按钮「新增站点」；后台可改站点名称 / 编码（级联更新）/ UDS 站点标识 / 时区 |
| ⑰ | alter_metric_card_caliber_traditional | 运营总览口径以库为准：⑫ 写入的简体口径换成页面显示的繁体口径（备份表 dashboard_metric_card_remark_bak_20261028） |
| ⑱ | alter_monitor_site_setting_currency | 站点币种、币种符号（monitor_site_setting.currency / currency_symbol，为空用 dashboard.currency / 按币种推导），后台站点管理可改；JITO 的 UDS 站点标识 SITE-B → JITO（只改还是 SITE-B 的行） |
| ⑲ | alter_monitor_site_setting_zone_currency | 站点时区、币种以站点表为准：空值补成 Asia/Kolkata / INR（生产 DASHBOARD_TIMEZONE / CURRENCY 不同时先改值）；dashboard 各接口的 timezone / currency 和时间计算按当前站点取，默认站点时区也可在站点管理改 |
| ⑳ | alter_metric_card_avg_time_divide | 平均到账时间 / 平均出款时间改为后端计算：sum(耗时合计) ÷ nullif(sum(单数), 0)（dep_time_sum / dep_time_count、wd_time_sum / wd_time_count），指标字典不再登记 dT_seconds / wT_seconds |
| ㉑ | alter_monitor_sample_bet_users | 平均杀率 / 赠金比 / 流水倍数的最小样本量改为投注人数 active（MON-34），指标配置和已建规则一起改 |
| ㉒ | alter_monitor_event_stop_reason | 事件停止原因 stop_reason（pause / delete / update，MON-28 / MON-29）；新 JAR 启动前必须有 |
| ㉓ | fix_monitor_event_level_peak | 到过严重的事件等级改回严重（MON-01）；新 JAR 上线后再执行一次 |

01 开头的「0.5 回滚用备份」只在**第一次**执行时记录：`dashboard_metric_card` 整表（含旧告警字段的值）、约束、索引、默认值、注释，以及 `sys_role.site_code` 原本是否存在。02 依赖这份记录。

## 执行

```bash
# 上线
psql -X -v ON_ERROR_STOP=1 -d dashboard_db -f 01_release_monitor_p1_postgresql.sql

# 回滚（会删除 8 张 monitor_* 表及其数据、全部相关菜单；生产先 pg_dump 再执行）
PGOPTIONS='-c monitor.rollback_confirm=yes' psql -X -v ON_ERROR_STOP=1 -d dashboard_db -f 02_rollback_monitor_p1_postgresql.sql
```

- 01 各段自带 BEGIN / COMMIT，不要加 `--single-transaction`；02 整体一个事务，失败什么都不改。
- 不加 `PGOPTIONS` 执行 02 会直接报错「未确认」，什么都不改。
- 回滚后要用旧 JAR；只回滚 JAR、保留数据时不要用 02，用 `sql/rollback_metric_card_monitor_postgresql.sql`。

## 本地验证建议

要验证「回到上线前」，起点要像生产（10-16 版本：有旧告警字段、有 BAR、没有 `site_code`、没有 monitor 表），不要用已经跑过监控管理的开发库：

```bash
createdb monitor_p1_check
psql -d monitor_p1_check -f sql/ry_20260819_postgresql.sql                    # 系统表
psql -d monitor_p1_check -c 'ALTER TABLE sys_role DROP COLUMN IF EXISTS site_code'
git show 510ea0c:sql/dashboard_metric_card_postgresql.sql | psql -d monitor_p1_check   # 10-16 生产版指标表
git show 510ea0c:sql/dashboard_page_metric_postgresql.sql | psql -d monitor_p1_check
pg_dump -s -d monitor_p1_check > before.sql                                  # 记下起点

cd sql/monitor-p1
psql -X -v ON_ERROR_STOP=1 -d monitor_p1_check -f 01_release_monitor_p1_postgresql.sql   # 执行两遍都应无 ERROR
PGOPTIONS='-c monitor.rollback_confirm=yes' psql -X -v ON_ERROR_STOP=1 -d monitor_p1_check -f 02_rollback_monitor_p1_postgresql.sql
pg_dump -s -d monitor_p1_check > after.sql && diff before.sql after.sql
```

已在 PostgreSQL 16 上按上面的方式验证（01 → 01 → 02 → 01 → 02）：

- 回滚后表、列（类型 / 默认值 / 非空）、索引、注释、`dashboard_metric_card` 全部数据、菜单与授权都和执行前一致；
- 唯一差异是被加回的 `ck_alert_level`、`ck_chart_type` 两个约束的**文本写法**（PostgreSQL 重新解析后的格式），含义相同；`pg_dump` 里旧告警列会排在表的最后；
- 回滚后再执行 01，结果与第一次上线完全一致。

在已经有 monitor 表的开发库上执行：02 照样会删除 monitor 表和菜单（回滚的是整个监控管理一期），`dashboard_metric_card` 恢复成执行 01 之前的样子。
