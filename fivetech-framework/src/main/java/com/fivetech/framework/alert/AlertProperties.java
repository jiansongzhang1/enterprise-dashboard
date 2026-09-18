package com.fivetech.framework.alert;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 系统错误告警配置。
 *
 * @author fivetech
 */
@Component
@ConfigurationProperties(prefix = "alert")
public class AlertProperties
{
    /** 总开关。关闭时只记日志，不发通知 */
    private boolean enabled = false;

    /** 环境标识，出现在消息首行，避免测试环境的告警被当成生产 */
    private String env = "unknown";

    /** 实例标识，留空则取主机名 */
    private String instance;

    /** Telegram chat_id（个人或群），可配多个 */
    private List<String> telegramChatIds = new ArrayList<>();

    /** 同一异常指纹的静默窗口（秒），窗口内只推第一条 */
    private int dedupWindowSeconds = 300;

    /** 全局每分钟最大推送条数，防止雪崩时刷屏 */
    private int ratePerMinute = 10;

    /** 消息中携带的堆栈行数 */
    private int stackLines = 6;

    /** 异步发送队列容量，满了直接丢弃（日志里已有完整记录） */
    private int queueCapacity = 200;

    /**
     * 不告警的异常类名（全限定名或简单类名均可匹配）。
     * 默认忽略客户端断开连接一类的噪音。
     */
    private List<String> ignoredExceptions = new ArrayList<>(List.of(
        "ClientAbortException",
        "AsyncRequestNotUsableException",
        "AsyncRequestTimeoutException",
        "HttpMessageNotReadableException",
        "MethodArgumentNotValidException",
        "BindException"));

    public boolean isEnabled()
    {
        return enabled;
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    public String getEnv()
    {
        return env;
    }

    public void setEnv(String env)
    {
        this.env = env;
    }

    public String getInstance()
    {
        return instance;
    }

    public void setInstance(String instance)
    {
        this.instance = instance;
    }

    public List<String> getTelegramChatIds()
    {
        return telegramChatIds;
    }

    public void setTelegramChatIds(List<String> telegramChatIds)
    {
        this.telegramChatIds = telegramChatIds;
    }

    public int getDedupWindowSeconds()
    {
        return dedupWindowSeconds;
    }

    public void setDedupWindowSeconds(int dedupWindowSeconds)
    {
        this.dedupWindowSeconds = dedupWindowSeconds;
    }

    public int getRatePerMinute()
    {
        return ratePerMinute;
    }

    public void setRatePerMinute(int ratePerMinute)
    {
        this.ratePerMinute = ratePerMinute;
    }

    public int getStackLines()
    {
        return stackLines;
    }

    public void setStackLines(int stackLines)
    {
        this.stackLines = stackLines;
    }

    public int getQueueCapacity()
    {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity)
    {
        this.queueCapacity = queueCapacity;
    }

    public List<String> getIgnoredExceptions()
    {
        return ignoredExceptions;
    }

    public void setIgnoredExceptions(List<String> ignoredExceptions)
    {
        this.ignoredExceptions = ignoredExceptions;
    }
}
