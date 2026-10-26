package com.fivetech.monitor.service;

/**
 * 监控模块的 Redis key。Redis 只是缓存，丢了从数据库重建；不要把只存在 Redis 里的状态当真。
 *
 * @author fivetech
 */
public final class MonitorCacheKeys
{
    /** 站点启用规则缓存（判定任务 / 总览读取） */
    public static String rules(String siteCode)
    {
        return "monitor:rules:" + siteCode;
    }

    /** 站点运行状态缓存（总览读取，读不到时从 monitor_rule_state 等重建） */
    public static String state(String siteCode)
    {
        return "monitor:state:" + siteCode;
    }

    private MonitorCacheKeys()
    {
    }
}
