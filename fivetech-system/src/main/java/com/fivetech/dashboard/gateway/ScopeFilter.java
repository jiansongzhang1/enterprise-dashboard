package com.fivetech.dashboard.gateway;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据权限过滤条件，由一期的 Data Scope 解析得到。
 * <p>
 * 空列表表示<b>不限</b>，只有 ALL 范围的角色才会得到空列表。
 * 网关实现必须把这些条件下推到外部查询，不得忽略。
 *
 * @author fivetech
 */
public class ScopeFilter implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 可见站点 */
    private List<String> siteCodes = new ArrayList<>();

    /** 可见组织/部门 */
    private List<Long> deptIds = new ArrayList<>();

    /** 可见渠道 */
    private List<String> channels = new ArrayList<>();

    /** 可见指标编码。为空表示不限 */
    private List<String> metricCodes = new ArrayList<>();

    /** 是否为全量可见 */
    private boolean unrestricted;

    public static ScopeFilter unrestricted()
    {
        ScopeFilter filter = new ScopeFilter();
        filter.unrestricted = true;
        return filter;
    }

    public List<String> getSiteCodes()
    {
        return siteCodes;
    }

    public void setSiteCodes(List<String> siteCodes)
    {
        this.siteCodes = siteCodes;
    }

    public List<Long> getDeptIds()
    {
        return deptIds;
    }

    public void setDeptIds(List<Long> deptIds)
    {
        this.deptIds = deptIds;
    }

    public List<String> getChannels()
    {
        return channels;
    }

    public void setChannels(List<String> channels)
    {
        this.channels = channels;
    }

    public List<String> getMetricCodes()
    {
        return metricCodes;
    }

    public void setMetricCodes(List<String> metricCodes)
    {
        this.metricCodes = metricCodes;
    }

    public boolean isUnrestricted()
    {
        return unrestricted;
    }

    public void setUnrestricted(boolean unrestricted)
    {
        this.unrestricted = unrestricted;
    }
}
