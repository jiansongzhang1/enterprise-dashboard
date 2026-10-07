package com.fivetech.dashboard.gateway.uds;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * UDS（统一数据服务）对接配置，挂在 {@code dashboard.gateway.uds.*} 下。
 * <p>
 * 地址、租户、身份等一律配置化，不写死在代码里 —— POC 环境、测试环境、生产环境
 * 的 IP 与租户都不同。
 *
 * @author fivetech
 */
@Component
@ConfigurationProperties(prefix = "dashboard.gateway.uds")
public class UdsProperties
{
    /** 服务地址，如 http://172.29.106.105:18082 */
    private String baseUrl;

    /** 查询接口路径 */
    private String queryPath = "/v1/query";

    /** 批量查询接口路径：Body 为 JSON 数组（不包 queries 字段），最多 50 项，响应按顺序返回 */
    private String batchPath = "/v1/query/batch";

    /** 查询预估接口路径：请求体与 /v1/query 相同，只编译不执行，返回 scanRowsEst / costTier */
    private String explainPath = "/v1/query/explain";

    public String getExplainPath()
    {
        return explainPath;
    }

    public void setExplainPath(String explainPath)
    {
        this.explainPath = explainPath;
    }

    /** 运营总览 / 指标汇总的数据集路由（见 {@link Overview}） */
    private Overview overview = new Overview();

    public String getBatchPath()
    {
        return batchPath;
    }

    public void setBatchPath(String batchPath)
    {
        this.batchPath = batchPath;
    }

    public Overview getOverview()
    {
        return overview;
    }

    public void setOverview(Overview overview)
    {
        this.overview = overview == null ? new Overview() : overview;
    }

    /**
     * 指标数据集路由（联调手册第 4 节）。三个字段的值是 {@code datasets} 下的 key：
     * <ul>
     *   <li>{@code hour}：小时行（dashboard_overview_hour_v2，uds_hh）。小时序列、单小时合计；</li>
     *   <li>{@code day}：完整日行（dashboard_overview_day_v2，uds_dt 仅日期）。
     *       只取「只能按日去重」的人数类指标（ARPPU、存款人数），不能用小时值相加替代；</li>
     *   <li>{@code amount}：金额 / 普通比率（dashboard_overview_amount_v2，uds_hh）。
     *       日、周序列与区间合计（dimensions=[]）。</li>
     * </ul>
     * {@code subtract} 是由后端相减组装的指标：UDS 不返回 net / ngr，
     * 产品口径为 net = dep − wd、ngr = ggr − bonus，任一操作数为 NULL 时结果为 NULL。
     */
    public static class Overview
    {
        private String hour;

        private String day;

        private String amount;

        /** 指标编码 → [被减数, 减数]（都是本系统指标编码） */
        private Map<String, java.util.List<String>> subtract = new LinkedHashMap<>();

        public String getHour()
        {
            return hour;
        }

        public void setHour(String hour)
        {
            this.hour = hour;
        }

        public String getDay()
        {
            return day;
        }

        public void setDay(String day)
        {
            this.day = day;
        }

        public String getAmount()
        {
            return amount;
        }

        public void setAmount(String amount)
        {
            this.amount = amount;
        }

        public Map<String, java.util.List<String>> getSubtract()
        {
            return subtract;
        }

        public void setSubtract(Map<String, java.util.List<String>> subtract)
        {
            this.subtract = subtract == null ? new LinkedHashMap<>() : subtract;
        }
    }

    /** 异步导出作业接口路径（POST 提交；GET/DELETE 拼 /{jobId}） */
    private String jobsPath = "/v1/jobs";

    /** 导出文件格式：CSV / PARQUET */
    private String exportFormat = "CSV";

    /** 导出压缩：NONE / GZIP / ZSTD / SNAPPY。GZIP 对 CSV 通常能压到 1/4 以下 */
    private String exportCompression = "GZIP";

    /** 压测数据集配置（只用于验证大文件下载链路） */
    private Benchmark benchmark = new Benchmark();

