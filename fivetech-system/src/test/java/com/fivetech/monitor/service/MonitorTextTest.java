package com.fivetech.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** 监控模块的繁体文案：指标名与运营总览一致，样本量文案按原型 MINNL */
class MonitorTextTest
{
    @Test
    void metricNamesAreTraditional()
    {
        assertEquals("註冊人數", MonitorText.metricName("reg", "注册人数"));
        assertEquals("存款總額", MonitorText.metricName("dep", "存款总额"));
        assertEquals("平均到帳時間", MonitorText.metricName("dT", "平均到帐时间"));
        assertEquals("流水倍數", MonitorText.metricName("turnX", "流水倍数"));
        assertEquals("平均殺率", MonitorText.metricName("killR", "平均杀率"));
        assertEquals("發放贈金總額", MonitorText.metricName("bonus", "发放赠金总额"));
        assertEquals("贈金比", MonitorText.metricName("bonusR", "赠金比"));
        assertEquals("出款成功率", MonitorText.metricName("wOkR", "出款成功率"));
        assertEquals("GGR", MonitorText.metricName("ggr", "GGR"));
    }

    @Test
    void metricNameWithoutCodeKeepsFallback()
    {
        assertEquals("x", MonitorText.metricName(null, "x"));
        assertEquals("x", MonitorText.metricName("", "x"));
    }

    @Test
    void sampleLabelsFollowPrototype()
    {
        assertEquals("基準值", MonitorText.sampleLabel("base", "基准"));
        assertEquals("存款總訂單數", MonitorText.sampleLabel("dTry", "存款尝试笔数"));
        assertEquals("存款成功筆數", MonitorText.sampleLabel("dOk", "存款成功笔数"));
        assertEquals("提款總訂單數", MonitorText.sampleLabel("wTry", "出款尝试笔数"));
        assertEquals("提款成功筆數", MonitorText.sampleLabel("wOk", "出款成功笔数"));
        assertEquals("投注人數", MonitorText.sampleLabel("betU", "betU"));
        assertEquals("去重存款人數", MonitorText.sampleLabel("depU", "存款人数"));
        assertEquals("註冊人數", MonitorText.sampleLabel("reg", "注册人数"));
        assertEquals("首存人數", MonitorText.sampleLabel("ftd", "首存人数"));
        assertNull(MonitorText.sampleLabel(null, null));
    }
}
