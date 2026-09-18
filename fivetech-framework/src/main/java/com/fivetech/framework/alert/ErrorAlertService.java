package com.fivetech.framework.alert;

import java.net.InetAddress;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.framework.notification.TelegramNotificationService;

/**
 * 系统错误告警服务：推送到 Telegram。
 * <p>
 * 三条约束贯穿实现，改动时不要绕过：
 * <ol>
 *   <li><b>异步且可丢弃</b>：发送在独立单线程 + 有界队列里完成，队列满直接丢，
 *       绝不阻塞业务线程；通知通道挂掉不影响任何接口。</li>
 *   <li><b>去重限流</b>：按异常指纹做静默窗口，再加一层全局每分钟上限。
 *       没有这层，一个循环里的异常能在半分钟内发出几千条消息。</li>
 *   <li><b>不自我递归</b>：告警自身失败只写 warn 日志，不再触发告警。</li>
 * </ol>
 *
 * @author fivetech
 */
@Service
public class ErrorAlertService
{
    private static final Logger log = LoggerFactory.getLogger(ErrorAlertService.class);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Telegram 单条消息上限 4096 字符，留出余量 */
    private static final int MAX_MESSAGE_LENGTH = 3500;

    private final AlertProperties properties;

    private final TelegramNotificationService telegram;

    /** Redis 可能不可用，用 ObjectProvider 取，取不到就退化为进程内去重 */
    private final ObjectProvider<StringRedisTemplate> redisProvider;

    private final ThreadPoolExecutor sender;

    /** Redis 不可用时的兜底：进程内指纹 -> 上次发送时间戳 */
    private final Map<String, Long> localGate = new ConcurrentHashMap<>();

    private final String instanceId;

    public ErrorAlertService(AlertProperties properties, TelegramNotificationService telegram,
            ObjectProvider<StringRedisTemplate> redisProvider)
    {
        this.properties = properties;
        this.telegram = telegram;
        this.redisProvider = redisProvider;
        this.instanceId = resolveInstanceId(properties);
        this.sender = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(Math.max(10, properties.getQueueCapacity())),
            runnable -> {
                Thread thread = new Thread(runnable, "error-alert-sender");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.DiscardPolicy());
    }

    /**
     * 提交一条告警。方法本身不做任何网络调用，立即返回。
     */
    public void alert(AlertContext context)
    {
        if (!properties.isEnabled() || context == null || context.getThrowable() == null)
        {
            return;
        }
        if (isIgnored(context.getThrowable()))
        {
            return;
        }
        try
        {
            sender.execute(() -> deliver(context));
        }
        catch (RejectedExecutionException ignored)
        {
            // 队列满，丢弃。异常本身已经写进 sys-error.log，不会丢信息
        }
    }

    /**
     * 便捷入口：后台任务直接用
     */
    public void alert(AlertLevel level, String title, String source, Throwable throwable)
    {
        alert(AlertContext.of(level, title, throwable)
            .setUri(source)
            .setTraceId(com.fivetech.common.utils.TraceIdUtils.get()));
    }

    private void deliver(AlertContext context)
    {
        try
        {
            String fingerprint = fingerprint(context);
            long suppressed = acquire(fingerprint);
            if (suppressed < 0)
            {
                return;   // 静默窗口内，或超过全局限流
            }
            String message = render(context, suppressed);
            for (String chatId : properties.getTelegramChatIds())
            {
                if (StringUtils.isNotEmpty(chatId))
                {
                    // 不传 parseMode：堆栈里的下划线、星号会让 Markdown 解析直接失败
                    telegram.send(chatId.trim(), message, null);
                }
            }
        }
        catch (Exception e)
        {
            // 告警自身失败只记日志，绝不再触发一次告警，否则会无限递归
            log.warn("系统错误告警发送失败: {}", e.getMessage());
        }
    }

