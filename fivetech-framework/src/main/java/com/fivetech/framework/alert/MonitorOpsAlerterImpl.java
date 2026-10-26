package com.fivetech.framework.alert;

import org.springframework.stereotype.Component;
import com.fivetech.monitor.engine.MonitorOpsAlerter;

/**
 * 监控模块的值班告警：转给 {@link ErrorAlertService}（Telegram，异步、按指纹去重限流）。
 * 同一原因的告警在静默窗口内只发一次，发送任务每分钟遇到凭证无效也不会刷屏。
 *
 * @author fivetech
 */
@Component
public class MonitorOpsAlerterImpl implements MonitorOpsAlerter
{
    private final ErrorAlertService errorAlertService;

    public MonitorOpsAlerterImpl(ErrorAlertService errorAlertService)
    {
        this.errorAlertService = errorAlertService;
    }

    @Override
    public void alert(String title, String detail)
    {
        // ErrorAlertService 以异常为单位去重，没有异常的告警会被忽略，这里包成一个不抛出的异常
        errorAlertService.alert(AlertLevel.P1, title, "monitor-notify", new MonitorNotifyException(detail));
    }

    /** 只用来承载告警内容，不会被抛出 */
    static final class MonitorNotifyException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        MonitorNotifyException(String message)
        {
            super(message, null, false, false);
        }
    }
}
