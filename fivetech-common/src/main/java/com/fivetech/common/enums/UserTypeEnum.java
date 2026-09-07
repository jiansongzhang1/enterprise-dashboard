package com.fivetech.common.enums;

/**
 * 用户身份类型。
 * user_type 是系统身份标识，具体菜单和操作权限仍由 RBAC 角色控制。
 */
public enum UserTypeEnum
{
    /** 普通用户 */
    NORMAL("00"),

    /** 超级管理员 */
    SUPER_ADMIN("01");

    private final String code;

    UserTypeEnum(String code)
    {
        this.code = code;
    }

    public String getCode()
    {
        return code;
    }

    public static boolean isSuperAdmin(String userType)
    {
        return SUPER_ADMIN.code.equals(userType);
    }
}
