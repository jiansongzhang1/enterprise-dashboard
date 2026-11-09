package com.fivetech.dashboard.subscription;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订阅（dashboard_subscription）：每天在站点时区的某个整点，把运营总览某个范围的指标整理成简报卡片，发到 Lark 群或个人。
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

    /** 截图的查询条件（运营总览页地址 ? 后面的部分，可用 {from} {to} {site}）；为空按统计范围生成 */
    private String snapshotQuery;

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
    public String getSnapshotQuery() { return snapshotQuery; }
    public void setSnapshotQuery(String snapshotQuery) { this.snapshotQuery = snapshotQuery; }

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
