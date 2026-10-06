package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营总览 · 主要指标返回体。
 *
 * <p>{@code asOf} 是<b>已完整落库的最后一个整点</b>——10:37 查询时是 10:00，
 * 因为 10:00 那一行要等 11:00 才落库；{@code updatedAt} 只用于「更新于」展示，
 * <b>不参与区间计算</b>。两者混用会让统计区间莫名多出一小时。</p>
 *
 * <p>{@code blocks} 是 map 不是数组：前端按 key 取、不依赖顺序，
 * 某块失败时该 key 缺席、原因落到 {@code blockErrors}，其余块照常返回。
 * 整页白屏是最差的降级。</p>
 *
 * @author fivetech
 */
public class OverviewVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 已完整落库的最后一个整点；今日尚无完整时间片时为 null */
    private String asOf;

    /** 上游任务写完的时间，仅用于展示 */
    private String updatedAt;

    private OverviewSlotVO slot;

    /** compareType=NONE 时为 null */
    private OverviewCompareVO compare;

    private String currency;

    /** 数据更新频率：指标墙按小时落库，固定 hour */
    private String updateFrequency = "hour";

    private String timezone;

    private OverviewMonitoringVO monitoring;

    /** 上游数据版本，口径有争议时唯一能对齐的凭证 */
    private String dataVersion;

    /** 空结果也是 200，靠这个标记，不要用错误码表达「今天还没数据」 */
    private boolean empty;

    private List<OverviewNoticeVO> notices = new ArrayList<>();

    /** key 为 OverviewBlock 名，value 为 MetricsBlockVO */
    private Map<String, Object> blocks = new LinkedHashMap<>();

    /** 取数失败的块，key 与 blocks 互斥 */
    private Map<String, OverviewBlockErrorVO> blockErrors = new LinkedHashMap<>();

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

    public OverviewSlotVO getSlot()
    {
        return slot;
    }

    public void setSlot(OverviewSlotVO slot)
    {
        this.slot = slot;
    }

    public OverviewCompareVO getCompare()
    {
        return compare;
    }

    public void setCompare(OverviewCompareVO compare)
    {
        this.compare = compare;
    }

    public String getCurrency()
    {
        return currency;
    }

    public void setCurrency(String currency)
    {
        this.currency = currency;
    }

    public String getTimezone()
    {
        return timezone;
    }

    public void setTimezone(String timezone)
    {
        this.timezone = timezone;
    }

    public OverviewMonitoringVO getMonitoring()
    {
        return monitoring;
    }

    public void setMonitoring(OverviewMonitoringVO monitoring)
    {
        this.monitoring = monitoring;
    }

    public String getDataVersion()
    {
        return dataVersion;
    }

    public void setDataVersion(String dataVersion)
    {
        this.dataVersion = dataVersion;
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

    public Map<String, Object> getBlocks()
    {
        return blocks;
    }

    public void setBlocks(Map<String, Object> blocks)
    {
        this.blocks = blocks;
    }

    public Map<String, OverviewBlockErrorVO> getBlockErrors()
    {
        return blockErrors;
    }

    public void setBlockErrors(Map<String, OverviewBlockErrorVO> blockErrors)
    {
        this.blockErrors = blockErrors;
    }

    public String getUpdateFrequency()
    {
        return updateFrequency;
    }

    public void setUpdateFrequency(String updateFrequency)
    {
        this.updateFrequency = updateFrequency;
    }
}
