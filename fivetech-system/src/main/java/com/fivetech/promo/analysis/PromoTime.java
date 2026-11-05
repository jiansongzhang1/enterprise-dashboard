package com.fivetech.promo.analysis;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.dashboard.enums.CompareType;
import com.fivetech.dashboard.enums.Granularity;

/**
 * 推广分析的时间规则：主区间、对比期（同时段对比）、粒度可选范围。时间左闭右开，按站点时区。
 *
 * @author fivetech
 */
public final class PromoTime
{
    public static final DateTimeFormatter SLOT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT);

    public static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    public static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    public static final DateTimeFormatter MMDD = DateTimeFormatter.ofPattern("MM-dd");

    public static final DateTimeFormatter COMPACT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 主区间最长 365 天 */
    public static final int MAX_DAYS = 365;

    private PromoTime()
    {
    }

    /** 主区间 [from, to) */
    public static final class Range
    {
        public final LocalDateTime from;
        public final LocalDateTime to;

        public Range(LocalDateTime from, LocalDateTime to)
        {
            this.from = from;
            this.to = to;
        }

        /** 覆盖的自然日数（首尾都算） */
        public long days()
        {
            return ChronoUnit.DAYS.between(from.toLocalDate(), to.minusNanos(1).toLocalDate()) + 1;
        }

        public LocalDate firstDay()
        {
            return from.toLocalDate();
        }

        /** 最后一个（含）自然日 */
        public LocalDate lastDay()
        {
            return to.minusNanos(1).toLocalDate();
        }
    }

    public static LocalDateTime parse(String text, String field, int code)
    {
        if (text == null || text.trim().isEmpty())
        {
            throw new ServiceException(field + " 不能為空（yyyy-MM-dd HH:mm）", code);
        }
        try
        {
            return LocalDateTime.parse(text.trim(), SLOT);
        }
        catch (RuntimeException e)
        {
            throw new ServiceException(field + " 格式應為 yyyy-MM-dd HH:mm", code);
        }
    }

    public static LocalDate parseDay(String text, String field, int code)
    {
        if (text == null || text.trim().isEmpty())
        {
            throw new ServiceException(field + " 不能為空（yyyy-MM-dd）", code);
        }
        try
        {
            return LocalDate.parse(text.trim(), YMD);
        }
        catch (RuntimeException e)
        {
            throw new ServiceException(field + " 格式應為 yyyy-MM-dd", code);
        }
    }

    /** 主区间：from < to、最多 365 天，否则 4001 */
    public static Range range(String slotFrom, String slotTo)
    {
        LocalDateTime from = parse(slotFrom, "slotFrom", PromoErrors.BAD_TIME);
        LocalDateTime to = parse(slotTo, "slotTo", PromoErrors.BAD_TIME);
        if (!to.isAfter(from))
        {
            throw new ServiceException("slotFrom 必須早於 slotTo（區間左閉右開）", PromoErrors.BAD_TIME);
        }
        if (Duration.between(from, to).compareTo(Duration.ofDays(MAX_DAYS)) > 0)
        {
            throw new ServiceException("查詢區間不得超過 " + MAX_DAYS + " 天", PromoErrors.BAD_TIME);
        }
        return new Range(from, to);
    }

    /**
     * 推广分析的粒度规则（与运营总览页面一致）：1 天只有小时；2–7 天小时 / 日；8–13 天只有日；≥ 14 天日 / 周。
     * 不合法的组合降级为最接近的可选粒度。
     */
    public static Granularity allowed(Range r, Granularity requested)
    {
        Granularity g = requested == null ? Granularity.HOUR : requested;
        long hours = ChronoUnit.HOURS.between(r.from, r.to);
        long days = (hours + 23) / 24;
        if (days <= 1)
        {
            return Granularity.HOUR;
        }
        if (days <= 7)
        {
            return g == Granularity.WEEK ? Granularity.DAY : g;
        }
        if (days <= 13)
        {
            return Granularity.DAY;
        }
        return g == Granularity.HOUR ? Granularity.DAY : g;
    }

    /**
     * 对比期（推广分析规则）。NONE 返回 null；LAST_YEAR 返回 4002；CUSTOM 须与主区间等长（cmpLock = true 时），否则 4002；
     * PREV_PERIOD：往前推「主区间覆盖的自然日数」天，对比期与主区间同一时刻结束（本期含今天时即同时段对比）。
     *
     * @param now 站点时区的当前整点；主区间终点已截到它时返回 cutAt
     */
    public static Map<String, Object> compare(CompareType type, String compareFrom, String compareTo, boolean cmpLock, Range r,
        LocalDateTime now)
    {
        CompareType t = type == null ? CompareType.PREV_PERIOD : type;
        if (t == CompareType.NONE)
        {
            return null;
        }
        if (t == CompareType.LAST_YEAR)
        {
            throw new ServiceException("推廣分析不支持去年同期對比", PromoErrors.BAD_COMPARE);
        }
        LocalDateTime cf;
        LocalDateTime ct;
        if (t == CompareType.CUSTOM)
        {
            cf = parse(compareFrom, "compareFrom", PromoErrors.BAD_COMPARE);
            ct = parse(compareTo, "compareTo", PromoErrors.BAD_COMPARE);
            if (!ct.isAfter(cf))
            {
                throw new ServiceException("compareFrom 必須早於 compareTo", PromoErrors.BAD_COMPARE);
            }
            if (cmpLock && !Duration.between(cf, ct).equals(Duration.between(r.from, r.to)))
            {
                throw new ServiceException("對比期長度需與主區間一致", PromoErrors.BAD_COMPARE);
            }
        }
        else
        {
            long days = r.days();
            cf = r.from.minusDays(days);
            ct = r.to.minusDays(days);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", cf.format(SLOT));
        m.put("to", ct.format(SLOT));
        m.put("type", t.name());
        m.put("cutAt", cutAt(r, now));
        return m;
    }

    /** 本期含今天（终点在今天之内）时返回终点时刻 HH:mm，否则 null */
    public static String cutAt(Range r, LocalDateTime now)
    {
        if (now == null)
        {
            return null;
        }
        LocalDate today = now.toLocalDate();
        boolean includesToday = r.to.isAfter(today.atStartOfDay()) && !r.from.isAfter(now);
        if (!includesToday)
        {
            return null;
        }
        LocalDateTime end = r.to.isAfter(now) ? now : r.to;
        return end.toLocalDate().equals(today) ? end.format(HHMM) : null;
    }

    /** 公共返回字段里的 slot */
    public static Map<String, Object> slot(Range r, Granularity granularity)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", r.from.format(SLOT));
        m.put("to", r.to.format(SLOT));
        m.put("granularity", granularity == null ? null : granularity.name());
        return m;
    }
}
