package com.fivetech.monitor.domain.vo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 运营总览「核心指标」下方的异常提醒条（overview 响应的 anomalies）。没有任何启用规则时整块为 null。
 *
 * @author fivetech
 */
public class MonitorAnomaliesVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 所选区间是否完全等于今日；false 时 items 为空，前端用 todayCount 提示「今日有 n 項異常 · 切到今日」 */
    private boolean inScope;

    /** 判定数据截至（本站最近一个已判定小时的结束时刻，yyyy-MM-dd HH:mm）；还没判定过为 null */
    private String asOf;

    /** 提醒条标题的条数与等级分布（inScope = false 时为 0） */
    private int total;

    private int critCount;

    private int warnCount;

    /** 今日正在发生的异常数（异常中的事件数），不随所选区间变化 */
    private int todayCount;

    /** 本站全部未处理事件数（异常中 + 已结束），与异常事件页签数字一致，为 0 时前端不显示 */
    private int pendingEventCount;

    /** 异常中事件：严重在前，同等级按持续时长从长到短 */
    private List<MonitorAnomalyItemVO> items = new ArrayList<>();

    public boolean isInScope() { return inScope; }
    public void setInScope(boolean inScope) { this.inScope = inScope; }
    public String getAsOf() { return asOf; }
    public void setAsOf(String asOf) { this.asOf = asOf; }
    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }
    public int getCritCount() { return critCount; }
    public void setCritCount(int critCount) { this.critCount = critCount; }
    public int getWarnCount() { return warnCount; }
    public void setWarnCount(int warnCount) { this.warnCount = warnCount; }
    public int getTodayCount() { return todayCount; }
    public void setTodayCount(int todayCount) { this.todayCount = todayCount; }
    public int getPendingEventCount() { return pendingEventCount; }
    public void setPendingEventCount(int pendingEventCount) { this.pendingEventCount = pendingEventCount; }
    public List<MonitorAnomalyItemVO> getItems() { return items; }
    public void setItems(List<MonitorAnomalyItemVO> items) { this.items = items; }
}