    /** 触发 UDS 限流（429 / 4290）时的最大重试次数，0 表示不重试 */
    private int rateLimitRetries = 3;

    /** 限流重试单次最长等待（毫秒），防止平台给出很长的 retryAfter 时把请求拖到网关超时 */
    private long rateLimitMaxWaitMillis = 2000;

    /** HTTPS / 双向 TLS 证书配置；base-url 为 https 且平台使用私有 CA 或要求客户端证书时必须配置 */
    private Tls tls = new Tls();

    /**
     * 身份头 X-Uds-*。直连 EC2 本机 8080（本地 SSM 隧道调试包）时 UDS 按它们识别调用方；
     * 经 8443 mTLS 网关时身份由客户端证书决定，这些头被忽略。为空的不发送。
     */
    /** X-Uds-Principal */
    private String principal = "fivetech-dashboard";

    /** X-Uds-Tenant */
    private String tenant = "poc";

    /** X-Uds-Roles，多个逗号分隔 */
    private String roles = "merchant_ops";

    /** X-Uds-Merchant-Codes，多个逗号分隔 */
    private String merchantCodes = "";

    private int connectTimeoutSeconds = 5;

    private int readTimeoutSeconds = 30;

    /**
     * 撞上「数据未就绪」时，把区间上界按粒度往回退几格再试。
     * 退到 0 格仍不就绪就放弃——不无限重试，免得一次页面刷新打出几十个请求。
     */
    private int notReadyRetries = 6;

    public int getNotReadyRetries()
    {
        return notReadyRetries;
    }

    public void setNotReadyRetries(int notReadyRetries)
    {
        this.notReadyRetries = notReadyRetries;
    }

    /**
     * 区间合计使用的粒度。
     * <p>
     * 合计必须由数据侧按整个区间重算，不能把时间序列加总（派生指标要先聚合分子分母、
     * 去重人数不可相加、平均耗时要按笔数加权）。这里传什么值取决于 UDS 的约定，
     * <b>待与数据平台确认</b>。
     */
    private String totalGranularity = "ALL";

    /** 指标编码 → 数据集的映射 */
    private Map<String, Dataset> datasets = new LinkedHashMap<>();

    /** 明细表映射：member / deposit / withdraw / bet */
    private Map<String, DetailDataset> details = new LinkedHashMap<>();

    /**
     * 运营总览的「拆解」查询：按维度分组的区间聚合（排行榜、队列、用户快照、注册渠道）。
     * key 见 {@code com.fivetech.dashboard.service.OverviewBreakdowns}，未配置时对应板块返回空并进 notices。
     */
    private Map<String, Breakdown> breakdowns = new LinkedHashMap<>();

    public Map<String, Breakdown> getBreakdowns()
    {
        return breakdowns;
    }

    public void setBreakdowns(Map<String, Breakdown> breakdowns)
    {
        this.breakdowns = breakdowns == null ? new LinkedHashMap<>() : breakdowns;
    }

    /**
     * 一个拆解查询的映射定义。
     * <p>
     * dimensions / metrics 的 key 是本系统字段编码，value 是 UDS 字段名或指标名，
     * 与 details 的 fields 一样：代码只认编码，UDS 侧改名只改配置。
     */
    public static class Breakdown
    {
        /** UDS 数据集名 */
        private String dataset;

        /** 时间维度字段名，如 biz_hh / biz_date / snapshot_date */
        private String timeDimension = "biz_hh";

        /** 查询粒度。区间聚合时用数据集原生粒度，上游按整个区间重算 */
        private String grain = "HOUR";

        /** 本系统维度编码 → UDS 维度名（分组字段） */
        private Map<String, String> dimensions = new LinkedHashMap<>();

        /** 本系统指标编码 → UDS 指标名 */
        private Map<String, String> metrics = new LinkedHashMap<>();

        /** 固定 options，如 { "channel": "ONLINE" } */
        private Map<String, Object> options = new LinkedHashMap<>();

