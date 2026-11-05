package com.fivetech.dashboard.subscription;

import java.time.LocalDate;
import java.time.LocalDateTime;
import com.fivetech.dashboard.enums.Granularity;

/**
 * 订阅简报的统计范围（预设、相对时间：每次发送时按站点时区往前算）。
 *
 * <p>数据都走运营总览同一套取数（{@code /dashboard/metrics/overview} 的服务层），所以和页面上看到的数一致：</p>
 * <ul>
 *   <li>{@link #TODAY}、{@link #YESTERDAY}：小时粒度，取 {@code dashboard_overview_hour_v2}；</li>
 *   <li>{@link #LAST_7D}、{@link #LAST_30D}：日粒度，完整自然日（不含发送当天），取 {@code dashboard_overview_day_v2}
 *       （人数类按日去重）与小时行合计。</li>
 * </ul>
 * 对比期一律「上一周期」：今日对比昨日同一时段，昨日对比前日，近 N 日对比再往前 N 日。
 *
 * @author fivetech
 */
public enum SubscriptionRange
{
    /** 今日 00:00 至发送整点（发送时刻 00:00 没有数据，不允许） */
    TODAY("今日（截至發送整點）", "dashboard_overview_hour_v2", Granularity.HOUR),

    /** 昨日全天 */
    YESTERDAY("昨日", "dashboard_overview_hour_v2", Granularity.HOUR),

    /** 近 7 日：发送日前 7 个完整自然日 */
    LAST_7D("近 7 日（不含當天）", "dashboard_overview_day_v2", Granularity.DAY),

    /** 近 30 日：发送日前 30 个完整自然日 */
    LAST_30D("近 30 日（不含當天）", "dashboard_overview_day_v2", Granularity.DAY);

    private final String label;

    private final String dataset;

    private final Granularity granularity;

    SubscriptionRange(String label, String dataset, Granularity granularity)
    {
        this.label = label;
        this.dataset = dataset;
        this.granularity = granularity;
    }

    public String getLabel()
    {
        return label;
    }

    public String getDataset()
    {
        return dataset;
    }

    public Granularity getGranularity()
    {
        return granularity;
    }

    /** 宽松解析（大小写、首尾空白）；不认识返回 null */
    public static SubscriptionRange of(String code)
    {
        if (code == null)
        {
            return null;
        }
        String c = code.trim().toUpperCase(java.util.Locale.ROOT);
        for (SubscriptionRange r : values())
        {
            if (r.name().equals(c))
            {
                return r;
            }
        }
        return null;
    }

    /**
     * 统计区间（左闭右开，站点时区的墙上时间）。
     *
     * @param date 发送日期
     * @param hour 发送整点（0–23）；只有 {@link #TODAY} 用到
     * @return [from, to]
     */
    public LocalDateTime[] window(LocalDate date, int hour)
    {
        LocalDateTime day = date.atStartOfDay();
        switch (this)
        {
            case TODAY:
                return new LocalDateTime[] { day, day.plusHours(hour) };
            case YESTERDAY:
                return new LocalDateTime[] { day.minusDays(1), day };
            case LAST_7D:
                return new LocalDateTime[] { day.minusDays(7), day };
            default:
                return new LocalDateTime[] { day.minusDays(30), day };
        }
    }
}
