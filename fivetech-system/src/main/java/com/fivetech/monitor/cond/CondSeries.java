package com.fivetech.monitor.cond;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;

/**
 * 一个「字段 + 范围」的小时数据（站点时区的整点 → UDS 列 → 值）。
 *
 * <p>UDS 按维度筛选时，某小时没有任何记录就不返回那一行，所以缺的小时按 0 处理；判断「历史够不够」用最早有记录的小时
 * {@link #getFirstHour()}：基准要求最早记录不晚于基准时段的起点。</p>
 *
 * @author fivetech
 */
public class CondSeries
{
    private final TreeMap<LocalDateTime, Map<String, BigDecimal>> hours = new TreeMap<>();

    /** 本次取数的起点（之前的数据没取，不能说没有） */
    private final LocalDateTime fetchedFrom;

    public CondSeries(LocalDateTime fetchedFrom)
    {
        this.fetchedFrom = fetchedFrom;
    }

    public void put(LocalDateTime hour, Map<String, BigDecimal> values)
    {
        hours.put(hour, values);
    }

    /** 这一小时某列的值；没有记录为 0 */
    public BigDecimal get(LocalDateTime hour, String column)
    {
        Map<String, BigDecimal> m = hours.get(hour);
        BigDecimal v = m == null ? null : m.get(column);
        return v == null ? BigDecimal.ZERO : v;
    }

    public boolean has(LocalDateTime hour)
    {
        return hours.containsKey(hour);
    }

    /** 最早有记录的小时；没有任何记录为 null */
    public LocalDateTime getFirstHour()
    {
        return hours.isEmpty() ? null : hours.firstKey();
    }

    /**
     * 从 start 起的数据够不够：有记录，且最早的记录不晚于 start。取数起点本身就晚于 start 时视为不够。
     */
    public boolean covers(LocalDateTime start)
    {
        LocalDateTime first = getFirstHour();
        return first != null && !first.isAfter(start) && !fetchedFrom.isAfter(start);
    }

    public LocalDateTime getFetchedFrom()
    {
        return fetchedFrom;
    }

    public int size()
    {
        return hours.size();
    }
}
