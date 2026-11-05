package com.fivetech.dashboard.subscription;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 订阅简报配置（application.yml 的 {@code dashboard.subscription.*}）。
 *
 * @author fivetech
 */
@Component
@ConfigurationProperties(prefix = "dashboard.subscription")
public class SubscriptionProperties
{
    /** 定时发送总开关；关闭时不注册定时任务（管理后台仍可增删改、立即发送） */
    private boolean enabled = true;

    /** 检查到期订阅的 cron（服务器时区无关：到期按各站点时区判断） */
    private String cron = "0 */5 * * * *";

    /** 整点后再等多少分钟才发：给 UDS 落上一小时的数据留时间 */
    private int sendDelayMinutes = 10;

    /** 发送时刻过了多久还补发（服务重启、UDS 暂时不可用）；超过就跳过这一次，等下一天 */
    private int graceHours = 3;

    /** 卡片底部「在 Yata 中查看」的地址（运营总览页）；为空不显示按钮 */
    private String overviewUrl = "";

    /** 一个订阅最多多少个接收者 */
    private int maxReceivers = 20;

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getCron() { return cron; }

    public void setCron(String cron) { this.cron = cron; }

    public int getSendDelayMinutes() { return sendDelayMinutes; }

    public void setSendDelayMinutes(int sendDelayMinutes) { this.sendDelayMinutes = sendDelayMinutes; }

    public int getGraceHours() { return graceHours; }

    public void setGraceHours(int graceHours) { this.graceHours = graceHours; }

    public String getOverviewUrl() { return overviewUrl; }

    public void setOverviewUrl(String overviewUrl) { this.overviewUrl = overviewUrl; }

    public int getMaxReceivers() { return maxReceivers; }

    public void setMaxReceivers(int maxReceivers) { this.maxReceivers = maxReceivers; }
}
