package com.fivetech.monitor.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 字段级校验失败：返回 code = 400，{@code data.fields} 列出每个字段的错误（繁体文案，前端直接显示）。
 *
 * <p>由 GlobalExceptionHandler 单独处理；msg 取第一条错误，方便只显示一行提示的场景。</p>
 *
 * @author fivetech
 */
public class MonitorFieldException extends MonitorException
{
    private static final long serialVersionUID = 1L;

    private final Map<String, String> fields;

    public MonitorFieldException(Map<String, String> fields)
    {
        super(MonitorErrorCode.BAD_REQUEST, fields.isEmpty() ? "參數錯誤" : fields.values().iterator().next());
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public static MonitorFieldException of(String field, String message)
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(field, message);
        return new MonitorFieldException(m);
    }

    public Map<String, String> getFields()
    {
        return fields;
    }
}
