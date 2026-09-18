package com.fivetech.framework.alert;

/**
 * 一条系统错误告警的上下文。
 * <p>
 * 所有字段都允许为空：后台任务没有 uri，未登录请求没有 username。
 *
 * @author fivetech
 */
public class AlertContext
{
    private AlertLevel level = AlertLevel.P2;

    /** 一句话说明发生了什么，例如「系统未捕获异常」「告警评估任务失败」 */
    private String title;

    /** 链路追踪号，收到告警后凭它捞日志 */
    private String traceId;

    /** 请求方法，如 POST */
    private String method;

    /** 请求路径，或后台任务标识，如 job:alert-evaluate */
    private String uri;

    /** 操作用户 */
    private String username;

    /** 客户端 IP */
    private String clientIp;

    /** 异常本体 */
    private Throwable throwable;

    public static AlertContext of(AlertLevel level, String title, Throwable throwable)
    {
        AlertContext context = new AlertContext();
        context.level = level;
        context.title = title;
        context.throwable = throwable;
        return context;
    }

    public AlertLevel getLevel()
    {
        return level;
    }

    public AlertContext setLevel(AlertLevel level)
    {
        this.level = level;
        return this;
    }

    public String getTitle()
    {
        return title;
    }

    public AlertContext setTitle(String title)
    {
        this.title = title;
        return this;
    }

    public String getTraceId()
    {
        return traceId;
    }

    public AlertContext setTraceId(String traceId)
    {
        this.traceId = traceId;
        return this;
    }

    public String getMethod()
    {
        return method;
    }

    public AlertContext setMethod(String method)
    {
        this.method = method;
        return this;
    }

    public String getUri()
    {
        return uri;
    }

    public AlertContext setUri(String uri)
    {
        this.uri = uri;
        return this;
    }

    public String getUsername()
    {
        return username;
    }

    public AlertContext setUsername(String username)
    {
        this.username = username;
        return this;
    }

    public String getClientIp()
    {
        return clientIp;
    }

    public AlertContext setClientIp(String clientIp)
    {
        this.clientIp = clientIp;
        return this;
    }

    public Throwable getThrowable()
    {
        return throwable;
    }

    public AlertContext setThrowable(Throwable throwable)
    {
        this.throwable = throwable;
        return this;
    }
}
