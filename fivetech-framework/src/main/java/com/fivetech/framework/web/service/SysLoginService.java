package com.fivetech.framework.web.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import com.fivetech.common.constant.Constants;
import com.fivetech.common.constant.GaConstants;
import com.fivetech.framework.notification.AccountMailService;
import com.fivetech.common.constant.UserConstants;
import com.fivetech.common.core.domain.entity.SysUser;
import com.fivetech.common.core.domain.model.LoginUser;
import com.fivetech.common.core.domain.model.GaBindInfo;
import com.fivetech.common.core.redis.RedisCache;
import com.fivetech.common.enums.UserStatus;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.common.exception.user.BlackListException;
import com.fivetech.common.exception.user.GoogleAuthenticatorException;
import com.fivetech.common.exception.user.UserNotExistsException;
import com.fivetech.common.exception.user.UserPasswordNotMatchException;
import com.fivetech.common.config.FiveTechConfig;
import com.fivetech.common.utils.DateUtils;
import com.fivetech.common.utils.GoogleAuthenticatorUtils;
import com.fivetech.common.utils.MessageUtils;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.common.utils.ip.IpUtils;
import com.fivetech.common.utils.uuid.IdUtils;
import com.fivetech.framework.manager.AsyncManager;
import com.fivetech.framework.manager.factory.AsyncFactory;
import com.fivetech.framework.security.context.AuthenticationContextHolder;
import com.fivetech.system.service.ISysConfigService;
import com.fivetech.system.service.ISysUserService;

/**
 * 登录校验方法
 * 
 * @author fivetech
 */
@Component
public class SysLoginService
{
    @Autowired
    private AccountMailService accountMailService;

    @Autowired
    private TokenService tokenService;

    @Resource
    private AuthenticationManager authenticationManager;

    @Autowired
    private ISysUserService userService;

    @Autowired
    private ISysConfigService configService;

    @Autowired
    private FiveTechConfig fivetechConfig;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private SysPermissionService permissionService;

    @Autowired
    private SysPasswordService passwordService;

    /** 两步登录：第一步通过后发的一次性凭证，Redis key 前缀 */
    private static final String LOGIN_TICKET_KEY = "login_ticket:";

    /** 两步登录凭证失效（超时未输入验证码 / 验证码错误次数过多）：前端收到后回到第一步 */
    public static final int LOGIN_TICKET_EXPIRED = 4011;

    /** 第一步密码正确但尚未绑定验证器（新帐号 / 管理员重置了验证器）：不是错误，前端转到首次绑定流程 */
    public static final int GA_NOT_BOUND = 4012;

    /** 第一步通过后，多少分钟内必须完成验证码 */
    @Value("${user.login.ticketMinutes:5}")
    private int ticketMinutes;

