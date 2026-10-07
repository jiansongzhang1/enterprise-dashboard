package com.fivetech.dashboard.gateway.uds;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.common.utils.TraceIdUtils;

/**
 * UDS（统一数据服务）HTTP 客户端。
 * <p>
 * 只调用 {@code POST /v1/query} 直接取数。
 * <p>
 * 不再在查询前调元数据接口预检指标：指标有效性由本系统的指标注册表（白名单）负责，
 * UDS 侧真缺指标时查询本身会报错，并带回真实原因。
 *
 * @author fivetech
 */
@Component
public class UdsClient
{
    private static final Logger log = LoggerFactory.getLogger(UdsClient.class);

    private final UdsProperties properties;

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient;

    public UdsClient(UdsProperties properties, ObjectMapper objectMapper)
    {
        this.properties = properties;
        this.objectMapper = objectMapper;
        HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getConnectTimeoutSeconds())));
        javax.net.ssl.SSLContext sslContext = buildSslContext(properties);
        if (sslContext != null)
        {
            builder.sslContext(sslContext);
        }
        this.httpClient = builder.build();
    }

    /**
     * 按配置构造只给 UDS 用的 TLS 上下文。
     * <p>
     * 证书配错（文件不存在、格式不对、cert 与 key 不配对）时<b>启动直接失败</b>：
     * 带着错误配置跑起来，只会在每次查询时报一句看不懂的握手失败。
     */
    private static javax.net.ssl.SSLContext buildSslContext(UdsProperties properties)
    {
        String base = properties.getBaseUrl();
        boolean https = base != null && base.trim().toLowerCase().startsWith("https://");
        UdsProperties.Tls tls = properties.getTls();
        if (!https)
        {
            if (tls != null && tls.isConfigured())
            {
                log.warn("[uds] 配置了 TLS 证书，但 base-url 不是 https（{}），证书不会生效", base);
            }
            return null;
        }
        if (tls == null || !tls.isConfigured())
        {
            log.info("[uds] base-url 为 https，未配置证书，使用 JDK 默认信任库：{}", base);
            return null;
        }
        try
        {
            javax.net.ssl.SSLContext context = UdsTlsSupport.build(tls);
            log.info("[uds] 已启用 TLS：{}，CA={}，客户端证书={}", base,
                tls.getCaCert() == null ? "JDK 默认" : UdsTlsSupport.describe(tls.getCaCert()),
                tls.getClientCert() == null ? "无（单向 TLS）" : UdsTlsSupport.describe(tls.getClientCert()));
            return context;
        }
        catch (Exception e)
        {
            throw new IllegalStateException("UDS TLS 证书加载失败，请检查 dashboard.gateway.uds.tls 配置："
                + e.getMessage(), e);
        }
    }

    // ===================== 查询 =====================

    /**
     * 执行一次查询，返回拍平后的行。
     *
     * @param body 请求体，结构见 {@code /v1/query} 约定
     */
    public List<UdsRow> query(Map<String, Object> body)
    {
        String json;
        try
        {
            json = objectMapper.writeValueAsString(body);
        }
        catch (Exception e)
        {
            throw new UdsQueryException("UDS 请求体序列化失败", 0, e);
        }
        return queryWithMeta(body).getRows();
    }

    /**
     * 执行一次查询，连同响应的 {@code meta} 一起返回。
     *
     * @param body 请求体，结构见 {@code /v1/query} 约定
     */
    public UdsQueryResult queryWithMeta(Map<String, Object> body)
    {
        String json;
        try
        {
            json = objectMapper.writeValueAsString(body);
        }
        catch (Exception e)
        {
            throw new UdsQueryException("UDS 请求体序列化失败", 0, e);
        }
        return toResult(readJson(post(properties.getQueryPath(), json)));
    }

    /**
     * 批量查询：{@code POST /v1/query/batch}，Body 是 JSON 数组（不包 queries 字段），最多 50 项。
     * <p>响应同样是按顺序排列的数组。一项失败会使整批失败（HTTP 非 2xx），按普通查询的异常抛出。
     * batch 不承诺数仓事务快照：列表与 COUNT 要靠 dataVersion 判断是否同源。</p>
     */
    public List<UdsQueryResult> queryBatch(List<Map<String, Object>> bodies)
    {
        if (bodies == null || bodies.isEmpty())
        {
            return new ArrayList<>();
        }
        if (bodies.size() > 50)
        {
            throw new UdsQueryException("批量查询最多 50 项，实际 " + bodies.size(), 0);
        }
        String json;
        try
        {
            json = objectMapper.writeValueAsString(bodies);
        }
        catch (Exception e)
        {
            throw new UdsQueryException("UDS 请求体序列化失败", 0, e);
        }
        JsonNode root = readJson(post(properties.getBatchPath(), json));
        JsonNode array = root.isArray() ? root : firstArray(root, "results", "data", "items");
        if (array == null || array.size() != bodies.size())
        {
            throw new UdsQueryException("UDS 批量响应条数与请求不一致：请求 " + bodies.size() + " 项", 200);
        }
        List<UdsQueryResult> results = new ArrayList<>(array.size());
        for (JsonNode item : array)
        {
            results.add(toResult(item));
        }
        return results;
    }

    /** 单条响应（/v1/query 或 batch 的一项）→ 行 + meta */
    private UdsQueryResult toResult(JsonNode root)
    {
        List<UdsRow> rows = parseRows(root);
        JsonNode meta = root.path("meta");
        JsonNode freshness = meta.path("freshness");
        boolean stale = freshness.path("stale").asBoolean(false);
        UdsQueryResult result = new UdsQueryResult(rows,
            firstText(meta, "binding"),
            firstText(meta, "dataVersion", "data_version", "version"),
            stale,
            parseWatermark(freshness));
        if (stale)
        {
            // 上游自己说数据过期了，这是运营会看到错数的直接原因，必须留痕
            log.warn("[uds] 上游标记数据过期 binding={} dataVersion={}",
                result.getBinding(), result.getDataVersion());
        }
        if (log.isDebugEnabled())
        {
            log.debug("[uds] requestId={} binding={} dataVersion={} rows={}",
                firstText(root, "requestId"), result.getBinding(), result.getDataVersion(), rows.size());
        }
        return result;
    }

    /**
     * 解析水位线。字段名待数据平台确认，这里按几个常见命名试探；
     * <b>拿不到返回 null</b>——上层会如实标记为「推测」，不编造精确时间。
     */
    private java.time.LocalDateTime parseWatermark(JsonNode freshness)
    {
        String text = firstText(freshness, "asOf", "as_of", "watermark", "maxTime", "max_time", "dataTime");
        if (text == null || text.isBlank())
        {
            return null;
        }
        try
        {
            String normalized = text.trim().replace('T', ' ');
            if (normalized.length() > 19)
            {
                normalized = normalized.substring(0, 19);
            }
            if (normalized.length() == 16)
            {
                normalized = normalized + ":00";
            }
            return java.time.LocalDateTime.parse(normalized,
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        catch (Exception e)
        {
            log.debug("[uds] 无法解析水位线: {}", text);
            return null;
        }
    }

    /**
     * 把响应拍平成行。
     * <p>
     * <b>这是唯一需要按 UDS 真实响应结构调整的地方。</b>
     * 当前同时兼容两种常见形态，拿到真实样例后可以删掉用不到的分支：
     * <pre>
     * A) 列式：{"data":{"columns":["dt","bet_amount"],"rows":[["2026-09-08",123]]}}
     * B) 行式：{"data":[{"dt":"2026-09-08","bet_amount":123}]}
     * </pre>
     * 数组也可能直接挂在根节点，或叫 records / items / result。
     */
    private List<UdsRow> parseRows(JsonNode root)
    {
        List<UdsRow> rows = new ArrayList<>();
        if (root == null || root.isMissingNode() || root.isNull())
        {
            return rows;
        }
        // 业务错误码：非 0 / 非 200 视为失败，直接抛出让上层告警
        JsonNode code = root.path("code");
        if (code.isNumber() && code.asInt() != 0 && code.asInt() != 200)
        {
            throw new UdsQueryException("UDS 返回错误：code=" + code.asInt()
                + " msg=" + firstText(root, "message", "msg", "error"), 200);
        }

        JsonNode data = root.path("data");
        JsonNode container = data.isMissingNode() || data.isNull() ? root : data;

        // A) 列式
        JsonNode columns = container.path("columns");
        JsonNode matrix = container.path("rows");
        if (columns.isArray() && matrix.isArray())
        {
            List<String> names = new ArrayList<>();
            for (JsonNode c : columns)
            {
                names.add(c.isTextual() ? c.asString() : firstText(c, "name", "code", "field"));
            }
            for (JsonNode line : matrix)
            {
                Map<String, Object> cells = new LinkedHashMap<>();
                if (line.isArray())
                {
                    for (int i = 0; i < names.size() && i < line.size(); i++)
                    {
                        cells.put(names.get(i), value(line.get(i)));
                    }
                }
                else if (line.isObject())
                {
                    putAll(line, cells);
                }
                rows.add(new UdsRow(cells));
            }
            return rows;
        }

        // B) 行式
        JsonNode array = firstArray(container, "rows", "records", "items", "result", "list");
        if (array == null && container.isArray())
        {
            array = container;
        }
        if (array != null)
        {
            for (JsonNode line : array)
            {
                Map<String, Object> cells = new LinkedHashMap<>();
                putAll(line, cells);
                rows.add(new UdsRow(cells));
            }
        }
        return rows;
    }

    private void putAll(JsonNode node, Map<String, Object> out)
    {
        if (node == null || !node.isObject())
        {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> it = node.properties().iterator();
        while (it.hasNext())
        {
            Map.Entry<String, JsonNode> entry = it.next();
            out.put(entry.getKey(), value(entry.getValue()));
        }
    }

    private Object value(JsonNode node)
    {
        if (node == null || node.isNull() || node.isMissingNode())
        {
            return null;
        }
        if (node.isNumber())
        {
            return node.decimalValue();
        }
        if (node.isBoolean())
        {
            return node.asBoolean();
        }
        return node.asString();
    }

    private JsonNode firstArray(JsonNode node, String... names)
    {
        for (String name : names)
        {
            JsonNode child = node.path(name);
            if (child.isArray())
            {
                return child;
            }
        }
        return null;
    }

    private String firstText(JsonNode node, String... names)
    {
        for (String name : names)
        {
            JsonNode child = node.path(name);
            if (child.isTextual())
            {
                return child.asString();
            }
        }
        return null;
    }

    /**
     * 扫描量预估：{@code POST /v1/query/explain}，请求体与 {@code /v1/query} 相同。
     * <p>只编译查询、不执行明细 SQL；响应根节点带 {@code scanRowsEst}（按底表统计估算，不考虑 filters 选择率）
     * 和 {@code costTier}。非 2xx 按普通查询异常抛出，不能当作 0 行。</p>
     */
    public JsonNode explain(Map<String, Object> body)
    {
        String json;
        try
        {
            json = objectMapper.writeValueAsString(body);
        }
        catch (Exception e)
        {
            throw new UdsQueryException("UDS 请求体序列化失败", 0, e);
        }
        return readJson(post(properties.getExplainPath(), json));
    }

    // ===================== 异步导出作业 =====================

    /** jobId 只允许这些字符，防止拼进路径时被注入 */
    private static final java.util.regex.Pattern JOB_ID = java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{1,80}$");

    /**
     * 提交导出作业：{@code POST /v1/jobs}。
     * <p>
     * 请求体与 {@code /v1/query} 相同，调用方负责把 {@code options.channel} 设为 EXPORT
     * 并给出 {@code options.export}。202 = 新作业，200 = 命中 7 天内的同一份导出（deduplicated=true）。
     */
    public JsonNode submitJob(Map<String, Object> body)
    {
        String json;
        try
        {
            json = objectMapper.writeValueAsString(body);
        }
        catch (Exception e)
        {
            throw new UdsQueryException("UDS 请求体序列化失败", 0, e);
        }
        return readJson(post(properties.getJobsPath(), json));
    }

    /** 查询作业状态：{@code GET /v1/jobs/{jobId}}。DONE 时每次都会现签新的 1 小时预签名链接 */
    public JsonNode getJob(String jobId)
    {
        return readJson(send(HttpRequest.newBuilder(uri(jobPath(jobId))).GET(), null));
    }

    /** 取消作业：{@code DELETE /v1/jobs/{jobId}}。已完成的作业返回 4407 */
    public JsonNode cancelJob(String jobId)
    {
        return readJson(send(HttpRequest.newBuilder(uri(jobPath(jobId))).DELETE(), null));
    }

    private String jobPath(String jobId)
    {
        if (jobId == null || !JOB_ID.matcher(jobId).matches())
        {
            throw new UdsQueryException("非法的作业编号", 0, 4406, null);
        }
        return properties.getJobsPath().replaceAll("/+$", "") + "/" + jobId;
    }

    private JsonNode readJson(String text)
    {
        try
        {
            return objectMapper.readTree(text);
        }
        catch (Exception e)
        {
            throw new UdsQueryException("UDS 响应解析失败：" + e.getMessage(), 200, e);
        }
    }

    // ===================== HTTP =====================

    private String post(String path, String json)
    {
        return send(HttpRequest.newBuilder(uri(path))
            .POST(HttpRequest.BodyPublishers.ofString(json, java.nio.charset.StandardCharsets.UTF_8)), json);
    }

    /** 限流响应里的建议等待时间 */
    private static final java.util.regex.Pattern RETRY_AFTER =
        java.util.regex.Pattern.compile("\"retryAfterMillis\"\\s*:\\s*(\\d+)");

    /**
     * 发送请求；遇到 UDS 限流（HTTP 429 / code=4290）时按 {@code retryAfterMillis} 等待后重试。
     * <p>
     * 限流是按租户、按通道的令牌桶，一次看板请求要并发打好几个查询（主区间、对比期、合计），
     * 很容易在同一瞬间撞上，而平台给的等待时间通常只有几十毫秒——这种失败等一下就能自愈，
     * 不该直接把「数据平台暂时不可用」甩给用户。重试次数与单次等待都有上限，避免拖垮请求。
     */
    private String send(HttpRequest.Builder builder, String body)
    {
        HttpRequest request = withHeaders(builder).build();
        // 每次调用的请求体记 DEBUG：平时不进日志文件，排查时把
        // com.fivetech.dashboard.gateway.uds 调成 debug 即可看到发给 UDS 的完整参数
        if (log.isDebugEnabled())
        {
            log.debug("[uds] {} {} requestId={} body={}", request.method(), request.uri().getPath(),
                requestId(request), body == null ? "-" : abbreviate(body, BODY_LOG_LIMIT));
        }
        int retries = Math.max(0, properties.getRateLimitRetries());
        for (int attempt = 0; ; attempt++)
        {
            try
            {
                return sendOnce(request, body);
            }
            catch (UdsQueryException e)
            {
                boolean throttled = e.getHttpStatus() == 429 || e.getUdsCode() == 4290;
                if (!throttled || attempt >= retries)
                {
                    throw e;
                }
                long wait = e.getRetryAfterMillis() > 0
                    ? Math.min(Math.max(50, properties.getRateLimitMaxWaitMillis()), e.getRetryAfterMillis())
                    : retryAfter(e.getResponseBody(), attempt);
                log.info("[uds] 触发限流，{}ms 后第 {} 次重试 {}", wait, attempt + 1, request.uri().getPath());
                try
                {
                    Thread.sleep(wait);
                }
                catch (InterruptedException ie)
                {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** 等待时间：优先用平台给的 retryAfterMillis，并加一点随机抖动错开并发请求；取不到时按次数指数退避 */
    private long retryAfter(String body, int attempt)
    {
        long base = 100L << Math.min(attempt, 4);
        if (body != null)
        {
            java.util.regex.Matcher m = RETRY_AFTER.matcher(body);
            if (m.find())
            {
                base = Long.parseLong(m.group(1));
            }
        }
        long jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(20, 80);
        long max = Math.max(50, properties.getRateLimitMaxWaitMillis());
        return Math.min(max, Math.max(50, base) + jitter);
    }

    /** 日志里请求体的最大长度，超出截断，避免明细导出等大请求刷屏 */
    private static final int BODY_LOG_LIMIT = 2000;

    private static String requestId(HttpRequest request)
    {
        return request.headers().firstValue("X-Request-Id").orElse("");
    }

    /**
     * UDS 返回错误时把这次的请求体记下来，报错时直接能看到发了什么，不用再复现。
     * <p>4403（数据未就绪）是预期内的：网关会按水位截断后重试，频率高，记 INFO（仍会写进 sys-info.log）；
     * 其余错误记 WARN。</p>
     */
    private void logFailedRequest(HttpRequest request, String body, int http, int udsCode, String responseBody)
    {
        String text = "[uds] 请求失败 {} {} requestId={} http={} udsCode={} response={} body={}";
        Object[] args = { request.method(), request.uri().getPath(), requestId(request), http, udsCode,
            abbreviate(responseBody, 500), body == null ? "-" : abbreviate(body, BODY_LOG_LIMIT) };
        if (udsCode == UdsQueryException.CODE_NO_READY_BINDING)
        {
            log.info(text, args);
        }
        else
        {
            log.warn(text, args);
        }
    }

    private String sendOnce(HttpRequest request, String body)
    {
        long start = System.currentTimeMillis();
        try
        {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long cost = System.currentTimeMillis() - start;
            if (response.statusCode() < 200 || response.statusCode() >= 300)
            {
                // 把响应体里的业务码解出来：4403（数据未就绪）要能和真正的故障区分开，
                // 否则上层只能靠 match 中文错误字符串，改一版文案就失效
                String requestId = requestId(request);
                int udsCode = businessCode(response.body());
                logFailedRequest(request, body, response.statusCode(), udsCode, response.body());
                UdsQueryException ex = new UdsQueryException("UDS 返回 HTTP " + response.statusCode()
                    + " requestId=" + requestId + "：" + abbreviate(response.body()), response.statusCode(),
                    udsCode, null, response.body());
                // 网关限流常只给 Retry-After 头（秒），不一定有 JSON 体
                response.headers().firstValue("Retry-After").ifPresent(v -> {
                    try
                    {
                        ex.withRetryAfterMillis(Math.max(0, Long.parseLong(v.trim())) * 1000L);
                    }
                    catch (NumberFormatException ignore)
                    {
                        // HTTP-date 形式不处理，退回指数退避
                    }
                });
                throw ex;
            }
            log.debug("[uds] {} {}ms", request.uri().getPath(), cost);
            return response.body();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new UdsQueryException("UDS 请求被中断", 0, e);
        }
        catch (UdsQueryException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            // 目标地址只进日志、不进异常消息：异常消息会作为提示返回给前端，不能暴露内网地址
            log.warn("[uds] 请求失败 {} {} requestId={} 耗时 {}ms : {} {} body={}", request.method(), request.uri(),
                requestId(request), System.currentTimeMillis() - start, e.getClass().getSimpleName(), e.getMessage(),
                body == null ? "-" : abbreviate(body, BODY_LOG_LIMIT));
            throw new UdsQueryException("UDS 请求失败：" + e.getMessage(), 0, e);
        }
    }

    private HttpRequest.Builder withHeaders(HttpRequest.Builder builder)
    {
        builder.timeout(Duration.ofSeconds(Math.max(1, properties.getReadTimeoutSeconds())))
            .header("Content-Type", "application/json");
        // 身份头：直连 EC2 本机 8080（本地 SSM 隧道调试）时 UDS 按这三个头识别调用方，必须带；
        // 经 8443 mTLS 网关时身份由客户端证书决定，网关会忽略这些头，带上也无害。配置为空的不发
        header(builder, "X-Uds-Principal", properties.getPrincipal());
        header(builder, "X-Uds-Tenant", properties.getTenant());
        header(builder, "X-Uds-Roles", properties.getRoles());
        header(builder, "X-Uds-Merchant-Codes", properties.getMerchantCodes());
        // X-Request-Id 每次调用唯一（联调手册第 3 节）：一个页面请求会打出多次 UDS 调用，
        // 用「本系统 traceId + 序号后缀」，既能在 UDS 日志里逐条定位，又能按前缀串回同一次页面请求
        String traceId = TraceIdUtils.get();
        String prefix = StringUtils.isEmpty(traceId) ? TraceIdUtils.generate() : traceId;
        builder.header("X-Request-Id", prefix + "-" + Long.toHexString(REQUEST_SEQ.incrementAndGet()));
        return builder;
    }

    private static void header(HttpRequest.Builder builder, String name, String value)
    {
        if (StringUtils.isNotEmpty(value))
        {
            builder.header(name, value.trim());
        }
    }

    /** X-Request-Id 后缀序号 */
    private static final java.util.concurrent.atomic.AtomicLong REQUEST_SEQ = new java.util.concurrent.atomic.AtomicLong();

    private URI uri(String path)
    {
        String base = properties.getBaseUrl();
        if (StringUtils.isEmpty(base))
        {
            throw new UdsQueryException("未配置 dashboard.gateway.uds.base-url", 0);
        }
        return URI.create(base.replaceAll("/+$", "") + path);
    }

    /** 从错误响应体里取业务码；解析不出返回 0 */
    private int businessCode(String body)
    {
        if (body == null || body.isBlank())
        {
            return 0;
        }
        try
        {
            JsonNode code = objectMapper.readTree(body).path("code");
            return code.isNumber() ? code.asInt() : 0;
        }
        catch (Exception e)
        {
            return 0;
        }
    }

    private static String abbreviate(String text, int max)
    {
        if (text == null)
        {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...(共 " + text.length() + " 字符，已截断)";
    }

    private String abbreviate(String text)
    {
        if (text == null)
        {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
