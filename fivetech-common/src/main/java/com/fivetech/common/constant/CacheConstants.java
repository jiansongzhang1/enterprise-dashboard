package com.fivetech.common.constant;

/**
 * 缓存的key 常量
 * 
 * @author fivetech
 */
public class CacheConstants
{
    /**
     * 登录用户缓存 key
     */
    public static final String LOGIN_TOKEN_KEY = "login_tokens:";

    /**
     * 在线会话缓存 key 前缀。在线状态由 Redis TTL 判断，不写入用户表。
     */
    public static final String ONLINE_SESSION_KEY = "online_sessions:";

    /**
     * 参数管理 cache key
     */
    public static final String SYS_CONFIG_KEY = "sys_config:";

    /**
     * 字典管理 cache key
     */
    public static final String SYS_DICT_KEY = "sys_dict:";

    /**
     * 防重提交缓存 key
     */
    public static final String REPEAT_SUBMIT_KEY = "repeat_submit:";

    /**
     * 限流缓存 key
     */
    public static final String RATE_LIMIT_KEY = "rate_limit:";

    /**
     * 登录账户密码错误次数缓存 key
     */
    public static final String PWD_ERR_CNT_KEY = "pwd_err_cnt:";
}
