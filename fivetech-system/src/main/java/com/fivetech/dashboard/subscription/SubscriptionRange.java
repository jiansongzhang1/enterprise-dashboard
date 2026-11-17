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
 *   <li>{@link #LAST_7D}、{@link #LAST_30D}：日粒度，含发送当天、截至发送整点（与运营总览一致，2026-11-13 确认），
 *       取 {@code dashboard_overview_day_v2}（人数类按日去重）与小时行合计；</li>
 *   <li>{@link #CUSTOM}：固定日期（订阅的 view_from – view_to）。</li>
 * </ul>
 * 对比期默认「上一周期」：今日对比昨日同一时段，昨日对比前日，近 N 天对比再往前 N 天；订阅也可以存自訂对比期。
 *
 * @author fivetech
 */
public enum SubscriptionRange
{
    /** 今日 00:00 至发送整点（发送时刻 00:00 没有数据，不允许） */
    TODAY("今日（截至發送整點）", "dashboard_overview_hour_v2", Granularity.HOUR),

    /** 昨日全天 */
    YESTERDAY("昨日", "dashboard_overview_hour_v2", Granularity.HOUR),

    /** 近 7 天：含发送当天，截至发送整点（2026-11-13 起与运营总览一致；原为不含当天的 7 个完整自然日） */
    LAST_7D("近 7 天", "dashboard_overview_day_v2", Granularity.DAY),

    /** 近 30 天：含发送当天，截至发送整点 */
    LAST_30D("近 30 天", "dashboard_overview_day_v2", Granularity.DAY),

    /** 固定日期：订阅的 view_from – view_to（含两端），每次发送的都是同一段资料 */
    CUSTOM("固定日期", "dashboard_overview_day_v2", Granularity.DAY);

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
     * @param hour 发送整点（0–23）；今日、近 N 天截至这个整点
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
                return new LocalDateTime[] { day.minusDays(6), day.plusHours(hour) };
            case LAST_30D:
                return new LocalDateTime[] { day.minusDays(29), day.plusHours(hour) };
            default:
                throw new IllegalStateException("固定日期請用 window(from, to)");
        }
    }

    /** 固定日期的统计区间：[from 00:00, to 次日 00:00) */
    public static LocalDateTime[] window(LocalDate from, LocalDate to)
    {
        return new LocalDateTime[] { from.atStartOfDay(), to.plusDays(1).atStartOfDay() };
    }
}
