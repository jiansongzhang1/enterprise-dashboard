package com.fivetech.dashboard.enums;

/**
 * 会员明细的列方案。
 * <p>
 * 会员表共 20 列，1366px 一屏只放得下 8–9 列，因此按视角分组，
 * 从指标卡下钻进来时第一屏就是相关列。
 *
 * @author fivetech
 */
public enum MemberViewScheme
{
    /** 注册视角：基础信息 + 注册相关 */
    REG,

    /** 首存视角：基础信息 + 首存相关 */
    FTD,

    /** 价值视角：基础信息 + 累计值 + 分层状态 */
    VALUE,

    /** 全部列 */
    ALL
}
