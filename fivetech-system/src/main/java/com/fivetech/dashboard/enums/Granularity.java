package com.fivetech.dashboard.enums;

/**
 * 时间粒度。可用性由区间长度决定，由服务端计算后下发，前端不得自行判断。
 *
 * @author fivetech
 */
public enum Granularity
{
    HOUR,
    DAY,
    WEEK
}
