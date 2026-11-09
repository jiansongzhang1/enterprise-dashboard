package com.fivetech.dashboard.view.domain;

/**
 * 配置位的可选项与全站默认（dashboard_view_slot_item）。
 *
 * @author fivetech
 */
public class ViewSlotItem
{
    private String slotCode;
    private String itemCode;
    /** 显示名；为空取项来源（如指标字典）的名称 */
    private String itemName;
    private String groupCode;
    private String groupName;
    private Integer sortNo;
    /** 全站默认里的顺序；为空 = 默认不选 */
    private Integer defaultSort;
    /** 0 启用 / 1 停用 */
    private String status;

    public ViewSlotItem()
    {
    }

    public ViewSlotItem(String slotCode, String itemCode, String itemName, String groupCode, String groupName, Integer sortNo,
        Integer defaultSort)
    {
        this.slotCode = slotCode;
        this.itemCode = itemCode;
        this.itemName = itemName;
        this.groupCode = groupCode;
        this.groupName = groupName;
        this.sortNo = sortNo;
        this.defaultSort = defaultSort;
        this.status = "0";
    }

    public boolean isEnabled() { return "0".equals(status); }

    public String getSlotCode() { return slotCode; }
    public void setSlotCode(String slotCode) { this.slotCode = slotCode; }
    public String getItemCode() { return itemCode; }
    public void setItemCode(String itemCode) { this.itemCode = itemCode; }
    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }
    public String getGroupCode() { return groupCode; }
    public void setGroupCode(String groupCode) { this.groupCode = groupCode; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }
    public Integer getDefaultSort() { return defaultSort; }
    public void setDefaultSort(Integer defaultSort) { this.defaultSort = defaultSort; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
