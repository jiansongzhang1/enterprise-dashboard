package com.fivetech.monitor.cond;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 把逐个检测点的命中结果切成连续段（纯函数）：连续命中达到 durationCount 次的段 = 一次触发（事件从第 durationCount 次开始），
 * 不够次数的段只算「孤立命中」。最后一个检测点仍在命中中的段为 open（異常中）。
 *
 * @author fivetech
 */
public final class CondRuns
{
    /** 一段连续命中：first / last 为首尾检测点，count 为检测点数，trigger 为第 durationCount 次的检测点（未触发为 null） */
    public record Run(LocalDateTime first, LocalDateTime last, int count, LocalDateTime trigger, boolean open, int fromIndex,
            int toIndex)
    {
        public boolean triggered()
        {
            return trigger != null;
        }
    }

    private CondRuns()
    {
    }

    public static List<Run> runs(List<CondEvaluator.Point> points, int durationCount)
    {
        List<Run> out = new ArrayList<>();
        int i = 0;
        while (i < points.size())
        {
            if (!points.get(i).hit())
            {
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < points.size() && points.get(j + 1).hit())
            {
                j++;
            }
            int count = j - i + 1;
            LocalDateTime trigger = count >= durationCount ? points.get(i + durationCount - 1).end() : null;
            out.add(new Run(points.get(i).end(), points.get(j).end(), count, trigger, j == points.size() - 1, i, j));
            i = j + 1;
        }
        return out;
    }

    /** 没有达到连续次数的命中检测点数 */
    public static int isolated(List<Run> runs)
    {
        int n = 0;
        for (Run r : runs)
        {
            if (!r.triggered())
            {
                n += r.count();
            }
        }
        return n;
    }
}
