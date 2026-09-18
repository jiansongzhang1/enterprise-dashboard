package com.fivetech.dashboard.gateway;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 数据新鲜度水位线，由外部数据平台提供。
 * <p>
 * asOf 是已完整落库的最后一个整点，updatedAt 是上游任务写完的时间。
 * 两者不同：前者决定统计区间能截到哪里，后者只是「更新于」的展示值。
 * <p>
 * 数据平台若无法提供水位线，asOf 只能由本系统推测，顶栏的两个时间就不可信，
 * 详见《可观测性与告警方案》与《后端设计方案》§12.1 的 SLA 约定。
 *
 * @author fivetech
 */
public class DataFreshness implements Serializable
{
    private static final long serialVersionUID = 1L;

    private LocalDateTime asOf;

    private LocalDateTime updatedAt;

    public static DataFreshness of(LocalDateTime asOf, LocalDateTime updatedAt)
    {
        DataFreshness freshness = new DataFreshness();
        freshness.asOf = asOf;
        freshness.updatedAt = updatedAt;
        return freshness;
    }

    public LocalDateTime getAsOf()
    {
        return asOf;
    }

    public void setAsOf(LocalDateTime asOf)
    {
        this.asOf = asOf;
    }

    public LocalDateTime getUpdatedAt()
    {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt)
    {
        this.updatedAt = updatedAt;
    }
}
