package com.fivetech.common.core.domain.model;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import com.alibaba.fastjson2.annotation.JSONField;
import com.fivetech.common.core.domain.entity.SysUser;

/**
 * 登录用户身份权限
 * 
 * @author fivetech
 */
public class LoginUser implements UserDetails, Serializable
{
    private static final long serialVersionUID = 1L;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 部门ID
     */
    private Long deptId;

    /**
     * 用户唯一标识
     */
    private String token;

    /**
     * 登录时间
     */
    private Long loginTime;

    /**
     * 过期时间
     */
    private Long expireTime;

    /**
     * 登录IP地址
     */
    private String ipaddr;

    /**
     * 登录地点
     */
    private String loginLocation;

    /**
     * 浏览器类型
     */
    private String browser;

    /**
     * 操作系统
     */
    private String os;

    /**
     * 权限列表
     */
    private Set<String> permissions;

    /**
     * 用户信息
     */
    private SysUser user;

    public LoginUser()
    {
    }

    public LoginUser(SysUser user, Set<String> permissions)
    {
        this.user = user;
        this.permissions = permissions;
    }

    public LoginUser(Long userId, Long deptId, SysUser user, Set<String> permissions)
    {
        this.userId = userId;
        this.deptId = deptId;
        this.user = user;
        this.permissions = permissions;
    }

    public Long getUserId()
    {
        return userId;
    }

    public void setUserId(Long userId)
    {
        this.userId = userId;
    }

    public Long getDeptId()
    {
        return deptId;
    }

    public void setDeptId(Long deptId)
    {
        this.deptId = deptId;
    }

    public String getToken()
    {
        return token;
    }

    public void setToken(String token)
    {
        this.token = token;
    }

    @JSONField(serialize = false)
    @Override
    public String getPassword()
    {
        return user.getPassword();
    }

    @Override
    public String getUsername()
    {
        return user.getUserName();
    }

    /**
     * 账户是否未过期,过期无法验证
     */
    @JSONField(serialize = false)
    @Override
    public boolean isAccountNonExpired()
    {
        return true;
    }

    /**
     * 指定用户是否解锁,锁定的用户无法进行身份验证
     * 
     * @return
     */
    @JSONField(serialize = false)
    @Override
    public boolean isAccountNonLocked()
    {
        return true;
    }

    /**
     * 指示是否已过期的用户的凭据(密码),过期的凭据防止认证
     * 
     * @return
     */
    @JSONField(serialize = false)
    @Override
    public boolean isCredentialsNonExpired()
    {
        return true;
    }

    /**
     * 是否可用 ,禁用的用户不能身份验证
     * 
     * @return
     */
    @JSONField(serialize = false)
    @Override
    public boolean isEnabled()
    {
        return true;
    }

    public Long getLoginTime()
    {
        return loginTime;
    }

    public void setLoginTime(Long loginTime)
    {
        this.loginTime = loginTime;
    }

    public String getIpaddr()
    {
        return ipaddr;
    }

    public void setIpaddr(String ipaddr)
    {
        this.ipaddr = ipaddr;
    }

    public String getLoginLocation()
    {
        return loginLocation;
    }

    public void setLoginLocation(String loginLocation)
    {
        this.loginLocation = loginLocation;
    }

    public String getBrowser()
    {
        return browser;
    }

    public void setBrowser(String browser)
    {
        this.browser = browser;
    }

    public String getOs()
    {
        return os;
    }

    public void setOs(String os)
    {
        this.os = os;
    }

    public Long getExpireTime()
    {
        return expireTime;
    }

    public void setExpireTime(Long expireTime)
    {
        this.expireTime = expireTime;
    }

    /**
     * 当前站点下的权限（多站点一期）。
     *
     * <p>角色带站点：通用角色（site_code 为空）在所有站点生效，站点角色只在本站点生效。
     * 这里按 {@link com.fivetech.common.utils.SiteContext} 的当前站点，从各角色的权限里现算：
     * 当前站点角色 ∪ 通用角色。所有权限判断（@ss.hasPermi、SecurityUtils.hasPermi、getInfo）都走这里，
     * 切换站点后不用重新登录。超级管理员、全部是通用角色的老账号，与原来完全一致。</p>
     */
    public Set<String> getPermissions()
    {
        if (permissions == null || permissions.contains("*:*:*") || user == null
            || user.getRoles() == null || user.getRoles().isEmpty())
        {
            return permissions;
        }
        boolean anySiteRole = false;
        for (com.fivetech.common.core.domain.entity.SysRole role : user.getRoles())
        {
            if (role.getSiteCode() != null && !role.getSiteCode().trim().isEmpty())
            {
                anySiteRole = true;
                break;
            }
        }
        if (!anySiteRole)
        {
            return permissions;
        }
        String site = com.fivetech.common.utils.SiteContext.code();
        Set<String> result = new java.util.HashSet<>();
        for (com.fivetech.common.core.domain.entity.SysRole role : user.getRoles())
        {
            if (role.getPermissions() == null)
            {
                continue;
            }
            String roleSite = role.getSiteCode() == null ? "" : role.getSiteCode().trim();
            if (roleSite.isEmpty() || (site != null && roleSite.equalsIgnoreCase(site)))
            {
                result.addAll(role.getPermissions());
            }
        }
        return result;
    }

    /** 全部站点权限的并集（原始值，仅供刷新与兼容使用） */
    public Set<String> getAllSitePermissions()
    {
        return permissions;
    }

    public void setPermissions(Set<String> permissions)
    {
        this.permissions = permissions;
    }

    public SysUser getUser()
    {
        return user;
    }

    public void setUser(SysUser user)
    {
        this.user = user;
    }

    @Override
    @JSONField(serialize = false)
    public Collection<? extends GrantedAuthority> getAuthorities()
    {
        if (permissions == null || permissions.isEmpty())
        {
            return Collections.emptyList();
        }
        return permissions.stream()
            .filter(Objects::nonNull)
            .map(SimpleGrantedAuthority::new)
            .collect(Collectors.toList());
    }
}
