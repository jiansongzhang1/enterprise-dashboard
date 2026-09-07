# FiveTech Dashboard Backend

FiveTech Dashboard 后端服务，为权限管理、登录认证、系统通知、审计日志和数据监控提供 API 能力。

## 1. 项目概览

### 1.1 核心能力

- 账号密码登录
- Google Authenticator TOTP 六位验证码
- 用户首次绑定 TOTP 二维码
- 用户、组织、角色和权限管理
- RBAC 菜单/按钮权限控制
- Data Scope 数据范围控制
- 超级管理员识别
- 登录日志和操作日志
- Redis Token 缓存
- 密码错误次数限制和账号锁定
- 防重复提交
- 邮件通知
- Telegram 通知
- WhatsApp 通知
- Lark 通知
- 通知联系人维护
- Doris 币种金额汇总查询
- 简体中文、繁体中文、英文菜单国际化

### 1.2 当前部署架构

```text
浏览器
  |
  v
CloudFront
  |
  +--> S3：前端 dist 静态资源
  |
  +--> EC2：FiveTech Spring Boot API
           |
           +--> RDS PostgreSQL：系统业务库
           +--> EC2 Redis：Token、登录限制和防重复提交
           +--> Doris：只读数据监控查询
           +--> SES SMTP：邮件发送
           +--> Telegram / WhatsApp / Lark：外部通知服务
```

### 1.3 生产环境约定

| 项目 | 配置 |
|---|---|
| 后端服务 | `fivetech.service` |
| 后端目录 | `/opt/fivetech/app/` |
| 后端 JAR | `/opt/fivetech/app/fivetech-admin.jar` |
| 后端端口 | `8080` |
| 生产 API 前缀 | `/prod-api` |
| 数据库 | AWS RDS PostgreSQL |
| Redis | 后端 EC2 本机 Redis |
| 前端资源 | S3 `s3://dmo-data-app/frontend/dist/` |
| CloudFront | `E18B5W6JRNNCCW` |

## 2. 技术栈

- Java 17
- Spring Boot 4.1.0
- Spring Security
- JWT
- MyBatis
- PostgreSQL 16+
- Redis / Lettuce
- Druid DataSource
- MySQL Connector/J，用于连接 Doris 的 MySQL 协议
- Maven 3.9+
- Node.js 及 npm，仅用于前端项目构建

## 3. 代码结构

```text
dashboard-backend/
├── fivetech-admin/       # 应用启动模块、Controller、配置和资源文件
├── fivetech-framework/   # 安全、Token、通知、Redis、拦截器和基础框架
├── fivetech-system/      # 用户、角色、组织、菜单、日志等业务模块
├── fivetech-common/      # 通用常量、工具类、异常和基础对象
├── sql/                  # PostgreSQL 初始化和增量迁移脚本
├── doc/                  # PRD 和技术设计文档
├── concurrency_add_test.sh
├── pom.xml
└── README.md
```

## 4. 环境准备

### 4.1 检查 Java、Maven 和 Redis

```bash
java -version
mvn -version
redis-cli ping
```

Redis 正常时返回：

```text
PONG
```

### 4.2 必需服务

本地启动至少需要：

- PostgreSQL
- Redis

Doris 只在需要测试“数据监控”页面时启用。本地无法访问 VPC 内 Doris 时，应保持：

```bash
DORIS_ENABLED=false
```

## 5. 配置说明

主配置文件：

```text
fivetech-admin/src/main/resources/application.yml
```

数据库配置文件：

```text
fivetech-admin/src/main/resources/application-druid.yml
```

生产环境建议通过环境变量或 `/etc/fivetech.env` 覆盖配置，不要把生产密码提交到代码仓库。

### 5.1 PostgreSQL

```bash
export DB_URL='jdbc:postgresql://localhost:5432/appdb?currentSchema=public'
export DB_USERNAME='appadmin'
export DB_PASSWORD='<POSTGRES_PASSWORD>'
```

AWS RDS 示例：

```bash
export DB_URL='jdbc:postgresql://<RDS_ENDPOINT>:5432/appdb?currentSchema=public&sslmode=require'
export DB_USERNAME='appadmin'
export DB_PASSWORD='<RDS_PASSWORD>'
```

### 5.2 Redis

```bash
export REDIS_HOST='127.0.0.1'
export REDIS_PORT='6379'
export REDIS_PASSWORD=''
export REDIS_DATABASE='0'
export REDIS_SSL='false'
```

