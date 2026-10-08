package com.fivetech.framework.notification;

import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fivetech.common.core.domain.entity.SysUser;

/**
 * 账号通知邮件服务：启用链接、密码已变更、账号停用、账号重新启用。
 *
 * <p>所有邮件都不包含密码。发信全程异步并吞掉异常——邮件失败绝不能让已经完成的
 * 账号操作回滚或让接口报错；发送结果写日志，启用邮件失败时管理员可在后台重发。</p>
 *
 * @author fivetech
 */
@Service
public class AccountMailService
{
    private static final Logger log = LoggerFactory.getLogger(AccountMailService.class);

    /**
     * 邮箱格式校验。刻意比 RFC 5322 严格：只放行日常可投递的地址。
     */
    private static final Pattern EMAIL_PATTERN = Pattern
            .compile("^[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]{1,64}@[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");

    private static final int MAX_EMAIL_LENGTH = 254;

    private final EmailNotificationService emailService;

    private final EmailProperties properties;

    private final ThreadPoolTaskExecutor executor;

    public AccountMailService(EmailNotificationService emailService, EmailProperties properties,
            @Qualifier("threadPoolTaskExecutor") ThreadPoolTaskExecutor executor)
    {
        this.emailService = emailService;
        this.properties = properties;
        this.executor = executor;
    }

    /**
     * 判断邮箱是否可投递。空、超长、格式非法一律返回 false。
     */
    public static boolean isDeliverable(String email)
    {
        if (!StringUtils.hasText(email))
        {
            return false;
        }
        String trimmed = email.trim();
        if (trimmed.length() > MAX_EMAIL_LENGTH)
        {
            return false;
        }
        if (trimmed.indexOf("..") >= 0 || trimmed.startsWith(".") || trimmed.indexOf(".@") >= 0)
        {
            return false;
        }
        return EMAIL_PATTERN.matcher(trimmed).matches();
    }

    /**
     * 能否发送启用邮件：邮件功能已开启、配置了前台地址。
     * 建号前调用，配置缺失时直接拒绝建号，避免建出一个永远收不到启用链接的账号。
     */
    public boolean isActivationEnabled()
    {
        return properties.getAccount().isActivationEnabled();
    }

    public boolean canSendActivation()
    {
        return properties.isEnabled() && properties.getAccount().isEnabled()
                && StringUtils.hasText(properties.getAccount().getPortalUrl());
    }

    /** 启用链接有效小时数，限制在设计允许的 24–168 小时 */
    public int getLinkExpireHours()
    {
        int hours = properties.getAccount().getLinkExpireHours();
        return Math.max(24, Math.min(168, hours));
    }

    /**
     * 发送启用邮件。
     *
     * @param user 用户
     * @param token 链接凭证原文（只出现在邮件里，不写日志）
     * @param expireTime 链接到期时间
     */
    public void sendActivation(SysUser user, String token, Date expireTime)
    {
        if (!canSendActivation())
        {
            log.warn("跳过启用邮件：邮件未启用或未配置 notification.email.account.portal-url，账号={}", userName(user));
            return;
        }
        String url = portalUrl() + "/activate?token=" + token;
        submit(user, "启用邮件", () -> AccountMailTemplates.activation(platform(), user.getNickName(),
                user.getUserName(), url, expireTime, zone()));
    }

    /**
     * 发送“密码已变更”通知。
     *
     * @param byAdmin true 管理员重设；false 本人修改
     * @param ip 本人修改时的来源 IP
     */
    public void sendPasswordChanged(SysUser user, boolean byAdmin, String ip)
    {
        Date now = new Date();
        submit(user, "密码变更通知", () -> AccountMailTemplates.passwordChanged(platform(), user.getNickName(),
                user.getUserName(), byAdmin, ip, now, zone()));
    }

    /** 发送“账号已停用”通知 */
    public void sendAccountDisabled(SysUser user)
    {
        Date now = new Date();
        submit(user, "停用通知", () -> AccountMailTemplates.accountDisabled(platform(), user.getNickName(),
                user.getUserName(), now, zone()));
    }

    /** 发送“账号已重新启用”通知 */
    public void sendAccountEnabled(SysUser user)
    {
        Date now = new Date();
        String loginUrl = StringUtils.hasText(portalUrl()) ? portalUrl() + "/login" : "";
        submit(user, "重新启用通知", () -> AccountMailTemplates.accountEnabled(platform(), user.getNickName(),
                user.getUserName(), now, zone(), loginUrl));
    }

    private interface MailBuilder
    {
        AccountMailTemplates.Mail build();
    }

    private void submit(SysUser user, String kind, MailBuilder builder)
    {
        if (user == null || !properties.isEnabled() || !properties.getAccount().isEnabled())
        {
            return;
        }
        if (!isDeliverable(user.getEmail()))
        {
            log.info("跳过{}：账号 {} 的邮箱为空或格式非法", kind, user.getUserName());
            return;
        }
        final String to = user.getEmail().trim();
        final String name = user.getUserName();
        final AccountMailTemplates.Mail mail;
        try
        {
            mail = builder.build();
        }
        catch (Exception e)
        {
            log.error("{}渲染失败：账号={}", kind, name, e);
            return;
        }
        try
        {
            executor.execute(() -> doSend(to, mail, kind, name));
        }
        catch (Exception e)
        {
            log.warn("{}入队失败，改为同步发送：账号={}", kind, name, e);
            doSend(to, mail, kind, name);
        }
    }

    private void doSend(String to, AccountMailTemplates.Mail mail, String kind, String userName)
    {
        try
        {
            emailService.send(Collections.singletonList(to), mail.getSubject(), mail.getHtml(), true);
            log.info("{}已发送：账号={}，收件人={}", kind, userName, mask(to));
        }
        catch (Exception e)
        {
            // 正文里可能含启用链接，不写入日志
            log.error("{}发送失败：账号={}，收件人={}，原因={}", kind, userName, mask(to), e.getMessage(), e);
        }
    }

    private String platform()
    {
        String name = properties.getAccount().getPlatformName();
        return StringUtils.hasText(name) ? name.trim() : "Yata";
    }

    private String portalUrl()
    {
        String url = properties.getAccount().getPortalUrl();
        if (!StringUtils.hasText(url))
        {
            return "";
        }
        url = url.trim();
        while (url.endsWith("/"))
        {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private ZoneId zone()
    {
        String tz = properties.getAccount().getTimeZone();
        if (StringUtils.hasText(tz))
        {
            try
            {
                return ZoneId.of(tz.trim());
            }
            catch (Exception e)
            {
                log.warn("notification.email.account.time-zone 配置无效：{}，改用服务器时区", tz);
            }
        }
        return ZoneId.systemDefault();
    }

    private static String userName(SysUser user)
    {
        return user == null ? null : user.getUserName();
    }

    /**
     * 日志脱敏：jackson@fivetech.co.jp -> ja*****@fivetech.co.jp
     */
    static String mask(String email)
    {
        int at = email.indexOf('@');
        if (at <= 2)
        {
            return "***" + (at >= 0 ? email.substring(at) : "");
        }
        return email.substring(0, 2) + "*****" + email.substring(at);
    }
}
