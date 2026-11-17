package com.fivetech.dashboard.subscription;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订阅（dashboard_subscription）：按发送频率（每日 / 每週 / 每 N 小時，站点时区整点）把运营总览某个视图的指标整理成简报卡片，
 * 发到 Lark 群（2026-11-13 起只发群；旧数据里的个人在上线迁移时去掉）。
 *
 * <ul>
 *   <li>{@code metrics}：勾选的指标编码，逗号分隔；发送时按站点运营总览的顺序输出，站点已隐藏或下线的指标跳过；</li>
 *   <li>{@code receivers}：接收者 JSON 数组 {@code [{"type":"chat","id":"oc_…","name":"群名"},{"type":"user","id":"12","name":"邮箱"}]}，
 *       个人发送时按用户当前邮箱发（Lark receive_id_type=email）；</li>
 *   <li>{@code lastSlot}：最近一次定时发送的发送时刻（yyyy-MM-dd HH:mm，站点时区），同一时刻只发一次。</li>
 * </ul>
 *
 * @author fivetech
 */
public class DashboardSubscription implements Serializable
{
    private static final long serialVersionUID = 1L;

    private Long id;

    private String siteCode;

    private String name;

    private String description;

    private String rangeCode;

    private String metrics;

    private Integer sendHour;

    private String receivers;

    private Boolean enabled;

    /** 卡片带 AI 摘要 */
    private Boolean aiEnabled;

    /** AI 模型 id；为空用 copilot.default-model */
    private String aiModel;

    /** 卡片带运营总览截图 */
    private Boolean snapshotEnabled;

    /** 截图的筛选条件 JSON（前端 localStorage overview-filters 的值，如 {"range":"custom","gran":"day","from":"…","to":"…","cmp":"previous-period"}，可用 {from} {to}）；为空按统计范围生成 */
    private String snapshotFilters;

    /** 发送频率：DAY / WEEK / HOURS（{@link SubscriptionSchedule}）；send_hour 为每日、每週的发送整点，每 N 小時的「從」 */
    private String freqType;

    /** 每週：週几（1=週一 … 7=週日） */
    private Integer freqDow;

    /** 每 N 小時：N（1–12） */
    private Integer freqEvery;

    /** 每 N 小時：「到」的整点（含） */
    private Integer freqTo;

    /** 视图（发起订阅时运营总览的选择，建立后不可改）：粒度 HOUR / DAY / WEEK */
    private String viewGran;

    /** 视图：对比 PREV（上一周期）/ CUSTOM（自訂） */
    private String viewCmp;

    /** 视图：固定日期（range_code = CUSTOM）的起止 */
    private java.time.LocalDate viewFrom;

    private java.time.LocalDate viewTo;

    /** 视图：自訂对比的起止 */
    private java.time.LocalDate viewCmpFrom;

    private java.time.LocalDate viewCmpTo;

    /** 来源页面，本期只有 ov（运营总览） */
    private String sourcePage;

    /** 负责人（sys_user.user_id）；默认建立人，可转交、管理员可接管 */
    private Long ownerUserId;

    private String lastSlot;

    private LocalDateTime lastRunAt;

    /** SUCCESS / PARTIAL / FAILED */
    private String lastStatus;

    private String lastError;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;

    public Long getId() { return id; }

    public void setId(Long id) { this.id = id; }

    public String getSiteCode() { return siteCode; }

    public void setSiteCode(String siteCode) { this.siteCode = siteCode; }

