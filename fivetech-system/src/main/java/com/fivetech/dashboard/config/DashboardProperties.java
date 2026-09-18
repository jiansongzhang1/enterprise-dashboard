package com.fivetech.dashboard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 仪表板查询配置。
 *
 * @author fivetech
 */
@Component
@ConfigurationProperties(prefix = "dashboard")
public class DashboardProperties
{
    /** 默认站点编码 */
    private String defaultSite = "SITE-IN";

    /** 统计时区。日切与环比同比都依此时区 */
    private String timezone = "Asia/Kolkata";

    /** 币别。一期单币别，不做汇率折算 */
    private String currency = "INR";

    /** 站点上线日 yyyy-MM-dd，所有时间选择的下界 */
    private String launchDate = "2026-03-01";

    /** 指标口径版本，随查询结果一起返回 */
    private String registryVersion = "v1.3";

    /** 数据延迟小时数，落在该窗口内的数据标记 delayed */
    private int delayWindowHours = 2;

    /** 在线查询的行数上限，超过则引导走异步导出 */
    private int rowLimit = 100000;

    /** hour 粒度允许的最大数据点数，超过则自动降级到 day */
    private int maxPointsPerGranularity = 744;

    /** 会员账号等敏感字段是否脱敏 */
    private boolean maskAccount = true;

    public String getDefaultSite()
    {
        return defaultSite;
    }

    public void setDefaultSite(String defaultSite)
    {
        this.defaultSite = defaultSite;
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

    public String getLaunchDate()
    {
        return launchDate;
    }

    public void setLaunchDate(String launchDate)
    {
        this.launchDate = launchDate;
    }

    public String getRegistryVersion()
    {
        return registryVersion;
    }

    public void setRegistryVersion(String registryVersion)
    {
        this.registryVersion = registryVersion;
    }

    public int getDelayWindowHours()
    {
        return delayWindowHours;
    }

    public void setDelayWindowHours(int delayWindowHours)
    {
        this.delayWindowHours = delayWindowHours;
    }

    public int getRowLimit()
    {
        return rowLimit;
    }

    public void setRowLimit(int rowLimit)
    {
        this.rowLimit = rowLimit;
    }

    public int getMaxPointsPerGranularity()
    {
        return maxPointsPerGranularity;
    }

    public void setMaxPointsPerGranularity(int maxPointsPerGranularity)
    {
        this.maxPointsPerGranularity = maxPointsPerGranularity;
    }

    public boolean isMaskAccount()
    {
        return maskAccount;
    }

    public void setMaskAccount(boolean maskAccount)
    {
        this.maskAccount = maskAccount;
    }
}
