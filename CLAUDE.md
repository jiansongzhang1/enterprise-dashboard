# Yata Dashboard 后端 · 项目记忆

给 Claude Code 用的项目约定与现状。2026-11-11 整理。

## 1. 工程与环境

- Spring Boot 4 + RuoYi，**Jackson 3（`tools.jackson.*`，不是 `com.fasterxml`）**，MyBatis，PostgreSQL。
- Maven 多模块：`fivetech-admin`（Controller、application.yml）、`fivetech-system`（业务）、`fivetech-framework`（安全、拦截器）、`fivetech-common`、`fivetech-generator`。
- 分支：**v3 开发**，v4 在 v3 基础上多订阅截图等功能。合并 v3 → v4 时 `sql/release_next_postgresql.sql` 的目录行必有冲突，保留 v4 的。
- 构建与测试在 IDEA 里跑 `mvn test`。开发机未装 Maven，云端连不上 Maven 仓库，**Claude 改完代码不编译、不提交，由本人提交**。
- 代码风格：RuoYi 风格（花括号另起一行），注释中文，面向用户的提示文案用繁體。

## 2. 站点模型（重要）

- 站点存在 `monitor_site_setting`（兼作站点表）：`site_name` 不为空的行才是站点。
- 站点级字段：`timezone`、`currency`、`currency_symbol`、`realtime`、`data_start_date`。
- `SiteRegistry` 启动时加载、每 60 秒按 `update_time` 版本号比对重载；结果写进 `DashboardProperties` 的内存快照。
  **直接用 SQL 改站点表时必须同时更新 `update_time`，否则不会重载。**
- 取值入口统一走 `DashboardProperties`：`siteTimezone/siteZone/currentZone`、`siteCurrency/siteCurrencySymbol`、
  `isRealtime(code)`、`siteDataStartDate(code)`（没设置回落 `dashboard.launch-date`）。
- 站点由请求头 `X-Site-Code` 决定（`SiteContext`），请求体里的 siteCode 一律不采信。

## 3. 时间与时区（反复踩坑）

- **后端不做时区转换**：前端传的 `slotFrom/slotTo`（`yyyy-MM-dd HH:mm`）原样当作站点时区本地时间。
- 「现在 / 今天」一律 `LocalDateTime.now(properties.currentZone())`，禁止 `LocalDateTime.now()`。
- 区间左闭右开；`slotTo` 不含。前端要含今天，须传明天 00:00。
- 终点截断：`UdsRealtime.ceilHour(now)`，16:50 → 17:00，把正在进行的小时一起查（UDS 2026-11-06 起支持实时）。
- 时间片个数 `countPoints`：小时按整点差；**日 / 周向上取整**，终点截到当前整点时今天也算一格（近 7 天 = 7 格）。
- `coversToday`（= `anomalies.inScope`、基准线、逐时段监控列的开关）：起点必须是今天 00:00，终点落在今天内；
  小时粒度直接 true，日 / 周要求终点是明天 00:00 或已覆盖到当前整点。用**向下取整**的当前整点判断，否则 23:xx 会算成明天。
- 前端算「今日」必须用 getInfo 返回的 `sites[].timezone`，不能用浏览器时区。

## 4. UDS 数据平台

- 配置在 `application.yml` 的 `dashboard.gateway.uds.*`：`datasets`（指标→字段映射）、`breakdowns`、`details`。
- 水位：只认响应 `meta.freshness.watermark`，`UdsClient.parseWatermark` 解析，按「站点+数据集」记在内存，只前进不后退。
  `getFreshness` 取运营总览两个数据集里较早的。**水位不是「数据完整」的权威信号。**
- 错误码：4403 = 数据未就绪（先截到水位重试一次，再不行放弃）；4002 = 维度不存在；4001 = 字面量类型不符。
- 宽表与精确人数是**两个来源，不能写在一条查询里**（`reg + deposit_users` 会 4403），要用 `/v1/query/batch`。
- 去重人数**不能跨时间片相加**；区间值要单独发一次不带时间分组的请求。
- 待接入能力（U1）：**行为数据没有推广渠道维度**，提款、净充值、GGR/ROAS、推广商渠道树都缺源。
- 配置里字段名留空（如 `filter-fields.lost: ""`）时，拼 `dimensions` 必须跳过，否则 UDS 报 4002。

## 5. 上线 SQL

- 统一写在 `sql/release_next_postgresql.sql`，顶部有目录行，底部有「新的改动从这里往下追加」标记。
- 每段固定格式：编号 + 日期 + 标题、用途说明、**回滚语句**、SQL 本体、`-- ⑧ 自检：` 开头的 SELECT。
- 已有段落：⑤ 推广分析指标登记、⑥ AI 助手、⑦ 个人视图、⑧ 16 个 promo 指标 UDS 对照、⑨ API Key、
  ⑩ 行维度去掉顺序限制、⑪ `realtime`、⑫ `data_start_date`。
