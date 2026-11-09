package com.fivetech.dashboard.subscription.snapshot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Chrome DevTools Protocol 的最小客户端：一条到浏览器的 WebSocket（flatten 模式，页面会话用 sessionId 区分），
 * 只用 JDK 自带的 {@link java.net.http.WebSocket}，不引第三方浏览器驱动。
 *
 * <p>线程安全：发送串行化（WebSocket 同一时间只能有一个未完成的发送）；回包按 id 完成对应的 future，
 * 事件按 sessionId 分发给监听器（在 WebSocket 的回调线程里调用，监听器要快）。</p>
 *
 * @author fivetech
 */
final class Cdp implements WebSocket.Listener, AutoCloseable
{
    /** CDP 调用出错（返回 error、超时、连接断开） */
    static final class CdpException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        CdpException(String message)
        {
            super(message);
        }

        CdpException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }

    private final ObjectMapper json;

    private final AtomicLong ids = new AtomicLong();

    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    /** sessionId（浏览器级事件为 ""）→ 监听器 */
    private final Map<String, List<Consumer<JsonNode>>> listeners = new ConcurrentHashMap<>();

    private final StringBuilder partial = new StringBuilder();

    private volatile WebSocket socket;

    private volatile boolean closed;

    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

    private Cdp(ObjectMapper json)
    {
        this.json = json;
    }

    /** 连到浏览器的 DevTools WebSocket（ws://127.0.0.1:端口/devtools/browser/…） */
    static Cdp connect(String wsUrl, ObjectMapper json, Duration timeout)
    {
        Cdp cdp = new Cdp(json);
        try
        {
            cdp.socket = HttpClient.newBuilder().connectTimeout(timeout).build().newWebSocketBuilder()
                .connectTimeout(timeout)
                .buildAsync(URI.create(wsUrl), cdp)
                .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (Exception e)
        {
            throw new CdpException("連不上瀏覽器的 DevTools：" + e.getMessage(), e);
        }
        return cdp;
    }

    boolean isOpen()
    {
        return !closed && socket != null && !socket.isOutputClosed() && !socket.isInputClosed();
    }

    /**
     * 调一个方法并等结果。
     *
     * @param sessionId 页面会话；浏览器级方法传 null
     * @return result 对象
     */
    JsonNode call(String method, Map<String, ?> params, String sessionId, Duration timeout)
    {
        if (!isOpen())
        {
            throw new CdpException("瀏覽器連接已關閉");
        }
        long id = ids.incrementAndGet();
        ObjectNode msg = json.createObjectNode();
        msg.put("id", id);
        msg.put("method", method);
        msg.set("params", params == null ? json.createObjectNode() : json.valueToTree(params));
        if (sessionId != null)
        {
            msg.put("sessionId", sessionId);
        }
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pending.put(id, f);
        String text = msg.toString();
        synchronized (this)
        {
            sendChain = sendChain.handle((r, e) -> null).thenCompose(x -> socket.sendText(text, true));
        }
        try
        {
            JsonNode reply = f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            JsonNode error = reply.get("error");
            if (error != null && !error.isNull())
            {
                throw new CdpException(method + " 失敗：" + error.path("message").asString(error.toString()));
            }
            return reply.path("result");
        }
        catch (TimeoutException e)
        {
            throw new CdpException(method + " 超時（" + timeout.toSeconds() + " 秒）");
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new CdpException(method + " 被中斷");
        }
        catch (ExecutionException e)
        {
            throw new CdpException(method + " 失敗：" + e.getCause().getMessage(), e.getCause());
        }
        finally
        {
            pending.remove(id);
        }
    }

    /** 监听某个会话的事件（消息里有 method 与 params）；sessionId 为 null 监听浏览器级事件 */
    void on(String sessionId, Consumer<JsonNode> listener)
    {
        listeners.computeIfAbsent(sessionId == null ? "" : sessionId, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /** 会话结束后去掉它的监听器 */
    void off(String sessionId)
    {
        if (sessionId != null)
        {
            listeners.remove(sessionId);
        }
    }

    // ------------------------------------------------------------------ WebSocket.Listener

    @Override
    public void onOpen(WebSocket webSocket)
    {
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last)
    {
        partial.append(data);
        if (last)
        {
            String text = partial.toString();
            partial.setLength(0);
            dispatch(text);
        }
        webSocket.request(1);
        return null;
    }

    private void dispatch(String text)
    {
        JsonNode msg;
        try
        {
            msg = json.readTree(text);
        }
        catch (RuntimeException e)
        {
            return;
        }
        JsonNode id = msg.get("id");
        if (id != null && id.isNumber())
        {
            CompletableFuture<JsonNode> f = pending.get(id.asLong());
            if (f != null)
            {
                f.complete(msg);
            }
            return;
        }
        String session = msg.path("sessionId").asString("");
        List<Consumer<JsonNode>> ls = listeners.get(session);
        if (ls != null)
        {
            for (Consumer<JsonNode> l : ls)
            {
                try
                {
                    l.accept(msg);
                }
                catch (RuntimeException ignored)
                {
                    // 监听器异常不影响其他事件
                }
            }
        }
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason)
    {
        failAll("瀏覽器連接已關閉（" + statusCode + "）");
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error)
    {
        failAll("瀏覽器連接出錯：" + error.getMessage());
    }

    private void failAll(String message)
    {
        closed = true;
        pending.values().forEach(f -> f.completeExceptionally(new CdpException(message)));
        pending.clear();
    }

    @Override
    public void close()
    {
        closed = true;
        WebSocket ws = socket;
        if (ws != null)
        {
            try
            {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(2, TimeUnit.SECONDS);
            }
            catch (Exception ignored)
            {
                ws.abort();
            }
        }
        failAll("瀏覽器連接已關閉");
    }
}
