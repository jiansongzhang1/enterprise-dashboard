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
import com.fivetech.common.utils.MessageUtils;
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
            recordFailure(username, retryCount);
        }
        else
        {
            clearLoginRecordCache(username);
        }
    }

    /**
     * 帐号不存在时也按同样规则计数并给出同样的提示（「帐号或密码错误，还可尝试 x 次」），
     * 避免通过提示差异判断帐号是否存在
     */
    public void validateUnknownUser(String username)
    {
        Integer retryCount = redisCache.getCacheObject(getCacheKey(username));
        if (retryCount == null)
        {
            retryCount = 0;
        }
        if (retryCount >= maxRetryCount)
        {
            throw new UserPasswordRetryLimitExceedException(maxRetryCount, lockTime);
        }
        recordFailure(username, retryCount);
    }

    /** 记一次失败：达到上限的这一次直接提示已锁定，否则提示剩余可尝试次数 */
    private void recordFailure(String username, int retryCount)
    {
        int count = retryCount + 1;
        redisCache.setCacheObject(getCacheKey(username), count, lockTime, TimeUnit.MINUTES);
        if (count >= maxRetryCount)
        {
            throw new UserPasswordRetryLimitExceedException(maxRetryCount, lockTime);
        }
        throw new UserPasswordNotMatchException(maxRetryCount - count);
    }

    // ===================== 登录第二步：验证码错误锁定 =====================

    /** 帐号累计输错验证码多少次锁定；0 = 不限制 */
    @Value("${user.login.ticketMaxCodeFails:5}")
    private int codeMaxFails;

    /** 验证码错误达到上限后锁定分钟数 */
    @Value("${user.login.codeLockMinutes:50}")
    private int codeLockMinutes;

    private String getCodeCacheKey(String username)
    {
        return CacheConstants.GA_ERR_CNT_KEY + username;
    }

    private int codeFailCount(String username)
    {
        Integer count = redisCache.getCacheObject(getCodeCacheKey(username));
        return count == null ? 0 : count;
    }

    /** 是否因验证码错误次数过多被锁定 */
    public boolean isCodeLocked(String username)
    {
        return codeMaxFails > 0 && codeFailCount(username) >= codeMaxFails;
    }

    /** 验证码错误锁定分钟数 */
    public int getCodeLockMinutes()
    {
        return codeLockMinutes;
    }

    /** 锁定提示：验证码输入错误{0}次，帐号已锁定{1}分钟，请稍后再试 */
    public String codeLockMessage()
    {
        return MessageUtils.message("user.ga.code.retry.limit.exceed", codeMaxFails, codeLockMinutes);
    }

    /**
     * 记一次验证码错误（计数有效期 codeLockMinutes 分钟，每次错误重新计时）。
     *
     * @return 还可尝试的次数；0 表示这一次已达到上限、帐号已锁定；-1 表示不限制
     */
    public int recordCodeFailure(String username)
    {
        if (codeMaxFails <= 0)
        {
            return -1;
        }
        int count = codeFailCount(username) + 1;
        redisCache.setCacheObject(getCodeCacheKey(username), count, codeLockMinutes, TimeUnit.MINUTES);
        return Math.max(codeMaxFails - count, 0);
    }

    /** 验证码通过后清零 */
    public void clearCodeFailures(String username)
    {
        redisCache.deleteObject(getCodeCacheKey(username));
    }

    /** 是否处于锁定状态（密码错误或验证码错误达到上限），供用户列表展示 */
    public boolean isLocked(String username)
    {
        Integer pwd = redisCache.getCacheObject(getCacheKey(username));
        return (pwd != null && pwd >= maxRetryCount) || isCodeLocked(username);
    }

    public boolean matches(SysUser user, String rawPassword)
    {
        return SecurityUtils.matchesPassword(rawPassword, user.getPassword());
    }

    /** 解锁：同时清除密码错误和验证码错误计数 */
    public void clearLoginRecordCache(String loginName)
    {
        redisCache.deleteObject(getCacheKey(loginName));
        redisCache.deleteObject(getCodeCacheKey(loginName));
    }
}
