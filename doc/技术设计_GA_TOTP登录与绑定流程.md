# 技术设计：Google Authenticator TOTP 登录与绑定流程

## 1. 设计目标

在保留若依现有 JWT 登录底座的前提下，增加 Google Authenticator TOTP 能力，并支持登录页的首次绑定流程。

## 2. 状态机

`sys_user` 新增 `ga_status` 字段：

- `0`：未开通
- `1`：待绑定
- `2`：已绑定

字段语义：

- `ga_secret` 为空：账号未开通 TOTP
- `ga_secret` 非空且 `ga_status=1`：允许展示二维码完成绑定
- `ga_secret` 非空且 `ga_status=2`：允许使用 6 位动态码登录

## 3. 数据流

### 3.1 已绑定登录

1. 前端提交 `username + password + code`
2. 后端验证账号密码
3. 后端读取 `sys_user.gaSecret` 并验证 TOTP
4. 通过后签发 JWT

### 3.2 首次绑定

1. 前端提交 `username + password`
2. 后端验证账号密码
3. 若 `gaSecret` 为空，返回“请联系管理员分配权限”
4. 若 `gaStatus=1`，后端返回 `otpauthUrl`
5. 前端渲染二维码，用户扫码
6. 用户输入 6 位验证码完成确认
7. 后端将 `gaStatus` 更新为 `2`

## 4. 接口设计

### 4.1 登录

`POST /login`

请求体：

- `username`
- `password`
- `code`

### 4.2 首次绑定初始化

`POST /ga/bind/start`

请求体：

- `username`
- `password`

返回：

- `gaStatus`
- `otpauthUrl`
- `issuer`
- `account`

### 4.3 首次绑定确认

`POST /ga/bind/confirm`

请求体：

- `username`
- `password`
- `code`

返回：

- 绑定成功提示

## 5. 后端实现点

- `SysLoginService`
  - 登录时校验 TOTP
  - 绑定时校验账号密码并生成绑定信息
  - 绑定确认时校验验证码并更新状态
- `GoogleAuthenticatorUtils`
  - 生成 `otpauth://` 地址
  - 校验 TOTP 动态码
- `SysUser`
  - 新增 `gaStatus`
- `SysUserMapper.xml`
  - 增加 `ga_status` 映射和写入

## 6. 前端实现点

- 登录页改为双 Tab：
  - 已绑定登录
  - 首次绑定
- 通过 `qrcode` 组件将 `otpauthUrl` 渲染为二维码
- 成功绑定后切换回登录页或直接提示用户使用 6 位验证码登录

## 7. 安全要求

- 不在前端暴露长期 secret
- 仅返回二维码内容 `otpauthUrl`
- 绑定完成后，账号状态必须落库
- 未开通账号不允许自助生成 secret

## 8. 更新记录

- 2026-08-20：补充 TOTP 登录与绑定接口设计，明确状态机与前端渲染方式。
- 2026-08-20：接口已落地，前端使用 `qrcode` 渲染 `otpauthUrl`，后端新增 `ga_status` 与绑定确认更新。
