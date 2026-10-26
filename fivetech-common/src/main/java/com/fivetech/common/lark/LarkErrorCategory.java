package com.fivetech.common.lark;

/**
 * Lark 调用结果的分类：调用方（拉群列表、告警发送）只看分类，不直接判断 Lark 错误码。
 *
 * @author fivetech
 */
public enum LarkErrorCategory
{
    /** 成功 */
    OK,

    /** 凭证错误、应用停用、应用缺少权限：同一个应用的所有调用都会失败，重试没有意义 */
    CREDENTIAL_INVALID,

    /** 目标不可用：机器人不在群里、群已解散、无权向该群发消息；只影响这一个群 */
    TARGET_INVALID,

    /** 可重试：网络超时、限流、5xx，以及未识别的错误码 */
    RETRYABLE
}
