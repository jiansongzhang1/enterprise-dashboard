package com.fivetech.dashboard.domain;

import java.io.Serializable;

/**
 * {@code dashboard_metric_group} 的一行：指标分组的编码、中英文名与顺序。
 * <p>
 * 分组名由运营维护，改名不用发版；前端的分组按钮与卡片上的分组名都以它为准。
 *
 * @author fivetech
 */
public class MetricGroupConfig implements Serializable
{
    private static final long serialVersionUID = 1L;

    private String groupCode;

    private String groupName;

    private String groupNameEn;

    private Integer sortNo;

    public String getGroupCode()
    {
        return groupCode;
    }

    public void setGroupCode(String groupCode)
    {
        this.groupCode = groupCode;
    }

    public String getGroupName()
    {
        return groupName;
    }

    public void setGroupName(String groupName)
    {
        this.groupName = groupName;
    }

    public String getGroupNameEn()
    {
        return groupNameEn;
    }

    public void setGroupNameEn(String groupNameEn)
    {
        this.groupNameEn = groupNameEn;
    }

    public Integer getSortNo()
    {
        return sortNo;
    }

    public void setSortNo(Integer sortNo)
    {
        this.sortNo = sortNo;
    }
}
