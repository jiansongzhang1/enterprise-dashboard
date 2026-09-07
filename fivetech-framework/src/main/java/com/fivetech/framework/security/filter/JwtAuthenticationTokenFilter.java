package com.fivetech.framework.security.filter;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.fivetech.common.core.domain.model.LoginUser;
import com.fivetech.common.core.domain.entity.SysUser;
import com.fivetech.common.enums.UserStatus;
import com.fivetech.common.utils.SecurityUtils;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.framework.web.service.SysPermissionService;
import com.fivetech.framework.web.service.TokenService;
import com.fivetech.system.service.ISysUserService;

/**
 * token过滤器 验证token有效性
 * 
 * @author fivetech
 */
@Component
public class JwtAuthenticationTokenFilter extends OncePerRequestFilter
{
    @Autowired
    private TokenService tokenService;

    @Autowired
    private ISysUserService userService;

    @Autowired
    private SysPermissionService permissionService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException
    {
        LoginUser loginUser = tokenService.getLoginUser(request);
        if (StringUtils.isNotNull(loginUser) && StringUtils.isNull(SecurityUtils.getAuthentication()))
        {
            // 权限不从会话缓存读取，每次请求都从数据库重新加载用户、角色和权限。
            // 这样角色停用、权限撤销和用户禁用可以立即生效。
            SysUser currentUser = userService.selectUserById(loginUser.getUserId());
            if (currentUser == null
                    || UserStatus.DELETED.getCode().equals(currentUser.getDelFlag())
                    || UserStatus.DISABLE.getCode().equals(currentUser.getStatus()))
            {
                tokenService.delLoginUser(loginUser.getToken());
                chain.doFilter(request, response);
                return;
            }
            loginUser.setUser(currentUser);
            loginUser.setDeptId(currentUser.getDeptId());
            loginUser.setPermissions(permissionService.getMenuPermission(currentUser));
            tokenService.verifyToken(loginUser);
            UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities());
            authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authenticationToken);
        }
        chain.doFilter(request, response);
    }
}
