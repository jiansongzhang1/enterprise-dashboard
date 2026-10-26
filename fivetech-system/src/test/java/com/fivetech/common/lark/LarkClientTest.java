package com.fivetech.common.lark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

/**
 * LarkClient：用本机 HttpServer 模拟 Lark 开放平台，覆盖翻页、token 缓存与失效重取、错误分类。
 */
class LarkClientTest
{
    private HttpServer server;

    private String baseUrl;

    /** path → 处理函数（返回 [httpStatus, body]） */
    private final Map<String, Function<HttpExchange, Object[]>> routes = new ConcurrentHashMap<>();

    private final AtomicInteger tokenCalls = new AtomicInteger();

    private final List<String> chatQueries = new ArrayList<>();

    private final List<String> authHeaders = new ArrayList<>();

    @BeforeEach
    void start() throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            Function<HttpExchange, Object[]> h = routes.get(ex.getRequestURI().getPath());
            Object[] r = h == null ? new Object[] { 404, "{\"code\":404,\"msg\":\"no route\"}" } : h.apply(ex);
            byte[] body = ((String) r[1]).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders((Integer) r[0], body.length);
            try (OutputStream os = ex.getResponseBody())
            {
                os.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        routes.put("/open-apis/auth/v3/tenant_access_token/internal", ex -> {
            int n = tokenCalls.incrementAndGet();
            return new Object[] { 200, "{\"code\":0,\"msg\":\"ok\",\"tenant_access_token\":\"t-" + n + "\",\"expire\":7200}" };
        });
    }

    @AfterEach
    void stop()
    {
        server.stop(0);
    }

    private LarkClient client()
    {
        return new LarkClient(JsonMapper.builder().build(), baseUrl + "/", 5);
    }

    @Test
    void listChatsPagesThroughAllAndSkipsDissolved()
    {
        routes.put("/open-apis/im/v1/chats", ex -> {
            String q = ex.getRequestURI().getRawQuery();
            synchronized (chatQueries)
            {
                chatQueries.add(q);
                authHeaders.add(ex.getRequestHeaders().getFirst("Authorization"));
            }
            if (!q.contains("page_token"))
            {
                return new Object[] { 200, "{\"code\":0,\"data\":{\"has_more\":true,\"page_token\":\"p+2/=\",\"items\":["
                        + "{\"chat_id\":\"oc_a\",\"name\":\"金流告警群\",\"chat_status\":\"normal\"},"
                        + "{\"chat_id\":\"oc_b\",\"name\":\"已解散\",\"chat_status\":\"dissolved\"}]}}" };
            }
            return new Object[] { 200, "{\"code\":0,\"data\":{\"has_more\":false,\"page_token\":\"\",\"items\":["
                    + "{\"chat_id\":\"oc_c\",\"name\":\"市場活動群\"},"
                    + "{\"chat_id\":\"oc_a\",\"name\":\"金流告警群\"}]}}" };
        });
        LarkResult<List<LarkChat>> r = client().listChats("cli_x", "s");
        assertTrue(r.isSuccess(), r.toString());
        assertEquals(LarkErrorCategory.OK, r.getCategory());
        assertEquals("[oc_a(金流告警群), oc_c(市場活動群)]", r.getData().toString());
        assertEquals(2, chatQueries.size());
        assertTrue(chatQueries.get(0).contains("page_size=100"), chatQueries.get(0));
        assertTrue(chatQueries.get(1).contains("page_token=p%2B2%2F%3D"), "page_token 要 URL 编码：" + chatQueries.get(1));
        assertEquals("Bearer t-1", authHeaders.get(0));
        assertEquals(1, tokenCalls.get(), "两页共用一个 token");
    }

    @Test
    void tokenIsCachedPerAppId()
    {
        routes.put("/open-apis/im/v1/chats", ex -> new Object[] { 200, "{\"code\":0,\"data\":{\"has_more\":false,\"items\":[]}}" });
        LarkClient c = client();
        assertTrue(c.listChats("cli_x", "s").isSuccess());
        assertTrue(c.listChats("cli_x", "s").isSuccess());
        assertEquals(1, tokenCalls.get());
        assertTrue(c.listChats("cli_y", "s").isSuccess());
        assertEquals(2, tokenCalls.get(), "不同 appId 各自取 token");
    }

