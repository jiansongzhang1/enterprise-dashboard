package com.fivetech.dashboard.export;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 导出文案：名称、分组用注册表（后台「指标管理」可改），格式改了按新格式；口径仍按模板 */
class ExportMetricTextTest
{
    @Test
    void templateMetricUsesDatabaseNameAndGroup()
    {
        ExportMetricText.M m = ExportMetricText.of("dOkR", "存款成功率（後台）", "支付健康度（新）", null, "PCT");
        assertEquals("存款成功率（後台）", m.label);
        assertEquals("支付健康度（新）", m.group);
        assertEquals("%", m.unit, "格式没变：沿用模板单位");
        assertEquals("0.00", m.format);
        assertEquals(ExportMetricText.ALL.get("dOkR").note, m.note, "口径仍按模板");
    }

    @Test
    void templateMetricFallsBackWhenNoName()
    {
        ExportMetricText.M m = ExportMetricText.of("dep", null, null, null, "MONEY");
        assertEquals(ExportMetricText.ALL.get("dep").label, m.label);
        assertEquals(ExportMetricText.ALL.get("dep").group, m.group);
        assertEquals("INR", m.unit);
    }

    @Test
    void formatChangedFollowsNewFormat()
    {
        ExportMetricText.M m = ExportMetricText.of("dep", "存款總額", "资金流", null, "INT");
        assertEquals("人數", m.unit, "后台把 MONEY 改成 INT：按新格式");
        assertEquals(null, m.format);
        assertEquals("資金流", m.group, "分组名按字转繁体");
        assertEquals("倍", ExportMetricText.of("turnX", "流水倍數", null, null, "MULTIPLE").unit, "MULTIPLE 是倍数");
    }

    @Test
    void nonTemplateMetricUsesRegistry()
    {
        ExportMetricText.M m = ExportMetricText.of("pay", "派彩金額", "投注", "口径", "MONEY");
        assertEquals("派彩金額", m.label);
        assertEquals("INR", m.unit);
    }
}
