package com.fivetech.framework.web.service;

import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import com.fivetech.common.constant.Constants;
import com.fivetech.common.constant.GaConstants;
import com.fivetech.common.constant.UserConstants;
import com.fivetech.common.core.domain.entity.SysUser;
import com.fivetech.common.core.domain.model.LoginUser;
import com.fivetech.common.core.domain.model.GaBindInfo;
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
    private TokenService tokenService;

    @Resource
    private AuthenticationManager authenticationManager;

    @Autowired
    private ISysUserService userService;

    @Autowired
    private ISysConfigService configService;

    @Autowired
    private FiveTechConfig fivetechConfig;

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
        bindInfo.setAccount(user.getUserName());
        bindInfo.setIssuer(fivetechConfig.getName());
        bindInfo.setOtpauthUrl(GoogleAuthenticatorUtils.buildOtpAuthUrl(fivetechConfig.getName(), user.getUserName(), user.getGaSecret()));
        return bindInfo;
    }

    /**
     * 确认绑定
     *
     * @param username 用户名
     * @param password 密码
     * @param code 验证码
     */
    public void confirmBind(String username, String password, String code)
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
