package com.fivetech.monitor.domain.vo;

import java.io.Serializable;

/**
 * 异常提醒条的一行（一条异常中的事件）。所有 text 字段由后端生成，繁体，已带单位。
 *
 * @author fivetech
 */
public class MonitorAnomalyItemVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 事件当前等级 WARN / CRIT */
    private String level;

    /** 由注意升级而来 */
    private boolean escalated;

    private String metricCode;

    private String metricName;

    private JudgeValue judgeValue;

    private JudgeLine judgeLine;

    /** 异常起点 yyyy-MM-dd HH:mm */
    private String startHour;

    /** 今日写「09:00」，跨天写「10-18 23:00」 */
    private String startText;

    /** 持续时长（最近一次判定周期的结束 − 起点，整小时） */
    private long durationHours;

    private String durationText;

    /** 规则编号 R-001 */
    private String ruleCode;

    private Long eventId;

    /** 判定窗口与窗口内的值 */
    public static class JudgeValue implements Serializable
    {
        private static final long serialVersionUID = 1L;

        /** 「近 2 小時」「00:00 – 12:00」「近 3 個週期」 */
        private String window;

        /** 「83.60% – 84.20%」「較基準下跌 12.4% – 13.1%」「1.80%、8.60%」 */
        private String text;

        public JudgeValue(String window, String text)
        {
            this.window = window;
            this.text = text;
        }

        public String getWindow() { return window; }
        public String getText() { return text; }
    }

    /** 越过的那条线 */
    public static class JudgeLine implements Serializable
    {
        private static final long serialVersionUID = 1L;

        /** 对照的是严重线（CRIT）还是注意线（WARN） */
        private String against;

        /** 「低於嚴重線 90.00%」「超出注意區間 2.00% – 8.00%」「跌幅超過注意線 10%」 */
        private String text;

        public JudgeLine(String against, String text)
        {
            this.against = against;
            this.text = text;
        }

        public String getAgainst() { return against; }
        public String getText() { return text; }
    }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public boolean isEscalated() { return escalated; }
    public void setEscalated(boolean escalated) { this.escalated = escalated; }
    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }
    public JudgeValue getJudgeValue() { return judgeValue; }
    public void setJudgeValue(JudgeValue judgeValue) { this.judgeValue = judgeValue; }
    public JudgeLine getJudgeLine() { return judgeLine; }
    public void setJudgeLine(JudgeLine judgeLine) { this.judgeLine = judgeLine; }
    public String getStartHour() { return startHour; }
    public void setStartHour(String startHour) { this.startHour = startHour; }
    public String getStartText() { return startText; }
    public void setStartText(String startText) { this.startText = startText; }
    public long getDurationHours() { return durationHours; }
    public void setDurationHours(long durationHours) { this.durationHours = durationHours; }
    public String getDurationText() { return durationText; }
    public void setDurationText(String durationText) { this.durationText = durationText; }
    public String getRuleCode() { return ruleCode; }
    public void setRuleCode(String ruleCode) { this.ruleCode = ruleCode; }
    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
}