        /** 行数上限，必须显式传给上游，否则长尾会被默认 limit 悄悄截断 */
        private int limit = 1000;

        /**
         * 排序，下推给 UDS：[{field, dir}]。榜单的 Top N 必须由上游按 orderBy + limit 截取，
         * 不能取回一页再在本地排序——游戏数超过 limit 时会漏掉真正的头部。
         * 最后一项应是稳定键（如 provider_code、game_id），保证同额时顺序确定。
         */
        private java.util.List<Map<String, Object>> orderBy = new java.util.ArrayList<>();

        /**
         * 是否另查一次全量分母（dimensions=[] 的同条件查询）。
         * 占比必须用同一数据集的全量做分母，不能用分页排名的合计。
         */
        private boolean withTotal;

        /**
         * 排名查询与全量分母是否用一次 /v1/query/batch 发出（游戏榜、赠金榜）。
         * false 时分两次 /v1/query（注册渠道 reg-source + reg-total）
         */
        private boolean batch;

        /** 时间参数写法：DATETIME（India_DDHH 等小时列）/ DATE（india_bet_dt 等日期列，只写日期） */
        private String timeFormat = "DATETIME";

        /** 属性补全：排名数据集只有编码时，从另一个数据集按编码查名称等属性（见 {@link Enrich}） */
        private Enrich enrich = new Enrich();

        public Enrich getEnrich()
        {
            return enrich;
        }

        public void setEnrich(Enrich enrich)
        {
            this.enrich = enrich == null ? new Enrich() : enrich;
        }

        /** UDS 返回 0～1 比率、页面要百分数的指标编码：读取后 ×100（保留 2 位） */
        private java.util.List<String> ratioMetrics = new java.util.ArrayList<>();

        public boolean isBatch()
        {
            return batch;
        }

        public void setBatch(boolean batch)
        {
            this.batch = batch;
        }

        public String getTimeFormat()
        {
            return timeFormat;
        }

        public void setTimeFormat(String timeFormat)
        {
            this.timeFormat = timeFormat;
        }

        public java.util.List<String> getRatioMetrics()
        {
            return ratioMetrics;
        }

        public void setRatioMetrics(java.util.List<String> ratioMetrics)
        {
            this.ratioMetrics = ratioMetrics == null ? new java.util.ArrayList<>() : ratioMetrics;
        }

        public java.util.List<Map<String, Object>> getOrderBy()
        {
            return orderBy;
        }

        public void setOrderBy(java.util.List<Map<String, Object>> orderBy)
        {
            this.orderBy = orderBy == null ? new java.util.ArrayList<>() : orderBy;
        }

        public boolean isWithTotal()
        {
            return withTotal;
        }

        public void setWithTotal(boolean withTotal)
        {
            this.withTotal = withTotal;
        }

        public boolean isConfigured()
        {
            return dataset != null && !dataset.isEmpty() && !metrics.isEmpty();
        }

        public String getDataset()
        {
            return dataset;
        }

        public void setDataset(String dataset)
        {
            this.dataset = dataset;
        }

        public String getTimeDimension()
        {
            return timeDimension;
        }

        public void setTimeDimension(String timeDimension)
        {
            this.timeDimension = timeDimension;
        }

        public String getGrain()
        {
            return grain;
        }

        public void setGrain(String grain)
        {
            this.grain = grain;
        }

        public Map<String, String> getDimensions()
        {
            return dimensions;
        }

        public void setDimensions(Map<String, String> dimensions)
        {
            this.dimensions = dimensions == null ? new LinkedHashMap<>() : dimensions;
        }

        public Map<String, String> getMetrics()
        {
            return metrics;
        }

        public void setMetrics(Map<String, String> metrics)
        {
            this.metrics = metrics == null ? new LinkedHashMap<>() : metrics;
        }

        public Map<String, Object> getOptions()
        {
            return options;
        }

        public void setOptions(Map<String, Object> options)
        {
            this.options = options == null ? new LinkedHashMap<>() : options;
        }

