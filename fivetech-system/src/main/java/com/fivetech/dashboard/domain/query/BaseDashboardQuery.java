package com.fivetech.dashboard.domain.query;

import java.io.Serializable;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

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

    /**
     * 统计区间开始日期 yyyy-MM-dd（站点时区，含当天）。
     * <p>
     * 区间只由 from / to 决定：两者都不传时默认「今天」；只传一个会报错。
     * 「昨日 / 近 7 天」这类快捷选项由前端换算成具体日期再传。
     */
    private String from;

    /** 统计区间结束日期 yyyy-MM-dd（站点时区，含当天） */
    private String to;

    /**
     * 下钻时间片开始 yyyy-MM-dd HH:mm。
     * 从指标汇总表某一行下钻到明细时传入，优先级高于 from / to。
     */
    private String slotFrom;

    /** 下钻时间片结束 yyyy-MM-dd HH:mm */
    private String slotTo;

    /** 下钻来源指标编码，仅用于埋点与日志，不参与查询条件 */
    private String sourceMetricCode;

    /**
     * 是否导出 CSV。
     * <p>
     * 为 true 时接口不返回 JSON，直接返回 CSV 文件流；此时<b>忽略分页参数</b>，
     * 导出当前筛选条件下的全部数据（上限见 {@code dashboard.export.max-rows}），
     * 与原型「匯出範圍：當前篩選條件下的全部資料（非當前頁）」一致。
     * <p>
     * 字段名按约定用下划线 {@code export_csv}，同时兼容驼峰 {@code exportCsv}。
     */
    @JsonProperty("export_csv")
    @JsonAlias("exportCsv")
    private boolean exportCsv;

    public String getSiteCode()
    {
        return siteCode;
    }

    public void setSiteCode(String siteCode)
    {
        this.siteCode = siteCode;
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

    @JsonProperty("export_csv")
    public boolean isExportCsv()
    {
        return exportCsv;
    }

    @JsonProperty("export_csv")
    public void setExportCsv(boolean exportCsv)
    {
        this.exportCsv = exportCsv;
    }
}
