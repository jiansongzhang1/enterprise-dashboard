package com.fivetech.risk.data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 风控取数的公共筛选：时间（站点时区，左闭右开）、游戏平台 / 品牌 / 类型、推广商 / 推广渠道。
 *
 * @author fivetech
 */
public final class RiskFilter
{
    public final String siteCode;

    public final LocalDateTime from;

    public final LocalDateTime to;

    public final List<String> platforms;

    public final List<String> brands;

    public final List<String> gameTypes;

    public final List<String> agents;

    public final List<String> channels;

    /** 只取这些用户（用户详情抽屉）；空 = 不限 */
    public final List<String> userIds;

    public RiskFilter(String siteCode, LocalDateTime from, LocalDateTime to, List<String> platforms, List<String> brands,
        List<String> gameTypes, List<String> agents, List<String> channels)
    {
        this.siteCode = siteCode;
        this.from = from;
        this.to = to;
        this.platforms = copy(platforms);
        this.brands = copy(brands);
        this.gameTypes = copy(gameTypes);
        this.agents = copy(agents);
        this.channels = copy(channels);
        this.userIds = List.of();
    }

    /** 同上，只取 userIds 这些用户 */
    public RiskFilter(String siteCode, LocalDateTime from, LocalDateTime to, List<String> userIds)
    {
        this.siteCode = siteCode;
        this.from = from;
        this.to = to;
        this.platforms = List.of();
        this.brands = List.of();
        this.gameTypes = List.of();
        this.agents = List.of();
        this.channels = List.of();
        this.userIds = copy(userIds);
    }

    private static List<String> copy(List<String> in)
    {
        List<String> out = new ArrayList<>();
        if (in != null)
        {
            for (String s : in)
            {
                if (s != null && !s.isBlank() && !out.contains(s.trim()))
                {
                    out.add(s.trim());
                }
            }
        }
        return List.copyOf(out);
    }

    /** 缓存键：同一站点、同一时间与筛选复用同一份计算结果 */
    public String key()
    {
        return siteCode + "|" + from + "|" + to + "|" + platforms + "|" + brands + "|" + gameTypes + "|" + agents + "|" + channels + "|" + userIds;
    }

    /** 选了游戏平台（游戏组的列只算这些平台） */
    public boolean hasGameFilter()
    {
        return !platforms.isEmpty() || !brands.isEmpty() || !gameTypes.isEmpty();
    }
}
