package com.fivetech.dashboard.subscription;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启定时任务给订阅简报用（{@code dashboard.subscription.enabled}，默认开）。
 * 监控判定引擎另有自己的开关和线程池（MonitorSchedulingConfig）；两处都开 @EnableScheduling 不冲突。
 *
 * @author fivetech
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "dashboard.subscription", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SubscriptionSchedulingConfig
{
}
