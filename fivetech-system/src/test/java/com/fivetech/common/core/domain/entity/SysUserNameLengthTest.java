package com.fivetech.common.core.domain.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

/** 用户管理：登录账号（userName）最长 50 个字符（2026-11-02，原 30；sys_user.user_name 同步改为 VARCHAR(50)） */
class SysUserNameLengthTest
{
    private static Set<ConstraintViolation<SysUser>> check(String name)
    {
        Validator v = Validation.buildDefaultValidatorFactory().getValidator();
        SysUser u = new SysUser();
        u.setUserName(name);
        return v.validateProperty(u, "userName");
    }

    @Test
    void fiftyCharsAllowed()
    {
        assertTrue(check("a".repeat(31)).isEmpty());
        assertTrue(check("a".repeat(50)).isEmpty());
    }

    @Test
    void over50Rejected()
    {
        Set<ConstraintViolation<SysUser>> r = check("a".repeat(51));
        assertEquals(1, r.size());
        assertEquals("用户账号长度不能超过50个字符", r.iterator().next().getMessage());
    }
}
