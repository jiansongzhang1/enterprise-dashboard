package com.fivetech.framework.security.filter;

import java.io.IOException;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.alibaba.fastjson2.JSON;
import com.fivetech.apikey.ApiKeyService;
import com.fivetech.common.constant.HttpStatus;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.common.core.domain.model.LoginUser;
import com.fivetech.common.utils.SecurityUtils;
import com.fivetech.common.utils.ServletUtils;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.framework.web.service.SysPermissionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * API Key 认证：请求头带 {@value ApiKeyService#HEADER}、且接口在 security.api-key.paths 里时，按 Key 绑定的后台账号登录本次请求。
 *
 * <ul>
 *   <li>只对支持的接口生效；其他接口带了 Key 也当没带（照常要 Bearer token）；</li>
 *   <li>Key 无效、作废、过期、不能访问该接口、IP 不在白名单、绑定账号停用：直接返回 401，原因只写日志；</li>
 *   <li>通过后按绑定账号的角色权限放行，接口上的 @PreAuthorize 照常校验；不签发、不缓存 token。</li>
 * </ul>
 *
 * @author fivetech
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter
{
    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    /** 拒绝时统一的提示，不区分具体原因 */
    static final String REJECT_MESSAGE = "API Key 無效、已過期或不能訪問該接口";

    @Autowired
    private ApiKeyService apiKeys;

    @Autowired
    private SysPermissionService permissionService;

    /** 来源 IP：与内部管理接口 IP 白名单同一套取法（CloudFront 头、可信代理层数），避免伪造 X-Forwarded-For 绕过 IP 限制 */
    @Autowired
    private com.fivetech.framework.interceptor.ClientIpResolver clientIp;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException
    {
        String raw = request.getHeader(ApiKeyService.HEADER);
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (StringUtils.isEmpty(raw) || !apiKeys.supports(path) || SecurityUtils.getAuthentication() != null)
        {
            chain.doFilter(request, response);
            return;
        }
        String ip = clientIp.resolve(request);
        String shown = raw.length() > 12 ? raw.substring(0, 12) : "?";
        ApiKeyService.Result r = apiKeys.authenticate(raw.trim(), path, ip, LocalDateTime.now(), permissionService::getMenuPermission);
        if (!r.ok())
        {
            log.warn("[api-key] 拒绝 {}{} {} 来自 {}：{}", shown, r.key() == null ? "" : "（" + r.key().getKeyName() + "）", path, ip, r.reason());
            reject(response);
            return;
        }
        LoginUser loginUser = r.user();
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities());
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);
        try
        {
            apiKeys.touch(r.key(), ip);
        }
        catch (RuntimeException e)
        {
            log.warn("[api-key] 记录最近使用失败：{}", e.getMessage());
        }
        log.info("[api-key] {}（{}）以 {} 调用 {} 来自 {}", shown, r.key().getKeyName(), loginUser.getUsername(), path, ip);
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response)
    {
        ServletUtils.renderString(response, JSON.toJSONString(AjaxResult.error(HttpStatus.UNAUTHORIZED, REJECT_MESSAGE)));
    }
}
