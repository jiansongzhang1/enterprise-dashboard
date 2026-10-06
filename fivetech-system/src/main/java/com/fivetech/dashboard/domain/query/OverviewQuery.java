package com.fivetech.dashboard.domain.query;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.enums.CompareType;
import com.fivetech.dashboard.enums.Granularity;

/**
 * 运营总览 · 主要指标查询入参。
 *
 * <p><b>时间语义是左闭右开</b>：{@code slotFrom = 09:00, slotTo = 10:00, granularity = HOUR}
 * 表示 1 个时间片（09:00 那一小时）。必须定成左闭右开，否则
 * {@code 09:00 ~ 09:00} 到底是 0 个点还是 1 个点没有答案。</p>
 *
 * <p>与 {@link BaseDashboardQuery} 不共用父类：总览走的是时间片区间
 * （{@code slotFrom/slotTo}），不是按日的 {@code from/to}，
 * 硬套父类会同时留着两套时间字段，前端不知道该传哪一套。</p>
 *
 * @author fivetech
 */
public class OverviewQuery implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 站点编码。一期单站点，留空时取配置的默认站点 */
    private String siteCode;

    /** 时间粒度。非法组合由服务端降级，不报错，降级结果写进 notices */
    private Granularity granularity = Granularity.HOUR;

    /** 区间开始 yyyy-MM-dd HH:mm，闭 */
    private String slotFrom;

    /** 区间结束 yyyy-MM-dd HH:mm，开 */
    private String slotTo;

    /** 对比期类型。NONE 时不返回 compare 与任何 prev* 值 */
    private CompareType compareType = CompareType.PREV_PERIOD;

    /** 对比期开始，compareType=CUSTOM 时必填；其余情况传了也忽略 */
    private String compareFrom;

    /** 对比期结束，compareType=CUSTOM 时必填 */
    private String compareTo;

    /** 是否锁定对比期长度 = 主区间长度 */
    private boolean cmpLock = true;

    /**
     * 要取哪几块。目前只有 {@code METRICS}（指标墙），为空时默认返回它。
     * <p>兼容单个字符串：前端传 {@code "blocks": "METRICS"} 也接受，
     * 服务端包成数组并在 notices 里提示。</p>
     */
    private List<String> blocks = new ArrayList<>();

    /** 指标分组筛选 eg1~eg4，为空表示全部 */
    private String group;

    /** 需要的指标编码，为空时用该页面配置的全部指标 */
    private List<String> metrics = new ArrayList<>();

    /** false 时不返回卡内趋势，只返回当期值与环比，省一次序列查询 */
    private boolean includeSeries = true;

    /**
     * 导出开关。为 true 时不返回 JSON 数据体，直接回一个文件流。
     *
     * <p>历史字段名是 {@code export_csv}，用 {@link JsonAlias} 保留兼容——
     * 前端不必跟着改，但新接入一律用 {@code export}。</p>
     */
    @JsonProperty("export")
    @JsonAlias({ "export_csv", "exportCsv" })
    private boolean export;

    /**
     * 导出格式，{@code XLSX}（默认）或 {@code CSV}。
     *
     * <p>XLSX 是多 sheet 的，指标卡、时间序列、口径说明各占一页。</p>
     */
    private String exportFormat = "XLSX";

    public String getSiteCode()
    {
        return siteCode;
    }

    public void setSiteCode(String siteCode)
    {
        this.siteCode = siteCode;
    }

    public Granularity getGranularity()
    {
        return granularity;
    }

    public void setGranularity(Granularity granularity)
    {
        this.granularity = granularity;
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

    public CompareType getCompareType()
    {
        return compareType;
    }

    public void setCompareType(CompareType compareType)
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

    public boolean isCmpLock()
    {
        return cmpLock;
    }

    public void setCmpLock(boolean cmpLock)
    {
        this.cmpLock = cmpLock;
    }

    public List<String> getBlocks()
    {
        return blocks;
    }

    /**
     * 同时接受数组与单个字符串：{@code ["METRICS"]} 和 {@code "METRICS"} 都能解。
     * <p>只能有这一个写入点——Jackson 对同一个属性名有两个 setter 时会在启动期报歧义。</p>
     */
    @JsonProperty("blocks")
    @JsonAlias("block")
    public void setBlocks(Object raw)
    {
        List<String> parsed = new ArrayList<>();
        if (raw instanceof String)
        {
            parsed.add((String) raw);
        }
        else if (raw instanceof List)
        {
            for (Object item : (List<?>) raw)
            {
                if (item != null)
                {
                    parsed.add(String.valueOf(item));
                }
            }
        }
        this.blocks = parsed;
    }

    public String getGroup()
    {
        return group;
    }

    public void setGroup(String group)
    {
        this.group = group;
    }

    public List<String> getMetrics()
    {
        return metrics;
    }

    public void setMetrics(List<String> metrics)
    {
        this.metrics = metrics == null ? new ArrayList<>() : metrics;
    }


    public boolean isIncludeSeries()
    {
        return includeSeries;
    }

    public void setIncludeSeries(boolean includeSeries)
    {
        this.includeSeries = includeSeries;
    }

    public boolean isExport()
    {
        return export;
    }

    public void setExport(boolean export)
    {
        this.export = export;
    }

    @JsonProperty("exportFormat")
    @JsonAlias({ "format", "export_format" })
    public String getExportFormat()
    {
        return exportFormat;
    }

    public void setExportFormat(String exportFormat)
    {
        this.exportFormat = StringUtils.isEmpty(exportFormat) ? "XLSX" : exportFormat.trim().toUpperCase();
    }

}