    public String getName() { return name; }

    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }

    public void setDescription(String description) { this.description = description; }

    public String getRangeCode() { return rangeCode; }

    public void setRangeCode(String rangeCode) { this.rangeCode = rangeCode; }

    public String getMetrics() { return metrics; }

    public void setMetrics(String metrics) { this.metrics = metrics; }

    public Integer getSendHour() { return sendHour; }

    public void setSendHour(Integer sendHour) { this.sendHour = sendHour; }

    public String getReceivers() { return receivers; }

    public void setReceivers(String receivers) { this.receivers = receivers; }

    public Boolean getEnabled() { return enabled; }

    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public boolean isOn() { return Boolean.TRUE.equals(enabled); }
    public Boolean getAiEnabled() { return aiEnabled; }
    public void setAiEnabled(Boolean aiEnabled) { this.aiEnabled = aiEnabled; }
    public boolean isAiOn() { return Boolean.TRUE.equals(aiEnabled); }
    public String getAiModel() { return aiModel; }
    public void setAiModel(String aiModel) { this.aiModel = aiModel; }
    public Boolean getSnapshotEnabled() { return snapshotEnabled; }
    public void setSnapshotEnabled(Boolean snapshotEnabled) { this.snapshotEnabled = snapshotEnabled; }
    public boolean isSnapshotOn() { return Boolean.TRUE.equals(snapshotEnabled); }
    public String getSnapshotFilters() { return snapshotFilters; }
    public void setSnapshotFilters(String snapshotFilters) { this.snapshotFilters = snapshotFilters; }

    public String getFreqType() { return freqType; }

    public void setFreqType(String freqType) { this.freqType = freqType; }

    public Integer getFreqDow() { return freqDow; }

    public void setFreqDow(Integer freqDow) { this.freqDow = freqDow; }

    public Integer getFreqEvery() { return freqEvery; }

    public void setFreqEvery(Integer freqEvery) { this.freqEvery = freqEvery; }

    public Integer getFreqTo() { return freqTo; }

    public void setFreqTo(Integer freqTo) { this.freqTo = freqTo; }

    public String getViewGran() { return viewGran; }

    public void setViewGran(String viewGran) { this.viewGran = viewGran; }

    public String getViewCmp() { return viewCmp; }

    public void setViewCmp(String viewCmp) { this.viewCmp = viewCmp; }

    public java.time.LocalDate getViewFrom() { return viewFrom; }

    public void setViewFrom(java.time.LocalDate viewFrom) { this.viewFrom = viewFrom; }

    public java.time.LocalDate getViewTo() { return viewTo; }

    public void setViewTo(java.time.LocalDate viewTo) { this.viewTo = viewTo; }

    public java.time.LocalDate getViewCmpFrom() { return viewCmpFrom; }

    public void setViewCmpFrom(java.time.LocalDate viewCmpFrom) { this.viewCmpFrom = viewCmpFrom; }

    public java.time.LocalDate getViewCmpTo() { return viewCmpTo; }

    public void setViewCmpTo(java.time.LocalDate viewCmpTo) { this.viewCmpTo = viewCmpTo; }

    public boolean isCustomCompare() { return "CUSTOM".equals(viewCmp) && viewCmpFrom != null && viewCmpTo != null; }

    public String getSourcePage() { return sourcePage; }

    public void setSourcePage(String sourcePage) { this.sourcePage = sourcePage; }

    public Long getOwnerUserId() { return ownerUserId; }

    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }

    public String getLastSlot() { return lastSlot; }

    public void setLastSlot(String lastSlot) { this.lastSlot = lastSlot; }

    public LocalDateTime getLastRunAt() { return lastRunAt; }

    public void setLastRunAt(LocalDateTime lastRunAt) { this.lastRunAt = lastRunAt; }

    public String getLastStatus() { return lastStatus; }

    public void setLastStatus(String lastStatus) { this.lastStatus = lastStatus; }

    public String getLastError() { return lastError; }

    public void setLastError(String lastError) { this.lastError = lastError; }

    public String getCreateBy() { return createBy; }

    public void setCreateBy(String createBy) { this.createBy = createBy; }

    public LocalDateTime getCreateTime() { return createTime; }

    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public String getUpdateBy() { return updateBy; }

    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }

    public LocalDateTime getUpdateTime() { return updateTime; }

    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
