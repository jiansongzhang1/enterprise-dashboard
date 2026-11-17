package com.fivetech.risk.rank;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次筛选（站点 + 时间 + 平台 + 渠道）汇总出的用户数据，按筛选缓存，翻页、换列、换排行字段、导出都复用。
 *
 * @author fivetech
 */
public final class RiskRankData
{
    /** userId → 用户 */
    final Map<String, RiskUser> users;

    /** 这次取不到的列：编码 → 原因（如游戏数据未接入） */
    final Map<String, String> unavailable;

    /** 用户数据未接入 */
    final boolean pending;

    final String asOf;

    RiskRankData(Map<String, RiskUser> users, Map<String, String> unavailable, boolean pending, String asOf)
    {
        this.users = users;
        this.unavailable = unavailable == null ? new LinkedHashMap<>() : unavailable;
        this.pending = pending;
        this.asOf = asOf;
    }

    public Map<String, RiskUser> getUsers()
    {
        return users;
    }

    public Map<String, String> getUnavailable()
    {
        return unavailable;
    }

    public boolean isPending()
    {
        return pending;
    }

    public String getAsOf()
    {
        return asOf;
    }
}
