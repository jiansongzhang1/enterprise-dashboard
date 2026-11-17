package com.fivetech.monitor.cond;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 条件规则的小时数据。实现见 {@link UdsCondDataSource}；测试用假数据源。
 *
 * @author fivetech
 */
public interface CondDataSource
{
    /** 一项取数：范围（维度 → 值）+ 要取的列；key 由调用方定，结果按 key 返回 */
    record Request(String key, Map<String, String> scope, List<String> columns)
    {
    }

    /** 接入了没有（小时数据集已配置） */
    boolean available();

    /**
     * 取 [from, to) 每小时的数据（站点时区整点）。
     *
     * @throws CondDataException 未接入或 UDS 失败
     */
    Map<String, CondSeries> fetch(List<Request> requests, LocalDateTime from, LocalDateTime to);

    /** 取数失败或未接入 */
    class CondDataException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        public CondDataException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }
}