    /**
     * 判断是否允许发送。
     *
     * @return 小于 0 表示不发送；大于等于 0 表示发送，值为自上次发送以来被静默的次数
     */
    private long acquire(String fingerprint)
    {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null)
        {
            return acquireLocally(fingerprint);
        }
        try
        {
            String countKey = "alert:fp:" + fingerprint + ":count";
            Long total = redis.opsForValue().increment(countKey);
            if (total != null && total == 1L)
            {
                redis.expire(countKey, Duration.ofSeconds(properties.getDedupWindowSeconds() * 3L));
            }

            // 静默闸门：setIfAbsent 成功即代表本窗口首次
            String gateKey = "alert:fp:" + fingerprint + ":gate";
            Boolean first = redis.opsForValue()
                .setIfAbsent(gateKey, "1", Duration.ofSeconds(properties.getDedupWindowSeconds()));
            if (!Boolean.TRUE.equals(first))
            {
                return -1;
            }

            if (!allowByRate(redis))
            {
                return -1;
            }

            // 本次要发送，把累计次数取出来并归零，消息里报告被静默了多少次
            String consumed = redis.opsForValue().getAndSet(countKey, "0");
            long times = parseLong(consumed);
            return Math.max(0L, times - 1);
        }
        catch (Exception e)
        {
            log.warn("告警去重读写 Redis 失败，退化为进程内去重: {}", e.getMessage());
            return acquireLocally(fingerprint);
        }
    }

    private boolean allowByRate(StringRedisTemplate redis)
    {
        String rateKey = "alert:rate:" + (System.currentTimeMillis() / 60000L);
        Long sent = redis.opsForValue().increment(rateKey);
        if (sent != null && sent == 1L)
        {
            redis.expire(rateKey, Duration.ofMinutes(2));
        }
        return sent == null || sent <= properties.getRatePerMinute();
    }

    private long acquireLocally(String fingerprint)
    {
        long now = System.currentTimeMillis();
        long window = properties.getDedupWindowSeconds() * 1000L;
        // 顺手清理过期条目，避免 map 无限增长
        localGate.entrySet().removeIf(entry -> now - entry.getValue() > window * 3);
        Long last = localGate.get(fingerprint);
        if (last != null && now - last < window)
        {
            return -1;
        }
        localGate.put(fingerprint, now);
        return 0;
    }

    /**
     * 异常指纹 = 接口 + 异常类 + 堆栈首行。
     * 同一个 bug 的反复触发会落到同一指纹，从而只推一条。
     */
    private String fingerprint(AlertContext context)
    {
        Throwable root = rootCause(context.getThrowable());
        StackTraceElement[] stack = root.getStackTrace();
        String top = (stack != null && stack.length > 0) ? stack[0].toString() : "unknown";
        String raw = StringUtils.nvl(context.getUri(), "-") + "|" + root.getClass().getName() + "|" + top;
        return Integer.toHexString(raw.hashCode());
    }

    private String render(AlertContext context, long suppressed)
    {
        Throwable throwable = context.getThrowable();
        Throwable root = rootCause(throwable);
        StringBuilder sb = new StringBuilder(512);
        sb.append('[').append(context.getLevel().name()).append(' ')
          .append(context.getLevel().getLabel()).append("] ")
          .append(StringUtils.nvl(context.getTitle(), "系统异常")).append('\n');
        sb.append("环境: ").append(properties.getEnv()).append(" / ").append(instanceId).append('\n');
        sb.append("时间: ").append(LocalDateTime.now().format(TIME)).append('\n');
        sb.append("位置: ").append(StringUtils.nvl(context.getMethod(), "")).append(' ')
          .append(StringUtils.nvl(context.getUri(), "-")).append('\n');
        sb.append("用户: ").append(StringUtils.nvl(context.getUsername(), "-"));
        if (StringUtils.isNotEmpty(context.getClientIp()))
        {
            sb.append(" @ ").append(context.getClientIp());
        }
        sb.append('\n');
        sb.append("追踪号: ").append(StringUtils.nvl(context.getTraceId(), "-")).append('\n');
        sb.append("异常: ").append(root.getClass().getSimpleName()).append(": ")
          .append(StringUtils.nvl(root.getMessage(), "(无消息)")).append('\n');
        if (root != throwable)
        {
            sb.append("外层: ").append(throwable.getClass().getSimpleName()).append('\n');
        }
        if (suppressed > 0)
        {
            sb.append("注意: 自上次推送以来该错误已累计 ").append(suppressed + 1).append(" 次\n");
        }
        sb.append("静默: ").append(properties.getDedupWindowSeconds()).append("s 内相同错误不再推送\n");
        StackTraceElement[] stack = root.getStackTrace();
        if (stack != null && stack.length > 0)
        {
            sb.append("堆栈:\n");
            int lines = Math.min(properties.getStackLines(), stack.length);
            for (int i = 0; i < lines; i++)
            {
                sb.append("  at ").append(stack[i]).append('\n');
            }
            if (stack.length > lines)
            {
                sb.append("  ... 其余 ").append(stack.length - lines).append(" 行见日志\n");
            }
        }
        String message = sb.toString();
        // Telegram 超长会整条发送失败，宁可截断也要发出去
        return message.length() > MAX_MESSAGE_LENGTH
            ? message.substring(0, MAX_MESSAGE_LENGTH) + "\n...(已截断)"
            : message;
    }

    private boolean isIgnored(Throwable throwable)
    {
        Throwable root = rootCause(throwable);
        for (String ignored : properties.getIgnoredExceptions())
        {
            if (StringUtils.isEmpty(ignored))
            {
                continue;
            }
            String name = ignored.trim();
            if (root.getClass().getName().equals(name) || root.getClass().getSimpleName().equals(name)
                || throwable.getClass().getName().equals(name) || throwable.getClass().getSimpleName().equals(name))
            {
                return true;
            }
        }
        // 客户端主动断开（用户关页面、刷新）不是我们的故障
        String message = StringUtils.nvl(root.getMessage(), "");
        return message.contains("Broken pipe") || message.contains("Connection reset by peer");
    }

    private Throwable rootCause(Throwable throwable)
    {
        Throwable current = throwable;
        int guard = 0;
        while (current.getCause() != null && current.getCause() != current && guard++ < 10)
        {
            current = current.getCause();
        }
        return current;
    }

    private long parseLong(String value)
    {
        try
        {
            return StringUtils.isEmpty(value) ? 0L : Long.parseLong(value.trim());
        }
        catch (NumberFormatException e)
        {
            return 0L;
        }
    }

    private String resolveInstanceId(AlertProperties properties)
    {
        if (StringUtils.isNotEmpty(properties.getInstance()))
        {
            return properties.getInstance();
        }
        try
        {
            return InetAddress.getLocalHost().getHostName();
        }
        catch (Exception e)
        {
            return "unknown-host";
        }
    }

    @PreDestroy
    public void shutdown()
    {
        sender.shutdown();
    }
}
