package com.fivetech.dashboard.gateway.uds;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一次 /v1/query 的结果：数据行 + 元信息。
 * <p>
 * meta 不是可有可无的装饰：{@code freshness.stale} 决定顶栏要不要打「数据延迟」标记，
 * {@code dataVersion} 是出现口径争议时唯一能对齐的凭证，
 * {@code binding} 说明这次查的是实时绑定还是离线绑定。丢掉它们，
 * 出问题时就只能靠「我记得当时是这个数」。
 *
 * @author fivetech
 */
public class UdsQueryResult
{
    private final List<UdsRow> rows;

    /** 实际命中的绑定，如 ops_hourly_rt */
    private final String binding;

    /** 数据版本号，出现在响应 meta.dataVersion */
    private final String dataVersion;

    /** 上游是否认为数据已过期 */
    private final boolean stale;

    /** 上游水位线；未提供时为 null，调用方不得自行编造 */
    private final LocalDateTime watermark;

    public UdsQueryResult(List<UdsRow> rows, String binding, String dataVersion,
            boolean stale, LocalDateTime watermark)
    {
        this.rows = rows;
        this.binding = binding;
        this.dataVersion = dataVersion;
        this.stale = stale;
        this.watermark = watermark;
    }

    public List<UdsRow> getRows()
    {
        return rows;
    }

    public String getBinding()
    {
        return binding;
    }

    public String getDataVersion()
    {
        return dataVersion;
    }

    public boolean isStale()
    {
        return stale;
    }

    public LocalDateTime getWatermark()
    {
        return watermark;
    }
}