        public int getLimit()
        {
            return limit;
        }

        public void setLimit(int limit)
        {
            this.limit = limit;
        }
    }

    /**
     * 榜单属性补全：排名数据集（如 dashboard_game_ranking）只给 provider_code + game_id，
     * 名称、类型等从另一个数据集（如 dashboard_bet_detail_v2）按同一时间窗、同一组编码分组取一次。
     * 查不到或查询失败时对应属性留 null，不影响榜单本身。
     */
    public static class Enrich
    {
        /** 属性来源数据集；为空表示不补全 */
        private String dataset;

        /** 来源数据集的时间维度（小时轴） */
        private String timeDimension = "uds_hh";

        /** 计数指标（来源数据集要求至少一个指标） */
        private String countMetric = "record_count";

        /** 关联键：榜单维度编码 → 来源数据集字段名，例 { platformCode: provider_code, gameId: game_id } */
        private Map<String, String> keys = new LinkedHashMap<>();

        /** 补全的属性：榜单维度编码 → 来源数据集字段名，例 { name: game_name, gameType: game_type } */
        private Map<String, String> fields = new LinkedHashMap<>();

        /** 按哪个关联键下推 OR 过滤（榜单维度编码），例 gameId */
        private String filterKey;

        private Map<String, Object> options = new LinkedHashMap<>();

        public boolean isConfigured()
        {
            return dataset != null && !dataset.isEmpty() && !keys.isEmpty() && !fields.isEmpty();
        }

        public String getDataset()
        {
            return dataset;
        }

        public void setDataset(String dataset)
        {
            this.dataset = dataset;
        }

        public String getTimeDimension()
        {
            return timeDimension;
        }

        public void setTimeDimension(String timeDimension)
        {
            this.timeDimension = timeDimension;
        }

        public String getCountMetric()
        {
            return countMetric;
        }

        public void setCountMetric(String countMetric)
        {
            this.countMetric = countMetric;
        }

        public Map<String, String> getKeys()
        {
            return keys;
        }

        public void setKeys(Map<String, String> keys)
        {
            this.keys = keys == null ? new LinkedHashMap<>() : keys;
        }

        public Map<String, String> getFields()
        {
            return fields;
        }

        public void setFields(Map<String, String> fields)
        {
            this.fields = fields == null ? new LinkedHashMap<>() : fields;
        }

        public String getFilterKey()
        {
            return filterKey;
        }

        public void setFilterKey(String filterKey)
        {
            this.filterKey = filterKey;
        }

        public Map<String, Object> getOptions()
        {
            return options;
        }

        public void setOptions(Map<String, Object> options)
        {
            this.options = options == null ? new LinkedHashMap<>() : options;
        }
    }

    /**
     * 一个数据集的映射定义
     */
    public static class Dataset
    {
        /** UDS 数据集名；为空时用 datasets 下的 key */
        private String name;

        /**
         * 时间参数写法：DATETIME（uds_hh 等小时列，from/to 写 yyyy-MM-dd HH:mm:ss）
         * 或 DATE（uds_dt，from/to 只写日期）。小时事实表只传日期会漏最后一天，UDS 会拒绝（4001）
         */
        private String timeFormat = "DATETIME";

        public String getName()
        {
            return name;
        }

        public void setName(String name)
        {
            this.name = name;
        }

        public String getTimeFormat()
        {
            return timeFormat;
        }

        public void setTimeFormat(String timeFormat)
        {
            this.timeFormat = timeFormat;
        }

        /** 时间维度字段名，如 dt */
        private String timeDimension = "dt";

        /**
         * 数据集的原生粒度。区间合计不传维度、只传这个粒度的时间块，
         * 让上游按整个区间重算——人数走 bitmap 跨小时合并去重，绝不是小时值相加。
         */
        private String grain = "HOUR";

        public String getGrain()
        {
            return grain;
        }

        public void setGrain(String grain)
        {
            this.grain = grain;
        }

        /** 查询时带上的维度，如 merchant_code */
        private java.util.List<String> dimensions = new java.util.ArrayList<>();

