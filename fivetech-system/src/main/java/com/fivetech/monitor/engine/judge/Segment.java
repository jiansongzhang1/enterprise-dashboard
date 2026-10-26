package com.fivetech.monitor.engine.judge;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 回测里的一次触发（越线段）：开始、等级、持续、最差值。
 *
 * @author fivetech
 */
public class Segment
{
    private LocalDateTime startHour;

    private LocalDateTime triggerHour;

    /** 回到正常范围的那个周期的开始；到窗口末尾仍异常时为 null */
    private LocalDateTime endHour;

    private boolean ongoing;

    private long durationHours;

    /** 最后一次判定后的等级 */
    private String level;

    private boolean escalated;

    /** 段内越线周期在输入序列里的下标 */
    private final List<Integer> pointIndexes = new ArrayList<>();

    /** 最差周期的下标；段内没有有效值时为 -1 */
    private int worstIndex = -1;

    public LocalDateTime getStartHour()
    {
        return startHour;
    }

    public void setStartHour(LocalDateTime startHour)
    {
        this.startHour = startHour;
    }

    public LocalDateTime getTriggerHour()
    {
        return triggerHour;
    }

    public void setTriggerHour(LocalDateTime triggerHour)
    {
        this.triggerHour = triggerHour;
    }

    public LocalDateTime getEndHour()
    {
        return endHour;
    }

    public void setEndHour(LocalDateTime endHour)
    {
        this.endHour = endHour;
    }

    public boolean isOngoing()
    {
        return ongoing;
    }

    public void setOngoing(boolean ongoing)
    {
        this.ongoing = ongoing;
    }

    public long getDurationHours()
    {
        return durationHours;
    }

    public void setDurationHours(long durationHours)
    {
        this.durationHours = durationHours;
    }

    public String getLevel()
    {
        return level;
    }

    public void setLevel(String level)
    {
        this.level = level;
    }

    public boolean isEscalated()
    {
        return escalated;
    }

    public void setEscalated(boolean escalated)
    {
        this.escalated = escalated;
    }

    public List<Integer> getPointIndexes()
    {
        return pointIndexes;
    }

    public int getWorstIndex()
    {
        return worstIndex;
    }

    public void setWorstIndex(int worstIndex)
    {
        this.worstIndex = worstIndex;
    }
}
