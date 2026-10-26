package com.fivetech.monitor.exception;

/**
 * 监控模块的自定义错误码（与现有 4011 / 4012 不冲突）。
 *
 * @author fivetech
 */
public final class MonitorErrorCode
{
    /** 参数校验失败，data.fields 列出字段级错误 */
    public static final int BAD_REQUEST = 400;

    /** 无站点权限 */
    public static final int FORBIDDEN = 403;

    /** 规则 / 事件不存在、已删除，或不属于当前站点 */
    public static final int NOT_FOUND = 404;

    /** 该指标已有规则（一个指标一条） */
    public static final int METRIC_OCCUPIED = 4801;

    /** Lark 凭证无效（取 token 失败），拉取群列表时返回 */
    public static final int LARK_CREDENTIAL_INVALID = 4802;

    /** 通知群已失效，不能被选用 */
    public static final int CHAT_DEAD = 4805;

    /** 群测试消息发送失败（网络超时、Lark 限流、无发言权限等），message 为繁体原因 */
    public static final int LARK_SEND_FAILED = 4808;

    /** 本站启用中的规则已达上限（monitor.max-enabled-rules-per-site，默认 40） */
    public static final int RULE_LIMIT = 4807;

    private MonitorErrorCode()
    {
    }
}