        /** 固定 options，如 { "channel": "ONLINE" } */
        private Map<String, Object> options = new LinkedHashMap<>();

        /** 本系统指标编码 → UDS 指标名 */
        private Map<String, String> metrics = new LinkedHashMap<>();

        public String getTimeDimension()
        {
            return timeDimension;
        }

        public void setTimeDimension(String timeDimension)
        {
            this.timeDimension = timeDimension;
        }

        public java.util.List<String> getDimensions()
        {
            return dimensions;
        }

        public void setDimensions(java.util.List<String> dimensions)
        {
            this.dimensions = dimensions;
        }

        public Map<String, Object> getOptions()
        {
            return options;
        }

        public void setOptions(Map<String, Object> options)
        {
            this.options = options;
        }

        public Map<String, String> getMetrics()
        {
            return metrics;
        }

        public void setMetrics(Map<String, String> metrics)
        {
            this.metrics = metrics;
        }
    }

    /**
     * 明细表映射。UDS 是聚合型接口，是否支持明细查询待确认；
     * 未配置时对应的明细方法返回空结果而不是报错。
     */
    public static class DetailDataset
    {
        private String dataset;

        /**
         * 精确时间列（如 created_date_india）。uds_hh 只是小时主轴，
         * 业务区间 [from, to) 还要在这一列上加 GTE / LT 精确过滤
         */
        private String timeColumn;

        /**
         * 快照数据集（如 dashboard_player_snapshot_v3）：time 固定为 {time-dimension, HOUR, now, now}，
         * 按与数据团队的约定表示「返回全部数据」；业务时间区间只落在 time-column 的筛选上，不再推 uds_hh 主轴
         */
        private boolean snapshot;

        /** 稳定行身份，排序的最后一项固定按它升序 */
        private String rowKey = "uds_rowkey";

        /** 计数指标：每行为 1，COUNT 查询按它汇总 */
        private String countMetric = "record_count";

        /**
         * 筛选 / 排序时使用的字段，覆盖 fields 的映射。
         * 例：amount 是混合原币，排序和区间筛选要用基准币 default_amount
         */
        private Map<String, String> filterFields = new LinkedHashMap<>();

        /**
         * 多字段「或」检索。key = 请求里的筛选编码（keyword 为关键字），value = UDS 字段列表；
         * 字段名以 ~ 结尾表示 LIKE 包含匹配，否则为 EQ。例：[player_id, billno, login_search~]
         */
        private Map<String, java.util.List<String>> searchFields = new LinkedHashMap<>();

        /**
         * 取值映射：列编码 → {本系统取值: UDS 取值}。例：status: {succ: 30}。
         * 配了映射的列，请求里出现映射外的取值会直接报错（不把未知取值发给上游）；
         * 返回行里能反查到的 UDS 取值翻译回本系统取值，反查不到的原值保留
         */
        private Map<String, Map<String, String>> valueMaps = new LinkedHashMap<>();

        /**
         * 兜底取值：列编码 → 本系统取值。返回行里 valueMaps 反查不到的 UDS 取值一律翻译成它；
         * 按它筛选时下推为「不等于 valueMaps 里的其他取值」。例：status: fail（存款非 30 一律视为失败）
         */
        private Map<String, String> valueDefaults = new LinkedHashMap<>();

        public Map<String, String> getValueDefaults()
        {
            return valueDefaults;
        }

        public void setValueDefaults(Map<String, String> valueDefaults)
        {
            this.valueDefaults = valueDefaults == null ? new LinkedHashMap<>() : valueDefaults;
        }

        public String getTimeColumn()
        {
            return timeColumn;
        }

        public boolean isSnapshot()
        {
            return snapshot;
        }

        public void setSnapshot(boolean snapshot)
        {
            this.snapshot = snapshot;
        }

        public void setTimeColumn(String timeColumn)
        {
            this.timeColumn = timeColumn;
        }

        public String getRowKey()
        {
            return rowKey;
        }

