package com.fivetech.framework.web.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import com.fivetech.common.constant.Constants;
import com.fivetech.common.constant.UserConstants;
import com.fivetech.common.core.domain.entity.SysRole;
import com.fivetech.common.core.domain.entity.SysUser;
import com.fivetech.common.utils.SiteContext;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.system.service.ISysMenuService;
import com.fivetech.system.service.ISysRoleService;

/**
 * 用户权限处理
 * 
 * @author fivetech
 */
@Component
public class SysPermissionService
{
    @Autowired
    private ISysRoleService roleService;

    @Autowired
    private ISysMenuService menuService;

    @Autowired
    private DashboardProperties dashboardProperties;

    /**
     * 获取角色数据权限
     * 
     * @param user 用户信息
     * @return 角色权限信息
     */
    public Set<String> getRolePermission(SysUser user)
    {
        Set<String> roles = new HashSet<String>();
        // 管理员拥有所有权限
        if (user.isAdmin())
        {
            roles.add(Constants.SUPER_ADMIN);
        }
        else
        {
            roles.addAll(roleService.selectRolePermissionByUserId(user.getUserId()));
        }
        return roles;
    }

    /**
     * 获取菜单数据权限
     * 
     * @param user 用户信息
     * @return 菜单权限信息
     */
    public Set<String> getMenuPermission(SysUser user)
    {
        Set<String> perms = new HashSet<String>();
        // 管理员拥有所有权限
        if (user.isAdmin())
        {
            perms.add(Constants.ALL_PERMISSION);
        }
        else
        {
            List<SysRole> roles = user.getRoles();
            if (!CollectionUtils.isEmpty(roles))
            {
                // 多角色设置permissions属性，以便数据权限匹配权限
                for (SysRole role : roles)
                {
                    if (StringUtils.equals(role.getStatus(), UserConstants.ROLE_NORMAL) && !role.isAdmin())
                    {
                        Set<String> rolePerms = menuService.selectMenuPermsByRoleId(role.getRoleId());
                        role.setPermissions(rolePerms);
                        perms.addAll(rolePerms);
                    }
                }
            }
            else
            {
                perms.addAll(menuService.selectMenuPermsByUserId(user.getUserId()));
            }
        }
        return perms;
    }

    // ===================== 多站点一期：角色带站点 =====================

    /**
     * 当前站点下的角色标识：当前站点的角色 + 通用角色（site_code 为空）。超级管理员返回 admin
     */
    public Set<String> getRolePermissionForSite(SysUser user)
    {
        Set<String> roles = new HashSet<String>();
        if (user.isAdmin())
        {
            roles.add(Constants.SUPER_ADMIN);
            return roles;
        }
        String site = SiteContext.code();
        if (user.getRoles() != null)
        {
            for (SysRole role : user.getRoles())
            {
                if (StringUtils.equals(role.getStatus(), UserConstants.ROLE_NORMAL) && appliesTo(role, site)
                    && StringUtils.isNotEmpty(role.getRoleKey()))
                {
                    roles.add(role.getRoleKey());
                }
            }
        }
        return roles;
    }

    /**
     * 用户可以切换的站点（按配置顺序）：超级管理员 = 全部；其他用户 = 站点角色所属的站点，
     * 有通用角色时再加上默认站点；一个都没有时只给默认站点（兼容未分配站点的老账号）
     */
    public List<String> accessibleSites(SysUser user)
    {
        List<String> roleSites = new java.util.ArrayList<>();
        if (user != null && user.getRoles() != null)
        {
            for (SysRole role : user.getRoles())
            {
                if (StringUtils.equals(role.getStatus(), UserConstants.ROLE_NORMAL))
                {
                    roleSites.add(role.getSiteCode());
                }
            }
        }
        return dashboardProperties.accessibleSites(user == null || user.isAdmin(), roleSites);
    }

    private static boolean appliesTo(SysRole role, String site)
    {
        String roleSite = StringUtils.trim(role.getSiteCode());
        return StringUtils.isEmpty(roleSite) || (site != null && roleSite.equalsIgnoreCase(site));
    }
}
