package com.fivetech.dashboard.gateway.uds;

/**
 * UDS 调用失败。
 * <p>
 * 单独一个异常类型，是为了让上层能区分「数据平台的问题」和「本系统的问题」——
 * 前者应该提示用户稍后重试并告警到值班，后者是代码 bug。
 *
 * @author fivetech
 */
public class UdsQueryException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    /**
     * 「数据未就绪」的业务码。UDS 在找不到水位足够高的 binding 时返回它，
     * 含义是<b>请求区间上界超过了上游已落库的位置</b>，不是查询本身有错。
     */
    public static final int CODE_NO_READY_BINDING = 4403;

    /** HTTP 状态码，无响应时为 0 */
    private final int httpStatus;

    /** UDS 响应体里的业务码，解析不到为 0 */
    private final int udsCode;

    /** 错误响应体原文 */
    private final String responseBody;

    public UdsQueryException(String message, int httpStatus)
    {
        this(message, httpStatus, 0, null);
    }

    public UdsQueryException(String message, int httpStatus, Throwable cause)
    {
        this(message, httpStatus, 0, cause);
    }

    public UdsQueryException(String message, int httpStatus, int udsCode, Throwable cause)
    {
        this(message, httpStatus, udsCode, cause, null);
    }

    public UdsQueryException(String message, int httpStatus, int udsCode, Throwable cause, String responseBody)
    {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.udsCode = udsCode;
        this.responseBody = responseBody;
    }

    /** UDS 错误响应体原文（可能为 null），用于取 detail.retryAfterMillis、detail.hint 等 */
    public String getResponseBody()
    {
        return responseBody;
    }

    public int getHttpStatus()
    {
        return httpStatus;
    }

    public int getUdsCode()
    {
        return udsCode;
    }

    /** 响应头 Retry-After 换算的毫秒数，没有为 0 */
    private long retryAfterMillis;

    public long getRetryAfterMillis()
    {
        return retryAfterMillis;
    }

    public UdsQueryException withRetryAfterMillis(long retryAfterMillis)
    {
        this.retryAfterMillis = retryAfterMillis;
        return this;
    }

    /**
     * 给页面看的提示（按联调手册第 14 节的错误码含义）。
     * 只说「是什么问题、用户能做什么」，不带内网地址与原始响应。
     */
    public String userMessage()
    {
        if (httpStatus == 429 || udsCode == 4290)
        {
            return "数据服务繁忙（限流），请稍后重试";
        }
        switch (udsCode)
        {
            case 4001:
                return "查询参数不被数据服务接受（4001），请调整时间或筛选条件";
            case 4002:
                return "数据集或字段当前不可用（4002），请联系数据平台核对";
            case 4401:
                return "当前服务身份没有该数据的访问权限（4401）";
            case 4402:
                return "该数据不支持所选的时间粒度（4402）";
            case CODE_NO_READY_BINDING:
                return "所选时间范围的数据尚未就绪（4403），请提前结束时间后重试";
            case 4405:
                return "查询范围过大被数据服务拒绝（4405），请缩小时间范围或增加筛选条件";
            default:
                break;
        }
        if (httpStatus == 0)
        {
            return "数据服务暂时无法连接，请稍后重试";
        }
        if (httpStatus == 403)
        {
            // HTML 403 来自网关的来源 / 证书限制，与 UDS JSON 4401 不是一回事
            return "数据服务网关拒绝访问（403），请检查客户端证书与来源授权";
        }
        return "数据服务查询失败，请稍后重试";
    }

    /**
     * 是否属于「数据还没到那个时间点」。
     * <p>
     * 这类失败<b>可以靠缩小区间上界自愈</b>，不该当成故障告警，
     * 更不该让整个看板报错——正确表现是尾部时间片留白。
     */
    public boolean isNotReady()
    {
        if (udsCode == CODE_NO_READY_BINDING)
        {
            return true;
        }
        String message = getMessage();
        if (message == null)
        {
            return false;
        }
        // TIME_COVERAGE：请求区间超出该绑定覆盖的时间范围。
        // 和 NOT_READY 一样能靠缩小上界自愈，所以走同一条退避路径
        return message.contains("NOT_READY")
            || message.contains("TIME_COVERAGE")
            || message.contains("数据未就绪")
            || message.contains("没有满足条件的 binding");
    }
}
