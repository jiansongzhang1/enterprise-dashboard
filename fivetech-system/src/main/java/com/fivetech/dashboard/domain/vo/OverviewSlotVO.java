package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import com.fivetech.dashboard.enums.Granularity;

/**
 * 主区间。<b>左闭右开</b>：{@code from = 09:00, to = 10:00, HOUR} 是 1 个时间片。
 * <p>
 * {@code points} 由服务端算出并回显，前端不要自己推——它同时是
 * {@code series} 数组长度的契约。
 *
 * @author fivetech
 */
public class OverviewSlotVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    private String from;

    private String to;

    private Granularity granularity;

    private int points;

    /**
     * 有数据的时间片个数（从第 0 片起），即统计区间 from ~ to 内的时间片数。
     * 区间包含今天且按小时时，序列补齐到今天 24:00（points 含未来的小时，值为 null），此时 dataPoints &lt; points；其他情况两者相等
     */
    private int dataPoints;

    /** 每个时间片的展示标签，长度等于 points */
    private List<String> labels = new ArrayList<>();

    public String getFrom()
    {
        return from;
    }

    public void setFrom(String from)
    {
        this.from = from;
    }

    public String getTo()
    {
        return to;
    }

    public void setTo(String to)
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

    public int getDataPoints()
    {
        return dataPoints;
    }

    public void setDataPoints(int dataPoints)
    {
        this.dataPoints = dataPoints;
    }

    public List<String> getLabels()
    {
        return labels;
    }

    public void setLabels(List<String> labels)
    {
        this.labels = labels;
    }
}
