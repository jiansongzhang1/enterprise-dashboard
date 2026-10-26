package com.fivetech.dashboard.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private String siteName = "uWin";

    /**
     * 默认站点之外的其他站点（多站点一期）。每个站点：编码、显示名、UDS 站点标识（X-Uds-Site）。
     * 默认站点不用写在这里：沿用 site-name，调 UDS 不带 X-Uds-Site。
     * 站点权限不在这里：角色带 site_code，用户在某站点的权限 = 该站点角色 + 通用角色（见 LoginUser.getPermissions）。
     * 时区、币种、上线日一期各站点共用全局配置。
     */
    private List<Site> sites = new ArrayList<>();

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

    public List<Site> getSites()
    {
        return sites;
    }

    public void setSites(List<Site> sites)
    {
        this.sites = sites == null ? new ArrayList<>() : sites;
    }

    // ===================== 多站点 =====================

    /** 是否为已配置的站点（含默认站点），忽略大小写 */
    public boolean isKnownSite(String code)
    {
        return canonical(code) != null;
    }

    /** 规范化站点编码（按配置的写法返回）；未配置返回 null */
    public String canonical(String code)
    {
        if (code == null || code.trim().isEmpty())
        {
            return null;
        }
        String c = code.trim();
        if (c.equalsIgnoreCase(defaultSite))
        {
            return defaultSite;
        }
        Site site = findSite(c);
        return site == null ? null : site.getCode();
    }

    /**
     * 用户可以进入的站点（按配置顺序）：超级管理员 = 全部；其他用户 = 站点角色所属的站点，
     * 有通用角色时再加上默认站点；一个都没有时只给默认站点（兼容未分配站点的老账号）。
     * 登录后可切换的站点（SysPermissionService）和监控负责人候选共用这一条规则。
     *
     * @param admin 是否超级管理员
     * @param normalRoleSiteCodes 用户每个正常状态角色的 site_code（通用角色为 null 或空串）
     */
    public List<String> accessibleSites(boolean admin, Collection<String> normalRoleSiteCodes)
    {
        List<String> all = new ArrayList<>();
        all.add(defaultSite);
        for (Site site : sites)
        {
            all.add(site.getCode());
        }
        if (admin)
        {
            return all;
        }
        Set<String> owned = new HashSet<>();
        boolean hasCommonRole = false;
        if (normalRoleSiteCodes != null)
        {
            for (String roleSite : normalRoleSiteCodes)
            {
                String code = canonical(roleSite);
                if (code == null)
                {
                    hasCommonRole = hasCommonRole || roleSite == null || roleSite.trim().isEmpty();
                }
                else
                {
                    owned.add(code);
                }
            }
        }
        if (hasCommonRole || owned.isEmpty())
        {
            owned.add(defaultSite);
        }
        List<String> result = new ArrayList<>();
        for (String code : all)
        {
            if (owned.contains(code))
            {
                result.add(code);
            }
        }
        return result;
    }

    /** 当前请求的站点：拦截器解析并校验过的站点，没有请求上下文时为默认站点 */
    public String currentSite()
    {
        String code = com.fivetech.common.utils.SiteContext.code();
        return code == null ? defaultSite : code;
    }

    /** 站点显示名 */
    public String siteName(String code)
    {
        Site site = findSite(code);
        String name = site != null ? site.getName() : siteName;
        return name == null || name.trim().isEmpty() ? (site != null ? site.getCode() : defaultSite) : name;
    }

    /**
     * 站点时区（IANA 时区 ID，如 Asia/Kolkata），getInfo 的 sites[].timezone 返回它。
     * 站点没单独配置、或配置的不是合法时区时，用全局的 dashboard.timezone（默认站点也用它）。
     */
    public String siteTimezone(String code)
    {
        Site site = findSite(code);
        String tz = site == null ? null : site.getTimezone();
        if (tz != null && !tz.trim().isEmpty())
        {
            try
            {
                return java.time.ZoneId.of(tz.trim()).getId();
            }
            catch (java.time.DateTimeException e)
            {
                // 配错了按全局时区处理，不让 getInfo 失败
            }
        }
        return timezone == null || timezone.trim().isEmpty() ? "Asia/Kolkata" : timezone.trim();
    }

    /** 站点在 UDS 的站点标识（X-Uds-Site）；默认站点返回 null，不发这个头 */
    public String udsSite(String code)
    {
        Site site = findSite(code);
        return site == null ? null : site.getUdsSite();
    }

    private Site findSite(String code)
    {
        if (code == null)
        {
            return null;
        }
        for (Site site : sites)
        {
            if (site.getCode() != null && site.getCode().equalsIgnoreCase(code.trim()))
            {
                return site;
            }
        }
        return null;
    }

    /** 站点配置 */
    public static class Site
    {
        /** 站点编码，与请求头 X-Site-Code 对应 */
        private String code;

        /** 显示名 */
        private String name;

        /** UDS 站点标识，请求头 X-Uds-Site 的值（与 UDS 约定，如 SITE-B）；租户 X-Uds-Tenant 各站点相同 */
        private String udsSite;

        /** 站点时区（IANA 时区 ID）；不配时用 dashboard.timezone */
        private String timezone;

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getUdsSite() { return udsSite; }
        public void setUdsSite(String udsSite) { this.udsSite = udsSite; }
        public String getTimezone() { return timezone; }
        public void setTimezone(String timezone) { this.timezone = timezone; }
    }
}
