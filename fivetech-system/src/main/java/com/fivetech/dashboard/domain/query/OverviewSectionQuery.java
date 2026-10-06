package com.fivetech.dashboard.domain.query;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;

/**
 * 运营总览各板块（排行榜 / 留存与 LTV / 用户与价值 / 注册渠道）的公共入参。
 * <p>
 * 时间语义<b>左闭右开</b>。{@code granularity} / {@code compareType} / {@code compareFrom} / {@code compareTo}
 * 只有部分板块使用；不使用的板块传了也不报错，服务端写进 notices（PARAM_IGNORED）。
 * 因此这几个字段按原样字符串接收，不做枚举反序列化——传错值不该让整个请求 400。
 *
 * @author fivetech
 */
public class OverviewSectionQuery implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 站点编码，留空取默认站点 */
    private String siteCode;

    /** 区间开始（含），yyyy-MM-dd HH:mm 或 yyyy-MM-dd */
    private String slotFrom;

    /** 区间结束（不含），格式同 slotFrom */
    private String slotTo;

    /** 粒度 HOUR / DAY / WEEK；仅排行榜用来校验区间 */
    private String granularity;

    /** 对比类型；四个板块都不做环比，传了进 notices */
    private String compareType;

    /** 同上 */
    private String compareFrom;

    /** 同上 */
    private String compareTo;

    @JsonProperty("export")
    @JsonAlias({ "export_csv", "exportCsv" })
    /** 导出开关，兼容历史字段名 export_csv */
    private boolean export = false;

    public String getSiteCode()
    {
        return siteCode;
    }

    public void setSiteCode(String siteCode)
    {
        this.siteCode = siteCode;
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

    public String getGranularity()
    {
        return granularity;
    }

    public void setGranularity(String granularity)
    {
        this.granularity = granularity;
    }

    public String getCompareType()
    {
        return compareType;
    }

    public void setCompareType(String compareType)
    {
        this.compareType = compareType;
    }

    public String getCompareFrom()
    {
        return compareFrom;
    }

    public void setCompareFrom(String compareFrom)
    {
        this.compareFrom = compareFrom;
    }

    public String getCompareTo()
    {
        return compareTo;
    }

    public void setCompareTo(String compareTo)
    {
        this.compareTo = compareTo;
    }

    public boolean isExport()
    {
        return export;
    }

    public void setExport(boolean export)
    {
        this.export = export;
    }
}
