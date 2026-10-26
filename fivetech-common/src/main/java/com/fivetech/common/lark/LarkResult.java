package com.fivetech.common.lark;

/**
 * Lark 调用结果：{@link LarkClient} 不抛异常，失败时带上 Lark 错误码、HTTP 状态和分类。
 *
 * @param <T> 成功时的数据
 * @author fivetech
 */
public final class LarkResult<T>
{
    private final boolean success;

    /** Lark 返回的 code；网络异常、响应不是 JSON 时为 null */
    private final Integer larkCode;

    private final String msg;

    /** HTTP 状态；网络异常时为 0 */
    private final int httpStatus;

    private final LarkErrorCategory category;

    /** 失败发生在取 tenant_access_token 这一步 */
    private final boolean tokenFailure;

    private final T data;

    private LarkResult(boolean success, Integer larkCode, String msg, int httpStatus, LarkErrorCategory category,
            boolean tokenFailure, T data)
    {
        this.success = success;
        this.larkCode = larkCode;
        this.msg = msg;
        this.httpStatus = httpStatus;
        this.category = category;
        this.tokenFailure = tokenFailure;
        this.data = data;
    }

    public static <T> LarkResult<T> ok(T data, int httpStatus)
    {
        return new LarkResult<>(true, 0, "success", httpStatus, LarkErrorCategory.OK, false, data);
    }

    public static <T> LarkResult<T> fail(LarkErrorCategory category, Integer larkCode, String msg, int httpStatus)
    {
        return new LarkResult<>(false, larkCode, msg, httpStatus, category, false, null);
    }

    public static <T> LarkResult<T> tokenFail(LarkErrorCategory category, Integer larkCode, String msg, int httpStatus)
    {
        return new LarkResult<>(false, larkCode, msg, httpStatus, category, true, null);
    }

    /** 把失败结果换成另一种数据类型（失败时没有数据） */
    public <R> LarkResult<R> castFailure()
    {
        if (success)
        {
            throw new IllegalStateException("成功结果不能转换为失败结果");
        }
        return new LarkResult<>(false, larkCode, msg, httpStatus, category, tokenFailure, null);
    }

    public boolean isSuccess()
    {
        return success;
    }

    public Integer getLarkCode()
    {
        return larkCode;
    }

    public String getMsg()
    {
        return msg;
    }

    public int getHttpStatus()
    {
        return httpStatus;
    }

    public LarkErrorCategory getCategory()
    {
        return category;
    }

    public boolean isTokenFailure()
    {
        return tokenFailure;
    }

    /** 发送失败是因为机器人已不在群里、群已解散或 chat_id 无效：调用方应把该群置为失效 */
    public boolean isChatGone()
    {
        return !success && LarkErrorCodes.isChatGone(larkCode);
    }

    public T getData()
    {
        return data;
    }

    @Override
    public String toString()
    {
        return "LarkResult{success=" + success + ", category=" + category + ", larkCode=" + larkCode
                + ", httpStatus=" + httpStatus + ", tokenFailure=" + tokenFailure + ", msg=" + msg + "}";
    }
}
