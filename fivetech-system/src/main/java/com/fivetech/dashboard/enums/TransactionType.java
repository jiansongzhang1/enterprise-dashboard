package com.fivetech.dashboard.enums;

/**
 * 交易方向。存款与提款合并在一张表，用本字段区分。
 *
 * @author fivetech
 */
public enum TransactionType
{
    /** 存款 */
    DEPOSIT,

    /** 提款 */
    WITHDRAW
}