Redis 用于：

- 登录 Token 存储
- Token 失效和退出登录
- 密码错误次数和账号锁定
- 防重复提交

### 5.3 JWT

```bash
export TOKEN_SECRET='<至少32位随机密钥>'
```

生产环境必须配置随机密钥。不要使用空值、示例值或公开到 Git 的固定密钥。

### 5.4 Druid 控制台

```bash
export DRUID_LOGIN_USERNAME='fivetech'
export DRUID_LOGIN_PASSWORD='<DRUID_PASSWORD>'
```

Druid 控制台路径：

```text
/druid/
```

生产环境不建议将 Druid 控制台暴露到公网，应通过安全组、内网或 Session Manager 访问。

### 5.5 Google Authenticator TOTP

TOTP 密钥保存于 `sys_user.ga_secret`，状态保存于 `sys_user.ga_status`。

系统流程：

1. 管理员创建用户并设置默认密码。
2. 管理员为用户生成或分配 `ga_secret`。
3. 用户通过绑定接口获取 `otpauthUrl`。
4. 前端根据 `otpauthUrl` 生成二维码。
5. 用户使用 Google Authenticator 扫码。
6. 用户输入六位验证码完成绑定。
7. 后续登录必须提交账号、密码和 TOTP 验证码。

如果用户没有 `ga_secret`，系统应提示联系管理员，不允许自行生成管理员权限。

### 5.6 邮件通知

生产环境使用 Amazon SES SMTP：

```bash
export MAIL_ENABLED=true
export MAIL_HOST='email-smtp.ap-southeast-1.amazonaws.com'
export MAIL_PORT=587
export MAIL_USERNAME='<SES_SMTP_USERNAME>'
export MAIL_PASSWORD='<SES_SMTP_PASSWORD>'
export MAIL_FROM='jackson@fivetech.co.jp'
export MAIL_PROTOCOL='smtp'
export MAIL_SMTP_AUTH=true
export MAIL_SMTP_STARTTLS=true
```

使用前需要确认：

- SES 身份已验证
- 发件人地址或域名已验证
- SES 账号已退出 Sandbox，或收件人地址也已验证
- SMTP 用户名和 SMTP 密码不是 AWS Access Key
- 发件地址与 SES 已验证身份一致

### 5.7 Telegram

```bash
export TELEGRAM_ENABLED=true
export TELEGRAM_BOT_TOKEN='<TELEGRAM_BOT_TOKEN>'
export TELEGRAM_TIMEOUT_SECONDS=10
```

Telegram 接收人必须先与机器人互动，接口使用 `chatId` 发送消息。

### 5.8 WhatsApp

```bash
export WHATSAPP_ENABLED=true
export WHATSAPP_ACCESS_TOKEN='<META_ACCESS_TOKEN>'
export WHATSAPP_PHONE_NUMBER_ID='<PHONE_NUMBER_ID>'
export WHATSAPP_GRAPH_API_VERSION='v23.0'
export WHATSAPP_TIMEOUT_SECONDS=10
```

收件人使用 E.164 国际号码格式，例如：

```text
819012345678
```

### 5.9 Lark

```bash
export LARK_ENABLED=true
export LARK_APP_ID='cli_xxxxxxxxx'
export LARK_APP_SECRET='<LARK_APP_SECRET>'
export LARK_API_BASE_URL='https://open.larksuite.com'
export LARK_TIMEOUT_SECONDS=10
```

Lark 接收人支持：

- `open_id`
- `chat_id`

机器人和接收人必须属于同一个 Lark Workspace，并且应用已经发布和安装。

### 5.10 Doris

```bash
export DORIS_ENABLED=true
export DORIS_URL='jdbc:mysql://<DORIS_HOST>:9030/dmo_dwh_dwd_2b2c_rt'
export DORIS_USERNAME='doris_reader'
export DORIS_PASSWORD='<DORIS_PASSWORD>'
```

Doris 是独立的只读数据源，不替换 PostgreSQL 主库。后端 EC2 必须能访问 Doris 的 TCP `9030` 端口。

## 6. 数据库初始化

### 6.1 新环境

完整初始化脚本：

```text
sql/ry_20260819_postgresql.sql
```

该脚本包含：

- 用户、组织、角色和菜单表
- RBAC 关联表
- 操作日志和登录日志表
- TOTP 字段
- 通知联系人 JSONB 字段
- 菜单多语言数据
- 系统通知菜单
- Doris 数据监控菜单
- 初始角色和权限数据

