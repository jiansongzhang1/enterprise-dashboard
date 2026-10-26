package com.fivetech.common.lark;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Lark 开放平台客户端（自建应用机器人）。
 *
 * <p>方法都以 appId + appSecret 为参数，可以同时服务多个机器人；不抛异常，失败返回带 Lark 错误码和分类的
 * {@link LarkResult}，由调用方按 {@link LarkErrorCategory} 决定怎么处理。</p>
 *
 * <p>tenant_access_token 按 appId 缓存：本机内存 + Redis（{@code lark:token:{appId}}，TTL = 过期时间 − 5 分钟），
 * Redis 不可用时只用本机缓存。收到 token 失效类错误码时删掉缓存、重取一次再调。</p>
 *
 * <p>放在 common 而不是 framework：监控模块在 system 里，framework 依赖 system，放 framework 会循环依赖。
 * App Secret 只作为参数传入，不写日志、不进缓存 key。</p>
 *
 * @author fivetech
 */
@Component
public class LarkClient
{
    private static final Logger log = LoggerFactory.getLogger(LarkClient.class);

    static final String TOKEN_KEY_PREFIX = "lark:token:";

    /** im/v1/chats 单页上限 */
    static final int PAGE_SIZE = 100;

    /** 最多翻 50 页（5000 个群），防止 page_token 异常时死循环 */
    static final int MAX_PAGES = 50;

    private final ObjectMapper objectMapper;

    private final ObjectProvider<StringRedisTemplate> redisProvider;

    private final String baseUrl;

    private final Duration timeout;

    private final HttpClient httpClient;

    private final Map<String, CachedToken> localTokens = new ConcurrentHashMap<>();

    private final Map<String, Object> tokenLocks = new ConcurrentHashMap<>();

