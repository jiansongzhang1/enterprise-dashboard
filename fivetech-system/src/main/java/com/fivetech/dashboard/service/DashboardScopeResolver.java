package com.fivetech.dashboard.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import com.fivetech.common.core.domain.entity.SysRole;
import com.fivetech.common.core.domain.model.LoginUser;
import com.fivetech.common.utils.SecurityUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.gateway.ScopeFilter;

/**
 * 数据权限解析：把一期的 Data Scope 翻译成下推给数据平台的过滤条件。
 * <p>
 * 三条硬规则（与《后端设计方案》§6 一致）：
 * <ol>
 *   <li>无权限的指标<b>不返回、不占位</b>，不能返回值再由前端隐藏；</li>
 *   <li>下钻明细的权限范围必须<b>不宽于</b>来源指标卡，否则会出现
 *       「卡片看不到、下钻能看到」的越权；</li>
 *   <li>过滤条件由服务端强制拼接，前端隐藏只是体验优化。</li>
 * </ol>
 *
 * @author fivetech
 */
@Component
public class DashboardScopeResolver
{
    /** 一期已有的数据范围编码，取值与 sys_role.data_scope 一致 */
    private static final String SCOPE_ALL = "1";

    private final DashboardProperties properties;

    public DashboardScopeResolver(DashboardProperties properties)
    {
        this.properties = properties;
    }

    /**
     * 解析当前登录用户的数据范围。
     * <p>
     * TODO 站点与指标集两个维度依赖 sys_role_site / sys_role_metric_set 两张新表，
     *      建表前先按「超管不限、其余按部门」处理，接表后在此补全即可。
     */
    public ScopeFilter resolve()
    {
        if (SecurityUtils.isAdmin(safeUserId()))
        {
            return ScopeFilter.unrestricted();
        }
        ScopeFilter filter = new ScopeFilter();
        LoginUser loginUser = safeLoginUser();
        if (loginUser == null || loginUser.getUser() == null)
        {
            // 取不到身份时给最小权限，绝不放行
            filter.getSiteCodes().add("__none__");
            return filter;
        }
        List<SysRole> roles = loginUser.getUser().getRoles();
        if (roles != null)
        {
            for (SysRole role : roles)
            {
                if (SCOPE_ALL.equals(role.getDataScope()))
                {
                    // 多角色的数据权限取并集，任一角色为全部即全部
                    return ScopeFilter.unrestricted();
                }
            }
        }
        if (loginUser.getUser().getDeptId() != null)
        {
            filter.getDeptIds().add(loginUser.getUser().getDeptId());
        }
        filter.getSiteCodes().add(properties.getDefaultSite());
        return filter;
    }

    /**
     * 当前用户可见的指标编码。空列表表示不限。
     */
    public List<String> allowedMetricCodes(ScopeFilter filter)
    {
        if (filter == null || filter.isUnrestricted())
        {
            return new ArrayList<>();
        }
        return filter.getMetricCodes();
    }

    private Long safeUserId()
    {
        try
        {
            return SecurityUtils.getUserId();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private LoginUser safeLoginUser()
    {
        try
        {
            return SecurityUtils.getLoginUser();
        }
        catch (Exception e)
        {
            return null;
        }
    }
}