该脚本会删除并重建业务表，只能用于空库或已确认可以清空的数据库。

执行：

```bash
psql \
  "host=<RDS_ENDPOINT> port=5432 dbname=appdb user=appadmin sslmode=require" \
  -W \
  -v ON_ERROR_STOP=1 \
  --single-transaction \
  -f sql/ry_20260819_postgresql.sql
```

### 6.2 已有环境

已有数据时不要执行完整初始化脚本，应按变更内容执行增量脚本：

```text
sql/add_doris_currency_monitor_postgresql.sql
sql/add_system_notification_menu_postgresql.sql
sql/add_email_permission_postgresql.sql
sql/add_telegram_permission_postgresql.sql
sql/add_whatsapp_permission_postgresql.sql
sql/add_lark_permission_postgresql.sql
sql/add_notification_contacts_postgresql.sql
sql/add_multilingual_menu_postgresql.sql
```

执行前先备份数据库，并确认脚本是否与当前数据库版本匹配。

### 6.3 主要表说明

| 表 | 用途 |
|---|---|
| `sys_user` | 登录账号、密码哈希、组织归属、TOTP 状态和通知联系人。 |
| `sys_dept` | 组织/部门树。 |
| `sys_role` | 角色、状态和数据范围。 |
| `sys_menu` | 目录、页面和按钮权限。 |
| `sys_menu_i18n` | 菜单的中简、中繁和英文名称。 |
| `sys_user_role` | 用户与角色关联。 |
| `sys_role_menu` | 角色与菜单/操作权限关联。 |
| `sys_role_dept` | 角色与部门数据范围关联。 |
| `sys_oper_log` | 用户新增、修改、删除和权限操作审计。 |
| `sys_logininfor` | 登录成功、失败、退出和锁定日志。 |
| `sys_config` | 密码策略、初始密码等运行配置。 |
| `sys_dict_type` / `sys_dict_data` | 页面状态、性别等字典数据。 |

`sys_config` 和字典表目前仍被后端代码使用，不应直接删除。

## 7. 权限模型

系统采用 RBAC + Data Scope：

```text
User -> UserRole -> Role -> RolePermission -> Permission
                         |
                         -> DataScope
```

### 7.1 系统权限

由 `sys_menu.perms` 定义，并通过后端注解校验，例如：

```java
@PreAuthorize("@ss.hasPermi('monitor:doris:query')")
```

### 7.2 数据权限

支持按组织、部门和用户范围控制查询结果。常见范围包括：

- 全部数据
- 本组织
- 本组织及下级组织
- 仅本人
- 自定义部门

### 7.3 超级管理员

超级管理员通过用户类型字段判断，不应通过固定用户 ID 判断。普通用户不应默认拥有用户新增、删除、角色管理等高危权限。

### 7.4 权限变更验证

修改角色或菜单权限后，需要：

1. 确认 `sys_role_menu` 关联数据。
2. 退出当前账号。
3. 重新登录获取新 Token 和路由。
4. 使用普通用户验证接口权限。

## 8. 主要接口

生产环境接口统一增加 `/prod-api` 前缀。

### 8.1 登录和认证

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/login` | 账号、密码和 TOTP 登录。 |
| `POST` | `/ga/bind/start` | 获取 TOTP 绑定信息和 `otpauthUrl`。 |
| `POST` | `/ga/bind/confirm` | 校验六位验证码并完成绑定。 |
| `GET` | `/getInfo` | 当前用户、角色和权限。 |
| `GET` | `/getRouters` | 当前用户可见菜单。 |
| `POST` | `/logout` | 退出登录并清理 Token。 |

### 8.2 系统管理

| 模块 | 基础路径 |
|---|---|
| 用户 | `/system/user` |
| 组织/部门 | `/system/dept` |
| 角色 | `/system/role` |
| 权限管理 | `/system/menu` |
| 岗位 | `/system/post` |
| 个人中心 | `/system/user/profile` |

### 8.3 审计和监控

| 模块 | 基础路径 |
|---|---|
| 操作日志 | `/monitor/operlog` |
| 登录日志 | `/monitor/logininfor` |
| Doris 数据监控 | `/monitor/doris` |

Doris 汇总接口：

```text
GET /monitor/doris/source-currency-summary
```

查询逻辑：

```sql
SELECT source_currency, SUM(source_amount) AS source_amount
FROM dwd_main_pax_admin_t_cache_rate_log
GROUP BY source_currency
ORDER BY source_currency;
```

### 8.4 系统通知

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/notification/contacts` | 查询当前用户通知联系人。 |
| `PUT` | `/notification/contacts/{userId}` | 更新通知联系人。 |
| `POST` | `/notification/email/send` | 发送邮件。 |
| `POST` | `/notification/telegram/send` | 发送 Telegram 消息。 |
| `POST` | `/notification/whatsapp/send` | 发送 WhatsApp 消息。 |
| `POST` | `/notification/lark/send` | 发送 Lark 消息。 |

