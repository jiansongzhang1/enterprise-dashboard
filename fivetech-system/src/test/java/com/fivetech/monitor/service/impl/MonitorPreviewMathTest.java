package com.fivetech.monitor.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** 回测预览的取整（百分位 2026-10-26 起由 UDS metric_distribution 计算，见 MonitorPreviewUsualTest） */
class MonitorPreviewMathTest
{
    @Test
    void roundByFormat()
    {
        assertEquals("97.42", MonitorPreviewService.round("PCT", new BigDecimal("97.4166")).toPlainString());
        assertEquals("5.3", MonitorPreviewService.round("MIN", new BigDecimal("5.26")).toPlainString());
        assertEquals("40000", MonitorPreviewService.round("MONEY", new BigDecimal("40000.0000")).toPlainString());
    }
}
