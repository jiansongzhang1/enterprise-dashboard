package com.fivetech.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.domain.MetricDefinition;

/** 运营总览文案全部以库为准：名称 = metric_name，统计口径 = remark 的「口徑：」行；分组名按字转繁体 */
class OverviewMetricTextTest
{
    private static MetricDefinition def(String code, String remark, String calc, String left, String right)
    {
        MetricDefinition d = new MetricDefinition(code, code + "名", "eg1", left == null ? "ATOM" : "DERIVED", "PCT", "SUM", null);
        d.setRemark(remark);
        d.setCalcType(calc);
        d.setLeftCode(left);
        d.setRightCode(right);
        return d;
    }

    @Test
    void labelIsDatabaseName()
    {
        assertEquals("存款成功率（後台改）", OverviewMetricText.label("dOkR", "存款成功率（後台改）"), "不再按原型覆盖");
        assertEquals("平均到帳時間", OverviewMetricText.label("dT", "平均到帳時間"));
        assertEquals("pay", OverviewMetricText.label("pay", null));
        assertEquals("pay", OverviewMetricText.label("pay", " "));
    }

    @Test
    void caliberFromRemark()
    {
        // 后台改的口径立即生效（不再用代码里写死的原型口径）
        assertEquals("出款成功筆數 ÷ 出款總訂單數",
                OverviewMetricText.caliber("wOkR", def("wOkR", "口徑：出款成功筆數 ÷ 出款總訂單數\n備註：UDS 直接提供比率", "DIVIDE", "wOk", "wTry"), null));
        // 简体「口径」、半角冒号、前后空白都认；取第一行口径，備註不返回
        assertEquals("首存人數 ÷ 註冊人數 × 100%", OverviewMetricText.caliberOf("备注：先写的备注\n  口径:  首存人數 ÷ 註冊人數 × 100%  \n"));
        assertEquals("所有會員成功存款金額加總", OverviewMetricText.caliberOf("口徑：所有會員成功存款金額加總"));
        assertNull(OverviewMetricText.caliberOf("口徑："), "口径为空当没填");
        assertNull(OverviewMetricText.caliberOf("只有備註，沒有口徑行"));
        assertNull(OverviewMetricText.caliberOf(null));
    }

    @Test
    void caliberFallbackWhenRemarkMissing()
    {
        // 派生指标没填口径：用两个操作数的名称拼（没有注册表时用编码）
        assertEquals("dOk ÷ dTry", OverviewMetricText.caliber("dOkR", def("dOkR", null, "DIVIDE", "dOk", "dTry"), null));
        assertEquals("dep − wd", OverviewMetricText.caliber("net", def("net", "備註：只有備註", "SUBTRACT", "dep", "wd"), null));
        // 原子指标没填口径：null
        assertNull(OverviewMetricText.caliber("reg", def("reg", "", null, null, null), null));
        assertNull(OverviewMetricText.caliber("reg", null, null));
    }

    @Test
    void groupStillConverted()
    {
        assertEquals("獲客與轉化", OverviewMetricText.group("获客与转化"));
    }
}
