package com.fivetech.common.constant;

/**
 * Google Authenticator 状态常量
 *
 * @author fivetech
 */
public class GaConstants
{
    /** 未开通 */
    public static final int GA_STATUS_DISABLED = 0;

    /** 待绑定 */
    public static final int GA_STATUS_PENDING = 1;

    /** 已绑定 */
    public static final int GA_STATUS_BOUND = 2;

    private GaConstants()
    {
    }
}
