package com.fivetech.common.utils;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.slf4j.MDC;

/**
 * 链路追踪号（traceId）工具类。
 * <p>
 * traceId 存放在 SLF4J 的 MDC 中，由 logback 的 %X{traceId} 输出到日志。
 * 请求入口由 {@code TraceIdFilter} 写入，异步/定时任务需自行调用 {@link #newTrace(String)}
 * 或使用 {@link #wrap(Runnable)} 传递父线程的 traceId。
 *
 * @author fivetech
 */
public class TraceIdUtils
{
    /** MDC 中的键名，需与 logback.xml 的 %X{traceId} 保持一致 */
    public static final String TRACE_ID = "traceId";

    /** HTTP 请求头 / 响应头名称 */
    public static final String TRACE_HEADER = "X-Trace-Id";

    /** 外部传入 traceId 的最大长度 */
    private static final int MAX_LENGTH = 64;

    private TraceIdUtils()
    {
    }

    /**
     * 生成一个新的 traceId（32 位十六进制）
     */
    public static String generate()
    {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 生成带业务前缀的 traceId，便于区分来源。
     * 例如定时任务使用 newTrace("job")，导出任务使用 newTrace("export")。
     *
     * @param prefix 前缀，只保留字母数字，超长会被截断
     */
    public static String newTrace(String prefix)
    {
        String safePrefix = (prefix == null) ? "" : prefix.replaceAll("[^A-Za-z0-9]", "");
        if (safePrefix.length() > 12)
        {
            safePrefix = safePrefix.substring(0, 12);
        }
        String traceId = safePrefix.isEmpty() ? generate() : safePrefix + "-" + generate().substring(0, 24);
        set(traceId);
        return traceId;
    }

    /**
     * 获取当前线程的 traceId，不存在时返回空串（不返回 null，便于拼接）
     */
    public static String get()
    {
        String traceId = MDC.get(TRACE_ID);
        return traceId == null ? "" : traceId;
    }

    /**
     * 获取当前线程的 traceId，不存在时生成一个并写入
     */
    public static String getOrCreate()
    {
        String traceId = MDC.get(TRACE_ID);
        if (traceId == null || traceId.isEmpty())
        {
            traceId = generate();
            MDC.put(TRACE_ID, traceId);
        }
        return traceId;
    }

    /**
     * 写入当前线程的 traceId
     */
    public static void set(String traceId)
    {
        if (traceId != null && !traceId.isEmpty())
        {
            MDC.put(TRACE_ID, traceId);
        }
    }

    /**
     * 清除当前线程的 traceId。线程池会复用线程，用完必须清理，否则会串号。
     */
    public static void clear()
    {
        MDC.remove(TRACE_ID);
    }

    /**
     * 校验外部传入的 traceId 是否可用。
     * <p>
     * 只允许字母、数字、中划线、下划线：traceId 会被写入日志文件和 HTTP 响应头，
     * 若放行换行符或控制字符，会造成日志伪造与响应头注入。
     */
    public static boolean isValid(String traceId)
    {
        if (traceId == null || traceId.isEmpty() || traceId.length() > MAX_LENGTH)
        {
            return false;
        }
        for (int i = 0; i < traceId.length(); i++)
        {
            char c = traceId.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok)
            {
                return false;
            }
        }
        return true;
    }

    /**
     * 包装 Runnable，把提交线程的 MDC 上下文带到执行线程。
     * 线程池提交任务时使用，否则异步日志会丢失 traceId。
     */
    public static Runnable wrap(final Runnable runnable)
    {
        final Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> origin = MDC.getCopyOfContextMap();
            setContext(context);
            try
            {
                runnable.run();
            }
            finally
            {
                setContext(origin);
            }
        };
    }

    /**
     * 包装 Callable，作用同 {@link #wrap(Runnable)}
     */
    public static <T> Callable<T> wrap(final Callable<T> callable)
    {
        final Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> origin = MDC.getCopyOfContextMap();
            setContext(context);
            try
            {
                return callable.call();
            }
            finally
            {
                setContext(origin);
            }
        };
    }

    private static void setContext(Map<String, String> context)
    {
        if (context == null || context.isEmpty())
        {
            MDC.clear();
        }
        else
        {
            MDC.setContextMap(context);
        }
    }
}
