# Permission Center 一期技术设计文档

## 1. 文档信息

| 项目 | 内容 |
|---|---|
| 项目名称 | Permission Center / FiveTech Dashboard |
| 文档版本 | V1.1 当前实现版 |
| 更新日期 | 2026-09-08 |
| 技术基线 | Java 17、Spring Boot 4.1.0、Spring Security、JWT、MyBatis、PostgreSQL 16、Redis、Vue 3、Vite |
| 部署环境 | AWS EC2、RDS PostgreSQL、S3、CloudFront |
| 文档范围 | 登录认证、RBAC、Data Scope、用户组织角色、审计日志、系统通知 |

> 本文以当前 FiveTech Dashboard 代码和 SQL 脚本为准。告警规则引擎、短信电话 Webhook 和复杂指标大盘仍属于后续扩展能力，不应视为当前已经完整上线的功能。

## 2. 当前已实现功能

- 账号密码登录。
- Google Authenticator TOTP 六位验证码登录。
- 管理员创建用户并维护 TOTP 密钥状态。
- 首次绑定接口返回 `otpauthUrl`，前端渲染二维码。
- 用户、组织、角色和菜单权限管理。
- RBAC 菜单、页面和按钮权限控制。
- Data Scope 组织/部门数据范围控制。
- `user_type` 超级管理员识别，不使用固定用户 ID 判断。
- Redis Token 存储、退出登录和 Token 失效。
- 密码错误次数限制和账号锁定。
- 防重复提交拦截。
- 登录日志和操作日志。
- 邮件、Telegram、WhatsApp、Lark 通知接口。
- 用户通知联系人 JSONB 配置。
- 简体中文、繁体中文和英文菜单数据。

## 3. 总体架构

| 层级 | 组件 | 主要职责 | 连接关系 |
|---|---|---|---|
| 访问层 | 浏览器 | 访问 Dashboard 页面和 API | HTTPS → CloudFront |
| 静态资源层 | CloudFront + S3 | 分发 Vue 3 前端 `dist` 文件 | CloudFront → S3 |
| 应用层 | EC2 + FiveTech Spring Boot :8080 | 登录、权限、用户、日志、通知和监控 API | CloudFront → EC2 |
| 业务数据层 | RDS PostgreSQL | 保存用户、组织、角色、菜单、日志和通知联系人 | EC2 → RDS |
| 缓存层 | EC2 Redis | 保存 Token、登录限制和防重复提交状态 | EC2 → Redis |
| 外部通知层 | SES SMTP、Telegram、WhatsApp、Lark | 发送邮件和 IM 消息 | EC2 → 外部服务 |

生产 API 使用 `/prod-api` context path，前端通过 CloudFront 行为转发到 EC2 的 8080 端口。PostgreSQL 和 Redis 不应直接暴露到公网。

## 4. 权限模型

系统采用 **RBAC + Data Scope**，权限判断分为两层：

| 权限对象 | 关联表/字段 | 控制结果 |
|---|---|---|
| 用户 | `sys_user` | 当前登录身份和用户类型 |
| 用户角色 | `sys_user_role` | 用户拥有的角色集合 |
| 角色权限 | `sys_role_menu` | 角色可以访问的菜单和按钮 |
| 菜单权限 | `sys_menu.perms` | `resource:action` 权限标识 |
| 数据范围 | `sys_role.data_scope`、`sys_role_dept` | 允许查询的组织、部门或本人数据 |

权限计算顺序：

1. 根据 `sys_user_role` 查询当前用户的有效角色。
2. 根据 `sys_role_menu` 和 `sys_menu.perms` 汇总角色权限。
3. `@PreAuthorize` 校验当前接口是否允许访问。
4. `@DataScope` 根据 `data_scope` 和 `sys_role_dept` 拼接数据范围条件。
5. MyBatis 执行带权限过滤条件的业务 SQL。

### 4.1 系统权限与数据权限的区别

| 类型 | 控制内容 | 主要实现 |
|---|---|---|
| 系统权限 | 能否访问页面、按钮或接口 | `sys_menu.perms`、`sys_role_menu`、`@PreAuthorize` |
| 数据权限 | 接口可以返回哪些组织/部门数据 | `sys_role.data_scope`、`sys_role_dept`、`@DataScope` |
| 超级管理员 | 跳过普通菜单和数据范围限制 | `user_type` 判断 `SysUser.isAdmin()` |

前端菜单隐藏只用于用户体验，不能作为安全边界。后端 Controller 必须使用权限注解进行二次校验。

### 4.2 权限请求链路

