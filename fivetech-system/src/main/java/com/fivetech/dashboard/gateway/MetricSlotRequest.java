package com.fivetech.dashboard.gateway;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import com.fivetech.dashboard.enums.Granularity;

/**
 * 向外部数据平台请求指标时间序列的参数。
 * <p>
 * 注意这里传的是<b>已解析好的确定时间戳</b>与<b>指标编码</b>，
 * 不传 rangeType 这类前端枚举，也不传表名列名 —— 映射由网关实现内部完成。
 *
 * @author fivetech
 */
public class MetricSlotRequest implements Serializable
{
    private static final long serialVersionUID = 1L;

    private String siteCode;

    private LocalDateTime from;

    private LocalDateTime to;

    private Granularity granularity;

    /** 需要的指标编码 */
    private List<String> metricCodes = new ArrayList<>();

    /**
     * 数据权限过滤条件。
     * 由 Data Scope 解析得到（组织、渠道、指标集），网关实现必须原样下推，
     * 不允许在此之外放宽。
     */
    private ScopeFilter scopeFilter;

    public String getSiteCode()
    {
        return siteCode;
    }

    public void setSiteCode(String siteCode)
    {
        this.siteCode = siteCode;
    }

    public LocalDateTime getFrom()
    {
        return from;
    }

    public void setFrom(LocalDateTime from)
    {
        this.from = from;
    }

    public LocalDateTime getTo()
    {
        return to;
    }

    public void setTo(LocalDateTime to)
    {
        this.to = to;
    }

    public Granularity getGranularity()
    {
        return granularity;
    }

    public void setGranularity(Granularity granularity)
    {
        this.granularity = granularity;
    }

    public List<String> getMetricCodes()
    {
        return metricCodes;
    }

    public void setMetricCodes(List<String> metricCodes)
    {
        this.metricCodes = metricCodes;
    }

    public ScopeFilter getScopeFilter()
    {
        return scopeFilter;
    }

    public void setScopeFilter(ScopeFilter scopeFilter)
    {
        this.scopeFilter = scopeFilter;
    }
}
