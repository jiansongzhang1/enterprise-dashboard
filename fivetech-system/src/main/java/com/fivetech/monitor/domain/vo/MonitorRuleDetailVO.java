package com.fivetech.monitor.domain.vo;

/**
 * 规则详情：列表字段 + 样本量与创建信息。
 *
 * @author fivetech
 */
public class MonitorRuleDetailVO extends MonitorRuleVO
{
    private MinSampleVO minSample;

    /** 邮箱 */
    private String createBy;

    private String createTime;

    public MinSampleVO getMinSample()
    {
        return minSample;
    }

    public void setMinSample(MinSampleVO minSample)
    {
        this.minSample = minSample;
    }

    public String getCreateBy()
    {
        return createBy;
    }

    public void setCreateBy(String createBy)
    {
        this.createBy = createBy;
    }

    public String getCreateTime()
    {
        return createTime;
    }

    public void setCreateTime(String createTime)
    {
        this.createTime = createTime;
    }
}
