package com.fivetech.monitor.engine.judge;

/**
 * 周期等级。OK 正常 / WARN 注意 / CRIT 严重 / SKIP 未判定（样本量不足、数据缺失、跌幅缺基准）。
 *
 * @author fivetech
 */
public final class Levels
{
    public static final String OK = "OK";

    public static final String WARN = "WARN";

    public static final String CRIT = "CRIT";

    public static final String SKIP = "SKIP";

    /** 越线（注意或严重） */
    public static boolean isOver(String level)
    {
        return WARN.equals(level) || CRIT.equals(level);
    }

    /** 事件等级只升不降（FY-23 R23）：两者取高；CRIT > WARN，null 视为没有 */
    public static String higher(String a, String b)
    {
        if (CRIT.equals(a) || CRIT.equals(b))
        {
            return CRIT;
        }
        return a != null ? a : b;
    }

    private Levels()
    {
    }
}
