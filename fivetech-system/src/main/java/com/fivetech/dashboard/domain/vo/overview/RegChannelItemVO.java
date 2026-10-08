package com.fivetech.dashboard.domain.vo.overview;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 单个渠道。
 *
 * @author fivetech
 */
public class RegChannelItemVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 唯一键 groupCode|渠道编码 */
    private String code;

    /** 显示名 */
    private String name;

    /** 所属分组编码 */
    private String groupCode;

    /** 所属分组名称 */
    private String groupName;

    /** 注册人数 */
    private Long registrations;

    /** 占总注册 % */
    private BigDecimal shareOfTotal;

    /** 占分组 % */
    private BigDecimal shareOfGroup;

    public String getCode()
    {
        return code;
    }

    public void setCode(String code)
    {
        this.code = code;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

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

    public Long getRegistrations()
    {
        return registrations;
    }

    public void setRegistrations(Long registrations)
    {
        this.registrations = registrations;
    }

    public BigDecimal getShareOfTotal()
    {
        return shareOfTotal;
    }

    public void setShareOfTotal(BigDecimal shareOfTotal)
    {
        this.shareOfTotal = shareOfTotal;
    }

    public BigDecimal getShareOfGroup()
    {
        return shareOfGroup;
    }

    public void setShareOfGroup(BigDecimal shareOfGroup)
    {
        this.shareOfGroup = shareOfGroup;
    }

    /** 页面展示精度（导出不调用）：占比 1 位小数 */
    public void applyViewScale()
    {
        this.shareOfTotal = com.fivetech.dashboard.format.MoneyScale.pct1(shareOfTotal);
        this.shareOfGroup = com.fivetech.dashboard.format.MoneyScale.pct1(shareOfGroup);
    }
}
