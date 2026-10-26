package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 指标抽屉「逐时段明细」监控列的一格（与 series 按下标对齐，一个小时一格）。
 * 多小时检测周期时，同一周期内各小时的 periodFrom / periodTo / value 相同，前端按 periodFrom 合并单元格。
 *
 * @author fivetech
 */
public class MetricMonitorSlotVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 判定结果 */
    public static final String OK = "OK";
    public static final String WARN = "WARN";
    public static final String CRIT = "CRIT";
    /** 未判定：样本不足（reason = SAMPLE）或数据缺失（reason = MISSING） */
    public static final String SKIP = "SKIP";
    /** 所在检测周期还没结束（多小时周期的最后一段） */
    public static final String PENDING = "PENDING";

    /** OK / WARN / CRIT / SKIP / PENDING；注意升级后的周期为 CRIT 且 escalated = true */
    private String level;

    /** 由注意升级为严重（level = CRIT，越过的仍是注意线） */
    private boolean escalated;

    /** level = SKIP 时：SAMPLE 样本不足 · MISSING 数据缺失；其余为 null */
    private String reason;

    /** 所在检测周期 [periodFrom, periodTo)，yyyy-MM-dd HH:mm */
    private String periodFrom;

    private String periodTo;

    /** 周期值（展示单位）；1 小时周期即该小时的值；PENDING / 数据缺失为 null */
    private BigDecimal value;

    /** 跌幅类：周期基准（展示单位） */
    private BigDecimal baseline;

    /** 跌幅类：较基准下跌百分比（1 位小数，涨了为负数） */
    private BigDecimal dropPct;

    /** 越过的线：WARN 注意线 · CRIT 严重线；没越线为 null */
    private String against;

    /**
     * 监控格文字（不含等级），繁体：下限「&lt; 90.00%」、上限「&gt; 8.00%」、区间只写越过的一侧；
     * 跌幅类「較基準 −12.4%」（正常也给）。没越线的阈值类为 null，前端只写「正常」
     */
    private String lineText;

    /** 跌幅类第二行灰字「基準 ₹76,240」；其余为 null */
    private String baselineText;

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public boolean isEscalated() { return escalated; }
    public void setEscalated(boolean escalated) { this.escalated = escalated; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getPeriodFrom() { return periodFrom; }
    public void setPeriodFrom(String periodFrom) { this.periodFrom = periodFrom; }
    public String getPeriodTo() { return periodTo; }
    public void setPeriodTo(String periodTo) { this.periodTo = periodTo; }
    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal value) { this.value = value; }
    public BigDecimal getBaseline() { return baseline; }
    public void setBaseline(BigDecimal baseline) { this.baseline = baseline; }
    public BigDecimal getDropPct() { return dropPct; }
    public void setDropPct(BigDecimal dropPct) { this.dropPct = dropPct; }
    public String getAgainst() { return against; }
    public void setAgainst(String against) { this.against = against; }
    public String getLineText() { return lineText; }
    public void setLineText(String lineText) { this.lineText = lineText; }
    public String getBaselineText() { return baselineText; }
    public void setBaselineText(String baselineText) { this.baselineText = baselineText; }
}
