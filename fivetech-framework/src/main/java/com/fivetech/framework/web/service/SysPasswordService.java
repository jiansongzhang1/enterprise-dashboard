package com.fivetech.framework.web.service;

import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import com.fivetech.common.constant.CacheConstants;
import com.fivetech.common.core.domain.entity.SysUser;
import com.fivetech.common.core.redis.RedisCache;
import com.fivetech.common.exception.user.UserPasswordNotMatchException;
import com.fivetech.common.exception.user.UserPasswordRetryLimitExceedException;
import com.fivetech.common.utils.SecurityUtils;
import com.fivetech.framework.security.context.AuthenticationContextHolder;

/**
 * 登录密码方法
 * 
 * @author fivetech
 */
@Component
public class SysPasswordService
{
    @Autowired
    private RedisCache redisCache;

    @Value(value = "${user.password.maxRetryCount}")
    private int maxRetryCount;

    @Value(value = "${user.password.lockTime}")
    private int lockTime;

    /**
     * 登录账户密码错误次数缓存键名
     * 
     * @param username 用户名
     * @return 缓存键key
     */
    private String getCacheKey(String username)
    {
        return CacheConstants.PWD_ERR_CNT_KEY + username;
    }

    /**
     * 校验密码：密码错误累计 maxRetryCount 次（默认 3）锁定 lockTime 分钟（默认 60）。
     * 计数存 Redis，到期自动删除即解锁，帐号状态不变；密码正确时清零。只统计密码错误，不含验证码
     */
    public void validate(SysUser user)
    {
        Authentication usernamePasswordAuthenticationToken = AuthenticationContextHolder.getContext();
        String username = usernamePasswordAuthenticationToken.getName();
        String password = usernamePasswordAuthenticationToken.getCredentials().toString();

        Integer retryCount = redisCache.getCacheObject(getCacheKey(username));
        if (retryCount == null)
        {
            retryCount = 0;
        }
        if (retryCount >= maxRetryCount)
        {
            throw new UserPasswordRetryLimitExceedException(maxRetryCount, lockTime);
        }
        if (!matches(user, password))
        {
            retryCount = retryCount + 1;
            redisCache.setCacheObject(getCacheKey(username), retryCount, lockTime, TimeUnit.MINUTES);
            // 达到上限的这一次直接提示已锁定，不用等下一次登录才知道
            if (retryCount >= maxRetryCount)
            {
                throw new UserPasswordRetryLimitExceedException(maxRetryCount, lockTime);
            }
            throw new UserPasswordNotMatchException();
        }
        else
        {
            clearLoginRecordCache(username);
        }
    }

    public boolean matches(SysUser user, String rawPassword)
    {
        return SecurityUtils.matchesPassword(rawPassword, user.getPassword());
    }

    public void clearLoginRecordCache(String loginName)
    {
        if (redisCache.hasKey(getCacheKey(loginName)))
        {
            redisCache.deleteObject(getCacheKey(loginName));
        }
    }
}
