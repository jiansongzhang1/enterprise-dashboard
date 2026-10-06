package com.fivetech.dashboard.domain.vo.overview;

import com.fivetech.dashboard.domain.vo.OverviewNoticeVO;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 运营总览各板块响应的公共部分。
 * <p>asOf 是数据截止（区间聚合类为上游水位，快照类为快照日 24:00），前端顶栏以它为准。
 *
 * @author fivetech
 */
public class OverviewSectionVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 数据截止 yyyy-MM-dd HH:mm */
    private String asOf;

    /** 上游计算完成时间 */
    private String updatedAt;

    /** 实际生效的区间（已按 asOf 截断） */
    private SectionSlotVO slot;

    /** 币别 */
    private String currency;

    /**
     * 数据更新频率：hour（按小时落库）/ day（T-1 日快照）。前端据此显示「每小时更新 / 每日更新」。
     * 默认 hour；按日快照的板块（留存与 LTV）在子类构造里改为 day。
     */
    private String updateFrequency = "hour";

    /** 统计时区 */
    private String timezone;

    /** 区间内没有任何数据 */
    private boolean empty = false;

    /** 提示，前端必须渲染 */
    private List<OverviewNoticeVO> notices = new ArrayList<>();

    public String getAsOf()
    {
        return asOf;
    }

    public void setAsOf(String asOf)
    {
        this.asOf = asOf;
    }

    public String getUpdatedAt()
    {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt)
    {
        this.updatedAt = updatedAt;
    }

    public SectionSlotVO getSlot()
    {
        return slot;
    }

    public void setSlot(SectionSlotVO slot)
    {
        this.slot = slot;
    }

    public String getCurrency()
    {
        return currency;
    }

    public void setCurrency(String currency)
    {
        this.currency = currency;
    }

    public String getUpdateFrequency()
    {
        return updateFrequency;
    }

    public void setUpdateFrequency(String updateFrequency)
    {
        this.updateFrequency = updateFrequency;
    }

    public String getTimezone()
    {
        return timezone;
    }

    public void setTimezone(String timezone)
    {
        this.timezone = timezone;
    }

    public boolean isEmpty()
    {
        return empty;
    }

    public void setEmpty(boolean empty)
    {
        this.empty = empty;
    }

    public List<OverviewNoticeVO> getNotices()
    {
        return notices;
    }

    public void setNotices(List<OverviewNoticeVO> notices)
    {
        this.notices = notices;
    }
}