    @Test
    void invalidTokenIsRefreshedOnce()
    {
        AtomicInteger calls = new AtomicInteger();
        routes.put("/open-apis/im/v1/chats", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            calls.incrementAndGet();
            if ("Bearer t-1".equals(auth))
            {
                return new Object[] { 400, "{\"code\":99991663,\"msg\":\"Invalid access token for authorization.\"}" };
            }
            return new Object[] { 200, "{\"code\":0,\"data\":{\"has_more\":false,\"items\":[{\"chat_id\":\"oc_a\",\"name\":\"A\"}]}}" };
        });
        LarkResult<List<LarkChat>> r = client().listChats("cli_x", "s");
        assertTrue(r.isSuccess(), r.toString());
        assertEquals(2, tokenCalls.get());
        assertEquals(2, calls.get());
    }

    @Test
    void stillInvalidAfterRefreshIsCredentialInvalid()
    {
        routes.put("/open-apis/im/v1/chats",
                ex -> new Object[] { 400, "{\"code\":99991663,\"msg\":\"Invalid access token for authorization.\"}" });
        LarkResult<List<LarkChat>> r = client().listChats("cli_x", "s");
        assertFalse(r.isSuccess());
        assertEquals(LarkErrorCategory.CREDENTIAL_INVALID, r.getCategory());
        assertEquals(2, tokenCalls.get(), "只重取一次");
    }

    @Test
    void badSecretIsCredentialInvalidTokenFailure()
    {
        routes.put("/open-apis/auth/v3/tenant_access_token/internal",
                ex -> new Object[] { 200, "{\"code\":10014,\"msg\":\"app secret invalid\"}" });
        LarkResult<List<LarkChat>> r = client().listChats("cli_x", "wrong");
        assertFalse(r.isSuccess());
        assertTrue(r.isTokenFailure());
        assertEquals(LarkErrorCategory.CREDENTIAL_INVALID, r.getCategory());
        assertEquals(Integer.valueOf(10014), r.getLarkCode());
        assertEquals("Lark访问令牌获取失败：app secret invalid", r.getMsg());
        assertFalse(r.getMsg().contains("wrong"), "App Secret 不能出现在错误信息里");
    }

    @Test
    void missingCredentialIsCredentialInvalidWithoutHttp()
    {
        LarkResult<List<LarkChat>> r = client().listChats("", null);
        assertEquals(LarkErrorCategory.CREDENTIAL_INVALID, r.getCategory());
        assertEquals(0, tokenCalls.get());
    }

    @Test
    void tokenEndpoint5xxIsRetryable()
    {
        routes.put("/open-apis/auth/v3/tenant_access_token/internal", ex -> new Object[] { 502, "<html>bad gateway</html>" });
        LarkResult<List<LarkChat>> r = client().listChats("cli_x", "s");
        assertEquals(LarkErrorCategory.RETRYABLE, r.getCategory());
        assertTrue(r.isTokenFailure());
        assertNull(r.getLarkCode());
    }

    @Test
    void serverErrorAndRateLimitAreRetryable()
    {
        routes.put("/open-apis/im/v1/chats", ex -> new Object[] { 500, "oops" });
        assertEquals(LarkErrorCategory.RETRYABLE, client().listChats("cli_x", "s").getCategory());
        routes.put("/open-apis/im/v1/chats", ex -> new Object[] { 400, "{\"code\":99991400,\"msg\":\"request trigger frequency limit\"}" });
        assertEquals(LarkErrorCategory.RETRYABLE, client().listChats("cli_x", "s").getCategory());
        routes.put("/open-apis/im/v1/chats", ex -> new Object[] { 400, "{\"code\":99991672,\"msg\":\"Access denied. scope im:chat:readonly\"}" });
        assertEquals(LarkErrorCategory.CREDENTIAL_INVALID, client().listChats("cli_x", "s").getCategory(), "缺少权限是应用级问题");
    }

    @Test
    void networkErrorIsRetryable()
    {
        LarkClient c = new LarkClient(JsonMapper.builder().build(), "http://127.0.0.1:1", 2);
        LarkResult<List<LarkChat>> r = c.listChats("cli_x", "s");
        assertEquals(LarkErrorCategory.RETRYABLE, r.getCategory());
        assertEquals(0, r.getHttpStatus());
    }

    @Test
    void hasMoreWithoutPageTokenStops()
    {
        AtomicInteger calls = new AtomicInteger();
        routes.put("/open-apis/im/v1/chats", ex -> {
            calls.incrementAndGet();
            return new Object[] { 200, "{\"code\":0,\"data\":{\"has_more\":true,\"items\":[{\"chat_id\":\"oc_a\",\"name\":\"A\"}]}}" };
        });
        assertTrue(client().listChats("cli_x", "s").isSuccess());
        assertEquals(1, calls.get());
    }

    @Test
    void endlessPagingIsRefusedNotTruncated()
    {
        routes.put("/open-apis/im/v1/chats",
                ex -> new Object[] { 200, "{\"code\":0,\"data\":{\"has_more\":true,\"page_token\":\"x\",\"items\":[]}}" });
        LarkResult<List<LarkChat>> r = client().listChats("cli_x", "s");
        assertFalse(r.isSuccess(), "列表不完整时不能当成功，否则没拉到的群会被置为失效");
        assertEquals(LarkErrorCategory.RETRYABLE, r.getCategory());
    }

    @Test
    void sendTextReturnsMessageIdAndClassifiesTarget()
    {
        StringBuilder sent = new StringBuilder();
        routes.put("/open-apis/im/v1/messages", ex -> {
            try
            {
                sent.append(ex.getRequestURI().getRawQuery()).append(' ')
                        .append(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            catch (IOException e)
            {
                throw new IllegalStateException(e);
            }
            return new Object[] { 200, "{\"code\":0,\"data\":{\"message_id\":\"om_1\"}}" };
        });
        LarkResult<String> ok = client().sendText("cli_x", "s", "chat_id", "oc_a", "測試 \"引號\"");
        assertTrue(ok.isSuccess(), ok.toString());
        assertEquals("om_1", ok.getData());
        assertTrue(sent.toString().startsWith("receive_id_type=chat_id "), sent.toString());
        assertTrue(sent.toString().contains("\"receive_id\":\"oc_a\""), sent.toString());

        routes.put("/open-apis/im/v1/messages",
                ex -> new Object[] { 400, "{\"code\":230002,\"msg\":\"Bot/User can NOT be out of the chat.\"}" });
        LarkResult<String> gone = client().sendText("cli_x", "s", "chat_id", "oc_a", "x");
        assertEquals(LarkErrorCategory.TARGET_INVALID, gone.getCategory());
        assertEquals(Integer.valueOf(230002), gone.getLarkCode());
        assertTrue(gone.isChatGone(), "机器人被移出群");
    }

    @Test
    void sendClassifiesDissolvedAndMutedChats()
    {
        Object[][] cases = {
            { 232009, LarkErrorCategory.TARGET_INVALID, true },   // 群已解散
            { 230034, LarkErrorCategory.TARGET_INVALID, true },   // chat_id 无效
            { 230035, LarkErrorCategory.TARGET_INVALID, false },  // 没有发言权限（被禁言）：群还在
            { 230018, LarkErrorCategory.TARGET_INVALID, false },  // 群设置不允许
            { 230027, LarkErrorCategory.TARGET_INVALID, false },  // 缺少必要权限（外部群等）
            { 230006, LarkErrorCategory.CREDENTIAL_INVALID, false }, // 机器人能力未开启
            { 230020, LarkErrorCategory.RETRYABLE, false },       // 发送频率限制
            { 230049, LarkErrorCategory.RETRYABLE, false },       // 消息正在发送（未列出的错误码按可重试）
        };
        for (Object[] c : cases)
        {
            int code = (Integer) c[0];
            routes.put("/open-apis/im/v1/messages", ex -> new Object[] { 400, "{\"code\":" + code + ",\"msg\":\"x\"}" });
            LarkResult<String> r = client().sendText("cli_x", "s", "chat_id", "oc_a", "x");
            assertEquals(c[1], r.getCategory(), "code " + code);
            assertEquals(c[2], r.isChatGone(), "code " + code + " isChatGone");
        }
    }
}
