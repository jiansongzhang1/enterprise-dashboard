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

    /** 去年同期 */
    LAST_YEAR,

    /** 自定义对比区间，需同时传 compareFrom / compareTo */
    CUSTOM
}
