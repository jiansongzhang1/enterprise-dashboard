package com.fivetech.dashboard.domain.vo.overview;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 渠道分组。
 *
 * @author fivetech
 */
public class RegChannelGroupVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** ad / ag / og */
    private String code;

    /** 分组名称 */
    private String name;

    /** 注册人数 */
    private Long registrations;

    /** 占比 %，分母 totalRegistrations */
    private BigDecimal share;

    /** 渠道数（含 0 注册） */
    private Integer channelCount;

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

    public Long getRegistrations()
    {
        return registrations;
    }

    public void setRegistrations(Long registrations)
    {
        this.registrations = registrations;
    }

    public BigDecimal getShare()
    {
        return share;
    }

    public void setShare(BigDecimal share)
    {
        this.share = share;
    }

    public Integer getChannelCount()
    {
        return channelCount;
    }

    public void setChannelCount(Integer channelCount)
    {
        this.channelCount = channelCount;
    }

    /** 页面展示精度（导出不调用）：占比 1 位小数 */
    public void applyViewScale()
    {
        this.share = com.fivetech.dashboard.format.MoneyScale.pct1(share);
    }
}
