package com.fivetech.monitor.service;

import java.math.BigDecimal;
import com.fivetech.monitor.engine.judge.JudgeRule;

/** 测试用：访问 MonitorSlotService 的包内方法 */
public final class MonitorSlotServiceTextProbe
{
    private MonitorSlotServiceTextProbe()
    {
    }

    public static String line(JudgeRule judge, String raw, BigDecimal v, String fmt)
    {
        return MonitorSlotService.lineText(judge, raw, v, fmt);
    }
}