- **有结构变更（加列）的段落必须在新 JAR 启动前执行**，否则启动加载站点表失败。
- 写 `dashboard_metric_card.source_table/source_field` 只能写纯标识符，指标管理保存时会校验（表 `^[A-Za-z_][A-Za-z0-9_.]*$`，字段 `^[A-Za-z_][A-Za-z0-9_]*$`），写中文会让该指标无法编辑。

## 6. 推广分析

- 入口：`PromoOverviewService`（概览）、`PromoAnalysisService`（渠道表现 / 漏斗 / 留存 / 裂变）、`PromoExportService`（三个导出）。
- 指标目录 `PromoMetricCatalog`，16 个概览指标 + `DAILY_REPORT_COLUMNS`（17 列）。
- 指标口径：`net`（淨充值）= `deposit_amount`（存款總額，2026-11-09 确认）；`act`（激活）= `android_first_open`，只在全部 / Android 有值，口径待定。
- 成本类（`PromoCost`）：CPA / 首存成本 / D0 ROI / UV 成本，按**有效格子**（日 × 推广渠道，消耗 > 0）计算。
  渠道行为数据缺源 → 推广商、渠道、终端都为「全部」时按**全站估算**（`PromoOverviewService.siteCost`），
  卡片 `note` 写明是估算；选了具体渠道仍返回 NO_SOURCE。
- 行维度（`ChannelTree`）：agent / channel / date / terminal / relation，1–5 层，**先后不限**（2026-11-10 去掉了「渠道须在推广商之下」）。
- 变化归因看 `dashboard_metric_card.attributable`，注意推广分析用的是 `promoReg` 等 promo 前缀指标，不是运营总览的 `reg`。
- 「仅登记」（status='1'）的指标不参与 `application.yml` 取数配置对照，指标管理不提示「與配置不一致」。

## 7. 导出

- 推广分析三个导出：`/dashboard/promo/{daily-report,channels,fission}/export`，POST，返回文件流。
  响应头：`download-filename`（URL 编码，前端要解码用它命名）、`export-summary`、`Content-Disposition`。
- `PromoSheet` 生成 xlsx，失败回落 csv（UTF-8 带 BOM）。上限 1,000,000 行，超了报 4005。
- 投放日报结构：标题行（含「資料截至」）→ 表头 → 區間合計 → 每天（各渠道行 → 當日合計）。
  **合计标签写在「推廣渠道」列**，日期列写日期 / 区间。
  默认「只含付費推廣渠道」只列当天有预算或消耗记录的渠道；取消勾选且渠道为全部时，合计行用全站值。
- 导出里的时间（提交时间、文件名时间戳、導出時間）一律用站点时区，走 `ExportTemplate.now(properties)`。

## 8. 安全约束（必须遵守）

- 令牌、密钥只放环境变量（`/etc/fivetech.env`、IDEA run config），**不进代码、yml、git、聊天、文档**。
- 私密联调包（`codex-市场预算消耗-私密联调包-r3` 等）含真实 token 与私钥，不读取、不打印其中的密钥值。
- 预算 / 消耗导入写的是生产数据，只用约定好的测试日期。
- `UDS_LOG_FULL` 全量日志只在测试环境开。
- API Key（`X-Api-Key`，前缀 `ftk_`）只在 `security.api-key.paths` 列出的接口认，目前只有 `/notification/lark/send`。

## 9. 本地协作方式

- 代码在 `/Users/jackson/Documents/dashboard-backend`，前端在 `/Users/jackson/Documents/dashboard-frontend`（前端目录不是 git 仓库，改前先备份）。
- 设备上 git 没有身份，**不要 commit**；改完告诉用户改了什么、让他在 IDEA 里编译提交。
- 临时文件放 `$HOME`（不在 `mnt/` 下），不要写进用户的工程目录。

## 10. 待办与待确认

- UDS 补推广渠道维度后，成本类改回按有效格子，全站估算分支可删。
- 激活（`promoAct`）口径待产品确定；UV-落地頁（`lp_uv`）口径待确认；下載點擊用 `lp_download` 还是 `lp_download_ok` 待定。
- 4403 的 `to` 规则等 UDS 确认，暂不改。
- OPT-01～04（V0.3 优化清单）：统一「资料截至」时刻、实时站点指标汇总含当前小时、页头实时更新与手动刷新。
  `realtime` 字段已落地（站点管理可编辑，getInfo / summary / promo options / member options 返回；overview 已于 2026-11-11 去掉），
  但**尚未用它区分取数**：目前所有站点都按实时取数。
- 指标汇总小时粒度下去重人数的区间值是各小时平均，与指标卡的区间去重口径不同，OPT-02「三者相等」验收过不了，待产品定。
