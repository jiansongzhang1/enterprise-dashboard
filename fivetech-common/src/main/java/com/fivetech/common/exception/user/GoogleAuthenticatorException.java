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
}