| 步骤 | 处理组件 | 处理内容 |
|---|---|---|
| 1 | `JwtAuthenticationTokenFilter` | 读取 Bearer Token，并从 Redis 校验 Token 是否有效 |
| 2 | 登录上下文 | 加载 `LoginUser` 和 `SysUser` |
| 3 | Spring Security | 执行接口上的 `@PreAuthorize` 权限判断 |
| 4 | PermissionService | 超级管理员直接放行，普通用户检查角色权限集合 |
| 5 | `@DataScope` | 普通用户追加组织、部门或本人数据过滤条件 |
| 6 | MyBatis | 执行最终带权限条件的业务 SQL |

### 4.3 菜单权限计算

| 来源 | 处理结果 |
|---|---|
| `sys_user` | 获取当前用户身份 |
| `sys_user_role` | 获取用户关联角色 |
| `sys_role_menu` | 获取角色关联菜单和按钮 |
| `sys_menu.perms` | 汇总 `resource:action` 权限标识 |
| `/getInfo` | 返回当前用户角色和权限集合 |
| `/getRouters` | 返回前端动态路由 |
| `@PreAuthorize` | 对后端接口执行最终权限校验 |

普通用户没有 `sys_role_menu` 关联记录时，不能获得对应菜单权限。新增菜单后必须同步：

1. 在 `sys_menu` 创建菜单或按钮。
2. 设置唯一的 `perms`。
3. 在 `sys_role_menu` 为指定角色授权。
4. 在后端接口增加相同的 `@PreAuthorize` 权限标识。
5. 退出并重新登录，重新获取路由和权限集合。

### 4.4 超级管理员判断

当前超级管理员判断使用 `sys_user.user_type`，不使用 `user_id = 1` 等固定 ID：

| 判断位置 | 超级管理员行为 |
|---|---|
| `SysUser.isAdmin()` | 根据 `user_type` 判断是否为超级管理员 |
| `PermissionService` | 放行系统权限校验 |
| `MenuService` | 返回全部可见菜单 |
| `DataScopeAspect` | 不追加普通用户的数据范围过滤 |

超级管理员账号必须严格控制，不能把超级管理员类型分配给普通运营用户。

### 4.5 Data Scope 实现

当前 `@DataScope` 通过方法参数中的表别名拼接数据范围条件，例如：

```java
@DataScope(deptAlias = "d", userAlias = "u")
public List<SysUser> selectUserList(SysUser user)
```

其中：

- `d` 是 SQL 中 `sys_dept` 的表别名。
- `u` 是 SQL 中 `sys_user` 的表别名。
- `d.dept_id` 用于部门范围过滤。
- `u.user_id` 用于仅本人数据过滤。

Data Scope 可能生成的条件包括：

```sql
-- 全部数据
1 = 1

-- 本部门
d.dept_id = 当前部门ID

-- 本部门及下级部门
d.dept_id IN (当前部门及子部门)

-- 仅本人
u.user_id = 当前用户ID

-- 自定义部门
d.dept_id IN (SELECT dept_id FROM sys_role_dept WHERE role_id = ...)
```

开发新查询接口时，必须确认 SQL 中存在与 `@DataScope` 一致的别名，否则可能出现过滤失效或 SQL 错误。

## 5. TOTP 登录和绑定

### 5.1 字段

| 字段 | 含义 |
|---|---|
| `sys_user.ga_secret` | Google Authenticator Base32 密钥 |
| `sys_user.ga_status` | `0` 未开通、`1` 待绑定、`2` 已绑定 |
| `sys_user.status` | 用户是否允许登录 |
| `sys_user.del_flag` | 逻辑删除标记 |
| `sys_user.pwd_update_date` | 密码更新时间 |

### 5.2 首次绑定流程

| 步骤 | 操作 | 结果 |
|---|---|---|
| 1 | 管理员新增用户并设置默认密码 | 用户具备待绑定条件 |
| 2 | 用户提交账号和密码到 `/ga/bind/start` | 后端生成唯一 `ga_secret` 和 `otpauthUrl` |
| 3 | 前端根据 `otpauthUrl` 生成二维码 | 用户可以扫码绑定验证器 |
| 4 | 用户提交六位验证码到 `/ga/bind/confirm` | 校验成功后 `ga_status = 2` |

如果用户没有管理员分配的绑定条件，不能自行获得权限，应提示联系管理员。

### 5.3 已绑定登录流程

| 步骤 | 成功处理 | 失败处理 |
|---|---|---|
| 账号和密码 | 检查用户状态、密码 Hash 和登录限制 | 记录登录失败并累计错误次数 |
| TOTP 六位验证码 | 校验成功，生成 JWT 并写入 Redis | 记录 TOTP 失败日志 |
| 后续请求 | 携带 Bearer Token 访问接口 | Token 失效或退出后拒绝访问 |

