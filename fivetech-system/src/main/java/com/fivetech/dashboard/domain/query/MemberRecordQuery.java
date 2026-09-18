package com.fivetech.dashboard.domain.query;

import com.fivetech.dashboard.enums.MemberTimeField;
import com.fivetech.dashboard.enums.MemberViewScheme;

/**
 * 会员明细查询入参。
 *
 * @author fivetech
 */
public class MemberRecordQuery extends BaseRecordQuery
{
    private static final long serialVersionUID = 1L;

    /** 列方案 */
    private MemberViewScheme viewScheme = MemberViewScheme.ALL;

    /**
     * 时间筛选字段。默认按注册时间；从首存类指标下钻时应传 FTD_TIME，
     * 从活跃类指标下钻时应传 ACTIVE_TIME，否则行数与指标值对不上。
     */
    private MemberTimeField timeField = MemberTimeField.REG_TIME;

    /** 注册渠道 */
    private String registerChannel;

    /** 注册设备 Android / iOS / H5 */
    private String device;

    /** 是否已首存。null 表示不限 */
    private Boolean hasFirstDeposit;

    /** 价值层级编码 */
    private String tier;

    /** 生命周期阶段编码 */
    private String stage;

    public MemberViewScheme getViewScheme()
    {
        return viewScheme;
    }

    public void setViewScheme(MemberViewScheme viewScheme)
    {
        this.viewScheme = viewScheme;
    }

    public MemberTimeField getTimeField()
    {
        return timeField;
    }

    public void setTimeField(MemberTimeField timeField)
    {
        this.timeField = timeField;
    }

    public String getRegisterChannel()
    {
        return registerChannel;
    }

    public void setRegisterChannel(String registerChannel)
    {
        this.registerChannel = registerChannel;
    }

    public String getDevice()
    {
        return device;
    }

    public void setDevice(String device)
    {
        this.device = device;
    }

    public Boolean getHasFirstDeposit()
    {
        return hasFirstDeposit;
    }

    public void setHasFirstDeposit(Boolean hasFirstDeposit)
    {
        this.hasFirstDeposit = hasFirstDeposit;
    }

    public String getTier()
    {
        return tier;
    }

    public void setTier(String tier)
    {
        this.tier = tier;
    }

    public String getStage()
    {
        return stage;
    }

    public void setStage(String stage)
    {
        this.stage = stage;
    }
}
