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
    private volatile String defaultSite = "SITE-IN";

    /** 默认站点在 UDS 的站点标识（X-Uds-Site）；为空用默认站点编码 */
    private volatile String defaultUdsSite;

    /** 站点显示名（导出文件「導出說明」的站点写作「{siteName} · {siteCode}」） */
    private volatile String siteName = "uWin";

    /**
     * 默认站点之外的其他站点（多站点一期）。每个站点：编码、显示名、UDS 站点标识（X-Uds-Site）。
     * 默认站点不用写在这里：沿用 site-name，调 UDS 不带 X-Uds-Site。
     * 站点权限不在这里：角色带 site_code，用户在某站点的权限 = 该站点角色 + 通用角色（见 LoginUser.getPermissions）。
     * 时区、币种、上线日一期各站点共用全局配置。
     */
    private volatile List<Site> sites = new ArrayList<>();

    /** 全部站点编码的显示顺序（含默认站点，按创建先后，即站点表的排序号从小到大）；为空时按「默认站点 + sites」 */
    private volatile List<String> siteOrder = List.of();

    /** 统计时区。日切与环比同比都依此时区 */
    private String timezone = "Asia/Kolkata";

    /** 币别（站点没设置币种时用）。不做汇率折算 */
    private String currency = "INR";

    /** 默认站点在站点表里设置的币种、币种符号（为空用 currency / 按币种推导） */
    private volatile String defaultSiteCurrency;

    private volatile String defaultSiteCurrencySymbol;

    /** 默认站点在站点表里设置的时区（为空用 timezone） */
    private volatile String defaultSiteTimezone;

    /** 常用币种的符号；站点没设置符号时按币种代码取，认不出用币种代码本身 */
    private static final java.util.Map<String, String> SYMBOLS = java.util.Map.of(
            "INR", "₹", "CNY", "¥", "USD", "US$", "USDT", "USDT", "PHP", "₱", "THB", "฿",
            "VND", "₫", "IDR", "Rp", "BRL", "R$", "PKR", "Rs");

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

    /**
     * 用站点表（monitor_site_setting 里 site_name 不为空的行，后台「站点管理」维护）替换站点配置：默认站点的编码 / 名称 / UDS 站点标识，以及其余站点。
     * 由 {@link com.fivetech.dashboard.service.SiteRegistry} 在启动和站点变更时调用；之后 application.yml 里的
     * default-site、site-name、default-uds-site、sites 只用于首次启动时导入站点表。
     */
    public synchronized void applySites(String defaultCode, String defaultName, String defaultUds, List<Site> others)
    {
        applySites(defaultCode, defaultName, defaultUds, others, null);
    }

    /**
     * 同上，并指定全部站点（含默认站点）的显示顺序 order：登录可切换的站点、站点管理、角色的站点选项都按这个顺序；
     * order 为空时按「默认站点 + others」。
     */
    public synchronized void applySites(String defaultCode, String defaultName, String defaultUds, List<Site> others,
            List<String> order)
    {
        applySites(defaultCode, defaultName, defaultUds, null, null, others, order);
    }

    /** 同上，并带默认站点的币种、币种符号 */
    public synchronized void applySites(String defaultCode, String defaultName, String defaultUds, String defaultCurrency,
            String defaultCurrencySymbol, List<Site> others, List<String> order)
    {
        applySites(defaultCode, defaultName, defaultUds, null, defaultCurrency, defaultCurrencySymbol, others, order);
    }

    /** 同上，并带默认站点的时区、币种、币种符号（站点表加载时用） */
    public synchronized void applySites(String defaultCode, String defaultName, String defaultUds, String defaultTimezone,
            String defaultCurrency, String defaultCurrencySymbol, List<Site> others, List<String> order)
    {
        if (defaultCode == null || defaultCode.trim().isEmpty())
        {
            throw new IllegalArgumentException("缺少默认站点");
        }
        List<Site> copy = new ArrayList<>();
        if (others != null)
        {
            for (Site s : others)
            {
                if (s != null && s.getCode() != null && !s.getCode().equalsIgnoreCase(defaultCode.trim()))
                {
                    copy.add(s);
                }
            }
        }
        this.defaultSite = defaultCode.trim();
        this.siteName = defaultName;
        this.defaultUdsSite = defaultUds;
        this.defaultSiteCurrency = defaultCurrency;
        this.defaultSiteCurrencySymbol = defaultCurrencySymbol;
        this.defaultSiteTimezone = defaultTimezone;
        this.sites = java.util.Collections.unmodifiableList(copy);
        this.siteOrder = order == null ? List.of() : List.copyOf(order);
    }

    /** 全部站点编码（含默认站点），按显示顺序：站点表加载后为创建先后（排序号从小到大），否则默认站点在前、其余按配置顺序 */
    public List<String> allSiteCodes()
    {
        List<String> order = siteOrder;
        List<String> all = new ArrayList<>();
        if (order != null && !order.isEmpty())
        {
            for (String code : order)
            {
                String c = canonical(code);
                if (c != null && !all.contains(c))
                {
                    all.add(c);
                }
            }
        }
        if (!all.contains(defaultSite))
        {
            all.add(0, defaultSite);
        }
        for (Site site : sites)
        {
            if (site.getCode() != null && !all.contains(site.getCode()))
            {
                all.add(site.getCode());
            }
        }
        return all;
    }

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
     * 用户可以进入的站点（按 {@link #allSiteCodes()} 的顺序，站点表加载后为创建先后（排序号从小到大））：超级管理员 = 全部；其他用户 = 站点角色所属的站点，
     * 有通用角色时再加上默认站点；一个都没有时只给默认站点（兼容未分配站点的老账号）。
     * 登录后可切换的站点（SysPermissionService）和监控负责人候选共用这一条规则。
     *
     * @param admin 是否超级管理员
     * @param normalRoleSiteCodes 用户每个正常状态角色的 site_code（通用角色为 null 或空串）
     */
    public List<String> accessibleSites(boolean admin, Collection<String> normalRoleSiteCodes)
    {
        List<String> all = allSiteCodes();
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
     * 站点时区（IANA 时区 ID，如 Asia/Kolkata）：来自站点表 monitor_site_setting.timezone（内存里，不查库），
     * UDS 该站点的数据也按这个时区统计。getInfo 的 sites[].timezone、运营总览 / 指标汇总 / 明细的 timezone、
     * 「今天 / 当前整点 / 区间」的计算、导出的时区说明都用它。
     * 站点表里没设置、或不是合法时区时，用 application.yml 的 dashboard.timezone 兜底。
     */
    public String siteTimezone(String code)
    {
        String tz = isDefault(code) ? defaultSiteTimezone : siteValue(code, Site::getTimezone);
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

    /** 站点时区（{@link #siteTimezone(String)}） */
    public java.time.ZoneId siteZone(String code)
    {
        return java.time.ZoneId.of(siteTimezone(code));
    }

    /** 当前请求站点（X-Site-Code，没有上下文时为默认站点）的时区 ID */
    public String currentTimezone()
    {
        return siteTimezone(currentSite());
    }

    /** 当前请求站点的时区 */
    public java.time.ZoneId currentZone()
    {
        return siteZone(currentSite());
    }

    /** 当前请求站点的币种代码 */
    public String currentCurrency()
    {
        return siteCurrency(currentSite());
    }

    /**
     * 导出说明里的时区写法：Asia/Kolkata 写「IST（UTC+5:30）」，其余写「时区 ID（UTC 偏移）」，如「Asia/Shanghai（UTC+8）」
     */
    public String siteZoneText(String code)
    {
        String label = siteZoneLabel(code);
        String offset = utcOffset(siteZone(code));
        return label.startsWith("UTC") ? siteTimezone(code) + "（" + offset + "）" : label + "（" + offset + "）";
    }

    /** UTC 偏移写法：UTC、UTC+8、UTC+5:30、UTC−3 */
    private static String utcOffset(java.time.ZoneId zone)
    {
        int seconds = zone.getRules().getOffset(java.time.Instant.now()).getTotalSeconds();
        if (seconds == 0)
        {
            return "UTC";
        }
        int abs = Math.abs(seconds);
        int h = abs / 3600;
        int m = abs % 3600 / 60;
        return "UTC" + (seconds < 0 ? "−" : "+") + h + (m == 0 ? "" : ":" + String.format("%02d", m));
    }

    /**
     * 站点币种代码（大写，如 INR、CNY），getInfo 的 sites[].currency 返回它。
     * 站点表里没设置时用全局的 dashboard.currency。
     */
    public String siteCurrency(String code)
    {
        String c = isDefault(code) ? defaultSiteCurrency : siteValue(code, Site::getCurrency);
        if (c == null || c.trim().isEmpty())
        {
            c = currency;
        }
        return c == null || c.trim().isEmpty() ? "INR" : c.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * 站点币种符号（如 ₹、¥），getInfo 的 sites[].currencySymbol 返回它。
     * 站点表里没设置时按币种代码取常用符号，认不出时用币种代码本身。
     */
    public String siteCurrencySymbol(String code)
    {
        String s = isDefault(code) ? defaultSiteCurrencySymbol : siteValue(code, Site::getCurrencySymbol);
        if (s != null && !s.trim().isEmpty())
        {
            return s.trim();
        }
        String c = siteCurrency(code);
        return SYMBOLS.getOrDefault(c, c);
    }

    /**
     * 站点时区的简写（导出说明用；Lark 消息 2026-10-29 起不写时区）：Asia/Kolkata 写「IST」，
     * 其余写 UTC 偏移，如 Asia/Shanghai「UTC+8」、Asia/Kathmandu「UTC+5:45」。时区来自站点表（内存里，不查库）。
     */
    public String siteZoneLabel(String code)
    {
        String tz = siteTimezone(code);
        if ("Asia/Kolkata".equals(tz) || "Asia/Calcutta".equals(tz))
        {
            return "IST";
        }
        return utcOffset(java.time.ZoneId.of(tz));
    }

    /** 统计时区（dashboard.timezone）的时刻换成站点时区的时刻；为 null 返回 null */
    public java.time.LocalDateTime toSiteTime(String code, java.time.LocalDateTime statTime)
    {
        if (statTime == null)
        {
            return null;
        }
        java.time.ZoneId from = java.time.ZoneId.of(timezone == null || timezone.trim().isEmpty() ? "Asia/Kolkata" : timezone.trim());
        return statTime.atZone(from).withZoneSameInstant(java.time.ZoneId.of(siteTimezone(code))).toLocalDateTime();
    }

    /** 默认站点在站点表里设置的时区（没设置为 null） */
    public String getDefaultSiteTimezone() { return defaultSiteTimezone; }

    /** 默认站点在站点表里设置的币种（没设置为 null） */
    public String getDefaultSiteCurrency() { return defaultSiteCurrency; }

    /** 默认站点在站点表里设置的币种符号（没设置为 null） */
    public String getDefaultSiteCurrencySymbol() { return defaultSiteCurrencySymbol; }

    private boolean isDefault(String code)
    {
        return code == null || code.trim().isEmpty() || code.trim().equalsIgnoreCase(defaultSite);
    }

    private String siteValue(String code, java.util.function.Function<Site, String> getter)
    {
        Site site = findSite(code);
        return site == null ? null : getter.apply(site);
    }

    /**
     * 站点在 UDS 的站点标识（请求头 X-Uds-Site，每次请求都带）：
     * 默认站点（或没有站点上下文）取 {@code default-uds-site}，没配用默认站点编码；
     * 其他站点取 {@code sites[].uds-site}，没配用站点编码；未配置的站点返回 null。
     */
    public String udsSite(String code)
    {
        if (code == null || code.trim().isEmpty() || code.trim().equalsIgnoreCase(defaultSite))
        {
            return defaultUdsSite == null || defaultUdsSite.trim().isEmpty() ? defaultSite : defaultUdsSite.trim();
        }
        Site site = findSite(code);
        if (site == null)
        {
            return null;
        }
        return site.getUdsSite() == null || site.getUdsSite().trim().isEmpty() ? site.getCode() : site.getUdsSite().trim();
    }

    public String getDefaultUdsSite()
    {
        return defaultUdsSite;
    }

    public void setDefaultUdsSite(String defaultUdsSite)
    {
        this.defaultUdsSite = defaultUdsSite;
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

        /** UDS 站点标识，请求头 X-Uds-Site 的值（与 UDS 约定，如 JITO）；租户 X-Uds-Tenant 各站点相同 */
        private String udsSite;

        /** 站点时区（IANA 时区 ID）；不配时用 dashboard.timezone */
        private String timezone;

        /** 币种代码（如 CNY）；不配时用 dashboard.currency */
        private String currency;

        /** 币种符号（如 ¥）；不配时按币种代码推导 */
        private String currencySymbol;

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getUdsSite() { return udsSite; }
        public void setUdsSite(String udsSite) { this.udsSite = udsSite; }
        public String getTimezone() { return timezone; }
        public void setTimezone(String timezone) { this.timezone = timezone; }
        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }
        public String getCurrencySymbol() { return currencySymbol; }
        public void setCurrencySymbol(String currencySymbol) { this.currencySymbol = currencySymbol; }
    }
}
