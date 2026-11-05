package com.fivetech.dashboard.subscription;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订阅发送记录（dashboard_subscription_log）：一次发送 × 一个接收者一行。
 *
 * @author fivetech
 */
public class DashboardSubscriptionLog implements Serializable
{
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long subscriptionId;

    private String siteCode;

    /** SCHEDULE 定时 / MANUAL 立即发送 / TEST 测试发送（发给操作人自己） */
    private String triggerType;

    /** 统计周期文字，如 10-31 00:00 – 11-01 00:00 */
    private String period;

    /** chat / user */
    private String receiverType;

    private String receiverId;

    private String receiverName;

    /** SUCCESS / FAILED */
    private String status;

    private String error;

    private String messageId;

    private String operator;

    private LocalDateTime createTime;

    public Long getId() { return id; }

    public void setId(Long id) { this.id = id; }

    public Long getSubscriptionId() { return subscriptionId; }

    public void setSubscriptionId(Long subscriptionId) { this.subscriptionId = subscriptionId; }

    public String getSiteCode() { return siteCode; }

    public void setSiteCode(String siteCode) { this.siteCode = siteCode; }

    public String getTriggerType() { return triggerType; }

    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }

    public String getPeriod() { return period; }

    public void setPeriod(String period) { this.period = period; }

    public String getReceiverType() { return receiverType; }

    public void setReceiverType(String receiverType) { this.receiverType = receiverType; }

    public String getReceiverId() { return receiverId; }

    public void setReceiverId(String receiverId) { this.receiverId = receiverId; }

    public String getReceiverName() { return receiverName; }

    public void setReceiverName(String receiverName) { this.receiverName = receiverName; }

    public String getStatus() { return status; }

    public void setStatus(String status) { this.status = status; }

    public String getError() { return error; }

    public void setError(String error) { this.error = error; }

    public String getMessageId() { return messageId; }

    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getOperator() { return operator; }

    public void setOperator(String operator) { this.operator = operator; }

    public LocalDateTime getCreateTime() { return createTime; }

    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
