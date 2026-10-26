package com.fivetech.monitor.domain.vo;

/**
 * 系统管理 · 站点管理 列表的一行。
 *
 * @author fivetech
 */
public class SiteSettingVO
{
    /** 站点编码（请求头 X-Site-Code 用的值） */
    private String siteCode;

    /** 显示名 */
    private String siteName;

    /** 请求 UDS 时的站点头 X-Uds-Site；默认站点为 null（不发这个头） */
    private String udsSite;

    /** 是否默认站点（不带 X-Site-Code 时用它） */
    private boolean defaultSite;

    /** 是否在 dashboard.sites 配置里；false = 数据库里有设置行但配置已去掉，只读 */
    private boolean configured;

    /** 所属包网；为空表示未设置 */
    private String packageNetwork;

    /** 下一个规则号（只读）；还没有设置行时为 1 */
    private int nextRuleNo;

    private String updateBy;

    /** yyyy-MM-dd HH:mm */
    private String updateTime;

    public String getSiteCode()
    {
        return siteCode;
    }

    public void setSiteCode(String siteCode)
    {
        this.siteCode = siteCode;
    }

    public String getSiteName()
    {
        return siteName;
    }

    public void setSiteName(String siteName)
    {
        this.siteName = siteName;
    }

    public String getUdsSite()
    {
        return udsSite;
    }

    public void setUdsSite(String udsSite)
    {
        this.udsSite = udsSite;
    }

    public boolean isDefaultSite()
    {
        return defaultSite;
    }

    public void setDefaultSite(boolean defaultSite)
    {
        this.defaultSite = defaultSite;
    }

    public boolean isConfigured()
    {
        return configured;
    }

    public void setConfigured(boolean configured)
    {
        this.configured = configured;
    }

    public String getPackageNetwork()
    {
        return packageNetwork;
    }

    public void setPackageNetwork(String packageNetwork)
    {
        this.packageNetwork = packageNetwork;
    }

    public int getNextRuleNo()
    {
        return nextRuleNo;
    }

    public void setNextRuleNo(int nextRuleNo)
    {
        this.nextRuleNo = nextRuleNo;
    }

    public String getUpdateBy()
    {
        return updateBy;
    }

    public void setUpdateBy(String updateBy)
    {
        this.updateBy = updateBy;
    }

    public String getUpdateTime()
    {
        return updateTime;
    }

    public void setUpdateTime(String updateTime)
    {
        this.updateTime = updateTime;
    }
}