## 9. 接口测试示例

先登录获取 Token：

```bash
TOKEN=$(curl -s -X POST 'http://localhost:8080/login' \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<PASSWORD>","code":"<TOTP_CODE>"}' \
  | jq -r '.token')
```

查询当前用户权限：

```bash
curl -H "Authorization: Bearer ${TOKEN}" \
  'http://localhost:8080/getInfo'
```

查询 Doris 数据：

```bash
curl -H "Authorization: Bearer ${TOKEN}" \
  'http://localhost:8080/monitor/doris/source-currency-summary'
```

发送邮件：

```bash
curl -X POST 'http://localhost:8080/notification/email/send' \
  -H "Authorization: Bearer ${TOKEN}" \
  -H 'Content-Type: application/json' \
  -d '{
    "to": ["recipient@example.com"],
    "subject": "FiveTech 测试邮件",
    "content": "<h2>邮件发送测试</h2><p>测试成功。</p>",
    "html": true
  }'
```

发送 Telegram：

```bash
curl -X POST 'http://localhost:8080/notification/telegram/send' \
  -H "Authorization: Bearer ${TOKEN}" \
  -H 'Content-Type: application/json' \
  -d '{
    "chatId": "<CHAT_ID>",
    "message": "FiveTech Telegram 测试消息"
  }'
```

发送 WhatsApp：

```bash
curl -X POST 'http://localhost:8080/notification/whatsapp/send' \
  -H "Authorization: Bearer ${TOKEN}" \
  -H 'Content-Type: application/json' \
  -d '{
    "to": "819012345678",
    "message": "FiveTech WhatsApp 测试消息"
  }'
```

发送 Lark：

```bash
curl -X POST 'http://localhost:8080/notification/lark/send' \
  -H "Authorization: Bearer ${TOKEN}" \
  -H 'Content-Type: application/json' \
  -d '{
    "receiveId": "<OPEN_ID_OR_CHAT_ID>",
    "receiveIdType": "open_id",
    "message": "FiveTech Lark 测试消息"
  }'
```

## 10. 本地构建和启动

### 10.1 构建

```bash
cd /Users/jackson/Documents/dashboard/dashboard-backend
mvn clean package -DskipTests
```

生成文件：

```text
fivetech-admin/target/fivetech-admin.jar
```

### 10.2 启动

```bash
java -jar fivetech-admin/target/fivetech-admin.jar
```

默认地址：

```text
http://localhost:8080
```

生产环境 API 前缀：

```bash
export SERVER_SERVLET_CONTEXT_PATH=/prod-api
```

本地如果不需要前缀，不配置该变量即可。

### 10.3 本地日志

```text
fivetech-admin/logs/
```

也可以直接查看控制台输出。

## 11. AWS EC2 部署

### 11.1 构建并上传 JAR

本地执行：

```bash
cd /Users/jackson/Documents/dashboard/dashboard-backend
mvn clean package -DskipTests

aws s3 cp \
  fivetech-admin/target/fivetech-admin.jar \
  s3://dmo-data-app/backend/releases/<VERSION>/fivetech-admin.jar \
  --region ap-southeast-1
```

### 11.2 连接 EC2

推荐使用 AWS Systems Manager Session Manager：

```bash
aws ssm start-session \
  --region ap-southeast-1 \
  --target <EC2_INSTANCE_ID>
```

### 11.3 配置环境变量

```bash
sudo vi /etc/fivetech.env
```

至少配置：

```bash
DB_URL=jdbc:postgresql://<RDS_ENDPOINT>:5432/appdb?currentSchema=public&sslmode=require
DB_USERNAME=appadmin
DB_PASSWORD=<RDS_PASSWORD>
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
TOKEN_SECRET=<TOKEN_SECRET>
SERVER_SERVLET_CONTEXT_PATH=/prod-api
DORIS_ENABLED=true
DORIS_URL=jdbc:mysql://<DORIS_HOST>:9030/dmo_dwh_dwd_2b2c_rt
DORIS_USERNAME=doris_reader
DORIS_PASSWORD=<DORIS_PASSWORD>
```