### 5.4 相关接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/login` | 账号、密码和 TOTP 登录 |
| `POST` | `/ga/bind/start` | 获取二维码绑定信息 |
| `POST` | `/ga/bind/confirm` | 校验验证码并完成绑定 |
| `GET` | `/getInfo` | 当前用户、角色和权限 |
| `GET` | `/getRouters` | 当前用户动态路由 |
| `POST` | `/logout` | 退出登录并使 Token 失效 |

`ga_secret`、Token 和验证码不得写入普通日志或返回给无关用户。

## 6. Redis 使用范围

当前 Redis 不是业务数据主存储，主要用于：

- 登录 Token 缓存和主动注销。
- 密码错误次数。
- 账号锁定状态。
- 防重复提交标记。

Redis 故障的影响：

- 新登录可能失败。
- Token 校验可能失败。
- 账号锁定和防重复提交能力受影响。

Redis 不保存用户、角色、菜单和审计日志的唯一数据，核心业务数据仍以 PostgreSQL 为准。

## 7. 用户、组织和角色

### 7.1 用户

`sys_user` 保存：

- 账号、昵称、邮箱、手机号。
- 密码 Hash。
- 组织/部门归属。
- `user_type` 超级管理员类型。
- TOTP 密钥和状态。
- `notification_contacts` 通知联系人 JSONB。
- 启用/停用和逻辑删除状态。

### 7.2 组织

`sys_dept` 保存组织树和部门数据。用户通过 `dept_id` 归属部门，角色通过 `sys_role_dept` 配置自定义数据范围。

### 7.3 角色

`sys_role` 保存角色标识、状态、菜单严格模式和数据范围。普通角色不应默认拥有用户删除、角色管理等高危权限。

## 8. 通知功能

### 8.1 统一接口

| 渠道 | 接口 |
|---|---|
| Email | `POST /notification/email/send` |
| Telegram | `POST /notification/telegram/send` |
| WhatsApp | `POST /notification/whatsapp/send` |
| Lark | `POST /notification/lark/send` |
| 联系人配置 | `GET/PUT /notification/contacts` |

### 8.2 通知联系人

`sys_user.notification_contacts` 使用 JSONB 保存联系人标识，例如：

```json
{
  "telegram": {
    "value": "123456789",
    "identifierType": "chat_id"
  },
  "lark": {
    "value": "oc_xxxxxxxxx",
    "identifierType": "chat_id"
  },
  "whatsapp": {
    "value": "819012345678",
    "identifierType": "phone"
  }
}
```

联系方式只用于消息发送，不参与系统权限判断。

### 8.3 Lark 群聊消息

群聊接收人使用：

```json
{
  "receiveId": "oc_xxxxxxxxx",
  "receiveIdType": "chat_id",
  "message": "FiveTech 测试消息"
}
```

机器人必须已发布、安装到目标 Workspace 并加入群聊。

## 10. 数据库逻辑关系图

### 10.1 权限核心关系

| 主表 | 关联表 | 关联字段 | 关系说明 |
|---|---|---|---|
| `sys_user` | `sys_user_role` | `user_id` | 一个用户可以拥有多个角色 |
| `sys_role` | `sys_user_role` | `role_id` | 一个角色可以关联多个用户 |
| `sys_role` | `sys_role_menu` | `role_id` | 角色拥有多个菜单或按钮权限 |
| `sys_menu` | `sys_role_menu` | `menu_id` | 菜单记录保存页面、按钮和 `perms` |
| `sys_role` | `sys_role_dept` | `role_id` | 角色配置自定义部门数据范围 |
| `sys_dept` | `sys_role_dept` | `dept_id` | 部门记录确定可访问的数据组织 |
| `sys_user` | `sys_dept` | `dept_id` | 用户归属一个组织或部门 |

### 10.2 审计和通知关系

| 数据对象 | 关联对象 | 关系说明 |
|---|---|---|
| `sys_user` | `sys_oper_log` | 记录用户新增、权限配置、通知发送等操作 |
| `sys_user` | `sys_logininfor` | 记录登录成功、失败、退出和锁定 |
| `sys_user` | `notification_contacts` | 在用户记录中保存 Email、Telegram、WhatsApp、Lark 联系人 |
| `notification_contacts` | 通知 API | 根据渠道和联系人标识发送外部消息 |

### 10.3 数据源关系

| 数据源 | 读写模式 | 保存或提供的内容 |
|---|---|---|
| RDS PostgreSQL | 读写 | 用户、权限、菜单、日志和通知联系人 |
| Redis | 读写 | Token、登录限制、防重复提交状态 |
| Doris | 只读 | 指标和币种金额汇总 |

## 11. 关键表说明

