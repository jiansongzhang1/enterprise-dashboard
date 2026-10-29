package com.fivetech.dashboard.domain;

import java.time.LocalDateTime;

/**
 * 站点（存在 monitor_site_setting，site_name 不为空的行；与所属包网、规则号同一行）：后台「系统管理 · 站点管理」维护，运行期由 {@link com.fivetech.dashboard.service.SiteRegistry} 加载到
 * {@link com.fivetech.dashboard.config.DashboardProperties}。
 *
 * @author fivetech
 */
public class SysSite
{
    /** 站点编码，与请求头 X-Site-Code 对应；所有业务表的 site_code */
    private String siteCode;

    /** 显示名 */
    private String siteName;

    /** UDS 站点标识（请求头 X-Uds-Site）；为空用站点编码 */
    private String udsSite;

    /** 站点时区（IANA）；为空用 dashboard.timezone。默认站点固定用 dashboard.timezone */
    private String timezone;

    /** 币种代码（如 INR、CNY）；为空用 dashboard.currency */
    private String currency;

    /** 币种符号（如 ₹、¥）；为空按币种代码取常用符号，认不出时用币种代码 */
    private String currencySymbol;

    /** 是否默认站点（只有一个，不能在后台改） */
    private boolean defaultSite;

    /** 排序号：新增站点时取最大值 + 1（即创建先后），站点列表按它从小到大 */
    private Integer sortNo;

    private String updateBy;

    private LocalDateTime updateTime;

    public String getSiteCode() { return siteCode; }
    public void setSiteCode(String siteCode) { this.siteCode = siteCode; }
    public String getSiteName() { return siteName; }
    public void setSiteName(String siteName) { this.siteName = siteName; }
    public String getUdsSite() { return udsSite; }
    public void setUdsSite(String udsSite) { this.udsSite = udsSite; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getCurrencySymbol() { return currencySymbol; }
    public void setCurrencySymbol(String currencySymbol) { this.currencySymbol = currencySymbol; }
    public boolean isDefaultSite() { return defaultSite; }
    public void setDefaultSite(boolean defaultSite) { this.defaultSite = defaultSite; }
    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
