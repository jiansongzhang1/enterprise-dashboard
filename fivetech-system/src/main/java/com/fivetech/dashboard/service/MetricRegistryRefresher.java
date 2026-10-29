package com.fivetech.dashboard.service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.fivetech.dashboard.mapper.DashboardConfigMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 指标配置的热加载：每隔 {@code dashboard.config-refresh-seconds} 秒（默认 60，0 = 关闭）查一次 dashboard_metric_card 的版本
 * （行数 + 最后修改时间），变了就重载 {@link MetricRegistry}。
 *
 * <p>后台「指标管理」改名称、分组、格式时，处理请求的实例当场重载；多实例部署时其他实例靠这里在一分钟内跟上。
 * 用自己的单线程（守护线程），不依赖 monitor.engine.enabled 才开启的 Spring 定时任务。重载失败只记日志，继续用旧快照。</p>
 *
 * @author fivetech
 */
@Component
public class MetricRegistryRefresher
{
    private static final Logger log = LoggerFactory.getLogger(MetricRegistryRefresher.class);

    private final MetricRegistry registry;

    private final DashboardConfigMapper mapper;

    private final int seconds;

    private volatile String version;

    private ScheduledExecutorService executor;

    public MetricRegistryRefresher(MetricRegistry registry, DashboardConfigMapper mapper,
            @Value("${dashboard.config-refresh-seconds:60}") int seconds)
    {
        this.registry = registry;
        this.mapper = mapper;
        this.seconds = seconds;
    }

    @PostConstruct
    public void start()
    {
        if (seconds <= 0)
        {
            return;
        }
        version = currentVersion();
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metric-config-refresh");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::check, seconds, seconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop()
    {
        if (executor != null)
        {
            executor.shutdownNow();
        }
    }

    /** 版本变了就重载；包内可见供测试 */
    void check()
    {
        String now = currentVersion();
        if (now == null || now.equals(version))
        {
            return;
        }
        try
        {
            registry.reload();
            log.info("[dashboard-config] 指标配置有变更（{} → {}），已重新加载", version, now);
            version = now;
        }
        catch (RuntimeException e)
        {
            log.warn("[dashboard-config] 指标配置有变更但重新加载失败，继续用旧配置：{}", e.getMessage());
        }
    }

    private String currentVersion()
    {
        try
        {
            return mapper.selectMetricCardVersion();
        }
        catch (RuntimeException e)
        {
            log.warn("[dashboard-config] 读取指标配置版本失败：{}", e.getMessage());
            return null;
        }
    }
}
