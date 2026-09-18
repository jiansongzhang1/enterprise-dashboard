package com.fivetech.common.filter;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import com.fivetech.common.utils.TraceIdUtils;

/**
 * 链路追踪过滤器。
 * <p>
 * 为每个请求生成或透传 traceId，写入 MDC 供日志输出，并回写到响应头，
 * 前端可在报错提示中展示，用户反馈时凭该追踪号即可定位全链路日志。
 * <p>
 * 该过滤器必须排在其它过滤器之前，保证整条链路的日志都带上 traceId。
 *
 * @author fivetech
 */
public class TraceIdFilter extends OncePerRequestFilter
{
    /** 请求属性名，异步派发时复用同一个 traceId，而不是重新生成 */
    private static final String ATTRIBUTE = TraceIdFilter.class.getName() + ".TRACE_ID";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException
    {
        String traceId = resolve(request);
        request.setAttribute(ATTRIBUTE, traceId);
        TraceIdUtils.set(traceId);
        if (!response.isCommitted())
        {
            response.setHeader(TraceIdUtils.TRACE_HEADER, traceId);
        }
        try
        {
            chain.doFilter(request, response);
        }
        finally
        {
            // Tomcat 线程会被复用，必须清理，否则下一个请求会串号
            TraceIdUtils.clear();
        }
    }

    private String resolve(HttpServletRequest request)
    {
        // 异步派发时首次生成的值已放在请求属性里，直接复用
        Object cached = request.getAttribute(ATTRIBUTE);
        if (cached instanceof String && TraceIdUtils.isValid((String) cached))
        {
            return (String) cached;
        }
        // 网关或前端传入的值不可信，格式非法时一律重新生成，避免日志伪造与响应头注入
        String header = request.getHeader(TraceIdUtils.TRACE_HEADER);
        return TraceIdUtils.isValid(header) ? header : TraceIdUtils.generate();
    }

    /**
     * 异步派发也要经过本过滤器：异步线程的 MDC 是空的，需要重新写入
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch()
    {
        return false;
    }

    /**
     * 错误页派发也要带 traceId
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch()
    {
        return false;
    }
}
