package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import com.fivetech.dashboard.enums.Granularity;

/**
 * 查询上下文：所有仪表板接口都必须返回。
 * <p>
 * 统计区间、数据截止、计算完成是三个不同的时间，混用就会出现
 * 「区间写 14:03、顶栏写 19:33」这种自相矛盾。前端一律以本对象为准，
 * 不得自行取当前时间。
 *
 * @author fivetech
 */
public class QueryContext implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 站点编码 */
    private String siteCode;

    /** 统计时区，如 Asia/Kolkata */
    private String timezone;

    /** 币别，如 INR */
    private String currency;

    /** 主区间开始（含），yyyy-MM-dd HH:mm */
    private String spanFrom;

    /** 主区间结束（含），yyyy-MM-dd HH:mm */
    private String spanTo;

    /** 对比区间开始，无对比时为空 */
    private String compareFrom;

    /** 对比区间结束，无对比时为空 */
    private String compareTo;

    /** 本次生效的粒度 */
    private Granularity granularity;

    /** 当前区间下可选的粒度，由服务端计算，前端只做渲染 */
    private List<Granularity> availableGranularities = new ArrayList<>();

    /** 数据截止时间：已完整落库的最后一个整点 */
    private String asOf;

    /** 计算完成时间：上游任务写完的时间 */
    private String updatedAt;

    /** 指标口径版本 */
    private String registryVersion;

    /** 本区间是否含延迟数据，为 true 时前端展示提示条 */
    private boolean delayed;

    /** 延迟窗口小时数 */
    private Integer delayWindowHours;

    /** 警告码，如 COMPARE_LENGTH_MISMATCH。不拦截请求，仅提示 */
    private List<String> warnings = new ArrayList<>();

    public void addWarning(String code)
    {
        if (code != null && !warnings.contains(code))
        {
            warnings.add(code);
        }
    }

    public String getSiteCode()
    {
        return siteCode;
    }

    public void setSiteCode(String siteCode)
    {
        this.siteCode = siteCode;
    }

    public String getTimezone()
    {
        return timezone;
    }

    public void setTimezone(String timezone)
    {
        this.timezone = timezone;
    }

    public String getCurrency()
    {
        return currency;
    }

    public void setCurrency(String currency)
    {
        this.currency = currency;
    }

    public String getSpanFrom()
    {
        return spanFrom;
    }

    public void setSpanFrom(String spanFrom)
    {
        this.spanFrom = spanFrom;
    }

    public String getSpanTo()
    {
        return spanTo;
    }

    public void setSpanTo(String spanTo)
    {
        this.spanTo = spanTo;
    }

    public String getCompareFrom()
    {
        return compareFrom;
    }

    public void setCompareFrom(String compareFrom)
    {
        this.compareFrom = compareFrom;
    }

    public String getCompareTo()
    {
        return compareTo;
    }

    public void setCompareTo(String compareTo)
    {
        this.compareTo = compareTo;
    }

    public Granularity getGranularity()
    {
        return granularity;
    }

    public void setGranularity(Granularity granularity)
    {
        this.granularity = granularity;
    }

    public List<Granularity> getAvailableGranularities()
    {
        return availableGranularities;
    }

    public void setAvailableGranularities(List<Granularity> availableGranularities)
    {
        this.availableGranularities = availableGranularities;
    }

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

    public String getRegistryVersion()
    {
        return registryVersion;
    }

    public void setRegistryVersion(String registryVersion)
    {
        this.registryVersion = registryVersion;
    }

    public boolean isDelayed()
    {
        return delayed;
    }

    public void setDelayed(boolean delayed)
    {
        this.delayed = delayed;
    }

    public Integer getDelayWindowHours()
    {
        return delayWindowHours;
    }

    public void setDelayWindowHours(Integer delayWindowHours)
    {
        this.delayWindowHours = delayWindowHours;
    }

    public List<String> getWarnings()
    {
        return warnings;
    }

    public void setWarnings(List<String> warnings)
    {
        this.warnings = warnings;
    }
}
