package com.fivetech.monitor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时写入 / 升级全站默认通知模板（见 {@link MonitorNotifyTemplateService#initDefaults()}）。
 * 表还没建（没执行 sql/add_monitor_notify_template_postgresql.sql）时只记日志，不影响启动；告警照常用内置默认模板。
 *
 * @author fivetech
 */
@Component
public class MonitorNotifyTemplateInitializer implements ApplicationRunner
{
    private static final Logger log = LoggerFactory.getLogger(MonitorNotifyTemplateInitializer.class);

    private final MonitorNotifyTemplateService templateService;

    public MonitorNotifyTemplateInitializer(MonitorNotifyTemplateService templateService)
    {
        this.templateService = templateService;
    }

    @Override
    public void run(ApplicationArguments args)
    {
        try
        {
            templateService.initDefaults();
        }
        catch (RuntimeException e)
        {
            log.warn("[monitor] 通知模板初始化跳过（表 monitor_notify_template 不存在或不可用，告警用内置默认模板）：{}", e.getMessage());
        }
    }
}
