package com.fivetech.dashboard.enums;

/**
 * 统计区间类型。与原型顶栏的时间范围选择器一一对应。
 *
 * @author fivetech
 */
public enum RangeType
{
    /** 今日，截断到最后一个完整小时 */
    TODAY,

    /** 昨日整天 */
    YESTERDAY,

    /** 近 7 天（不含今日） */
    LAST_7D,

    /** 近 30 天（不含今日） */
    LAST_30D,

    /** 自定义区间，需同时传 from / to */
    CUSTOM,

    /** 不限时间。仅记录表可用，指标汇总表不支持 */
    ALL
}
