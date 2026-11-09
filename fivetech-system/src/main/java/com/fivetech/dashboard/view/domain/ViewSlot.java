package com.fivetech.dashboard.view.domain;

/**
 * 配置位（dashboard_view_slot）：页面上一处可自定义的列表（一组卡片、一张表的列、一条行维度路径）。
 *
 * @author fivetech
 */
public class ViewSlot
{
    private String slotCode;
    private String pageCode;
    private String name;
    /** METRICS 指标卡 / COLUMNS 表格列 / DIMENSIONS 行维度路径 */
    private String kind;
    private Integer minItems;
    /** 为空 = 不限 */
    private Integer maxItems;
    /** 同组的项必须相邻 */
    private Boolean groupAdjacent;
    /** 额外顺序规则 JSON：[{"before":"agent","after":"channel","message":"…"}] */
    private String rules;
    /** 可选：叠加哪个页面的站点排列（OVERVIEW / SUMMARY）；为空不叠加 */
    private String siteLayoutPage;
    private Integer sortNo;
    /** 0 启用 / 1 停用 */
    private String status;

    public boolean isEnabled() { return "0".equals(status); }
    public int min() { return minItems == null || minItems < 0 ? 0 : minItems; }
    public boolean adjacent() { return Boolean.TRUE.equals(groupAdjacent); }

    public String getSlotCode() { return slotCode; }
    public void setSlotCode(String slotCode) { this.slotCode = slotCode; }
    public String getPageCode() { return pageCode; }
    public void setPageCode(String pageCode) { this.pageCode = pageCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public Integer getMinItems() { return minItems; }
    public void setMinItems(Integer minItems) { this.minItems = minItems; }
    public Integer getMaxItems() { return maxItems; }
    public void setMaxItems(Integer maxItems) { this.maxItems = maxItems; }
    public Boolean getGroupAdjacent() { return groupAdjacent; }
    public void setGroupAdjacent(Boolean groupAdjacent) { this.groupAdjacent = groupAdjacent; }
    public String getRules() { return rules; }
    public void setRules(String rules) { this.rules = rules; }
    public String getSiteLayoutPage() { return siteLayoutPage; }
    public void setSiteLayoutPage(String siteLayoutPage) { this.siteLayoutPage = siteLayoutPage; }
    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
