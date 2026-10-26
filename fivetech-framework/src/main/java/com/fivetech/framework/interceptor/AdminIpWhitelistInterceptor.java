package com.fivetech.framework.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import com.alibaba.fastjson2.JSON;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.common.utils.ServletUtils;

/**
 * 内部管理接口 IP 白名单。
 * <p>
 * 拦截范围在 {@link com.fivetech.framework.config.ResourcesConfig} 注册：<b>所有接口都拦</b>，
 * 只放过 {@code security.admin-ip-whitelist.exclude-paths} 里的路径——仪表板（/dashboard/**）、监控管理的 Dashboard 接口、
 * 登录与账号自助（登录、GA 绑定、账号启用、getInfo / getRouters、个人中心改密码）及页面运行必需的只读接口。
 * 用户 / 角色 / 权限 / 部门 / 系统通知 / 日志等管理接口只能从白名单 IP 访问。
 * 新增的 Controller 默认受白名单保护，不会因为忘了登记而对外暴露。
 * <p>
 * 被放过的这些路径另由 {@link DashboardIpWhitelistInterceptor}（Dashboard 白名单，独立开关）控制。
 * <p>
 * 配置（{@code security.admin-ip-whitelist.*}）：
 * <ul>
 *   <li>{@code enabled}：总开关，默认 false</li>
 *   <li>{@code allowed}：逗号分隔，支持单个 IP（IPv4 / IPv6）和 CIDR 网段，如 {@code 1.2.3.4,10.0.0.0/8}</li>
 *   <li>{@code client-ip-header}、{@code trusted-proxy-hops}：客户端 IP 的取法，见 {@link ClientIpResolver}</li>
 * </ul>
 *
 * @author fivetech
 */
@Component
public class AdminIpWhitelistInterceptor implements HandlerInterceptor
{
    private static final Logger log = LoggerFactory.getLogger(AdminIpWhitelistInterceptor.class);

    private final boolean enabled;

    private final IpWhitelist whitelist;

    private final ClientIpResolver clientIpResolver;

    public AdminIpWhitelistInterceptor(@Value("${security.admin-ip-whitelist.enabled:false}") boolean enabled,
            @Value("${security.admin-ip-whitelist.allowed:}") String allowed, ClientIpResolver clientIpResolver)
    {
        this.enabled = enabled;
        this.whitelist = IpWhitelist.parse("admin", allowed);
        this.clientIpResolver = clientIpResolver;
        if (enabled && whitelist.isEmpty())
        {
            log.warn("[ip-whitelist] 管理接口白名单已开启但名单为空，所有管理接口请求都会被拒绝");
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
    {
        if (!enabled)
        {
            return true;
        }
        String ip = clientIpResolver.resolve(request);
        if (ip != null && whitelist.contains(ip))
        {
            return true;
        }
        log.warn("[ip-whitelist] 拒绝访问 ip={} uri={} xff={}", ip, request.getRequestURI(),
            request.getHeader("X-Forwarded-For"));
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        ServletUtils.renderString(response, JSON.toJSONString(AjaxResult.error(403, "当前网络（" + ip + "）不在管理接口访问白名单内")));
        return false;
    }

    /**
     * ip 是否在管理接口白名单的名单里（不看开关）。
     * Dashboard 白名单用它取并集：公司内部能进管理接口的 IP，自然也能用 Dashboard。
     */
    public boolean inList(String ip)
    {
        return ip != null && whitelist.contains(ip);
    }
}
