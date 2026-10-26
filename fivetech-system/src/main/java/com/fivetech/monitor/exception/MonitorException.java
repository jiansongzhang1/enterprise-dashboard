package com.fivetech.monitor.exception;

/**
 * 监控模块的业务错误（403 无站点权限、404 不在数据范围、48xx）。
 *
 * <p>ServiceException 是 final 的，所以单独继承 RuntimeException；
 * 由 GlobalExceptionHandler 的专门处理器原样返回 code 和 msg，只记一行 WARN。</p>
 *
 * @author fivetech
 */
public class MonitorException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    private final int code;

    public MonitorException(int code, String message)
    {
        super(message);
        this.code = code;
    }

    public int getCode()
    {
        return code;
    }

    public static MonitorException notFound(String message)
    {
        return new MonitorException(MonitorErrorCode.NOT_FOUND, message);
    }

    public static MonitorException forbidden(String message)
    {
        return new MonitorException(MonitorErrorCode.FORBIDDEN, message);
    }
}