确认配置权限：

```bash
sudo chown root:root /etc/fivetech.env
sudo chmod 600 /etc/fivetech.env
```

### 11.4 下载并替换 JAR

```bash
aws s3 cp \
  s3://dmo-data-app/backend/releases/<VERSION>/fivetech-admin.jar \
  /tmp/fivetech-admin.jar

sudo mkdir -p /opt/fivetech/app/releases
sudo cp \
  /opt/fivetech/app/fivetech-admin.jar \
  /opt/fivetech/app/releases/fivetech-admin.jar.previous

sudo install -o root -g root -m 755 \
  /tmp/fivetech-admin.jar \
  /opt/fivetech/app/fivetech-admin.jar
```

### 11.5 systemd 服务

服务文件：

```text
/etc/systemd/system/fivetech.service
```

查看真实启动命令：

```bash
sudo systemctl cat fivetech
```

典型配置：

```ini
[Unit]
Description=FiveTech Dashboard Backend
After=network.target

[Service]
User=root
EnvironmentFile=/etc/fivetech.env
ExecStart=/usr/bin/java -Xms512m -Xmx1024m -jar /opt/fivetech/app/fivetech-admin.jar
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

启动或重启：

```bash
sudo systemctl daemon-reload
sudo systemctl enable fivetech
sudo systemctl restart fivetech
sudo systemctl status fivetech --no-pager
```

查看日志：

```bash
sudo journalctl -u fivetech -n 200 --no-pager
sudo journalctl -u fivetech -f
```

检查端口：

```bash
sudo ss -lntp | grep 8080
```

## 12. 前端和 CloudFront 发布

前端资源由独立的 `dashboard-frontend` 项目构建，不上传到 EC2。

```bash
npm run build:prod

aws s3 sync dist/ \
  s3://dmo-data-app/frontend/dist/ \
  --delete \
  --region ap-southeast-1

aws cloudfront create-invalidation \
  --distribution-id E18B5W6JRNNCCW \
  --paths '/*'
```

发布后检查：

1. CloudFront 首页可以加载。
2. 浏览器 Network 中静态资源返回 `200`。
3. API 请求使用 `/prod-api` 前缀。
4. 登录、权限路由和通知页面正常。
5. 无痕窗口验证，避免旧缓存影响判断。

## 13. 推荐发版顺序

### 数据库变更、后端和前端一起发布

1. 备份或确认 RDS 自动备份可用。
2. 执行数据库结构和初始化数据脚本。
3. 查询确认表、字段、菜单和权限存在。
4. 上传并替换后端 JAR。
5. 重启 `fivetech.service`。
6. 查看 systemd 日志和端口状态。
7. 调用 `/login`、`/getInfo` 和业务接口验证后端。
8. 构建前端并同步到 S3。
9. 创建 CloudFront invalidation。
10. 使用浏览器验证完整业务流程。

### 仅后端发布

1. 备份旧 JAR。
2. 上传新 JAR 到 S3。
3. EC2 下载并替换 JAR。
4. 重启 systemd 服务。
5. 验证后端接口。

### 仅前端发布

1. 构建 `dist`。
2. 同步到 S3。
3. 刷新 CloudFront。
4. 使用无痕窗口验证页面。

## 14. 回滚

### 14.1 后端回滚

```bash
sudo systemctl stop fivetech

sudo cp \
  /opt/fivetech/app/releases/fivetech-admin.jar.previous \
  /opt/fivetech/app/fivetech-admin.jar

sudo systemctl start fivetech
sudo systemctl status fivetech --no-pager
sudo journalctl -u fivetech -n 100 --no-pager
```

### 14.2 前端回滚

使用 S3 中上一版本的前端资源重新同步到 `frontend/dist/`，然后刷新 CloudFront：

```bash
aws cloudfront create-invalidation \
  --distribution-id E18B5W6JRNNCCW \
  --paths '/*'
