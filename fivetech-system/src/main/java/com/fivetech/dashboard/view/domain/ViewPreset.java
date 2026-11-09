package com.fivetech.dashboard.view.domain;

/**
 * 预置组合（dashboard_view_preset），如表格列的「標準」「投放日報」。
 *
 * @author fivetech
 */
public class ViewPreset
{
    private String slotCode;
    private String presetCode;
    private String name;
    private String tip;
    /** 按顺序的项编码，逗号分隔 */
    private String items;
    /** 是否就是该配置位的全站默认；为 true 时优先于 slot_item.default_sort */
    private Boolean isDefault;
    private Integer sortNo;

    public ViewPreset()
    {
    }

    public ViewPreset(String slotCode, String presetCode, String name, String items, boolean isDefault, int sortNo)
    {
        this.slotCode = slotCode;
        this.presetCode = presetCode;
        this.name = name;
        this.items = items;
        this.isDefault = isDefault;
        this.sortNo = sortNo;
    }

    public boolean defaultPreset() { return Boolean.TRUE.equals(isDefault); }

    public String getSlotCode() { return slotCode; }
    public void setSlotCode(String slotCode) { this.slotCode = slotCode; }
    public String getPresetCode() { return presetCode; }
    public void setPresetCode(String presetCode) { this.presetCode = presetCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTip() { return tip; }
    public void setTip(String tip) { this.tip = tip; }
    public String getItems() { return items; }
    public void setItems(String items) { this.items = items; }
    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }
    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }
}
