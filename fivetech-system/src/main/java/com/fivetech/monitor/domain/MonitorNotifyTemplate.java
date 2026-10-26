package com.fivetech.monitor.domain;

import java.time.LocalDateTime;

/**
 * monitor_notify_template 的一行：告警通知模板。
 *
 * @author fivetech
 */
public class MonitorNotifyTemplate
{
    /** 新触发的严重（一触发就严重，或越过严重线由注意变严重）：同一事件只发一次 */
    public static final String ALERT_NEW = "ALERT_NEW";
    /** 注意持续未恢复，经注意升级升为严重：同一事件只发一次（FY-30 R2，不重复提醒） */
    public static final String ALERT_ESCALATED = "ALERT_ESCALATED";
    public static final java.util.List<String> TYPES = java.util.List.of(ALERT_NEW, ALERT_ESCALATED);

    /**
     * 内置默认模板（FY-30「四、页面与交互」格式）：启动时写入全站默认模板，也是取不到模板时的兜底文案。
     * 只在代码里维护这一份，SQL 脚本里不写正文（避免 SQL 客户端把 {{…}} 当成要填的参数）。
     * 两种类型内容相同，升级的「，已升為嚴重」由 {{judgeText}} 自带。
     */
    public static final String DEFAULT_CONTENT = "【{{site}}】Yata 告警 · 嚴重\n"
            + "指標：{{metric}} {{currentValue}}\n"
            + "判定：{{judgeText}} · {{ruleCode}}\n"
            + "時間：{{triggerTime}}\n"
            + "負責人：{{owner}}";

    /** 改版前（2026-10-22 首版）预置的默认正文 */
    public static final String LEGACY_CONTENT = "【{{site}}】Yata 告警 · 嚴重\n{{metric}}：{{reason}}\n"
            + "起點 {{start}} · 已持續 {{durationHours}} 小時 · 規則 {{ruleCode}} · 負責人 {{owner}}\n"
            + "{{#eventUrl}}查看：{{eventUrl}}{{/eventUrl}}";

    /** 带「查看監控詳情」深链接的版本（2026-10-23 第一稿；一期不做深链接，已去掉这一行） */
    public static final String LEGACY_CONTENT_WITH_DETAIL = "【{{site}}】Yata 告警 · 嚴重\n"
            + "指標：{{metric}} {{currentValue}}\n"
            + "判定：{{judgeText}} · {{ruleCode}}\n"
            + "時間：{{triggerTime}}\n"
            + "負責人：{{owner}}\n"
            + "{{#detailUrl}}[查看監控詳情]({{detailUrl}}){{/detailUrl}}";

    /** 启动时发现全站默认还是这些旧的预置内容（没人改过），就换成 {@link #DEFAULT_CONTENT} */
    public static final java.util.List<String> LEGACY_CONTENTS = java.util.List.of(LEGACY_CONTENT, LEGACY_CONTENT_WITH_DETAIL);

    private Long templateId;
    /** 为空 = 全站默认 */
    private String siteCode;
    private String notifyType;
    private String platform;
    private String msgFormat;
    private String content;
    private Boolean enabled;
    private Integer version;
    private String remark;
    private String createBy;
    private LocalDateTime createTime;
    private String updateBy;
    private LocalDateTime updateTime;

    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getSiteCode() { return siteCode; }
    public void setSiteCode(String siteCode) { this.siteCode = siteCode; }
    public String getNotifyType() { return notifyType; }
    public void setNotifyType(String notifyType) { this.notifyType = notifyType; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getMsgFormat() { return msgFormat; }
    public void setMsgFormat(String msgFormat) { this.msgFormat = msgFormat; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
