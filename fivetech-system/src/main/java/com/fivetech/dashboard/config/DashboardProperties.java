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

    /** 站点显示名（导出文件「導出說明」的站点写作「{siteName} · {siteCode}」） */
    private String siteName = "Uwin";

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

    /** CSV 导出配置 */
    private ExportProperties export = new ExportProperties();

    /**
     * 从指标下钻到存款明细时按哪个时间筛：createTime 创建时间 / finishTime 完成时间。
     * <p>必须与数据平台「存款总额」的时间归属一致，否则跨时间片的订单（09:58 创建、10:02 成功）
     * 会让下钻金额和指标值对不上。TODO：待数据团队确认口径后定默认值。</p>
     */
    private String depositDrillTimeField = "createTime";

    /** 从指标下钻到提款明细时按哪个时间筛，含义同 depositDrillTimeField */
    private String withdrawDrillTimeField = "createTime";

    /**
     * 指标汇总表中支持点单元格下钻的指标编码，对应 /summary 返回 columns[].drillable。
     * <p>一期先放配置，不进数据库；取值与 API 文档第 5 章「下钻映射」一致。</p>
     */
    private java.util.List<String> summaryDrillableMetrics = new java.util.ArrayList<>(java.util.List.of(
        "reg", "ftd", "active", "dep", "wd"));

    public java.util.List<String> getSummaryDrillableMetrics()
    {
        return summaryDrillableMetrics;
    }

    public void setSummaryDrillableMetrics(java.util.List<String> summaryDrillableMetrics)
    {
        this.summaryDrillableMetrics = summaryDrillableMetrics;
    }

    public String getDepositDrillTimeField()
    {
        return depositDrillTimeField;
    }

    public void setDepositDrillTimeField(String depositDrillTimeField)
    {
        this.depositDrillTimeField = depositDrillTimeField;
    }

    public String getWithdrawDrillTimeField()
    {
        return withdrawDrillTimeField;
    }

    public void setWithdrawDrillTimeField(String withdrawDrillTimeField)
    {
        this.withdrawDrillTimeField = withdrawDrillTimeField;
    }

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

    public ExportProperties getExport()
    {
        return export;
    }

    public void setExport(ExportProperties export)
    {
        this.export = export;
    }

    public String getSiteName()
    {
        return siteName;
    }

    public void setSiteName(String siteName)
    {
        this.siteName = siteName;
    }
}
