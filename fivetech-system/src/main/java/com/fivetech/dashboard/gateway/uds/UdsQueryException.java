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
        super(message, cause);
        this.httpStatus = httpStatus;
        this.udsCode = udsCode;
    }

    public int getHttpStatus()
    {
        return httpStatus;
    }

    public int getUdsCode()
    {
        return udsCode;
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
        return message.contains("NOT_READY")
            || message.contains("数据未就绪")
            || message.contains("没有满足条件的 binding");
    }
}
