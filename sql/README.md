# FiveTech Dashboard 数据库脚本

数据库：PostgreSQL 16+

## 正式上线脚本

正式脚本为 `ry_20260819_postgresql.sql`。它是独立、可重复获取的完整初始化脚本，包含：

- 系统用户、组织、角色、菜单及 RBAC 关联表
- 菜单多语言表 `sys_menu_i18n`
- 用户 Google Authenticator TOTP 字段
- 用户管理、权限管理、操作日志和登录日志所需数据
- 系统运行依赖的 `sys_config`、`sys_dict_type`、`sys_dict_data`
- Permission Center 一期菜单清理
- 初始化数据后的 PostgreSQL identity 序列修正
- 用户登录名、邮箱、手机号及角色名称/标识的并发唯一性索引

该脚本会删除并重建同名业务表，只能用于空库或已确认可以清空的数据库。生产环境已有数据时，不要执行该脚本。

## 新环境初始化

旧表数据已清空时，直接执行下面一个完整脚本即可：

```text
ry_20260819_postgresql.sql
```

该脚本已经包含建表、初始数据、RBAC 菜单、TOTP 字段、权限概览菜单和一期菜单清理逻辑。脚本会重建表，不能在需要保留数据的环境重复执行。

执行示例：

```bash
psql "postgresql://用户名:密码@主机:5432/数据库名" \
  -v ON_ERROR_STOP=1 \
  --single-transaction \
-f ry_20260819_postgresql.sql
```

针对 AWS RDS，推荐直接上传并执行上述正式脚本。不要只上传 `aws_rds_deploy_postgresql.sql`，因为它是依赖相对路径 `\ir` 的旧包装脚本；单独下载到 `/tmp` 执行时会找不到主脚本。

执行示例：

```bash
aws s3 cp s3://dmo-data-app/sql/ry_20260819_postgresql.sql \
  /tmp/ry_20260819_postgresql.sql

psql "host=<RDS_ENDPOINT> port=5432 dbname=appdb user=appadmin sslmode=require" \
  -W -v ON_ERROR_STOP=1 --single-transaction \
  -f /tmp/ry_20260819_postgresql.sql
```

## 已有环境迁移

如果数据库中已有需要保留的数据，不要执行完整初始化脚本。此时只执行对应的增量脚本：

增量脚本请根据实际数据库版本单独评审后执行；不要将增量脚本与正式初始化脚本混合执行。

该脚本会递归删除非一期菜单及其子菜单授权，并删除自助注册、图形验证码配置；不会删除用户、组织、角色、权限、操作日志和登录日志核心表。

## 一期保留表

| 表 | 用途 |
|---|---|
| `sys_user` | 登录账号、密码哈希、组织归属、TOTP 状态和通知联系人 JSONB。 |
| `sys_dept` | 组织/部门树及用户数据范围基础信息。 |
| `sys_role` | RBAC 角色和 Data Scope 数据范围。 |
| `sys_menu` | 页面、目录和按钮权限定义，`perms` 与后端权限注解对应。 |
| `sys_menu_i18n` | 菜单的简体中文、繁体中文和英文名称。 |
| `sys_user_role` | 用户与角色的多对多关联。 |
| `sys_role_menu` | 角色与菜单/操作权限的多对多关联。 |
| `sys_role_dept` | 按部门配置角色数据范围。 |
| `sys_post`、`sys_user_post` | 岗位字典及用户岗位关联，岗位本身不替代权限。 |
| `sys_oper_log` | 新增、修改、删除、权限配置等操作审计。 |
| `sys_logininfor` | 登录成功、失败、退出和异常登录审计。 |
| `sys_config` | 登录黑名单、初始密码和密码策略等运行参数。 |
| `sys_dict_type`、`sys_dict_data` | 前端状态、性别、显示状态等字典下拉和标签展示。 |
| `sys_job`、`sys_job_log` | 兼容现有后端定时任务模块；未开放菜单不等于代码已解除依赖。 |

`sys_config`、`sys_dict_type`、`sys_dict_data` 当前仍是后端运行依赖，不建议删除。`sys_job`、`sys_job_log` 可以作为后续专项清理，但必须先移除对应 Java 模块、Mapper、启动配置和任务调用，再单独执行迁移。

## TOTP 账号初始化

脚本不会写入固定的管理员 TOTP 密钥。执行完成后，请使用安全的 Base32 密钥生成方式为管理员注入密钥：

```sql
UPDATE sys_user
SET ga_secret = '替换为安全生成的Base32密钥',
    ga_status = 1
WHERE user_name = 'admin';
```

之后管理员首次登录即可通过登录页获取二维码，绑定 Google Authenticator 后完成 TOTP 校验。不要把真实密钥提交到代码仓库。

不要把真实密钥、备份码或生产数据库密码写入脚本并提交到代码仓库。

## 清理原则

一期采用“先移除菜单入口、再解耦代码、最后删除表”的顺序。不要直接删除仍被用户查询、字典缓存或登录配置引用的表；确认代码和数据迁移完成后，再单独提交生产清理脚本。
