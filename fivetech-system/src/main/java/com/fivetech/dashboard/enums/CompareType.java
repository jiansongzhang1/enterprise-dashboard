package com.fivetech.dashboard.enums;

/**
 * 对比期类型。
 *
 * @author fivetech
 */
public enum CompareType
{
    /** 不对比 */
    NONE,

    /** 上一周期：与主区间等长且紧邻 */
    PREV_PERIOD,

    /** 去年同期。接口文档里写作 YOY，两种写法都接受 */
    LAST_YEAR,

    /** 自定义对比区间，需同时传 compareFrom / compareTo */
    CUSTOM;

    /**
     * 宽松解析。接口文档用的是 {@code YOY}，枚举名是 {@code LAST_YEAR}，
     * 这里把两种写法都认下来——让前端改文案不如让后端多认一个别名。
     * <p>传了不认识的值返回 null，由调用方决定是报错还是回落默认值，
     * 不在这里替它做主。</p>
     */
    @com.fasterxml.jackson.annotation.JsonCreator
    public static CompareType of(String code)
    {
        if (code == null || code.isBlank())
        {
            return null;
        }
        String v = code.trim().toUpperCase();
        if ("YOY".equals(v))
        {
            return LAST_YEAR;
        }
        for (CompareType t : values())
        {
            if (t.name().equals(v))
            {
                return t;
            }
        }
        return null;
    }
}
