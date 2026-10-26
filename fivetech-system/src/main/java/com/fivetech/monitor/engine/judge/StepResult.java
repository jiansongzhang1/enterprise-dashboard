package com.fivetech.monitor.engine.judge;

import java.time.LocalDateTime;

/**
 * 判定一个周期的结果：周期等级，以及对事件做什么。
 *
 * @author fivetech
 */
public class StepResult
{
    /** 对事件的动作 */
    public enum Action
    {
        /** 不动事件（未触发、或事件中途未判定） */
        NONE,
        /** 新开事件 */
        OPEN,
        /** 事件仍异常中，按本周期重算等级 */
        UPDATE,
        /** 事件恢复 */
        RESOLVE
    }

    private String level;

    private Action action = Action.NONE;

    /** OPEN / UPDATE 后事件的当前等级 */
    private String eventLevel;

    /** 由注意升级为严重 */
    private boolean escalated;

    /** OPEN：事件起点（最初越线的周期开始，最多回看 lookbackHours） */
    private LocalDateTime startHour;

    /** OPEN：触发时间（第 N 个越线周期的开始） */
    private LocalDateTime triggerHour;

    /** RESOLVE：回到正常范围的那个周期的开始（= 最后一个越线周期的结束） */
    private LocalDateTime endHour;

    private RunState next;

    public String getLevel()
    {
        return level;
    }

    public void setLevel(String level)
    {
        this.level = level;
    }

    public Action getAction()
    {
        return action;
    }

    public void setAction(Action action)
    {
        this.action = action;
    }

    public String getEventLevel()
    {
        return eventLevel;
    }

    public void setEventLevel(String eventLevel)
    {
        this.eventLevel = eventLevel;
    }

    public boolean isEscalated()
    {
        return escalated;
    }

    public void setEscalated(boolean escalated)
    {
        this.escalated = escalated;
    }

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

    public RunState getNext()
    {
        return next;
    }

    public void setNext(RunState next)
    {
        this.next = next;
    }
}
