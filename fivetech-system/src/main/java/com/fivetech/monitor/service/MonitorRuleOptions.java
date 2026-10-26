package com.fivetech.monitor.service;

import java.util.List;
import java.util.Map;

/**
 * 规则参数的可选值，按检测周期区分（与原型下拉框一致）。
 *
 * <ul>
 *   <li>检测周期：1 / 4 / 6 / 12 / 24 小时</li>
 *   <li>注意升级：每小时 2/3/6/12，每 4 小时 4/8/12/24，每 6 小时 6/12/24，每 12 小时 12/24，每 24 小时 24/48</li>
 *   <li>静默期：每小时 1/2/4/12，其余周期同注意升级</li>
 *   <li>持续次数上限：每小时 4，周期 &lt; 12 小时 3，其余 2</li>
 * </ul>
 *
 * @author fivetech
 */
public final class MonitorRuleOptions
{
    public static final List<Integer> PERIODS = List.of(1, 4, 6, 12, 24);

    private static final Map<Integer, List<Integer>> ESCALATE = Map.of(
            1, List.of(2, 3, 6, 12),
            4, List.of(4, 8, 12, 24),
            6, List.of(6, 12, 24),
            12, List.of(12, 24),
            24, List.of(24, 48));

    private static final Map<Integer, List<Integer>> MUTE = Map.of(
            1, List.of(1, 2, 4, 12),
            4, List.of(4, 8, 12, 24),
            6, List.of(6, 12, 24),
            12, List.of(12, 24),
            24, List.of(24, 48));

    private MonitorRuleOptions()
    {
    }

    public static boolean isValidPeriod(Integer periodHours)
    {
        return periodHours != null && PERIODS.contains(periodHours);
    }

    public static List<Integer> escalateOptions(int periodHours)
    {
        return ESCALATE.getOrDefault(periodHours, List.of());
    }

    public static List<Integer> muteOptions(int periodHours)
    {
        return MUTE.getOrDefault(periodHours, List.of());
    }

    public static int maxDuration(int periodHours)
    {
        if (periodHours == 1)
        {
            return 4;
        }
        return periodHours < 12 ? 3 : 2;
    }

    /** 2/3/6/12 这种显示文案 */
    public static String join(List<Integer> values)
    {
        StringBuilder sb = new StringBuilder();
        for (Integer v : values)
        {
            if (sb.length() > 0)
            {
                sb.append('/');
            }
            sb.append(v);
        }
        return sb.toString();
    }
}
