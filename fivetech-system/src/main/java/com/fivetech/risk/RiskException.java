package com.fivetech.risk;

import java.util.Map;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.exception.MonitorFieldException;

/**
 * 风控接口的错误：参数错返回 code = 400（msg 为第一个错误，data 为 {字段: 原因}），不存在返回 404。
 *
 * @author fivetech
 */
public final class RiskException
{
    private RiskException()
    {
    }

    /** 400，带字段 */
    public static RuntimeException field(String field, String message)
    {
        return MonitorFieldException.of(field, message);
    }

    /** 400，多个字段 */
    public static RuntimeException fields(Map<String, String> errors)
    {
        return new MonitorFieldException(errors);
    }

    /** 400，不带字段（如超出上限） */
    public static RuntimeException tooLarge(String message)
    {
        return new ServiceException(message, 400);
    }

    public static RuntimeException notFound(String message)
    {
        return MonitorException.notFound(message);
    }

    /** 有错误就抛 */
    public static void failIfAny(Map<String, String> errors)
    {
        if (errors != null && !errors.isEmpty())
        {
            throw fields(errors);
        }
    }
}