```

### 14.3 数据库回滚

数据库变更发布前必须评估回滚方案。优先使用：

- RDS 自动备份恢复
- RDS 手工快照
- 经过审核的反向迁移 SQL

不要直接对生产库执行 `DROP TABLE` 或完整初始化脚本。

## 15. 故障排查

### 15.1 服务启动失败

```bash
sudo systemctl status fivetech --no-pager
sudo journalctl -u fivetech -n 200 --no-pager
sudo ls -l /etc/fivetech.env
sudo systemctl cat fivetech
```

重点检查：

- JAR 是否存在且可读
- Java 版本是否为 17
- `/etc/fivetech.env` 是否存在
- PostgreSQL、Redis 是否可连接
- `TOKEN_SECRET` 是否配置
- 环境变量名称是否拼写正确
- 8080 端口是否被其他进程占用

### 15.2 401 认证失败

检查：

- `Authorization: Bearer <TOKEN>` 是否正确
- Token 是否过期
- Redis 是否正常
- CloudFront 是否正确转发 `/prod-api/*`
- 后端是否配置 `SERVER_SERVLET_CONTEXT_PATH=/prod-api`
- 用户是否拥有对应 `sys_menu` 权限

### 15.3 Doris 查询失败

```bash
nc -vz <DORIS_HOST> 9030
```

重点检查：

- `DORIS_ENABLED=true`
- Doris 用户名和密码
- EC2 到 Doris 的安全组授权
- Doris 表名和字段名
- 后端日志中的 JDBC 连接错误

### 15.4 邮件发送失败

重点检查：

- `MAIL_ENABLED=true`
- SES 发件人身份已验证
- SES SMTP 用户名和密码正确
- SMTP 端口为 `587` 且启用 STARTTLS
- SES 账号是否仍处于 Sandbox
- 收件人邮箱是否被 SES 允许

### 15.5 菜单不显示

```sql
SELECT menu_id, menu_name, perms, component, status, visible
FROM sys_menu
WHERE menu_id = 124
   OR perms = 'monitor:doris:query';
```

然后确认：

1. `sys_role_menu` 中存在对应角色授权。
2. 当前用户拥有该角色。
3. 退出并重新登录，重新获取路由和 Token。
4. CloudFront 已完成刷新。
5. 前端组件路径与 `component` 值一致。

## 16. 安全规范

- 不在 Git、SQL、README、日志和聊天记录中保存生产密码。
- 已暴露的 SMTP、Telegram、WhatsApp、Lark、数据库和 JWT 密钥应及时轮换。
- RDS、Doris 和 Redis 优先使用内网访问。
- Security Group 只开放必要端口和来源安全组。
- Doris 查询菜单默认仅管理员可见。
- 普通角色遵循最小权限原则，不默认授予用户删除、角色管理等高危权限。
- `/druid/`、Swagger 和 Actuator 等运维入口不应直接暴露公网。
- 生产环境必须启用 HTTPS，并正确配置 CloudFront 到后端的行为转发。
- 所有管理员操作应保留操作日志。
- 生产数据库执行 SQL 前必须备份并使用事务。

## 17. 测试清单

### 登录与权限

- 正确账号、密码和 TOTP 可以登录。
- 错误密码达到阈值后账号锁定。
- 禁用用户无法登录。
- 普通用户无法访问管理员接口。
- 普通用户只能看到授权菜单。
- 超级管理员可以访问管理员菜单。
- 退出后旧 Token 无法继续访问。

### 通知

- 邮件发送成功并收到邮件。
- 未配置邮件时返回明确错误。
- Telegram `chatId` 正确时发送成功。
- WhatsApp 使用合法 E.164 号码。
- Lark 的 `open_id` 或 `chat_id` 属于当前 Workspace。
- 外部服务失败时后端返回可定位的错误日志。

### Doris 数据监控

- Doris 可连接时页面能展示币种汇总。
- Doris 不可连接时页面显示错误，不影响登录和权限管理。
- 未授权用户无法调用接口。
- 页面刷新不会修改 Doris 数据。

### 发布验证

- 后端服务为 `active (running)`。
- 8080 端口正常监听。
- `/prod-api/login` 返回预期结果。
- `/prod-api/getInfo` 和 `/prod-api/logout` 正常。
- CloudFront 页面和静态资源返回 `200`。
- CloudFront API 请求正确转发到 EC2。

## 18. 相关文档

- [权限中心说明](README-PERMISSION-CENTER.md)
- [SQL 说明](sql/README.md)
- [权限控制与审计监控技术设计](doc/权限控制与审计监控Dashboard技术设计文档.md)
- [Google Authenticator PRD](doc/PRD_GA_TOTP登录与绑定流程.md)
- [Google Authenticator 技术设计](doc/技术设计_GA_TOTP登录与绑定流程.md)
- [项目数据库梳理](doc/项目数据库梳理.md)

