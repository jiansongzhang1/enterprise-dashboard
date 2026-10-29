package com.fivetech.monitor.domain;

import java.time.LocalDateTime;

/**
 * monitor_site_setting 的一行：站点级设置（规则号取号器、所属包网）。
 *
 * @author fivetech
 */
public class MonitorSiteSetting
{
    private String siteCode;

    /** 所属包网；为空表示未设置 */
    private String packageNetwork;

    private Integer nextRuleNo;

    /** 站点列表的排序号（从小到大）；不是站点的设置行也有，但列表里排在最后 */
    private Integer sortNo;

    private String updateBy;

    private LocalDateTime updateTime;

    public String getSiteCode()
    {
        return siteCode;
    }

    public void setSiteCode(String siteCode)
    {
        this.siteCode = siteCode;
    }

    public String getPackageNetwork()
    {
        return packageNetwork;
    }

    public void setPackageNetwork(String packageNetwork)
    {
        this.packageNetwork = packageNetwork;
    }

    public Integer getNextRuleNo()
    {
        return nextRuleNo;
    }

    public void setNextRuleNo(Integer nextRuleNo)
    {
        this.nextRuleNo = nextRuleNo;
    }

    public Integer getSortNo()
    {
        return sortNo;
    }

    public void setSortNo(Integer sortNo)
    {
        this.sortNo = sortNo;
    }

    public String getUpdateBy()
    {
        return updateBy;
    }

    public void setUpdateBy(String updateBy)
    {
        this.updateBy = updateBy;
    }

    public LocalDateTime getUpdateTime()
    {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime)
    {
        this.updateTime = updateTime;
    }
}