        public void setRowKey(String rowKey)
        {
            this.rowKey = rowKey;
        }

        public String getCountMetric()
        {
            return countMetric;
        }

        public void setCountMetric(String countMetric)
        {
            this.countMetric = countMetric;
        }

        public Map<String, String> getFilterFields()
        {
            return filterFields;
        }

        public void setFilterFields(Map<String, String> filterFields)
        {
            this.filterFields = filterFields == null ? new LinkedHashMap<>() : filterFields;
        }

        public Map<String, java.util.List<String>> getSearchFields()
        {
            return searchFields;
        }

        public void setSearchFields(Map<String, java.util.List<String>> searchFields)
        {
            this.searchFields = searchFields == null ? new LinkedHashMap<>() : searchFields;
        }

        public Map<String, Map<String, String>> getValueMaps()
        {
            return valueMaps;
        }

        public void setValueMaps(Map<String, Map<String, String>> valueMaps)
        {
            this.valueMaps = valueMaps == null ? new LinkedHashMap<>() : valueMaps;
        }

        private String timeDimension = "dt";

        /** VO 字段名 → UDS 列名 */
        private Map<String, String> fields = new LinkedHashMap<>();

        private Map<String, Object> options = new LinkedHashMap<>();

        public String getDataset()
        {
            return dataset;
        }

        public void setDataset(String dataset)
        {
            this.dataset = dataset;
        }

        public String getTimeDimension()
        {
            return timeDimension;
        }

        public void setTimeDimension(String timeDimension)
        {
            this.timeDimension = timeDimension;
        }

        public Map<String, String> getFields()
        {
            return fields;
        }

        public void setFields(Map<String, String> fields)
        {
            this.fields = fields;
        }

        public Map<String, Object> getOptions()
        {
            return options;
        }

        public void setOptions(Map<String, Object> options)
        {
            this.options = options;
        }
    }

    public String getBaseUrl()
    {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl)
    {
        this.baseUrl = baseUrl;
    }

    public String getQueryPath()
    {
        return queryPath;
    }

    public void setQueryPath(String queryPath)
    {
        this.queryPath = queryPath;
    }

    public String getPrincipal()
    {
        return principal;
    }

    public void setPrincipal(String principal)
    {
        this.principal = principal;
    }

    public String getTenant()
    {
        return tenant;
    }

    public void setTenant(String tenant)
    {
        this.tenant = tenant;
    }

    public String getRoles()
    {
        return roles;
    }

    public void setRoles(String roles)
    {
        this.roles = roles;
    }

    public String getMerchantCodes()
    {
        return merchantCodes;
    }

    public void setMerchantCodes(String merchantCodes)
    {
        this.merchantCodes = merchantCodes;
    }

