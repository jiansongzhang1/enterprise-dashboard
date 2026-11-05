package com.fivetech.configcenter.domain;

import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 配置中心的一条配置（config_center）：唯一键 = 配置编码 + 站点；站点为空串表示全站通用。
 *
 * @author fivetech
 */
public class ConfigItem
{
    private Long id;

    /** 配置编码，大写字母、数字、下划线，如 PROMO_CHANNELS */
    private String configCode;

    /** 站点编码；空串 = 全站通用（站点没有自己的配置时用它） */
    private String siteCode;

    /** 所属页面，如「推廣分析 · 預算與消耗」 */
    private String pageName;

    /** 含义 */
    private String description;

    /** 配置值（JSON） */
    private String configValue;

    private String createBy;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    private String updateBy;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getConfigCode() { return configCode; }
    public void setConfigCode(String configCode) { this.configCode = configCode; }
    public String getSiteCode() { return siteCode; }
    public void setSiteCode(String siteCode) { this.siteCode = siteCode; }
    public String getPageName() { return pageName; }
    public void setPageName(String pageName) { this.pageName = pageName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getConfigValue() { return configValue; }
    public void setConfigValue(String configValue) { this.configValue = configValue; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
