package com.fivetech.monitor.engine.judge;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨周期的运行状态（对应 monitor_rule_state）。回测预览从空状态开始。
 *
 * @author fivetech
 */
public class RunState
{
    /** 本段连续越线的第一个周期开始 */
    private LocalDateTime runStartHour;

    /**
     * 本段连续越线中最近 N 个周期的等级（N = 持续次数）。没有异常中的事件时，攒满 N 个即触发；
     * 有事件时用于「最近 N 个周期全是严重」。中断（正常、未判定、断档）时清空。
     */
    private List<String> recentLevels = new ArrayList<>();

    /** 上一个已处理周期的结束；新周期的开始晚于它说明中间断了（服务停过），按中断处理 */
    private LocalDateTime lastPeriodEnd;

    /** 是否有异常中的事件（每小时判定时来自 monitor_event，不信任状态表里的 open_event_id） */
    private boolean eventOpen;

    /** 异常中事件的触发时间：注意升级按「从触发时间起已持续满 N 小时」计算 */
    private LocalDateTime eventTriggerHour;

    public static RunState empty()
    {
        return new RunState();
    }

    public RunState copy()
    {
        RunState s = new RunState();
        s.runStartHour = runStartHour;
        s.recentLevels = new ArrayList<>(recentLevels);
        s.lastPeriodEnd = lastPeriodEnd;
        s.eventOpen = eventOpen;
        s.eventTriggerHour = eventTriggerHour;
        return s;
    }

    void resetRun()
    {
        runStartHour = null;
        recentLevels = new ArrayList<>();
    }

    void push(String level, int keep)
    {
        recentLevels.add(level);
        while (recentLevels.size() > keep)
        {
            recentLevels.remove(0);
        }
    }

    public LocalDateTime getRunStartHour()
    {
        return runStartHour;
    }

    public void setRunStartHour(LocalDateTime runStartHour)
    {
        this.runStartHour = runStartHour;
    }

    public List<String> getRecentLevels()
    {
        return recentLevels;
    }

    public void setRecentLevels(List<String> recentLevels)
    {
        this.recentLevels = recentLevels == null ? new ArrayList<>() : new ArrayList<>(recentLevels);
    }

    public LocalDateTime getLastPeriodEnd()
    {
        return lastPeriodEnd;
    }

    public void setLastPeriodEnd(LocalDateTime lastPeriodEnd)
    {
        this.lastPeriodEnd = lastPeriodEnd;
    }

    public boolean isEventOpen()
    {
        return eventOpen;
    }

    public void setEventOpen(boolean eventOpen)
    {
        this.eventOpen = eventOpen;
    }

    public LocalDateTime getEventTriggerHour()
    {
        return eventTriggerHour;
    }

    public void setEventTriggerHour(LocalDateTime eventTriggerHour)
    {
        this.eventTriggerHour = eventTriggerHour;
    }
}