    @Autowired
    public LarkClient(ObjectMapper objectMapper, ObjectProvider<StringRedisTemplate> redisProvider,
            @Value("${notification.lark.api-base-url:https://open.larksuite.com}") String baseUrl,
            @Value("${notification.lark.timeout-seconds:10}") int timeoutSeconds)
    {
        this.objectMapper = objectMapper;
        this.redisProvider = redisProvider;
        this.baseUrl = (baseUrl == null || baseUrl.isBlank() ? "https://open.larksuite.com" : baseUrl).replaceAll("/+$", "");
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    /** 不用 Redis 的构造（测试、脚本用） */
    public LarkClient(ObjectMapper objectMapper, String baseUrl, int timeoutSeconds)
    {
        this(objectMapper, null, baseUrl, timeoutSeconds);
    }

    // ------------------------------------------------------------------ 对外方法

    /**
     * 取 tenant_access_token（优先用缓存）。
     * 失败时 tokenFailure = true：超时、5xx、限流为 RETRYABLE，其余（App ID / Secret 错误、应用停用）为 CREDENTIAL_INVALID。
     */
    public LarkResult<String> getToken(String appId, String appSecret)
    {
        if (isBlank(appId) || isBlank(appSecret))
        {
            return LarkResult.tokenFail(LarkErrorCategory.CREDENTIAL_INVALID, null, "Lark App ID / App Secret 未配置", 0);
        }
        String cached = cachedToken(appId);
        if (cached != null)
        {
            return LarkResult.ok(cached, 200);
        }
        synchronized (tokenLocks.computeIfAbsent(appId, k -> new Object()))
        {
            cached = cachedToken(appId);
            if (cached != null)
            {
                return LarkResult.ok(cached, 200);
            }
            return fetchToken(appId, appSecret);
        }
    }

    /**
     * 列出机器人所在的全部群（翻完所有页，page_size 100）。机器人刚进群、群里没人发过消息也能列出。
     * 已解散的群（chat_status = dissolved / dissolved_save）不返回；同一个 chat_id 只保留一次。
     */
    public LarkResult<List<LarkChat>> listChats(String appId, String appSecret)
    {
        List<LarkChat> chats = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String pageToken = null;
        for (int page = 0; page < MAX_PAGES; page++)
        {
            StringBuilder url = new StringBuilder(baseUrl).append("/open-apis/im/v1/chats?page_size=").append(PAGE_SIZE);
            if (pageToken != null)
            {
                url.append("&page_token=").append(URLEncoder.encode(pageToken, StandardCharsets.UTF_8));
            }
            String uri = url.toString();
            LarkResult<JsonNode> r = call(appId, appSecret, token -> HttpRequest.newBuilder(URI.create(uri))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build());
            if (!r.isSuccess())
            {
                return r.castFailure();
            }
            JsonNode data = r.getData().path("data");
            for (JsonNode item : data.path("items"))
            {
                String chatId = item.path("chat_id").asText("");
                String status = item.path("chat_status").asText("");
                if (chatId.isEmpty() || status.startsWith("dissolved") || !seen.add(chatId))
                {
                    continue;
                }
                chats.add(new LarkChat(chatId, item.path("name").asText("")));
            }
            String next = data.path("page_token").asText("");
            if (!data.path("has_more").asBoolean(false) || next.isEmpty())
            {
                return LarkResult.ok(chats, r.getHttpStatus());
            }
            pageToken = next;
        }
        log.warn("Lark 群列表超过 {} 页，未拉取完整 appId={}", MAX_PAGES, appId);
        // 不能返回不完整的列表：调用方会把没拉到的群置为失效
        return LarkResult.fail(LarkErrorCategory.RETRYABLE, null, "群數量超過 " + (MAX_PAGES * PAGE_SIZE) + "，未拉取完整", 200);
    }

    /**
     * 发送文本消息。
     *
     * @param receiveIdType chat_id / open_id 等
     * @return 成功时 data 为 message_id
     */
    public LarkResult<String> sendText(String appId, String appSecret, String receiveIdType, String receiveId, String text)
    {
        String body;
        try
        {
            Map<String, Object> content = new HashMap<>();
            content.put("text", text);
            Map<String, Object> payload = new HashMap<>();
            payload.put("receive_id", receiveId);
            payload.put("msg_type", "text");
            payload.put("content", objectMapper.writeValueAsString(content));
            body = objectMapper.writeValueAsString(payload);
        }
        catch (RuntimeException e)
        {
            return LarkResult.fail(LarkErrorCategory.RETRYABLE, null, "消息序列化失败：" + e.getMessage(), 0);
        }
        URI uri = URI.create(baseUrl + "/open-apis/im/v1/messages?receive_id_type="
                + URLEncoder.encode(receiveIdType, StandardCharsets.UTF_8));
        LarkResult<JsonNode> r = call(appId, appSecret, token -> HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
        if (!r.isSuccess())
        {
            return r.castFailure();
        }
        return LarkResult.ok(r.getData().path("data").path("message_id").asText(""), r.getHttpStatus());
    }

    /**
     * 发送富文本消息（msg_type = post），用于文本里带可点击链接的告警。
     *
     * @param paragraphs 每段若干元素：{"tag":"text","text":…} 或 {"tag":"a","text":…,"href":…}
     * @return 成功时 data 为 message_id
     */
    public LarkResult<String> sendPost(String appId, String appSecret, String receiveIdType, String receiveId,
            String title, List<List<Map<String, String>>> paragraphs)
    {
        String body;
        try
        {
            Map<String, Object> post = new HashMap<>();
            post.put("title", title == null ? "" : title);
            post.put("content", paragraphs);
            Map<String, Object> content = new HashMap<>();
            content.put("zh_cn", post);
            Map<String, Object> payload = new HashMap<>();
            payload.put("receive_id", receiveId);
            payload.put("msg_type", "post");
            payload.put("content", objectMapper.writeValueAsString(content));
            body = objectMapper.writeValueAsString(payload);
        }
        catch (RuntimeException e)
        {
            return LarkResult.fail(LarkErrorCategory.RETRYABLE, null, "消息序列化失败：" + e.getMessage(), 0);
        }
        URI uri = URI.create(baseUrl + "/open-apis/im/v1/messages?receive_id_type="
                + URLEncoder.encode(receiveIdType, StandardCharsets.UTF_8));
        LarkResult<JsonNode> r = call(appId, appSecret, token -> HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
        if (!r.isSuccess())
        {
            return r.castFailure();
        }
        return LarkResult.ok(r.getData().path("data").path("message_id").asText(""), r.getHttpStatus());
    }

    /**
     * 发送卡片消息（msg_type = interactive）。
     *
     * @param card 卡片 JSON（config / header / elements）
     * @return 成功时 data 为 message_id
     */
    public LarkResult<String> sendCard(String appId, String appSecret, String receiveIdType, String receiveId,
            Map<String, Object> card)
    {
        String body;
        try
        {
            Map<String, Object> payload = new HashMap<>();
            payload.put("receive_id", receiveId);
            payload.put("msg_type", "interactive");
            payload.put("content", objectMapper.writeValueAsString(card));
            body = objectMapper.writeValueAsString(payload);
        }
        catch (RuntimeException e)
        {
            return LarkResult.fail(LarkErrorCategory.RETRYABLE, null, "消息序列化失败：" + e.getMessage(), 0);
        }
        URI uri = URI.create(baseUrl + "/open-apis/im/v1/messages?receive_id_type="
                + URLEncoder.encode(receiveIdType, StandardCharsets.UTF_8));
        LarkResult<JsonNode> r = call(appId, appSecret, token -> HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
        if (!r.isSuccess())
        {
            return r.castFailure();
        }
        return LarkResult.ok(r.getData().path("data").path("message_id").asText(""), r.getHttpStatus());
    }

    /**
     * 机器人名称（GET /open-apis/bot/v3/info 的 bot.app_name）。
     *
     * @return 成功时 data 为名称（Lark 没返回时为空串）
     */
    public LarkResult<String> getBotName(String appId, String appSecret)
    {
        URI uri = URI.create(baseUrl + "/open-apis/bot/v3/info");
        LarkResult<JsonNode> r = call(appId, appSecret, token -> HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .GET()
                .build());
        if (!r.isSuccess())
        {
            return r.castFailure();
        }
        return LarkResult.ok(r.getData().path("bot").path("app_name").asText(""), r.getHttpStatus());
    }

    // ------------------------------------------------------------------ 调用与分类

    /** 带 token 调一次；token 失效类错误码时删缓存、重取一次 token 再调 */
    private LarkResult<JsonNode> call(String appId, String appSecret, Function<String, HttpRequest> request)
    {
        LarkResult<String> token = getToken(appId, appSecret);
        if (!token.isSuccess())
        {
            return token.castFailure();
        }
        LarkResult<JsonNode> r = exchange(request.apply(token.getData()));
        if (!r.isSuccess() && LarkErrorCodes.isTokenInvalid(r.getHttpStatus(), r.getLarkCode()))
        {
            log.info("Lark token 失效（code={}），重取后重试 appId={}", r.getLarkCode(), appId);
            invalidateToken(appId, token.getData());
            token = getToken(appId, appSecret);
            if (!token.isSuccess())
            {
                return token.castFailure();
            }
            r = exchange(request.apply(token.getData()));
        }
        if (!r.isSuccess())
        {
            log.warn("Lark 调用失败 {} {} → {}", r.getCategory(), request.apply("***").uri().getPath(), r);
        }
        return r;
    }

    private LarkResult<JsonNode> exchange(HttpRequest request)
    {
        HttpResponse<String> response;
        try
        {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        }
        catch (IOException e)
        {
            return LarkResult.fail(LarkErrorCategory.RETRYABLE, null, "網絡錯誤：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage()), 0);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return LarkResult.fail(LarkErrorCategory.RETRYABLE, null, "請求被中斷", 0);
        }
        JsonNode node = parse(response.body());
        Integer code = larkCode(node);
        LarkErrorCategory category = LarkErrorCodes.classify(response.statusCode(), code);
        if (category == LarkErrorCategory.OK)
        {
            return LarkResult.ok(node, response.statusCode());
        }
        return LarkResult.fail(category, code, message(node, response.body()), response.statusCode());
    }

    // ------------------------------------------------------------------ token 缓存

    private LarkResult<String> fetchToken(String appId, String appSecret)
    {
        String body;
        try
        {
            Map<String, String> payload = new HashMap<>();
            payload.put("app_id", appId);
            payload.put("app_secret", appSecret);
            body = objectMapper.writeValueAsString(payload);
        }
        catch (RuntimeException e)
        {
            return LarkResult.tokenFail(LarkErrorCategory.RETRYABLE, null, "Lark访问令牌获取失败：请求序列化失败", 0);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/open-apis/auth/v3/tenant_access_token/internal"))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response;
        try
        {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        }
        catch (IOException e)
        {
            return LarkResult.tokenFail(LarkErrorCategory.RETRYABLE, null,
                    "Lark访问令牌获取失败：" + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage()), 0);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return LarkResult.tokenFail(LarkErrorCategory.RETRYABLE, null, "Lark访问令牌获取失败：请求被中断", 0);
        }
        JsonNode node = parse(response.body());
        Integer code = larkCode(node);
        String token = node.path("tenant_access_token").asText("");
        int status = response.statusCode();
        if (status >= 200 && status < 300 && code != null && code == 0 && !token.isEmpty())
        {
            long expire = Math.max(60, node.path("expire").asLong(7200));
            long ttl = Math.max(30, expire - 300);
            localTokens.put(appId, new CachedToken(token, System.currentTimeMillis() + ttl * 1000));
            StringRedisTemplate redis = redis();
            if (redis != null)
            {
                try
                {
                    redis.opsForValue().set(TOKEN_KEY_PREFIX + appId, token, ttl, TimeUnit.SECONDS);
                }
                catch (RuntimeException e)
                {
                    log.warn("Lark token 写入 Redis 失败，只用本机缓存：{}", e.getMessage());
                }
            }
            return LarkResult.ok(token, status);
        }
        boolean retryable = status == 429 || status >= 500 || (code != null && LarkErrorCodes.RATE_LIMIT.contains(code));
        LarkErrorCategory category = retryable ? LarkErrorCategory.RETRYABLE : LarkErrorCategory.CREDENTIAL_INVALID;
        log.warn("Lark token 获取失败 appId={} http={} code={} category={}", appId, status, code, category);
        return LarkResult.tokenFail(category, code, "Lark访问令牌获取失败：" + message(node, response.body()), status);
    }

    private String cachedToken(String appId)
    {
        CachedToken local = localTokens.get(appId);
        if (local != null && local.expireAt > System.currentTimeMillis())
        {
            return local.token;
        }
        StringRedisTemplate redis = redis();
        if (redis == null)
        {
            return null;
        }
        try
        {
            String token = redis.opsForValue().get(TOKEN_KEY_PREFIX + appId);
            if (!isBlank(token))
            {
                Long ttl = redis.getExpire(TOKEN_KEY_PREFIX + appId, TimeUnit.SECONDS);
                long seconds = ttl == null || ttl <= 0 ? 60 : ttl;
                localTokens.put(appId, new CachedToken(token, System.currentTimeMillis() + seconds * 1000));
                return token;
            }
        }
        catch (RuntimeException e)
        {
            log.warn("读取 Redis 里的 Lark token 失败：{}", e.getMessage());
        }
        return null;
    }

    /** 只删和失效 token 相同的缓存，避免把别的线程刚取到的新 token 删掉 */
    private void invalidateToken(String appId, String badToken)
    {
        CachedToken local = localTokens.get(appId);
        if (local != null && local.token.equals(badToken))
        {
            localTokens.remove(appId, local);
        }
        StringRedisTemplate redis = redis();
        if (redis == null)
        {
            return;
        }
        try
        {
            if (badToken.equals(redis.opsForValue().get(TOKEN_KEY_PREFIX + appId)))
            {
                redis.delete(TOKEN_KEY_PREFIX + appId);
            }
        }
        catch (RuntimeException e)
        {
            log.warn("删除 Redis 里的 Lark token 失败：{}", e.getMessage());
        }
    }

    private StringRedisTemplate redis()
    {
        if (redisProvider == null)
        {
            return null;
        }
        try
        {
            return redisProvider.getIfAvailable();
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    // ------------------------------------------------------------------ 工具

    private JsonNode parse(String body)
    {
        try
        {
            JsonNode node = body == null || body.isBlank() ? null : objectMapper.readTree(body);
            return node == null ? objectMapper.createObjectNode() : node;
        }
        catch (RuntimeException e)
        {
            return objectMapper.createObjectNode();
        }
    }

    private static Integer larkCode(JsonNode node)
    {
        JsonNode code = node.path("code");
        return code.isNumber() ? Integer.valueOf(code.asInt()) : null;
    }

    private static String message(JsonNode node, String body)
    {
        String msg = node.path("msg").asText("");
        if (!msg.isEmpty())
        {
            return msg;
        }
        if (body == null || body.isBlank())
        {
            return "空響應";
        }
        return body.length() > 200 ? body.substring(0, 200) + "…" : body;
    }

    private static boolean isBlank(String s)
    {
        return s == null || s.isBlank();
    }

    private static final class CachedToken
    {
        private final String token;

        private final long expireAt;

        private CachedToken(String token, long expireAt)
        {
            this.token = token;
            this.expireAt = expireAt;
        }
    }
}
