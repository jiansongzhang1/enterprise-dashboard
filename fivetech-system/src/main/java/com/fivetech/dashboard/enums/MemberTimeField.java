package com.fivetech.dashboard.enums;

/**
 * 会员明细的时间筛选字段。
 * <p>
 * 同一张会员表，从「注册人数」下钻按注册时间筛，从「首存人数」下钻按首存时间筛，
 * 从「活跃人数」下钻按最近活跃时间筛。字段选错，下钻结果就对不上指标值。
 *
 * @author fivetech
 */
public enum MemberTimeField
{
    /** 注册时间 */
    REG_TIME,

    /** 首存时间 */
    FTD_TIME,

    /** 最近活跃时间 */
    ACTIVE_TIME
}
