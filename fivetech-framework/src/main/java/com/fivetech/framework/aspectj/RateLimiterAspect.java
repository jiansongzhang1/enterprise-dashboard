package com.fivetech.framework.aspectj;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fivetech.common.annotation.RateLimiter;
import com.fivetech.common.enums.LimitType;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.common.utils.ip.IpUtils;

/**
 * 限流处理
 *
 * @author fivetech
 */
@Aspect
@Component
public class RateLimiterAspect
{
    private static final Logger log = LoggerFactory.getLogger(RateLimiterAspect.class);

    private static final Map<String, WindowCounter> COUNTERS = new ConcurrentHashMap<>();

    @Before("@annotation(rateLimiter)")
    public void doBefore(JoinPoint point, RateLimiter rateLimiter) throws Throwable
    {
        int time = rateLimiter.time();
        int count = rateLimiter.count();

        String combineKey = getCombineKey(rateLimiter, point);
        try
        {
            long current = System.currentTimeMillis();
            WindowCounter windowCounter = COUNTERS.computeIfAbsent(combineKey, key -> new WindowCounter(current, 0));
            synchronized (windowCounter)
            {
                if ((current - windowCounter.windowStart) >= time * 1000L)
                {
                    windowCounter.windowStart = current;
                    windowCounter.count = 0;
                }
                windowCounter.count++;
                if (windowCounter.count > count)
                {
                    throw new ServiceException("访问过于频繁，请稍候再试");
                }
            }
            log.info("限制请求'{}',当前请求'{}',缓存key'{}'", count, COUNTERS.get(combineKey).count, combineKey);
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new RuntimeException("服务器限流异常，请稍候再试");
        }
    }

    public String getCombineKey(RateLimiter rateLimiter, JoinPoint point)
    {
        StringBuffer stringBuffer = new StringBuffer(rateLimiter.key());
        if (rateLimiter.limitType() == LimitType.IP)
        {
            stringBuffer.append(IpUtils.getIpAddr()).append("-");
        }
        MethodSignature signature = (MethodSignature) point.getSignature();
        Method method = signature.getMethod();
        Class<?> targetClass = method.getDeclaringClass();
        stringBuffer.append(targetClass.getName()).append("-").append(method.getName());
        return stringBuffer.toString();
    }

    private static final class WindowCounter
    {
        private long windowStart;
        private int count;

        private WindowCounter(long windowStart, int count)
        {
            this.windowStart = windowStart;
            this.count = count;
        }
    }
}
