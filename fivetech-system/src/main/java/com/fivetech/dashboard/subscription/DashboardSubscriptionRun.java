package com.fivetech.dashboard.subscription;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 推送批次（dashboard_subscription_run）：一次定时发送或立即发送一行；每个接收群的结果在 dashboard_subscription_log（run_id）。
 * 测试发送不记录。
 *
 * <p>plan_at / sent_at 是站点时区的墙上时间（plan_at = 排程时刻，立即发送为点击时的整点；sent_at = 实际发出时间，
 * 已跳过的写排程时刻）；started_at / finished_at 是服务器时间，用于把中断的 RUNNING 批次改为失败。</p>
 *
 * @author fivetech
 */
public class DashboardSubscriptionRun implements Serializable
{
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long subscriptionId;

    private String siteCode;

    /** SCHEDULE / MANUAL */
    private String triggerType;

    private LocalDateTime planAt;

    private LocalDateTime sentAt;

    /** 本次统计周期、对比期（文字） */
    private String period;

    private String comparePeriod;

    /** RUNNING / OK / PART / FAIL / SKIP */
    private String result;

    /** 整次失败时的原因代码（data / shot），逐群的在 log 表 */
    private String reasonCode;

    private String error;

    private Integer receiverTotal;

    private Integer sent;

    private Integer failed;

    /** AI 摘要：OFF / OK / FAILED */
    private String aiStatus;

    private String aiError;

    /** 截图：OFF / OK / FAILED */
    private String snapshotStatus;

    private String snapshotError;

    /** 手动发送的操作人；定时为 system */
    private String operator;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    public Long getId() { return id; }

    public void setId(Long id) { this.id = id; }

    public Long getSubscriptionId() { return subscriptionId; }

    public void setSubscriptionId(Long subscriptionId) { this.subscriptionId = subscriptionId; }

    public String getSiteCode() { return siteCode; }

    public void setSiteCode(String siteCode) { this.siteCode = siteCode; }

    public String getTriggerType() { return triggerType; }

    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }

    public LocalDateTime getPlanAt() { return planAt; }

    public void setPlanAt(LocalDateTime planAt) { this.planAt = planAt; }

    public LocalDateTime getSentAt() { return sentAt; }

    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }

    public String getPeriod() { return period; }

    public void setPeriod(String period) { this.period = period; }

    public String getComparePeriod() { return comparePeriod; }

    public void setComparePeriod(String comparePeriod) { this.comparePeriod = comparePeriod; }

    public String getResult() { return result; }

    public void setResult(String result) { this.result = result; }

    public String getReasonCode() { return reasonCode; }

    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }

    public String getError() { return error; }

    public void setError(String error) { this.error = error; }

    public Integer getReceiverTotal() { return receiverTotal; }

    public void setReceiverTotal(Integer receiverTotal) { this.receiverTotal = receiverTotal; }

    public Integer getSent() { return sent; }

    public void setSent(Integer sent) { this.sent = sent; }

    public Integer getFailed() { return failed; }

    public void setFailed(Integer failed) { this.failed = failed; }

    public String getAiStatus() { return aiStatus; }

    public void setAiStatus(String aiStatus) { this.aiStatus = aiStatus; }

    public String getAiError() { return aiError; }

    public void setAiError(String aiError) { this.aiError = aiError; }

    public String getSnapshotStatus() { return snapshotStatus; }

    public void setSnapshotStatus(String snapshotStatus) { this.snapshotStatus = snapshotStatus; }

    public String getSnapshotError() { return snapshotError; }

    public void setSnapshotError(String snapshotError) { this.snapshotError = snapshotError; }

    public String getOperator() { return operator; }

    public void setOperator(String operator) { this.operator = operator; }

    public LocalDateTime getStartedAt() { return startedAt; }

    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getFinishedAt() { return finishedAt; }

    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
}
