package com.fivetech.monitor.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.fivetech.common.utils.ServletUtils;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.monitor.exception.MonitorException;

/**
 * 解析当前请求的站点。
 *
 * <p>站点由 {@code SiteContextInterceptor} 按请求头 {@code X-Site-Code} 解析并校验：未配置的站点、
 * 用户没有权限的站点都已在拦截器返回 403；不带请求头为默认站点（{@code dashboard.default-site}）。
 * 用户能进入哪些站点见 {@code DashboardProperties.accessibleSites}（角色带 site_code）。</p>
 * <p>监控模块的其余代码都只认 {@code MonitorScope.siteCode}。</p>
 *
 * @author fivetech
 */
@Component
public class MonitorSiteResolver
{
    public static final String HEADER = "X-Site-Code";

    private final DashboardProperties properties;

    public MonitorSiteResolver(DashboardProperties properties)
    {
        this.properties = properties;
    }

    public String currentSite()
    {
        // 多站点一期：站点由 SiteContextInterceptor 按 X-Site-Code 解析并校验（未配置的站点已在拦截器返回 403）
        return properties.currentSite();
    }

    /** 默认站点（后台任务没有请求上下文时使用） */
    public String defaultSite()
    {
        return properties.getDefaultSite();
    }
}
