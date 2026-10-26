package com.fivetech.monitor.engine;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.fivetech.common.utils.SiteContext;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.monitor.mapper.MonitorEngineMapper;

/**
 * 监控的两个定时入口，只有 {@code monitor.engine.enabled = true} 时才注册：
 * <ul>
 *   <li>判定（{@link #poll()}）：每 10 分钟（IST :00 / :10 / … / :50）逐站点处理上一个完整小时，Redis 锁 {@code monitor:lock:poll}。</li>
 *   <li>发送（{@link #sendNotifications()}）：每分钟第 30 秒逐站点投递到期的待发送记录，Redis 锁 {@code monitor:lock:notify}。</li>
 * </ul>
 * 多实例部署时靠 Redis 锁保证同一时刻只有一个实例在跑；一个站点失败只记日志，不影响其他站点，下一轮重试。
 *
 * @author fivetech
 */
@Component
@ConditionalOnProperty(prefix = "monitor.engine", name = "enabled", havingValue = "true")
public class MonitorEngineScheduler
{
    private static final Logger log = LoggerFactory.getLogger(MonitorEngineScheduler.class);

    static final String LOCK_KEY = "monitor:lock:poll";

    static final String NOTIFY_LOCK_KEY = "monitor:lock:notify";

    private static final DefaultRedisScript<Long> UNLOCK = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final MonitorEngineMapper mapper;

    private final MonitorHourJob job;

    private final MonitorNotifySender sender;

    private final DashboardProperties dashboard;

    private final MonitorProperties properties;

    private final ObjectProvider<StringRedisTemplate> redisProvider;

    public MonitorEngineScheduler(MonitorEngineMapper mapper, MonitorHourJob job, MonitorNotifySender sender,
            DashboardProperties dashboard, MonitorProperties properties, ObjectProvider<StringRedisTemplate> redisProvider)
    {
        this.mapper = mapper;
        this.job = job;
        this.sender = sender;
        this.dashboard = dashboard;
        this.properties = properties;
        this.redisProvider = redisProvider;
    }

    @Scheduled(cron = "${monitor.engine.poll-cron:0 0/10 * * * *}", zone = "${dashboard.timezone:Asia/Kolkata}")
    public void poll()
    {
        withLock(LOCK_KEY, Math.max(60, properties.getEngine().getLockSeconds()), "判定", () -> {
            LocalDateTime now = LocalDateTime.now(ZoneId.of(dashboard.getTimezone()));
            List<String> sites = mapper.selectEngineSites();
            for (String site : sites)
            {
                if (!dashboard.isKnownSite(site))
                {
                    log.warn("[monitor] 站点 {} 有启用规则但不在 dashboard.sites 配置里，跳过", site);
                    continue;
                }
                String code = dashboard.canonical(site);
                try
                {
                    SiteContext.callWith(code, dashboard.udsSite(code), () -> job.run(code, now));
                }
                catch (RuntimeException e)
                {
                    log.error("[monitor] 站点 {} 本轮判定失败，下一轮重试", code, e);
                }
            }
        });
    }

    @Scheduled(cron = "${monitor.engine.notify-cron:30 * * * * *}", zone = "${dashboard.timezone:Asia/Kolkata}")
    public void sendNotifications()
    {
        withLock(NOTIFY_LOCK_KEY, Math.max(10, properties.getEngine().getNotifyLockSeconds()), "发送", () -> {
            LocalDateTime now = LocalDateTime.now(ZoneId.of(dashboard.getTimezone())).truncatedTo(ChronoUnit.SECONDS);
            long deadline = sender.deadline();
            for (String site : sender.dueSites(now))
            {
                // 发送记录的站点就是写入时的站点，不要求仍在 dashboard.sites 里（已发出的告警不应因配置变更丢失）
                try
                {
                    sender.sendSite(site, now, deadline);
                }
                catch (RuntimeException e)
                {
                    log.error("[monitor] 站点 {} 本轮发送失败，下一轮重试", site, e);
                }
                if (System.currentTimeMillis() > deadline)
                {
                    break;
                }
            }
        });
    }

    /** 拿到 Redis 锁才执行；拿不到（其他实例在跑、Redis 不可用）本轮跳过 */
    private void withLock(String key, int seconds, String name, Runnable task)
    {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null)
        {
            log.error("[monitor] 没有可用的 Redis，本轮{}跳过（需要 {} 防止多实例重复执行）", name, key);
            return;
        }
        String token = UUID.randomUUID().toString();
        Boolean locked;
        try
        {
            locked = redis.opsForValue().setIfAbsent(key, token, Duration.ofSeconds(seconds));
        }
        catch (RuntimeException e)
        {
            log.error("[monitor] 取锁 {} 失败，本轮{}跳过：{}", key, name, e.getMessage());
            return;
        }
        if (!Boolean.TRUE.equals(locked))
        {
            log.info("[monitor] 其他实例正在{}，本轮跳过", name);
            return;
        }
        try
        {
            task.run();
        }
        finally
        {
            try
            {
                redis.execute(UNLOCK, Collections.singletonList(key), token);
            }
            catch (RuntimeException e)
            {
                log.warn("[monitor] 释放锁 {} 失败（{} 秒后自动过期）：{}", key, seconds, e.getMessage());
            }
        }
    }
}
