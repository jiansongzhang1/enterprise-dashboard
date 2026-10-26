package com.fivetech.framework.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import com.alibaba.fastjson2.JSON;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.common.utils.ServletUtils;
import com.fivetech.common.utils.SiteContext;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.common.core.domain.model.LoginUser;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.framework.web.service.SysPermissionService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 多站点一期：按请求头 {@code X-Site-Code} 确定本次请求的站点。
 *
 * <ul>
 *   <li>不带请求头 → 默认站点（dashboard.default-site）；</li>
 *   <li>是已配置的站点，且已登录用户在该站点有角色（站点角色或通用角色）→ 该站点，同时带上它在 UDS 的站点标识（X-Uds-Site）；</li>
 *   <li>未配置的站点、或用户在该站点没有角色 → 直接返回 403，不进入业务代码。</li>
 * </ul>
 * <p>具体能看哪些页面 / 调哪些接口，由角色的站点决定：LoginUser.getPermissions() 只返回当前站点角色 + 通用角色的权限，
 * 接口上的 @ss.hasPermi 不用改。请求结束时清除，避免线程复用串站点。</p>
 *
 * @author fivetech
 */
@Component
public class SiteContextInterceptor implements HandlerInterceptor
{
    public static final String HEADER = "X-Site-Code";

    private final DashboardProperties properties;

    private final SysPermissionService permissionService;

    public SiteContextInterceptor(DashboardProperties properties, SysPermissionService permissionService)
    {
        this.properties = properties;
        this.permissionService = permissionService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
    {
        SiteContext.clear();
        String requested = StringUtils.trim(request.getHeader(HEADER));
        if (StringUtils.isEmpty(requested))
        {
            SiteContext.set(properties.getDefaultSite(), null);
            return true;
        }
        String code = properties.canonical(requested);
        if (code == null)
        {
            ServletUtils.renderString(response, JSON.toJSONString(AjaxResult.error(403, "沒有該站點的權限")));
            return false;
        }
        LoginUser loginUser = currentLoginUser();
        if (loginUser != null && loginUser.getUser() != null
            && !permissionService.accessibleSites(loginUser.getUser()).contains(code))
        {
            ServletUtils.renderString(response, JSON.toJSONString(AjaxResult.error(403, "沒有該站點的權限")));
            return false;
        }
        SiteContext.set(code, properties.udsSite(code));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex)
    {
        SiteContext.clear();
    }

    /** 已登录用户；匿名接口（登录、启用链接等）返回 null，不做站点权限判断 */
    private static LoginUser currentLoginUser()
    {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof LoginUser ? (LoginUser) auth.getPrincipal() : null;
    }
}
