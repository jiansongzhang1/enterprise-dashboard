package com.fivetech.dashboard.domain.vo.overview;

import java.io.Serializable;

/**
 * 板块的查询区间回显。
 *
 * @author fivetech
 */
public class SectionSlotVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 区间开始（含） */
    private String from;

    /** 区间结束（不含） */
    private String to;

    /** 粒度；队列 / 快照类板块为 null */
    private String granularity;

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

    public String getGranularity()
    {
        return granularity;
    }

    public void setGranularity(String granularity)
    {
        this.granularity = granularity;
    }
}