    public int getConnectTimeoutSeconds()
    {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds)
    {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds()
    {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds)
    {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    public String getTotalGranularity()
    {
        return totalGranularity;
    }

    public void setTotalGranularity(String totalGranularity)
    {
        this.totalGranularity = totalGranularity;
    }

    public Map<String, Dataset> getDatasets()
    {
        return datasets;
    }

    public void setDatasets(Map<String, Dataset> datasets)
    {
        this.datasets = datasets;
    }

    public Map<String, DetailDataset> getDetails()
    {
        return details;
    }

    public void setDetails(Map<String, DetailDataset> details)
    {
        this.details = details;
    }

    public String getJobsPath()
    {
        return jobsPath;
    }

    public void setJobsPath(String jobsPath)
    {
        this.jobsPath = jobsPath;
    }

    public String getExportFormat()
    {
        return exportFormat;
    }

    public void setExportFormat(String exportFormat)
    {
        this.exportFormat = exportFormat;
    }

    public String getExportCompression()
    {
        return exportCompression;
    }

    public void setExportCompression(String exportCompression)
    {
        this.exportCompression = exportCompression;
    }

    public Benchmark getBenchmark()
    {
        return benchmark;
    }

    public void setBenchmark(Benchmark benchmark)
    {
        this.benchmark = benchmark;
    }

    /**
     * 压测数据集。UDS 的 {@code bulk_bets} 绑定一张 3200 万行的压测表，只开放一天；
     * 维度里带 payload 就是按明细行导出（每行约 512 字节），不带就是按天 × 商户汇总。
     */
    public static class Benchmark
    {
        private String dataset = "bulk_bets";

        private String timeDimension = "dt";

        /** 开放的那一天 yyyy-MM-dd，其他日期 UDS 返回 4403 */
        private String date = "2026-09-10";

        /** 明细行维度 */
        private java.util.List<String> detailDimensions = new java.util.ArrayList<>(
            java.util.List.of("dt", "merchant_code", "payload"));

        /** 汇总维度 */
        private java.util.List<String> summaryDimensions = new java.util.ArrayList<>(
            java.util.List.of("dt", "merchant_code"));

        private java.util.List<String> metrics = new java.util.ArrayList<>(java.util.List.of("bet_amount"));

        /** 商户过滤字段 */
        private String merchantField = "merchant_code";

        public String getDataset() { return dataset; }
        public void setDataset(String dataset) { this.dataset = dataset; }
        public String getTimeDimension() { return timeDimension; }
        public void setTimeDimension(String timeDimension) { this.timeDimension = timeDimension; }
        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }
        public java.util.List<String> getDetailDimensions() { return detailDimensions; }
        public void setDetailDimensions(java.util.List<String> detailDimensions) { this.detailDimensions = detailDimensions; }
        public java.util.List<String> getSummaryDimensions() { return summaryDimensions; }
        public void setSummaryDimensions(java.util.List<String> summaryDimensions) { this.summaryDimensions = summaryDimensions; }
        public java.util.List<String> getMetrics() { return metrics; }
        public void setMetrics(java.util.List<String> metrics) { this.metrics = metrics; }
        public String getMerchantField() { return merchantField; }
        public void setMerchantField(String merchantField) { this.merchantField = merchantField; }
    }

    public Tls getTls()
    {
        return tls;
    }

    public void setTls(Tls tls)
    {
        this.tls = tls;
    }

    public int getRateLimitRetries()
    {
        return rateLimitRetries;
    }

    public void setRateLimitRetries(int rateLimitRetries)
    {
        this.rateLimitRetries = rateLimitRetries;
    }

    public long getRateLimitMaxWaitMillis()
    {
        return rateLimitMaxWaitMillis;
    }

    public void setRateLimitMaxWaitMillis(long rateLimitMaxWaitMillis)
    {
        this.rateLimitMaxWaitMillis = rateLimitMaxWaitMillis;
    }

    /**
     * UDS 的 TLS 证书（PEM 文件路径）。
     * <ul>
     *   <li>{@code caCert}：平台方的 CA 证书，用来校验 UDS 服务端证书（私有 CA 必配）；</li>
     *   <li>{@code clientCert} + {@code clientKey}：本服务的客户端证书与私钥，UDS 开启双向 TLS 时必配。</li>
     * </ul>
     * 私钥文件只需运行本服务的系统用户可读（chmod 600），切勿提交到代码仓库。
     */
    public static class Tls
    {
        private String caCert;

        private String clientCert;

        private String clientKey;

        public boolean isConfigured()
        {
            return notBlank(caCert) || notBlank(clientCert) || notBlank(clientKey);
        }

        private static boolean notBlank(String s)
        {
            return s != null && !s.isBlank();
        }

        public String getCaCert() { return notBlank(caCert) ? caCert.trim() : null; }
        public void setCaCert(String caCert) { this.caCert = caCert; }
        public String getClientCert() { return notBlank(clientCert) ? clientCert.trim() : null; }
        public void setClientCert(String clientCert) { this.clientCert = clientCert; }
        public String getClientKey() { return notBlank(clientKey) ? clientKey.trim() : null; }
        public void setClientKey(String clientKey) { this.clientKey = clientKey; }
    }
}