| 表 | 作用 |
|---|---|
| `sys_user` | 登录账号、密码 Hash、部门、用户类型、TOTP、通知联系人 |
| `sys_dept` | 组织和部门树 |
| `sys_role` | 角色、状态、Data Scope |
| `sys_menu` | 菜单、页面、按钮、权限标识 |
| `sys_user_role` | 用户和角色多对多关系 |
| `sys_role_menu` | 角色和菜单/按钮多对多关系 |
| `sys_role_dept` | 角色自定义部门数据范围 |
| `sys_oper_log` | 用户操作审计 |
| `sys_logininfor` | 登录成功、失败、退出和锁定审计 |
| `sys_config` | 当前后端仍使用的密码和系统配置 |
| `sys_dict_type` / `sys_dict_data` | 当前后端仍使用的字典数据 |

`sys_config`、`sys_dict_type` 和 `sys_dict_data` 目前仍存在代码依赖，不应直接删除。

## 12. 日志和审计要求

### 12.1 登录日志

覆盖：

- 登录成功。
- 用户不存在或密码错误。
- TOTP 校验失败。
- 用户停用。
- 账号锁定。
- 登出。

### 12.2 操作日志

覆盖：

- 用户新增、修改、删除。
- 组织新增、修改、删除。
- 角色新增、修改、删除。
- 菜单和权限变更。
- 通知发送。
- 管理员重置 TOTP。

密码、TOTP Secret、Token、SMTP 密码和第三方 API 密钥不得写入日志。

## 13. 安全边界和风险控制

- 前端权限控制不能替代后端权限校验。
- 所有外部通知接口必须使用权限注解。
- 超级管理员只通过 `user_type` 判定。
- 普通角色默认不授予用户删除、角色管理等高危权限。
- RDS 5432 和 Redis 6379 使用内网或安全组限制。
- `/druid/` 和 Swagger 不直接暴露公网。
- 生产配置通过 `/etc/fivetech.env` 或 Secrets Manager 注入。
- 生产环境启用 HTTPS 和 CloudFront 缓存失效策略。
- 数据库 SQL 发布前执行备份和事务校验。

## 14. 发布和验证

### 14.1 数据库

新环境执行：

```text
sql/ry_20260819_postgresql.sql
```

已有环境按需执行增量脚本，例如：

```text
sql/add_system_notification_menu_postgresql.sql
sql/add_notification_contacts_postgresql.sql
```

### 14.2 后端

```bash
mvn clean package -DskipTests

aws s3 cp \
  fivetech-admin/target/fivetech-admin.jar \
  s3://dmo-data-app/backend/releases/<VERSION>/fivetech-admin.jar
```

EC2 上：

```bash
sudo cp /opt/fivetech/app/fivetech-admin.jar \
  /opt/fivetech/app/releases/fivetech-admin.jar.previous

sudo aws s3 cp \
  s3://dmo-data-app/backend/releases/<VERSION>/fivetech-admin.jar \
  /tmp/fivetech-admin.jar

sudo install -o root -g root -m 755 \
  /tmp/fivetech-admin.jar \
  /opt/fivetech/app/fivetech-admin.jar

sudo systemctl daemon-reload
sudo systemctl restart fivetech
sudo systemctl status fivetech --no-pager
```

### 14.3 前端

```bash
npm run build:prod

aws s3 sync dist/ s3://dmo-data-app/frontend/dist/ --delete

aws cloudfront create-invalidation \
  --distribution-id E18B5W6JRNNCCW \
  --paths '/*'
```

### 14.4 验证顺序

1. `fivetech.service` 状态为 `active (running)`。
2. 8080 端口正常监听。
3. `/prod-api/login` 登录成功。
4. `/prod-api/getInfo` 返回用户角色和权限。
5. `/prod-api/getRouters` 返回正确菜单。
6. 普通用户无法调用未授权接口。
7. 系统通知按授权正常显示。
8. CloudFront 页面、静态资源和 API 路径正常。

## 15. 回滚

```bash
sudo systemctl stop fivetech

sudo cp \
  /opt/fivetech/app/releases/fivetech-admin.jar.previous \
  /opt/fivetech/app/fivetech-admin.jar

sudo systemctl start fivetech
sudo systemctl status fivetech --no-pager
sudo journalctl -u fivetech -n 100 --no-pager
```

数据库回滚优先使用 RDS 快照或备份恢复，不要直接对生产库执行完整初始化脚本。

## 16. 当前未完整实现的能力

- 告警规则引擎和告警事件持久化。
- 指标趋势图和复杂指标口径管理。
- 短信、电话类触达 Webhook 的完整编排。
- 告警静默、升级、责任人路由和恢复通知。
- 组织架构自动同步。
- 复杂审批和多级权限委派。
- 代码生成器。

这些能力应单独设计数据库、权限标识、审计字段和回滚策略后再开发，不能仅通过复制旧若依模块直接上线。
