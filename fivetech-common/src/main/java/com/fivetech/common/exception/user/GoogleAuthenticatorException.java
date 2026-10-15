package com.fivetech.common.exception.user;

/**
 * Google Authenticator 验证码错误异常类
 *
 * @author fivetech
 */
public class GoogleAuthenticatorException extends UserException
{
    private static final long serialVersionUID = 1L;

    public GoogleAuthenticatorException()
    {
        super("user.ga.code.error", null);
    }

    /**
     * 验证码错误，并提示剩余可尝试次数（两步登录第二步）
     *
     * @param remaining 还可尝试的次数
     */
    public GoogleAuthenticatorException(int remaining)
    {
        super("user.ga.code.error.remaining", new Object[] { remaining });
    }
}
