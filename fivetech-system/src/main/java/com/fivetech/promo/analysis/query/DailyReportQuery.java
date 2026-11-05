package com.fivetech.promo.analysis.query;

/**
 * 投放日报导出（FY-57）。
 *
 * @author fivetech
 */
public class DailyReportQuery extends PromoFilter
{
    private static final long serialVersionUID = 1L;

    /** yest 昨日 / p3 前 3 天（到昨天）/ top 按顶部时间范围（slotFrom、slotTo 必填） */
    private String mode;

    /** true（默认）：只列所选日期内有预算或消耗记录的推广渠道；false：全部有数据的推广渠道 */
    private Boolean paidOnly = Boolean.TRUE;

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public Boolean getPaidOnly() { return paidOnly; }
    public void setPaidOnly(Boolean paidOnly) { this.paidOnly = paidOnly; }
}
