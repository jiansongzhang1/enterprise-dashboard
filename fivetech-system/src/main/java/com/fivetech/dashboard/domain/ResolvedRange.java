package com.fivetech.dashboard.domain;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import com.fivetech.dashboard.enums.Granularity;

/**
 * 解析后的统计区间。
 * <p>
 * 所有下游查询只认这里的确定时间戳，不再接触 rangeType 这类枚举。
 *
 * @author fivetech
 */
public class ResolvedRange implements Serializable
{
    private static final long serialVersionUID = 1L;

    public static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 区间开始（含） */
    private LocalDateTime from;

    /** 区间结束（不含），已截断到 asOf */
    private LocalDateTime to;

    /** 生效粒度 */
    private Granularity granularity;

    /** 时间片数量 */
    private int points;

    /** 是否为不限时间（仅记录表可用） */
    private boolean unbounded;

    public static ResolvedRange of(LocalDateTime from, LocalDateTime to, Granularity granularity)
    {
        ResolvedRange range = new ResolvedRange();
        range.from = from;
        range.to = to;
        range.granularity = granularity;
        return range;
    }

    public static ResolvedRange unbounded()
    {
        ResolvedRange range = new ResolvedRange();
        range.unbounded = true;
        return range;
    }

    public String formatFrom()
    {
        return from == null ? null : from.format(FMT);
    }

    public String formatTo()
    {
        return to == null ? null : to.format(FMT);
    }

    public LocalDateTime getFrom()
    {
        return from;
    }

    public void setFrom(LocalDateTime from)
    {
        this.from = from;
    }

    public LocalDateTime getTo()
    {
        return to;
    }

    public void setTo(LocalDateTime to)
    {
        this.to = to;
    }

    public Granularity getGranularity()
    {
        return granularity;
    }

    public void setGranularity(Granularity granularity)
    {
        this.granularity = granularity;
    }

    public int getPoints()
    {
        return points;
    }

    public void setPoints(int points)
    {
        this.points = points;
    }

    public boolean isUnbounded()
    {
        return unbounded;
    }

    public void setUnbounded(boolean unbounded)
    {
        this.unbounded = unbounded;
    }
}
