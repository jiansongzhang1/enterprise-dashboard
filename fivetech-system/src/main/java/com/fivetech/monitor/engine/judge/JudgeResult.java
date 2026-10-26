package com.fivetech.monitor.engine.judge;

import java.util.ArrayList;
import java.util.List;

/**
 * 一串周期的判定结果（回测预览用；每小时判定只用 step）。
 *
 * @author fivetech
 */
public class JudgeResult
{
    /** 每个周期的等级 OK / WARN / CRIT / SKIP，与输入一一对应 */
    private final List<String> levels = new ArrayList<>();

    /** 越线段，按时间正序 */
    private final List<Segment> segments = new ArrayList<>();

    /** 越线（注意或严重）的周期数 */
    private int overPeriods;

    /** 其中不属于任何触发段的周期数（零星越线，不满连续 N 次） */
    private int isolatedPeriods;

    private RunState next;

    public List<String> getLevels()
    {
        return levels;
    }

    public List<Segment> getSegments()
    {
        return segments;
    }

    public int getOverPeriods()
    {
        return overPeriods;
    }

    public void setOverPeriods(int overPeriods)
    {
        this.overPeriods = overPeriods;
    }

    public int getIsolatedPeriods()
    {
        return isolatedPeriods;
    }

    public void setIsolatedPeriods(int isolatedPeriods)
    {
        this.isolatedPeriods = isolatedPeriods;
    }

    public RunState getNext()
    {
        return next;
    }

    public void setNext(RunState next)
    {
        this.next = next;
    }

    public int countLevel(String level)
    {
        int n = 0;
        for (Segment s : segments)
        {
            if (level.equals(s.getLevel()))
            {
                n++;
            }
        }
        return n;
    }
}
