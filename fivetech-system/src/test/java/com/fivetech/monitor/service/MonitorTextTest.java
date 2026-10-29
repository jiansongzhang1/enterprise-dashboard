package com.fivetech.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** 监控模块的文案：指标名与运营总览一致（库里的名称），样本量文案按原型 MINNL */
class MonitorTextTest
{
    @Test
    void metricNameIsTheDatabaseName()
    {
        // 2026-10-27 起名称以库为准（库里存繁体、后台可改），不再按原型覆盖或转繁体
        assertEquals("平均到帳時間", MonitorText.metricName("dT", "平均到帳時間"));
        assertEquals("存款成功率（新）", MonitorText.metricName("dOkR", "存款成功率（新）"));
        assertEquals("注册人数", MonitorText.metricName("reg", "注册人数"), "库里还是简体时原样返回，不转换");
        assertEquals("GGR", MonitorText.metricName("ggr", "GGR"));
        assertEquals("ggr", MonitorText.metricName("ggr", ""), "库里没名称时用编码");
        assertEquals("ggr", MonitorText.metricName("ggr", null));
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
        assertEquals("出款總訂單數", MonitorText.sampleLabel("wTry", "出款尝试笔数"));
        assertEquals("提款成功筆數", MonitorText.sampleLabel("wOk", "出款成功笔数"));
        assertEquals("投注人數", MonitorText.sampleLabel("betU", "betU"));
        assertEquals("投注人數", MonitorText.sampleLabel("active", "活跃人数"));
        assertEquals("存款人數", MonitorText.sampleLabel("depU", "存款人数"));
        assertEquals("註冊人數", MonitorText.sampleLabel("reg", "注册人数"));
        assertEquals("首存人數", MonitorText.sampleLabel("ftd", "首存人数"));
        assertNull(MonitorText.sampleLabel(null, null));
    }
}
