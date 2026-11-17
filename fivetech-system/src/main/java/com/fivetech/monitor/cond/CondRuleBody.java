package com.fivetech.monitor.cond;

import java.util.List;
import java.util.Map;

/**
 * 新建 / 编辑条件规则、触发预览的请求体（设计文档 7.3.2）。enabled 只在新建时生效；预览只用 matchMode、window、durationCount、level、conditions。
 *
 * @author fivetech
 */
public class CondRuleBody
{
    private String name;

    private String description;

    private Long ownerUserId;

    private String matchMode;

    private String window;

    private Integer durationCount;

    private String level;

    private Boolean notifyEnabled;

    private List<String> notifyChatIds;

    private Integer muteHours;

    private Boolean enabled;

    /** 每项：field、scope（维度 → 值）、how、op、unit、threshold（数字或 [下限, 上限]）、minSample */
    private List<Map<String, Object>> conditions;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getMatchMode() { return matchMode; }
    public void setMatchMode(String matchMode) { this.matchMode = matchMode; }
    public String getWindow() { return window; }
    public void setWindow(String window) { this.window = window; }
    public Integer getDurationCount() { return durationCount; }
    public void setDurationCount(Integer durationCount) { this.durationCount = durationCount; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public Boolean getNotifyEnabled() { return notifyEnabled; }
    public void setNotifyEnabled(Boolean notifyEnabled) { this.notifyEnabled = notifyEnabled; }
    public List<String> getNotifyChatIds() { return notifyChatIds; }
    public void setNotifyChatIds(List<String> notifyChatIds) { this.notifyChatIds = notifyChatIds; }
    public Integer getMuteHours() { return muteHours; }
    public void setMuteHours(Integer muteHours) { this.muteHours = muteHours; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public List<Map<String, Object>> getConditions() { return conditions; }
    public void setConditions(List<Map<String, Object>> conditions) { this.conditions = conditions; }
}
