package com.fivetech.dashboard.subscription;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 发送历史的一行：一次发送 × 一个接收群（dashboard_subscription_log ⋈ dashboard_subscription_run ⋈ dashboard_subscription）。
 *
 * @author fivetech
 */
public class SubscriptionHistoryRow implements Serializable
{
    private static final long serialVersionUID = 1L;

    private Long logId;

    private Long runId;

    private Long subscriptionId;

    private String subscriptionName;

    private Boolean deleted;

    private String triggerType;

    private String operator;

    private LocalDateTime planAt;

    private LocalDateTime sentAt;

    private String period;

    private String comparePeriod;

    private String receiverId;

    private String receiverName;

    private String result;

    private String reasonCode;

    private Integer attempts;

    public Long getLogId() { return logId; }

    public void setLogId(Long logId) { this.logId = logId; }

    public Long getRunId() { return runId; }

    public void setRunId(Long runId) { this.runId = runId; }

    public Long getSubscriptionId() { return subscriptionId; }

    public void setSubscriptionId(Long subscriptionId) { this.subscriptionId = subscriptionId; }

    public String getSubscriptionName() { return subscriptionName; }

    public void setSubscriptionName(String subscriptionName) { this.subscriptionName = subscriptionName; }

    public Boolean getDeleted() { return deleted; }

    public void setDeleted(Boolean deleted) { this.deleted = deleted; }

    public String getTriggerType() { return triggerType; }

    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }

    public String getOperator() { return operator; }

    public void setOperator(String operator) { this.operator = operator; }

    public LocalDateTime getPlanAt() { return planAt; }

    public void setPlanAt(LocalDateTime planAt) { this.planAt = planAt; }

    public LocalDateTime getSentAt() { return sentAt; }

    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }

    public String getPeriod() { return period; }

    public void setPeriod(String period) { this.period = period; }

    public String getComparePeriod() { return comparePeriod; }

    public void setComparePeriod(String comparePeriod) { this.comparePeriod = comparePeriod; }

    public String getReceiverId() { return receiverId; }

    public void setReceiverId(String receiverId) { this.receiverId = receiverId; }

    public String getReceiverName() { return receiverName; }

    public void setReceiverName(String receiverName) { this.receiverName = receiverName; }

    public String getResult() { return result; }

    public void setResult(String result) { this.result = result; }

    public String getReasonCode() { return reasonCode; }

    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }

    public Integer getAttempts() { return attempts; }

    public void setAttempts(Integer attempts) { this.attempts = attempts; }
}
