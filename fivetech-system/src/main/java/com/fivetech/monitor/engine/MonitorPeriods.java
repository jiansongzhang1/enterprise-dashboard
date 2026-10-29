package com.fivetech.monitor.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.fivetech.monitor.engine.judge.Levels;

/**
 * 规则运行状态 recent_periods（逐周期 {from, to, value, baseline, level, escalated}）的取舍。
 *
 * <p>MON-18：原来只保留最后 durationCount 个周期，最新周期未判定（样本不足 / 无数据）时判定窗口里就是空值，
 * 原因句写成「近 1 小時 —」。现在保留最近 durationCount 个已判定的周期以及之后的未判定周期，
 * 原因句、提醒条、Lark 消息都取「最近 durationCount 个已判定的周期」，停在上一次有效判定（FY-23 R15）。</p>
 *
 * @author fivetech
 */
public final class MonitorPeriods
{
    /** 已判定周期之后最多再保留多少个未判定周期（防止长时间无数据时无限增长） */
    static final int MAX_TRAILING_SKIPS = 72;

    private MonitorPeriods()
    {
    }

    /** 已判定（OK / WARN / CRIT） */
    public static boolean judged(Map<String, Object> p)
    {
        Object level = p == null ? null : p.get("level");
        return level != null && !Levels.SKIP.equals(String.valueOf(level)) && !"null".equals(String.valueOf(level));
    }

    /** 写回前裁剪：保留最近 n 个已判定周期及其后的未判定周期，总数不超过 n + {@link #MAX_TRAILING_SKIPS} */
    public static void trim(List<Map<String, Object>> periods, int n)
    {
        int keep = Math.max(1, n);
        int judged = 0;
        int cut = 0;
        for (int i = periods.size() - 1; i >= 0; i--)
        {
            if (judged(periods.get(i)) && ++judged == keep)
            {
                cut = i;
                break;
            }
        }
        if (cut > 0)
        {
            periods.subList(0, cut).clear();
        }
        while (periods.size() > keep + MAX_TRAILING_SKIPS)
        {
            periods.remove(0);
        }
    }

    /** 判定窗口：最近 n 个已判定周期（时间正序）；一个都没有时退回最后 n 个周期 */
    public static List<Map<String, Object>> window(List<Map<String, Object>> periods, int n)
    {
        int keep = Math.max(1, n);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = periods.size() - 1; i >= 0 && out.size() < keep; i--)
        {
            if (judged(periods.get(i)))
            {
                out.add(0, periods.get(i));
            }
        }
        if (out.isEmpty())
        {
            int from = Math.max(0, periods.size() - keep);
            out.addAll(periods.subList(from, periods.size()));
        }
        return out;
    }

    /** 最后一个周期（不论是否已判定）的等级；没有为 null */
    public static String lastLevel(List<Map<String, Object>> periods)
    {
        if (periods.isEmpty())
        {
            return null;
        }
        Object level = periods.get(periods.size() - 1).get("level");
        return level == null ? null : String.valueOf(level);
    }
}
