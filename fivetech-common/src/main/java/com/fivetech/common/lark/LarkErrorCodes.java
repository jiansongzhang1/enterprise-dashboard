package com.fivetech.common.lark;

import java.util.Set;

/**
 * Lark 错误码到 {@link LarkErrorCategory} 的对照表，集中在这一处维护。
 *
 * <p>未列出的错误码一律按 {@link LarkErrorCategory#RETRYABLE} 处理（发送任务最多重试 3 次，拉群列表返回缓存），
 * 并在日志里打出原始 code 和 msg，遇到新的错误码再补进来。</p>
 *
 * @author fivetech
 */
public final class LarkErrorCodes
{
    /** tenant_access_token 失效或格式不对：删掉缓存重取一次 token 再调 */
    static final Set<Integer> TOKEN_INVALID = Set.of(99991661, 99991663, 99991668);

    /** 应用级问题：缺少权限（如没开 im:chat:readonly / im:message:send_as_bot）、230006 机器人能力未开启 */
    static final Set<Integer> CREDENTIAL = Set.of(99991672, 99991679, 230006);

    /**
     * 群已不可用（发送时据此把 monitor_lark_chat 里的群置为失效）：
     * 230002 机器人不在群里（被移出）、232009 群已解散、230034 receive_id（chat_id）无效。
     * 机器人被拉回同一个群后，下次拉取群列表或发送成功时自动恢复。
     */
    static final Set<Integer> CHAT_GONE = Set.of(230002, 232009, 230034);

    /**
     * 群级问题（只影响这一个群，不重试）：上面的 CHAT_GONE，以及 230013 机器人对该用户不可用、
     * 230018 群设置不允许、230027 缺少必要权限（如外部群）、230035 没有发言权限（被禁言等）。
     * 后面这几种群还在，不置失效。
     */
    static final Set<Integer> TARGET = Set.of(230002, 232009, 230034, 230013, 230018, 230027, 230035);

    /** 限流 */
    static final Set<Integer> RATE_LIMIT = Set.of(99991400, 230020);

    private LarkErrorCodes()
    {
    }

    /** 机器人已不在群里、群已解散或 chat_id 无效（以 Lark 发送消息接口的错误码表为准，2026-10-21 核对） */
    public static boolean isChatGone(Integer code)
    {
        return code != null && CHAT_GONE.contains(code);
    }

    public static boolean isTokenInvalid(int httpStatus, Integer code)
    {
        return code != null && TOKEN_INVALID.contains(code);
    }

    /** 业务接口（列群、发消息）的分类；token 接口的失败由 LarkClient 单独处理（除 5xx/限流外都算凭证错误） */
    public static LarkErrorCategory classify(int httpStatus, Integer code)
    {
        if (httpStatus >= 200 && httpStatus < 300 && code != null && code == 0)
        {
            return LarkErrorCategory.OK;
        }
        if (httpStatus == 429 || httpStatus >= 500 || httpStatus == 0 || (code != null && RATE_LIMIT.contains(code)))
        {
            return LarkErrorCategory.RETRYABLE;
        }
        if (code != null && (CREDENTIAL.contains(code) || TOKEN_INVALID.contains(code)))
        {
            // 走到这里的 token 失效已经重取过一次，仍然失效就当凭证问题
            return LarkErrorCategory.CREDENTIAL_INVALID;
        }
        if (code != null && TARGET.contains(code))
        {
            return LarkErrorCategory.TARGET_INVALID;
        }
        return LarkErrorCategory.RETRYABLE;
    }
}
