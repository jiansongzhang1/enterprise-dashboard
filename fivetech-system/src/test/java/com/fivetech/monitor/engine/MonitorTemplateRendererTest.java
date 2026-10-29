package com.fivetech.monitor.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 通知模板：占位符、可选段落、校验 */
class MonitorTemplateRendererTest
{
    private static final String DEFAULT = com.fivetech.monitor.domain.MonitorNotifyTemplate.DEFAULT_CONTENT;

    private static Map<String, String> values()
    {
        Map<String, String> v = new HashMap<>();
        v.put("site", "uWin");
        v.put("metric", "GGR");
        v.put("currentValue", "₹12,340");
        v.put("judgeText", "跌幅超過嚴重閾值 20%");
        v.put("ruleCode", "R-006");
        v.put("triggerTime", "2026-10-22 14:00");
        v.put("owner", "jackson@fivetech.co.jp");
        return v;
    }

    @Test
    void defaultTemplateIsFy30Format()
    {
        String expected = "【uWin】Yata 告警 · 嚴重\n指標：GGR ₹12,340\n判定：跌幅超過嚴重閾值 20% · R-006\n"
            + "時間：2026-10-22 14:00\n負責人：jackson@fivetech.co.jp";
        assertEquals(expected, MonitorTemplateRenderer.render(DEFAULT, values()));
        assertTrue(MonitorTemplateRenderer.validate(DEFAULT).isEmpty(), "内置默认模板本身要能通过校验");
        assertTrue(MonitorTemplateRenderer.validate(com.fivetech.monitor.domain.MonitorNotifyTemplate.LEGACY_CONTENT).isEmpty() == false,
            "旧模板用到的 start / durationHours 已不是可用占位符");
    }

    @Test
    void judgeTextAndTriggerTime()
    {
        JudgeRuleFactory f = new JudgeRuleFactory();
        assertEquals("低於嚴重閾值 90.00%", MonitorMessageBuilder.judgeText("PCT", f.lo(), false));
        assertEquals("低於注意閾值 95.00%，已升為嚴重", MonitorMessageBuilder.judgeText("PCT", f.lo(), true));
        assertEquals("跌幅超過嚴重閾值 20%", MonitorMessageBuilder.judgeText("MONEY", f.drop(), false));
        assertEquals("超出嚴重區間 2.00% – 8.00%", MonitorMessageBuilder.judgeText("PCT", f.band(), false));
        java.time.LocalDateTime t = java.time.LocalDateTime.of(2026, 10, 22, 14, 0);
        assertEquals("2026-10-22 14:00", MonitorMessageBuilder.triggerTime(t, 1), "不写时区");
        assertEquals("2026-10-22 12:00 – 24:00", MonitorMessageBuilder.triggerTime(t.withHour(12), 12));
        assertEquals("2026-10-22 全天", MonitorMessageBuilder.triggerTime(t.withHour(0), 24));
    }

    static class JudgeRuleFactory
    {
        com.fivetech.monitor.engine.judge.JudgeRule lo()
        {
            return new com.fivetech.monitor.engine.judge.JudgeRule("lo", new java.math.BigDecimal("95"), null,
                new java.math.BigDecimal("90"), null, 1, 2, 3, null, false);
        }

        com.fivetech.monitor.engine.judge.JudgeRule drop()
        {
            return new com.fivetech.monitor.engine.judge.JudgeRule("drop", new java.math.BigDecimal("10"), null,
                new java.math.BigDecimal("20.0000"), null, 1, 2, null, null, false);
        }

        com.fivetech.monitor.engine.judge.JudgeRule band()
        {
            return new com.fivetech.monitor.engine.judge.JudgeRule("band", new java.math.BigDecimal("3"), new java.math.BigDecimal("6"),
                new java.math.BigDecimal("2"), new java.math.BigDecimal("8"), 1, 2, null, null, false);
        }
    }

    @Test
    void missingValueShowsDash()
    {
        assertEquals("負責人 —", MonitorTemplateRenderer.render("負責人 {{owner}}", Map.of()));
        assertEquals("負責人 x", MonitorTemplateRenderer.render("負責人 {{ owner }}", Map.of("owner", "x")), "允许空格");
    }

    @Test
    void sectionInsideLineKeepsRestOfLine()
    {
        assertEquals("規則 R-001",
            MonitorTemplateRenderer.render("規則 {{ruleCode}}{{#escalateHours}} · 升級 {{escalateHours}} 小時{{/escalateHours}}",
                Map.of("ruleCode", "R-001", "escalateHours", "")));
        assertEquals("規則 R-001 · 升級 3 小時",
            MonitorTemplateRenderer.render("規則 {{ruleCode}}{{#escalateHours}} · 升級 {{escalateHours}} 小時{{/escalateHours}}",
                Map.of("ruleCode", "R-001", "escalateHours", "3")));
    }

    @Test
    void validateCatchesUnknownAndUnbalanced()
    {
        List<String> e1 = MonitorTemplateRenderer.validate("{{metirc}} 異常");
        assertTrue(e1.get(0).contains("metirc"), e1.toString());
        assertTrue(!MonitorTemplateRenderer.validate("{{#eventUrl}}查看").isEmpty(), "没有结束标记");
        assertTrue(!MonitorTemplateRenderer.validate("查看{{/eventUrl}}").isEmpty(), "没有开始标记");
        assertTrue(!MonitorTemplateRenderer.validate("{{#eventUrl}}{{#owner}}x{{/owner}}{{/eventUrl}}").isEmpty(), "不能嵌套");
        assertTrue(!MonitorTemplateRenderer.validate("{{ metric").isEmpty(), "格式不对");
        assertTrue(!MonitorTemplateRenderer.validate("   ").isEmpty(), "空模板");
    }

    @Test
    void valueWithDollarSignIsSafe()
    {
        assertEquals("金額 $1,000 \\x", MonitorTemplateRenderer.render("金額 {{currentValue}}", Map.of("currentValue", "$1,000 \\x")));
    }

    @Test
    void larkPostLinks()
    {
        List<List<Map<String, String>>> p = MonitorLarkPost.paragraphs("標題\n查看：https://x/e?id=1，謝謝\n[打開事件](https://x/e)");
        assertEquals(3, p.size());
        assertEquals("text", p.get(0).get(0).get("tag"));
        assertEquals("a", p.get(1).get(1).get("tag"));
        assertEquals("https://x/e?id=1", p.get(1).get(1).get("href"), "网址到中文逗号为止");
        assertEquals("，謝謝", p.get(1).get(2).get("text"));
        assertEquals("打開事件", p.get(2).get(0).get("text"));
        assertEquals("https://x/e", p.get(2).get(0).get("href"));
        assertEquals(1, MonitorLarkPost.paragraphs("").size(), "空正文也有一段");
    }
}
