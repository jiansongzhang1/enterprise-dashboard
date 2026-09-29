package com.fivetech.framework.web.service;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import com.fivetech.common.core.domain.entity.SysUser;
import com.fivetech.common.enums.UserStatus;
import com.fivetech.common.utils.PasswordPolicy;
import com.fivetech.common.utils.SecurityUtils;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.framework.notification.AccountMailService;
import com.fivetech.system.domain.SysAccountLink;
import com.fivetech.system.service.ISysAccountLinkService;
import com.fivetech.system.service.ISysAccountLinkService.IssuedLink;
import com.fivetech.system.service.ISysUserService;

/**
 * 账号启用：管理员建号后发送一次性链接，员工打开链接自己设置密码。
 *
 * <p>链接只有在最后提交成功时才作废，这样企业邮件网关预先打开链接扫描时不会把它用掉。
 * 对外返回的错误码与设计文档第 9 节一致。</p>
 *
 * @author fivetech
 */
@Service
public class SysAccountActivationService
{
    private static final Logger log = LoggerFactory.getLogger(SysAccountActivationService.class);

    public static final String LINK_INVALID = "LINK_INVALID";

    public static final String PASSWORD_POLICY = "PASSWORD_POLICY";

    public static final String PASSWORD_MISMATCH = "PASSWORD_MISMATCH";

    static final String MSG_LINK_INVALID = "此連結已失效（已過期、已使用或已重新寄送），請聯繫管理員重新寄送。";

    static final String MSG_PASSWORD_MISMATCH = "兩次輸入的密碼不一致。";

    @Autowired
    private ISysAccountLinkService linkService;

    @Autowired
    private ISysUserService userService;

    @Autowired
    private AccountMailService accountMailService;

    @Autowired
    private SysPasswordService passwordService;

    /**
     * 生成新的启用链接并发送邮件；该账号之前未使用的链接立即作废。
     *
     * @param user 待启用账号（需含 userId、userName、nickName、email）
     * @param operator 操作人
     */
    public void issueActivation(SysUser user, String operator)
    {
        IssuedLink link = linkService.issue(user.getUserId(), SysAccountLink.TYPE_ACTIVATE,
                accountMailService.getLinkExpireHours(), operator);
        accountMailService.sendActivation(user, link.getToken(), link.getExpireTime());
    }

    /**
     * 作废账号所有未使用的链接（停用账号时调用）。
     */
    public void revokeLinks(Long userId)
    {
        linkService.revokeAll(userId);
    }

    /**
     * 打开链接时校验，不改变链接状态。
     *
     * @return valid=true 时带 userName、expireTime；否则带 errorCode、msg
     */
    public Map<String, Object> inspect(String token)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        SysAccountLink link = linkService.findUsable(token, SysAccountLink.TYPE_ACTIVATE);
        SysUser user = link == null ? null : loadActivatableUser(link.getUserId());
        if (user == null)
        {
            result.put("valid", false);
            result.put("errorCode", LINK_INVALID);
            result.put("msg", MSG_LINK_INVALID);
            return result;
        }
        result.put("valid", true);
        result.put("userName", user.getUserName());
        result.put("expireTime", link.getExpireTime());
        return result;
    }

    /**
     * 设置密码并启用账号。成功后不自动登录：员工随后在登录页绑定验证器。
     *
     * @return ok=true 表示成功；否则带 errorCode、msg
     */
    @Transactional
    public Map<String, Object> complete(String token, String password, String confirmPassword)
    {
        SysAccountLink link = linkService.findUsable(token, SysAccountLink.TYPE_ACTIVATE);
        SysUser user = link == null ? null : loadActivatableUser(link.getUserId());
        if (user == null)
        {
            return fail(LINK_INVALID, MSG_LINK_INVALID);
        }
        if (password == null || !password.equals(confirmPassword))
        {
            return fail(PASSWORD_MISMATCH, MSG_PASSWORD_MISMATCH);
        }
        String policyError = PasswordPolicy.check(user.getUserName(), password);
        if (policyError != null)
        {
            return fail(PASSWORD_POLICY, policyError);
        }

        // 先占用链接再改密码：两次并发提交只有一次能占用成功
        if (!linkService.markUsed(link.getLinkId())
                || userService.activateUser(user.getUserId(), SecurityUtils.encryptPassword(password)) != 1)
        {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return fail(LINK_INVALID, MSG_LINK_INVALID);
        }
        linkService.revokeAll(user.getUserId());
        passwordService.clearLoginRecordCache(user.getUserName());
        log.info("账号已通过邮件链接启用：{}", user.getUserName());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("userName", user.getUserName());
        return result;
    }

    /** 账号仍处于待启用、未删除、未停用时才允许使用启用链接 */
    private SysUser loadActivatableUser(Long userId)
    {
        SysUser user = userService.selectUserById(userId);
        if (user == null
                || UserStatus.DELETED.getCode().equals(user.getDelFlag())
                || UserStatus.DISABLE.getCode().equals(user.getStatus())
                || user.getActivateTime() != null
                || StringUtils.isEmpty(user.getUserName()))
        {
            return null;
        }
        return user;
    }

    private static Map<String, Object> fail(String code, String msg)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("errorCode", code);
        result.put("msg", msg);
        return result;
    }

}
