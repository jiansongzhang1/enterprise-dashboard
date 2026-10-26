package com.fivetech.monitor.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 可选负责人：状态正常、有邮箱、拥有 monitor:rule:list（或超级管理员）的用户。全站只显示邮箱。
 *
 * @author fivetech
 */
public class MonitorOwner
{
    private Long userId;

    private String email;

    /** 用户类型（01 超级管理员） */
    private String userType;

    /** 正常状态角色的 site_code（通用角色为空串），用来判断能否进入某个站点 */
    private List<String> roleSiteCodes = new ArrayList<>();

    public Long getUserId()
    {
        return userId;
    }

    public void setUserId(Long userId)
    {
        this.userId = userId;
    }

    public String getEmail()
    {
        return email;
    }

    public void setEmail(String email)
    {
        this.email = email;
    }

    public String getUserType()
    {
        return userType;
    }

    public void setUserType(String userType)
    {
        this.userType = userType;
    }

    public List<String> getRoleSiteCodes()
    {
        return roleSiteCodes;
    }

    public void setRoleSiteCodes(List<String> roleSiteCodes)
    {
        this.roleSiteCodes = roleSiteCodes == null ? new ArrayList<>() : roleSiteCodes;
    }

    /** 从查询结果（逗号分隔，通用角色写作 *）还原 roleSiteCodes；null 表示没有正常角色 */
    public void setRoleSites(String csv)
    {
        List<String> codes = new ArrayList<>();
        if (csv != null && !csv.isEmpty())
        {
            for (String code : csv.split(","))
            {
                codes.add("*".equals(code) ? "" : code);
            }
        }
        this.roleSiteCodes = codes;
    }
}
