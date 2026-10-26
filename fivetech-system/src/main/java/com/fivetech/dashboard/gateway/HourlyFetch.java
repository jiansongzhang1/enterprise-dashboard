package com.fivetech.dashboard.gateway;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 监控判定引擎的一次小时取数结果（{@link MetricDataGateway#queryHourly}）。
 * <p>值是 UDS 原始量纲（比率 0～1、时长秒），换算由调用方按指标做；无数据为 null，不是 0。</p>
 *
 * @author fivetech
 */
public class HourlyFetch
{
    private final LocalDateTime from;

    private final int points;

    /** UDS 返回 4403（数据未就绪）：本次没有数据，不是故障 */
    private boolean notReady;

    /** 本次响应自报的水位（meta.freshness.watermark），右开上界；取不到为 null */
    private LocalDateTime watermark;

    /** key：指标编码，或调用方给的附加列的 key；value：逐小时值，长度 = points */
    private final Map<String, List<BigDecimal>> series = new LinkedHashMap<>();

    public HourlyFetch(LocalDateTime from, int points)
    {
        this.from = from;
        this.points = Math.max(0, points);
    }

    /** 某个 key 的逐小时序列；没有取到时返回全 null 的序列 */
    public List<BigDecimal> get(String key)
    {
        List<BigDecimal> list = series.get(key);
        return list != null ? list : new ArrayList<>(Collections.nCopies(points, null));
    }

    /** 某个 key 在某个小时的值；越界或没有为 null */
    public BigDecimal at(String key, LocalDateTime hour)
    {
        int i = (int) java.time.temporal.ChronoUnit.HOURS.between(from, hour);
        List<BigDecimal> list = series.get(key);
        return list == null || i < 0 || i >= list.size() ? null : list.get(i);
    }

    public void put(String key, List<BigDecimal> values)
    {
        series.put(key, values);
    }

    /** 已取到的字段（指标编码、附加列 key） */
    public java.util.Set<String> keys()
    {
        return java.util.Collections.unmodifiableSet(series.keySet());
    }

    public boolean has(String key)
    {
        return series.containsKey(key);
    }

    public LocalDateTime getFrom()
    {
        return from;
    }

    public int getPoints()
    {
        return points;
    }

    public boolean isNotReady()
    {
        return notReady;
    }

    public void setNotReady(boolean notReady)
    {
        this.notReady = notReady;
    }

    public LocalDateTime getWatermark()
    {
        return watermark;
    }

    public void setWatermark(LocalDateTime watermark)
    {
        this.watermark = watermark;
    }
}