    /**
     * 登录验证
     * 
     * @param username 用户名
     * @param password 密码
     * @param code Google Authenticator 6 位验证码
     * @return 结果
     */
    public String login(String username, String password, String code)
    {
        // 登录前置校验
        loginPreCheck(username, password);
        LoginUser loginUser;
        try
        {
            loginUser = authenticate(username, password);
            validateTotpCode(loginUser.getUser(), code);
        }
        catch (UserPasswordNotMatchException | GoogleAuthenticatorException | ServiceException e)
        {
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, e.getMessage()));
            throw e;
        }
        AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_SUCCESS, MessageUtils.message("user.login.success")));
        recordLoginInfo(loginUser.getUserId());
        // 生成token
        return tokenService.createToken(loginUser);
    }

    /**
     * 两步登录 · 第一步：校验帐号密码（密码错误计入锁定次数）。
     * 已绑定验证器：发一次性凭证 ticket（有效期 ticketMinutes 分钟），第二步凭它提交验证码；
     * 未绑定（新帐号 / 管理员重置了验证器）：不报错，返回 bindRequired=true，前端转到首次绑定流程
     * （/ga/bind/start → /ga/bind/confirm，绑定成功直接返回 token）。
     *
     * @return bindRequired；已绑定时另有 ticket、expireSeconds
     */
    public Map<String, Object> loginPassword(String username, String password)
    {
        loginPreCheck(username, password);
        LoginUser loginUser;
        try
        {
            // 验证码错误次数过多被锁定：锁定期内直接拒绝，不再校验密码
            if (passwordService.isCodeLocked(username))
            {
                throw new ServiceException(passwordService.codeLockMessage());
            }
            loginUser = authenticate(username, password);
        }
        catch (UserPasswordNotMatchException | ServiceException e)
        {
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, e.getMessage()));
            throw e;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (!isGaBound(loginUser.getUser()))
        {
            // 密码正确但还没绑定验证器：放行到绑定流程，不发 ticket（第二步验证码无从校验）
            result.put("bindRequired", true);
            return result;
        }
        String ticket = IdUtils.fastSimpleUUID();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", loginUser.getUserId());
        data.put("userName", loginUser.getUsername());
        redisCache.setCacheObject(LOGIN_TICKET_KEY + ticket, data, ticketMinutes, TimeUnit.MINUTES);

        result.put("bindRequired", false);
        result.put("ticket", ticket);
        result.put("expireSeconds", ticketMinutes * 60);
        return result;
    }

    /**
     * 两步登录 · 第二步：凭第一步的 ticket 提交 6 位验证码，通过后签发 token。
     * ticket 过期（超过 ticketMinutes 分钟未提交）或已用过 → 错误码 LOGIN_TICKET_EXPIRED，前端回到第一步。
     */
    public String loginVerify(String ticket, String code)
    {
        String key = LOGIN_TICKET_KEY + StringUtils.trimToEmpty(ticket);
        Map<String, Object> data = StringUtils.isEmpty(ticket) ? null : redisCache.getCacheObject(key);
        if (data == null)
        {
            throw new ServiceException(MessageUtils.message("user.login.ticket.expired", ticketMinutes), LOGIN_TICKET_EXPIRED);
        }
        String username = String.valueOf(data.get("userName"));
        Long userId = ((Number) data.get("userId")).longValue();
        SysUser user = userService.selectUserById(userId);
        try
        {
            // 两步之间帐号可能被停用 / 删除 / 重置验证器：重新检查
            if (user == null || UserStatus.DELETED.getCode().equals(user.getDelFlag()))
            {
                redisCache.deleteObject(key);
                throw new ServiceException(MessageUtils.message("user.password.delete"));
            }
            if (UserStatus.DISABLE.getCode().equals(user.getStatus()))
            {
                redisCache.deleteObject(key);
                throw new ServiceException(MessageUtils.message("user.blocked"));
            }
            checkGaReady(user);
            // 已锁定（例如另一个窗口里刚输错到上限）：作废凭证，回到第一步并提示锁定
            if (passwordService.isCodeLocked(username))
            {
                redisCache.deleteObject(key);
                throw new ServiceException(passwordService.codeLockMessage(), LOGIN_TICKET_EXPIRED);
            }
            if (!GoogleAuthenticatorUtils.verifyCode(user.getGaSecret(), code))
            {
                // 按帐号累计（不是按凭证），重新登录也不会清零；达到上限锁定 codeLockMinutes 分钟
                int remaining = passwordService.recordCodeFailure(username);
                if (remaining == 0)
                {
                    redisCache.deleteObject(key);
                    // 只在验证码错误触发锁定时提醒（密码已正确，通常说明密码已泄露）；密码错误触发的锁定不发邮件
                    accountMailService.sendCodeLocked(user, passwordService.getCodeLockMinutes());
                    throw new ServiceException(passwordService.codeLockMessage(), LOGIN_TICKET_EXPIRED);
                }
                throw remaining > 0 ? new GoogleAuthenticatorException(remaining) : new GoogleAuthenticatorException();
            }
            passwordService.clearCodeFailures(username);
            // 一次性：先删再发 token，并发重放只有一次能删成功
            if (!redisCache.deleteObject(key))
            {
                throw new ServiceException(MessageUtils.message("user.login.ticket.expired", ticketMinutes), LOGIN_TICKET_EXPIRED);
            }
        }
        catch (GoogleAuthenticatorException | ServiceException e)
        {
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, e.getMessage()));
            throw e;
        }
        LoginUser loginUser = new LoginUser(user.getUserId(), user.getDeptId(), user, permissionService.getMenuPermission(user));
        AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_SUCCESS, MessageUtils.message("user.login.success")));
        recordLoginInfo(user.getUserId());
        return tokenService.createToken(loginUser);
    }

    /** 是否已完成验证器绑定（有密钥且状态为已绑定） */
    private boolean isGaBound(SysUser user)
    {
        return StringUtils.isNotNull(user) && StringUtils.isNotEmpty(user.getGaSecret())
                && user.getGaStatus() != null && user.getGaStatus().intValue() == GaConstants.GA_STATUS_BOUND;
    }

    /** 已开通且已绑定验证器，否则抛出对应提示（前端据「尚未完成绑定」切到绑定页） */
    private void checkGaReady(SysUser user)
    {
        // 管理员重置验证器后：密钥为空、状态为待绑定 → 提示去登录页「绑定」重新绑定，而不是「未开通」
        if (StringUtils.isNotNull(user) && StringUtils.isEmpty(user.getGaSecret())
                && user.getGaStatus() != null && user.getGaStatus().intValue() == GaConstants.GA_STATUS_PENDING)
        {
            throw new ServiceException(MessageUtils.message("user.ga.bind.pending"));
        }
        if (StringUtils.isNull(user) || StringUtils.isEmpty(user.getGaSecret()))
        {
            throw new ServiceException(MessageUtils.message("user.ga.secret.missing"));
        }
        if (user.getGaStatus() == null || user.getGaStatus().intValue() != GaConstants.GA_STATUS_BOUND)
        {
            throw new ServiceException(MessageUtils.message("user.ga.bind.pending"));
        }
    }

    /**
     * 校验 Google Authenticator 动态码
     * 
     * @param username 用户名
     * @param code 动态码
     * @return 结果
     */
    public void validateTotpCode(SysUser user, String code)
    {
        if (StringUtils.isNull(user) || StringUtils.isEmpty(user.getGaSecret()))
        {
            String message = MessageUtils.message("user.ga.secret.missing");
            throw new ServiceException(message);
        }
        if (user.getGaStatus() == null || user.getGaStatus().intValue() != GaConstants.GA_STATUS_BOUND)
        {
            throw new ServiceException(MessageUtils.message("user.ga.bind.pending"));
        }
        if (!GoogleAuthenticatorUtils.verifyCode(user.getGaSecret(), code))
        {
            throw new GoogleAuthenticatorException();
        }
    }

    /**
     * 生成绑定信息
     *
     * @param username 用户名
     * @param password 密码
     * @return 绑定信息
     */
    public GaBindInfo createBindInfo(String username, String password)
    {
        loginPreCheck(username, password);
        LoginUser loginUser = authenticate(username, password);
        SysUser user = loginUser.getUser();
        if (StringUtils.isNull(user))
        {
            throw new UserNotExistsException();
        }
        if (user.getGaStatus() != null && user.getGaStatus().intValue() == GaConstants.GA_STATUS_BOUND)
        {
            throw new ServiceException(MessageUtils.message("user.ga.bind.already"));
        }

        // 首次绑定由后端生成密钥并条件写入，避免客户端或重复请求覆盖密钥。
        if (StringUtils.isEmpty(user.getGaSecret()))
        {
            String generatedSecret = GoogleAuthenticatorUtils.generateSecret(32);
            userService.updateGaSecretIfEmpty(user.getUserId(), generatedSecret, GaConstants.GA_STATUS_PENDING);
            user = userService.selectUserById(user.getUserId());
        }
        if (StringUtils.isEmpty(user.getGaSecret()))
        {
            throw new ServiceException(MessageUtils.message("user.ga.secret.missing"));
        }

        GaBindInfo bindInfo = new GaBindInfo();
        bindInfo.setGaStatus(user.getGaStatus());
        // 验证器里显示为「Yata: 用户名」：发行方取 gaIssuer（默认 Yata），帐户名取登录帐号，
        // 一个手机上绑定多个帐号时可以区分；手动输入设置密钥时，帐户名称仍由用户自己填写
        String issuer = StringUtils.isNotEmpty(fivetechConfig.getGaIssuer()) ? fivetechConfig.getGaIssuer() : "Yata";
        bindInfo.setIssuer(issuer);
        String account = StringUtils.isNotEmpty(user.getUserName()) ? user.getUserName() : issuer;
        bindInfo.setAccount(account);
        bindInfo.setSecret(user.getGaSecret());
        bindInfo.setOtpauthUrl(GoogleAuthenticatorUtils.buildOtpAuthUrl(issuer, account, user.getGaSecret()));
        return bindInfo;
    }

    /**
     * 确认绑定
     *
     * @param username 用户名
     * @param password 密码
     * @param code 验证码
     */
    public String confirmBind(String username, String password, String code)
    {
        loginPreCheck(username, password);
        LoginUser loginUser = authenticate(username, password);
        SysUser user = loginUser.getUser();
        if (StringUtils.isNull(user) || StringUtils.isEmpty(user.getGaSecret()))
        {
            throw new ServiceException(MessageUtils.message("user.ga.secret.missing"));
        }
        if (user.getGaStatus() != null && user.getGaStatus().intValue() == GaConstants.GA_STATUS_BOUND)
        {
            throw new ServiceException(MessageUtils.message("user.ga.bind.already"));
        }
        if (!GoogleAuthenticatorUtils.verifyCode(user.getGaSecret(), code))
        {
            throw new GoogleAuthenticatorException();
        }

        userService.updateGaStatus(user.getUserId(), GaConstants.GA_STATUS_BOUND);

        // 绑定时已校验过密码 + 当前验证码，等同完成两步登录：直接签发 token，不用再走一遍登录
        user.setGaStatus(GaConstants.GA_STATUS_BOUND);
        AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_SUCCESS, MessageUtils.message("user.login.success")));
        recordLoginInfo(user.getUserId());
        return tokenService.createToken(loginUser);
    }

    /**
     * 用户验证
     *
     * @param username 用户名
     * @param password 密码
     * @return 登录用户
     */
    private LoginUser authenticate(String username, String password)
    {
        Authentication authentication = null;
        try
        {
            UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(username, password);
            AuthenticationContextHolder.setContext(authenticationToken);
            authentication = authenticationManager.authenticate(authenticationToken);
        }
        catch (Exception e)
        {
            if (e instanceof BadCredentialsException)
            {
                throw new UserPasswordNotMatchException();
            }
            throw new ServiceException(e.getMessage());
        }
        finally
        {
            AuthenticationContextHolder.clearContext();
        }
        return (LoginUser) authentication.getPrincipal();
    }

    /**
     * 登录前置校验
     * @param username 用户名
     * @param password 用户密码
     */
    public void loginPreCheck(String username, String password)
    {
        // 用户名或密码为空 错误
        if (StringUtils.isEmpty(username) || StringUtils.isEmpty(password))
        {
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, MessageUtils.message("not.null")));
            throw new UserNotExistsException();
        }
        // 密码如果不在指定范围内 错误
        if (password.length() < UserConstants.PASSWORD_MIN_LENGTH
                || password.length() > UserConstants.PASSWORD_MAX_LENGTH)
        {
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, MessageUtils.message("user.password.not.match")));
            throw new UserPasswordNotMatchException();
        }
        // 用户名不在指定范围内 错误
        if (username.length() < UserConstants.USERNAME_MIN_LENGTH
                || username.length() > UserConstants.USERNAME_MAX_LENGTH)
        {
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, MessageUtils.message("user.password.not.match")));
            throw new UserPasswordNotMatchException();
        }
        // IP黑名单校验
        String blackStr = configService.selectConfigByKey("sys.login.blackIPList");
        if (IpUtils.isMatchedIp(blackStr, IpUtils.getIpAddr()))
        {
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, MessageUtils.message("login.blocked")));
            throw new BlackListException();
        }
    }

    /**
     * 记录登录信息
     *
     * @param userId 用户ID
     */
    public void recordLoginInfo(Long userId)
    {
        userService.updateLoginInfo(userId, IpUtils.getIpAddr(), DateUtils.getNowDate());
    }
}
