package com.fivetech.framework.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * WhatsApp Cloud API 配置。
 */
@Component
@ConfigurationProperties(prefix = "notification.whatsapp")
public class WhatsAppProperties
{
    private boolean enabled;

    private String accessToken;

    private String phoneNumberId;

    private String graphApiVersion = "v23.0";

    private int timeoutSeconds = 10;

    public boolean isEnabled()
    {
        return enabled;
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    public String getAccessToken()
    {
        return accessToken;
    }

    public void setAccessToken(String accessToken)
    {
        this.accessToken = accessToken;
    }

    public String getPhoneNumberId()
    {
        return phoneNumberId;
    }

    public void setPhoneNumberId(String phoneNumberId)
    {
        this.phoneNumberId = phoneNumberId;
    }

    public String getGraphApiVersion()
    {
        return graphApiVersion;
    }

    public void setGraphApiVersion(String graphApiVersion)
    {
        this.graphApiVersion = graphApiVersion;
    }

    public int getTimeoutSeconds()
    {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds)
    {
        this.timeoutSeconds = timeoutSeconds;
    }
}
