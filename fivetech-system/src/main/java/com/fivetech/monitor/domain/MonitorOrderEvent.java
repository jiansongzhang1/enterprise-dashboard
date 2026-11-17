package com.fivetech.monitor.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单告警事件（monitor_order_event，release_next ⑳）：外部判定服务判定一笔提款订单命中订单规则后，
 * 调 POST /monitor/rules/{ruleId}/alert 时带上订单号、金额、当日累计笔数，Yata 存一行。
 *
 * <p>只存命中那一刻由判定服务给出的数据；订单审核状态、用户信息不存，展示时实时查 UDS。
 * (site_code, rule_id, order_no) 唯一：同一笔订单重复上报不新增。</p>
 *
 * @author fivetech
 */
public class MonitorOrderEvent
{
    private Long eventId;

    private String siteCode;

    private Long ruleId;

    /** 提款订单号 */
    private String orderNo;

    /** 本笔金额 */
    private BigDecimal amount;

    /** 账号当日累计笔数（含本笔），只有当日累计规则有 */
    private Integer cumCount;

    /** 账号当日累计金额（含本笔），只有当日累计规则有，选填 */
    private BigDecimal cumAmount;

    /** 触发时间（收到上报的时间，统计时区） */
    private LocalDateTime triggerTime;

    /** 命中时规则的等级、时点、金额口径、币种快照 */
    private String level;

    private String triggerPoint;

    private String amountMode;

    private String currency;

    /** PENDING 发送中 / SENT 全部群已发送 / PARTIAL 部分群失败 / FAILED 全部失败 / LOG_ONLY 只记录 */
    private String notifyStatus;

    /** 只记录的原因：CAPPED 判定服务按每日上限不发 / NOTIFY_OFF 规则关闭了通知 */
    private String notifyReason;

    /** 每个群的发送结果 JSON：[{chatId, name, status: SENT/FAILED, messageId, error}]（release_next ⑳） */
    private String notifyChats;

    private LocalDateTime notifiedAt;

    /** pending 未处理 / done 已处理 */
    private String handleStatus;

    /** 处理说明（标记已处理时必填） */
    private String handleNote;

    /** 处理人：sys_user.user_id 的文本 */
    private String handleBy;

    /** 处理时间（统计时区） */
    private LocalDateTime handleTime;

    /** 上报的账号（API Key 绑定的账号或登录账号） */
    private String reportBy;

    private LocalDateTime createTime;

    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
    public String getSiteCode() { return siteCode; }
    public void setSiteCode(String siteCode) { this.siteCode = siteCode; }
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Integer getCumCount() { return cumCount; }
    public void setCumCount(Integer cumCount) { this.cumCount = cumCount; }
    public BigDecimal getCumAmount() { return cumAmount; }
    public void setCumAmount(BigDecimal cumAmount) { this.cumAmount = cumAmount; }
    public LocalDateTime getTriggerTime() { return triggerTime; }
    public void setTriggerTime(LocalDateTime triggerTime) { this.triggerTime = triggerTime; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getTriggerPoint() { return triggerPoint; }
    public void setTriggerPoint(String triggerPoint) { this.triggerPoint = triggerPoint; }
    public String getAmountMode() { return amountMode; }
    public void setAmountMode(String amountMode) { this.amountMode = amountMode; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getNotifyStatus() { return notifyStatus; }
    public void setNotifyStatus(String notifyStatus) { this.notifyStatus = notifyStatus; }
    public String getNotifyReason() { return notifyReason; }
    public void setNotifyReason(String notifyReason) { this.notifyReason = notifyReason; }
    public String getNotifyChats() { return notifyChats; }
    public void setNotifyChats(String notifyChats) { this.notifyChats = notifyChats; }
    public LocalDateTime getNotifiedAt() { return notifiedAt; }
    public void setNotifiedAt(LocalDateTime notifiedAt) { this.notifiedAt = notifiedAt; }
    public String getHandleStatus() { return handleStatus; }
    public void setHandleStatus(String handleStatus) { this.handleStatus = handleStatus; }
    public String getHandleNote() { return handleNote; }
    public void setHandleNote(String handleNote) { this.handleNote = handleNote; }
    public String getHandleBy() { return handleBy; }
    public void setHandleBy(String handleBy) { this.handleBy = handleBy; }
    public LocalDateTime getHandleTime() { return handleTime; }
    public void setHandleTime(LocalDateTime handleTime) { this.handleTime = handleTime; }
    public String getReportBy() { return reportBy; }
    public void setReportBy(String reportBy) { this.reportBy = reportBy; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
