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

    /** 是否站点（monitor_site_setting.site_name 不为空）；false = 只有设置行（包网 / 规则号）的历史数据，可在页面「配置为站点」 */
    private boolean configured;

    /** 排序号：站点列表、登录后的站点下拉按它从小到大（相同按编码）；页面可改 */
    private Integer sortNo;

    public Integer getSortNo() { return sortNo; }

    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }

    /** 所属包网；为空表示未设置 */
    private String packageNetwork;

    /** 下一个规则号（只读）；还没有设置行时为 1 */
    private int nextRuleNo;

    private String updateBy;

    /** yyyy-MM-dd HH:mm */
    private String updateTime;

    /** 站点表里设置的时区（IANA）；没设置为 null（用 dashboard.timezone） */
    private String timezone;

    /** 实际使用的时区：站点设置的，没设置用 dashboard.timezone；dashboard 各接口、UDS 统计都按它 */
    private String effectiveTimezone;

    /** 站点表里设置的币种代码；没设置为 null */
    private String currency;

    /** 站点表里设置的币种符号；没设置为 null */
    private String currencySymbol;

    /** 实际使用的币种：站点设置的，或 dashboard.currency */
    private String effectiveCurrency;

    /** 实际使用的币种符号：站点设置的，或按币种推导 */
    private String effectiveCurrencySymbol;

    public String getCurrency() { return currency; }

    public void setCurrency(String currency) { this.currency = currency; }

    public String getCurrencySymbol() { return currencySymbol; }

    public void setCurrencySymbol(String currencySymbol) { this.currencySymbol = currencySymbol; }

    public String getEffectiveCurrency() { return effectiveCurrency; }

    public void setEffectiveCurrency(String effectiveCurrency) { this.effectiveCurrency = effectiveCurrency; }

    public String getEffectiveCurrencySymbol() { return effectiveCurrencySymbol; }

    public void setEffectiveCurrencySymbol(String effectiveCurrencySymbol) { this.effectiveCurrencySymbol = effectiveCurrencySymbol; }

    public String getTimezone() { return timezone; }

    public void setTimezone(String timezone) { this.timezone = timezone; }

    public String getEffectiveTimezone() { return effectiveTimezone; }

    public void setEffectiveTimezone(String effectiveTimezone) { this.effectiveTimezone = effectiveTimezone; }

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
