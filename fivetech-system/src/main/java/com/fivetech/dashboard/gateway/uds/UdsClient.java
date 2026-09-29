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
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getConnectTimeoutSeconds())))
            .build();
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
        String response = post(properties.getQueryPath(), json);
        JsonNode root;
        try
        {
            root = objectMapper.readTree(response);
        }
        catch (Exception e)
        {
            throw new UdsQueryException("UDS 响应解析失败：" + e.getMessage(), 200, e);
        }
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

    // ===================== HTTP =====================

    private String post(String path, String json)
    {
        return send(HttpRequest.newBuilder(uri(path))
            .POST(HttpRequest.BodyPublishers.ofString(json, java.nio.charset.StandardCharsets.UTF_8)));
    }

    private String send(HttpRequest.Builder builder)
    {
        HttpRequest request = withHeaders(builder).build();
        long start = System.currentTimeMillis();
        try
        {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long cost = System.currentTimeMillis() - start;
            if (response.statusCode() < 200 || response.statusCode() >= 300)
            {
                // 把响应体里的业务码解出来：4403（数据未就绪）要能和真正的故障区分开，
                // 否则上层只能靠 match 中文错误字符串，改一版文案就失效
                throw new UdsQueryException("UDS 返回 HTTP " + response.statusCode()
                    + "：" + abbreviate(response.body()), response.statusCode(),
                    businessCode(response.body()), null);
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
            throw new UdsQueryException("UDS 请求失败：" + e.getMessage(), 0, e);
        }
    }

    private HttpRequest.Builder withHeaders(HttpRequest.Builder builder)
    {
        builder.timeout(Duration.ofSeconds(Math.max(1, properties.getReadTimeoutSeconds())))
            .header("Content-Type", "application/json")
            .header("X-Uds-Principal", properties.getPrincipal())
            .header("X-Uds-Tenant", properties.getTenant());
        if (StringUtils.isNotEmpty(properties.getRoles()))
        {
            builder.header("X-Uds-Roles", properties.getRoles());
        }
        if (StringUtils.isNotEmpty(properties.getMerchantCodes()))
        {
            builder.header("X-Uds-Merchant-Codes", properties.getMerchantCodes());
        }
        // 用本系统的 traceId 作为 X-Request-Id，一次慢查询两边日志能对上
        String traceId = TraceIdUtils.get();
        builder.header("X-Request-Id", StringUtils.isEmpty(traceId) ? TraceIdUtils.generate() : traceId);
        return builder;
    }

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

    private String abbreviate(String text)
    {
        if (text == null)
        {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
