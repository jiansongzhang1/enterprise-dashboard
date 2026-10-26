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
 * Dashboard IP 白名单（独立开关）。
 * <p>
 * 开启后整个系统只允许名单内的 IP 访问：Dashboard 数据接口、监控管理接口，<b>以及登录、getInfo、getRouters</b>，
 * 白名单外的 IP 连登录都会被拒绝，前端在登录页就能提示。拦截范围在 {@link com.fivetech.framework.config.ResourcesConfig}
 * 注册为全部接口，只放过 {@code security.dashboard-ip-whitelist.exclude-paths}（默认只有 /error）。
 * <p>
 * 放行的 IP = 本名单 ∪ 管理接口白名单的名单（{@code security.admin-ip-whitelist.allowed}，不论其开关）：
 * 公司内部的 IP 只需配在管理接口白名单里，本名单只填客户的 IP。
 * 管理接口另受 {@link AdminIpWhitelistInterceptor} 限制，只有管理名单里的 IP 能用，两者互不放宽。
 * <p>
 * 配置（{@code security.dashboard-ip-whitelist.*}，修改后重启生效）：
 * <ul>
 *   <li>{@code enabled}：开关，默认 false（环境变量 DASHBOARD_IP_WHITELIST_ENABLED）</li>
 *   <li>{@code allowed}：逗号分隔的 IP / CIDR（环境变量 DASHBOARD_IP_WHITELIST）</li>
 *   <li>{@code exclude-paths}：不受限制的路径，Ant 风格，逗号分隔</li>
 * </ul>
 * 客户端 IP 的取法与管理接口白名单相同，见 {@link ClientIpResolver}。
 * 健康检查走管理端口（actuator），不经过本拦截器，开启后不影响部署脚本的健康检查。
 *
 * @author fivetech
 */
@Component
public class DashboardIpWhitelistInterceptor implements HandlerInterceptor
{
    private static final Logger log = LoggerFactory.getLogger(DashboardIpWhitelistInterceptor.class);

    private final boolean enabled;

    private final IpWhitelist whitelist;

    private final AdminIpWhitelistInterceptor adminWhitelist;

    private final ClientIpResolver clientIpResolver;

    public DashboardIpWhitelistInterceptor(@Value("${security.dashboard-ip-whitelist.enabled:false}") boolean enabled,
            @Value("${security.dashboard-ip-whitelist.allowed:}") String allowed,
            AdminIpWhitelistInterceptor adminWhitelist, ClientIpResolver clientIpResolver)
    {
        this.enabled = enabled;
        this.whitelist = IpWhitelist.parse("dashboard", allowed);
        this.adminWhitelist = adminWhitelist;
        this.clientIpResolver = clientIpResolver;
        if (enabled)
        {
            log.info("[ip-whitelist] Dashboard 白名单已开启：本名单 {} 条，另放行管理接口白名单中的 IP", whitelist.size());
            if (whitelist.isEmpty())
            {
                log.warn("[ip-whitelist] Dashboard 白名单已开启但本名单为空：只有管理接口白名单中的 IP 能访问系统");
            }
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
        if (ip != null && (whitelist.contains(ip) || adminWhitelist.inList(ip)))
        {
            return true;
        }
        log.warn("[ip-whitelist] Dashboard 拒绝访问 ip={} uri={} xff={}", ip, request.getRequestURI(),
            request.getHeader("X-Forwarded-For"));
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        ServletUtils.renderString(response, JSON.toJSONString(AjaxResult.error(403, "當前網絡（" + ip + "）不在訪問白名單內，請聯繫管理員")));
        return false;
    }
}
