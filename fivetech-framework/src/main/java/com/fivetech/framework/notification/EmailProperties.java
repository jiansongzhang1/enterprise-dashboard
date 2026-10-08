package com.fivetech.framework.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 邮件通知业务配置。
 */
@Component
@ConfigurationProperties(prefix = "notification.email")
public class EmailProperties
{
    private boolean enabled;

    private String from;

    public boolean isEnabled()
    {
        return enabled;
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    public String getFrom()
    {
        return from;
    }

    public void setFrom(String from)
    {
        this.from = from;
    }

    private Account account = new Account();

    public Account getAccount()
    {
        return account;
    }

    public void setAccount(Account account)
    {
        this.account = account;
    }

    /**
     * 账号通知邮件配置：启用链接、密码已变更、账号停用、账号重新启用。
     */
    public static class Account
    {
        /** 是否发送账号通知邮件 */
        private boolean enabled = true;

        /** 平台名称，用于邮件主题和正文 */
        private String platformName = "Yata";

        /**
         * 前台访问地址（含部署子路径，不带结尾斜杠），例如 https://dashboard.example.com。
         * 启用链接为 {portalUrl}/activate?token=…，登录地址为 {portalUrl}/login。
         * 留空时无法发送启用邮件。
         */
        private String portalUrl = "";

        /** 启用链接有效小时数，设计允许 24–168 */
        private int linkExpireHours = 72;

        /** 邮件中时间的显示时区（IANA 名称，如 Asia/Taipei）；留空使用服务器时区 */
        private String timeZone = "";

        /**
         * 新建账号是否走「邮件启用链接」流程（员工点链接自己设密码，需要配置 portalUrl）。
         * 关闭时（默认）：新建账号不发启用邮件、不要求 portalUrl，由管理员在用户列表里「重置密码」设置初始密码。
         * 其他账号通知邮件（密码变更、停用、重新启用）不受此开关影响，仍由 enabled 控制。
         */
        private boolean activationEnabled = false;

        public boolean isActivationEnabled()
        {
            return activationEnabled;
        }

        public void setActivationEnabled(boolean activationEnabled)
        {
            this.activationEnabled = activationEnabled;
        }

        public boolean isEnabled()
        {
            return enabled;
        }

        public void setEnabled(boolean enabled)
        {
            this.enabled = enabled;
        }

        public String getPlatformName()
        {
            return platformName;
        }

        public void setPlatformName(String platformName)
        {
            this.platformName = platformName;
        }

        public String getPortalUrl()
        {
            return portalUrl;
        }

        public void setPortalUrl(String portalUrl)
        {
            this.portalUrl = portalUrl;
        }

        public int getLinkExpireHours()
        {
            return linkExpireHours;
        }

        public void setLinkExpireHours(int linkExpireHours)
        {
            this.linkExpireHours = linkExpireHours;
        }

        public String getTimeZone()
        {
            return timeZone;
        }

        public void setTimeZone(String timeZone)
        {
            this.timeZone = timeZone;
        }
    }
}
