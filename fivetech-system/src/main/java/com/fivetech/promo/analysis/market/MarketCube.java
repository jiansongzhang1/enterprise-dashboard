package com.fivetech.promo.analysis.market;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * 一个时间范围内、按时间桶（小时 / 日）和注册终端拆好的市场数据。
 *
 * <p>终端键：{@code all}（宽表不带前缀的指标；精确指标为各终端相加）、{@code web}、{@code android}、{@code ios}、{@code unknown}。
 * 宽表的终端值来自带前缀的指标（{@code web_reg} …），精确指标来自按 terminal 分组。精确人数是每个桶内去重，
 * 多个桶相加即页面的「按日累加」（所以合计必须用按日的 cube）。</p>
 *
 * <p>没有任何行 = 没有数据（null），不是 0。</p>
 *
 * @author fivetech
 */
public final class MarketCube
{
    public static final String ALL = "all";

    /** 桶起点 → 终端键 → 指标 → 值 */
    private final Map<LocalDateTime, Map<String, Map<String, BigDecimal>>> buckets = new TreeMap<>();

    /** 本站整集缺口（meta.omitted 只有 dataset，没有 metric） */
    private boolean omitted;

    /** 响应 meta 里的新鲜度（观察值，不是完成水位） */
    private LocalDateTime watermark;

    void setWatermark(LocalDateTime watermark)
    {
        this.watermark = watermark;
    }

    public LocalDateTime getWatermark()
    {
        return watermark;
    }

    public void add(LocalDateTime bucket, String terminal, String metric, BigDecimal value)
    {
        if (bucket == null || value == null)
        {
            return;
        }
        buckets.computeIfAbsent(bucket, k -> new LinkedHashMap<>())
            .computeIfAbsent(terminal, k -> new LinkedHashMap<>())
            .merge(metric, value, BigDecimal::add);
    }

    void setOmitted(boolean omitted)
    {
        this.omitted = omitted;
    }

    public boolean isOmitted()
    {
        return omitted;
    }

    public boolean isEmpty()
    {
        return buckets.isEmpty();
    }

    public java.util.Set<LocalDateTime> bucketStarts()
    {
        return buckets.keySet();
    }

    /** 满足条件的桶里某终端某指标之和；一个值都没有返回 null */
    public BigDecimal sum(String metric, String terminal, Predicate<LocalDateTime> bucketFilter)
    {
        BigDecimal total = null;
        for (Map.Entry<LocalDateTime, Map<String, Map<String, BigDecimal>>> e : buckets.entrySet())
        {
            if (bucketFilter != null && !bucketFilter.test(e.getKey()))
            {
                continue;
            }
            Map<String, BigDecimal> m = e.getValue().get(terminal);
            BigDecimal v = m == null ? null : m.get(metric);
            if (v != null)
            {
                total = total == null ? v : total.add(v);
            }
        }
        return total;
    }

    /** 有没有任何一个桶（满足条件的） */
    public boolean any(Predicate<LocalDateTime> bucketFilter)
    {
        return buckets.keySet().stream().anyMatch(b -> bucketFilter == null || bucketFilter.test(b));
    }
}
