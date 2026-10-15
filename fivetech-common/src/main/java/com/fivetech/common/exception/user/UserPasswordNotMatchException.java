package com.fivetech.common.exception.user;

/**
 * 用户密码不正确或不符合规范异常类
 * 
 * @author fivetech
 */
public class UserPasswordNotMatchException extends UserException
{
    private static final long serialVersionUID = 1L;

    public UserPasswordNotMatchException()
    {
        super("user.password.not.match", null);
    }

    /**
     * 帐号或密码错误，并提示剩余可尝试次数（达到上限前）
     *
     * @param remaining 还可尝试的次数
     */
    public UserPasswordNotMatchException(int remaining)
    {
        super("user.password.not.match.remaining", new Object[] { remaining });
    }
}
