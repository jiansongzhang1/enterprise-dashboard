package com.fivetech.dashboard.gateway.uds;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * UDS 实时数据（2026-11-06 起 UDS 查询支持当前小时的实时数据）。
 *
 * <p>本系统内部区间左闭右开；UDS 小时列（uds_hh 等）的 {@code time.to} 是闭区间，平时发「最后一个包含的整点」（to 回退一小时）。
 * <b>查询今日或包含今日</b>——区间伸进正在进行的小时——时，{@code time.to} 改为按当前时间向右取整到整点
 * （站点时区，例：16:50 → 17:00），把正在进行的小时一起查出来。</p>
 *
 * @author fivetech
 */
public final class UdsRealtime
{
    private UdsRealtime()
    {
    }

    /** 当前时间向右取整到整点：16:50 → 17:00；正好整点时不变 */
    public static LocalDateTime ceilHour(LocalDateTime now)
    {
        LocalDateTime floor = now.truncatedTo(ChronoUnit.HOURS);
        return floor.equals(now) ? floor : floor.plusHours(1);
    }

    /**
     * 小时列的 time.to。
     *
     * @param toExclusive 内部区间终点（不含）
     * @param inclusive   平时发的值（最后一个包含的整点）
     * @param now         站点时区的现在；null 时不做实时调整
     * @return 区间终点晚于当前整点（伸进正在进行的小时）时为 {@link #ceilHour}，否则为 inclusive
     */
    public static LocalDateTime hourTo(LocalDateTime toExclusive, LocalDateTime inclusive, LocalDateTime now)
    {
        if (now == null || toExclusive == null)
        {
            return inclusive;
        }
        return toExclusive.isAfter(now.truncatedTo(ChronoUnit.HOURS)) ? ceilHour(now) : inclusive;
    }
}
