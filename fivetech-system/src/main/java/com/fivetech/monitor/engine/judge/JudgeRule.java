package com.fivetech.monitor.engine.judge;

import java.math.BigDecimal;

/**
 * 判定用的规则快照（与数据库、时钟无关）。阈值都是展示单位：百分比为 0～100，时长为分钟；
 * 跌幅类的阈值放在 low，表示「较基准下跌的百分比」。
 *
 * @author fivetech
 */
public class JudgeRule
{
    public static final String LO = "lo";

    public static final String HI = "hi";

    public static final String BAND = "band";

    public static final String DROP = "drop";

    private final String judgeType;

    private final BigDecimal warnLow;

    private final BigDecimal warnHigh;

    private final BigDecimal critLow;

    private final BigDecimal critHigh;

    private final int periodHours;

    private final int durationCount;

    private final Integer escalateHours;

    /** 最小样本量，null 或 0 表示不限 */
    private final BigDecimal minSample;

    /** 样本量看跌幅基准（min_sample_key = base），否则看 PeriodPoint.sample */
    private final boolean sampleIsBaseline;

    public JudgeRule(String judgeType, BigDecimal warnLow, BigDecimal warnHigh, BigDecimal critLow, BigDecimal critHigh,
            int periodHours, int durationCount, Integer escalateHours, BigDecimal minSample, boolean sampleIsBaseline)
    {
        this.judgeType = judgeType;
        this.warnLow = warnLow;
        this.warnHigh = warnHigh;
        this.critLow = critLow;
        this.critHigh = critHigh;
        this.periodHours = Math.max(1, periodHours);
        this.durationCount = Math.max(1, durationCount);
        this.escalateHours = escalateHours;
        this.minSample = minSample;
        this.sampleIsBaseline = sampleIsBaseline;
    }

    /** 设了注意线（区间类两端都要有） */
    public boolean hasWarn()
    {
        if (BAND.equals(judgeType))
        {
            return warnLow != null && warnHigh != null;
        }
        return HI.equals(judgeType) ? warnHigh != null : warnLow != null;
    }

    /** 注意升级换算成周期数：从触发起再过多少个越线周期升为严重；0 = 不升级 */
    public int escalatePeriods()
    {
        if (escalateHours == null || escalateHours <= 0)
        {
            return 0;
        }
        return (escalateHours + periodHours - 1) / periodHours;
    }

    public String getJudgeType()
    {
        return judgeType;
    }

    public BigDecimal getWarnLow()
    {
        return warnLow;
    }

    public BigDecimal getWarnHigh()
    {
        return warnHigh;
    }

    public BigDecimal getCritLow()
    {
        return critLow;
    }

    public BigDecimal getCritHigh()
    {
        return critHigh;
    }

    public int getPeriodHours()
    {
        return periodHours;
    }

    public int getDurationCount()
    {
        return durationCount;
    }

    public Integer getEscalateHours()
    {
        return escalateHours;
    }

    public BigDecimal getMinSample()
    {
        return minSample;
    }

    public boolean isSampleIsBaseline()
    {
        return sampleIsBaseline;
    }
}
