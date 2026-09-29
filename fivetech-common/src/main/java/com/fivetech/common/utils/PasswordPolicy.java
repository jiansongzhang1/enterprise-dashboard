package com.fivetech.common.utils;

import java.util.Locale;
import java.util.Set;

/**
 * 密码规则（设计文档第 8 节）。
 *
 * <ul>
 *   <li>长度 10–20 位（上限与登录接口 {@code UserConstants.PASSWORD_MAX_LENGTH} 保持一致，否则设置后无法登录）</li>
 *   <li>大写、小写、数字、符号中至少 3 种</li>
 *   <li>不包含登录账号（不区分大小写）</li>
 *   <li>拦截常见弱密码</li>
 * </ul>
 *
 * <p>“不与最近 3 次相同”需要密码历史表，本期未实现。</p>
 *
 * @author fivetech
 */
public final class PasswordPolicy
{
    public static final int MIN_LENGTH = 10;

    public static final int MAX_LENGTH = 20;

    /** 常见弱密码（统一小写比较）；满足复杂度但仍常被撞库的写法 */
    private static final Set<String> WEAK_PASSWORDS = Set.of(
            "password123!", "password@123", "p@ssw0rd123", "p@ssword123", "passw0rd!23",
            "qwerty123!", "qwer1234!@", "qwe123!@#", "abc123!@#$", "abcd1234!@",
            "admin123!@", "admin@1234", "admin@12345", "welcome123!", "welcome@123",
            "1qaz@wsx3edc", "1qaz!qaz2wsx", "zaq12wsx!@", "iloveyou123!", "changeme123!");

    private PasswordPolicy()
    {
    }

    /**
     * 校验密码。
     *
     * @param userName 登录账号
     * @param password 明文密码
     * @return 不符合时返回繁體提示；符合返回 null
     */
    public static String check(String userName, String password)
    {
        String rule = "密碼需為 " + MIN_LENGTH + "–" + MAX_LENGTH + " 碼，包含大寫、小寫、數字、符號其中 3 種，且不可包含帳號。";
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH)
        {
            return rule;
        }
        int upper = 0, lower = 0, digit = 0, symbol = 0;
        for (int i = 0; i < password.length(); i++)
        {
            char c = password.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c))
            {
                return "密碼不可包含空白或控制字元。";
            }
            if (c >= 'A' && c <= 'Z')
            {
                upper = 1;
            }
            else if (c >= 'a' && c <= 'z')
            {
                lower = 1;
            }
            else if (c >= '0' && c <= '9')
            {
                digit = 1;
            }
            else
            {
                symbol = 1;
            }
        }
        if (upper + lower + digit + symbol < 3)
        {
            return rule;
        }
        if (userName != null && !userName.isBlank()
                && password.toLowerCase(Locale.ROOT).contains(userName.trim().toLowerCase(Locale.ROOT)))
        {
            return rule;
        }
        if (WEAK_PASSWORDS.contains(password.toLowerCase(Locale.ROOT)))
        {
            return "此密碼過於常見，請改用其他密碼。";
        }
        return null;
    }
}
