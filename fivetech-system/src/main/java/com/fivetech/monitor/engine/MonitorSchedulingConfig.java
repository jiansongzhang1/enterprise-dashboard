package com.fivetech.monitor.engine;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 判定任务的定时调度。项目里原来没有开启 Spring 定时任务，这里只在 {@code monitor.engine.enabled = true} 时开启，
 * 并给定时任务一个单独的线程池（线程名 monitor-engine-），不占用现有的 scheduledExecutorService（异步日志在用）。
 *
 * @author fivetech
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "monitor.engine", name = "enabled", havingValue = "true")
public class MonitorSchedulingConfig implements SchedulingConfigurer
{
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar)
    {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("monitor-engine-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        registrar.setTaskScheduler(scheduler);
    }
}
