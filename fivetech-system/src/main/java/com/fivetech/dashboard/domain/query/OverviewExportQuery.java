package com.fivetech.dashboard.domain.query;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 运营总览整页导出的请求参数：一次导出「核心指标 / 注册渠道 / 赠金项目结构 / 热销游戏 / 留存率 / LTV」六个子页面。
 *
 * <p>时间参数与页面一致：{@code slotFrom} / {@code slotTo} 为 {@code yyyy-MM-dd HH:mm}，左闭右开，
 * 核心指标、注册渠道、排行榜三个 Tab 共用；留存与 LTV 是 T-1 的日分群，单独用
 * {@code cohortFrom} / {@code cohortTo}（{@code yyyy-MM-dd}，左闭右开，最多 90 天），不传时按 slot 的日期推导。</p>
 *
 * @author fivetech
 */
public class OverviewExportQuery implements Serializable
{
    private static final long serialVersionUID = 1L;

    private String siteCode;

    /** 区间开始（含），yyyy-MM-dd HH:mm */
    @NotBlank(message = "slotFrom 不能为空")
    private String slotFrom;

    /** 区间结束（不含），yyyy-MM-dd HH:mm */
    @NotBlank(message = "slotTo 不能为空")
    private String slotTo;

    /** 核心指标的时间粒度，同时用于排行榜的区间对齐校验：HOUR / DAY / WEEK */
    @Pattern(regexp = "^(HOUR|DAY|WEEK)?$", message = "granularity 取值应为 HOUR/DAY/WEEK")
    private String granularity = "HOUR";

    /** 核心指标的对比方式：NONE / PREV_PERIOD / LAST_YEAR / CUSTOM，与 /overview 一致 */
    @Pattern(regexp = "^(NONE|PREV_PERIOD|LAST_YEAR|CUSTOM)?$", message = "compareType 取值应为 NONE/PREV_PERIOD/LAST_YEAR/CUSTOM")
    private String compareType = "PREV_PERIOD";

    private String compareFrom;

    private String compareTo;

    /** 核心指标只导出这些指标；为空导出全部（与页面一致） */
    private List<String> metrics = new ArrayList<>();

    /** 留存与 LTV 的分群日开始（含），yyyy-MM-dd；不传时取 slotFrom 的日期 */
    private String cohortFrom;

    /** 留存与 LTV 的分群日结束（不含），yyyy-MM-dd；不传时取 slotTo 的日期 */
    private String cohortTo;

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

    public List<String> getMetrics()
    {
        return metrics;
    }

    public void setMetrics(List<String> metrics)
    {
        this.metrics = metrics == null ? new ArrayList<>() : metrics;
    }

    public String getCohortFrom()
    {
        return cohortFrom;
    }

    public void setCohortFrom(String cohortFrom)
    {
        this.cohortFrom = cohortFrom;
    }

    public String getCohortTo()
    {
        return cohortTo;
    }

    public void setCohortTo(String cohortTo)
    {
        this.cohortTo = cohortTo;
    }
}
