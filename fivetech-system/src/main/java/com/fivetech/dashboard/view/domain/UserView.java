package com.fivetech.dashboard.view.domain;

import java.util.Date;

/**
 * 个人视图（dashboard_user_view）：某人在某站点、某配置位上的选择。用户 + 站点 + 配置位唯一。
 *
 * @author fivetech
 */
public class UserView
{
    public static final String DEFAULT = "DEFAULT";
    public static final String PRESET = "PRESET";
    public static final String CUSTOM = "CUSTOM";

    private Long id;
    private Long userId;
    private String siteCode;
    private String slotCode;
    /** DEFAULT 跟随默认（只存了小状态）/ CUSTOM 自己挑的 / PRESET 跟随某个预置组合 */
    private String mode;
    private String presetCode;
    /** mode = CUSTOM 时的项编码 JSON 数组，按显示顺序 */
    private String items;
    /** 最近一次自定义（JSON 数组） */
    private String lastCustom;
    /** 小状态 JSON 对象，服务端不解读 */
    private String attrs;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getSiteCode() { return siteCode; }
    public void setSiteCode(String siteCode) { this.siteCode = siteCode; }
    public String getSlotCode() { return slotCode; }
    public void setSlotCode(String slotCode) { this.slotCode = slotCode; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getPresetCode() { return presetCode; }
    public void setPresetCode(String presetCode) { this.presetCode = presetCode; }
    public String getItems() { return items; }
    public void setItems(String items) { this.items = items; }
    public String getLastCustom() { return lastCustom; }
    public void setLastCustom(String lastCustom) { this.lastCustom = lastCustom; }
    public String getAttrs() { return attrs; }
    public void setAttrs(String attrs) { this.attrs = attrs; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
