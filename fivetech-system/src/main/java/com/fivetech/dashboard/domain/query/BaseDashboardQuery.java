package com.fivetech.dashboard.domain.query;

import java.io.Serializable;
import com.fivetech.dashboard.enums.RangeType;

/**
 * 仪表板查询的公共入参。
 * <p>
 * 约定：前端只传枚举与业务标识，<b>永远不传表名、列名或任何 SQL 片段</b>。
 * 指标编码、列编码均由服务端的注册表白名单校验，白名单之外一律拒绝。
 *
 * @author fivetech
 */
public class BaseDashboardQuery implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 站点编码。一期单站点，留空时取配置的默认站点 */
    private String siteCode;

    /** 区间类型 */
    private RangeType rangeType = RangeType.TODAY;

    /** 自定义区间开始日期 yyyy-MM-dd，rangeType=CUSTOM 时必填 */
    private String from;

    /** 自定义区间结束日期 yyyy-MM-dd，rangeType=CUSTOM 时必填 */
    private String to;

    /**
     * 下钻时间片开始 yyyy-MM-dd HH:mm。
     * 从指标汇总表某一行下钻到明细时传入，优先级高于 rangeType。
     */
    private String slotFrom;

    /** 下钻时间片结束 yyyy-MM-dd HH:mm */
    private String slotTo;

    /** 下钻来源指标编码，仅用于埋点与日志，不参与查询条件 */
    private String sourceMetricCode;

    public String getSiteCode()
    {
        return siteCode;
    }

    public void setSiteCode(String siteCode)
    {
        this.siteCode = siteCode;
    }

    public RangeType getRangeType()
    {
        return rangeType;
    }

    public void setRangeType(RangeType rangeType)
    {
        this.rangeType = rangeType;
    }

    public String getFrom()
    {
        return from;
    }

    public void setFrom(String from)
    {
        this.from = from;
    }

    public String getTo()
    {
        return to;
    }

    public void setTo(String to)
    {
        this.to = to;
    }

    public String getSlotFrom()
    {
        return slotFrom;
    }

    public void setSlotFrom(String slotFrom)
    {
        this.slotFrom = slotFrom;
    }

    public String getSlotTo()
    {
        return slotTo;
    }

    public void setSlotTo(String slotTo)
    {
        this.slotTo = slotTo;
    }

    public String getSourceMetricCode()
    {
        return sourceMetricCode;
    }

    public void setSourceMetricCode(String sourceMetricCode)
    {
        this.sourceMetricCode = sourceMetricCode;
    }
}
