package com.fivetech.monitor.cond;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 条件规则校验与文案：字段、范围、取值方式与比较、门槛、通知、连续次数上限；摘要与值的显示 */
class CondRulesTest
{
    static final CondRules.DimLabels LABELS = (dim, v) -> switch (dim)
    {
        case "ch" -> v.equals("g:1001") ? "ROI 推廣" : v.equals("c:1001|tg") ? "ROI 推廣 › Telegram" : null;
        case "term" -> v.equals("Android") ? "Android" : null;
        default -> v;
    };

    static Map<String, Object> c(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2)
        {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    static CondRuleBody body()
    {
        CondRuleBody b = new CondRuleBody();
        b.setName("疑似批量註冊");
        b.setLevel("CRIT");
        b.setWindow("1h");
        b.setDurationCount(2);
        b.setNotifyChatIds(List.of("oc_1"));
        b.setConditions(List.of(
            c("field", "reg", "scope", c("ch", "g:1001", "term", "Android"), "how", "BASE", "op", "UP", "threshold", 50),
            c("field", "ftdr", "how", "CUR", "op", "LT", "threshold", 5, "minSample", 100)));
        return b;
    }

    @Test
    void validRuleAndSummary()
    {
        CondProperties p = new CondProperties();
        Map<String, CondField> fields = CondFixtures.catalog().fields();
        Map<String, String> errors = new HashMap<>();
        CondRules.Checked ck = CondRules.check(body(), fields, LABELS, p, errors);
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals("PCT", ck.spec.getConditions().get(0).getUnit(), "变化单位默认 PCT");
        assertEquals(2, ck.muteHours, "静默默认 2 小时");
        assertEquals("每 1 小時檢測，連續 2 次滿足以下全部條件時記為嚴重：［ROI 推廣 · Android］註冊人數 較基準 上升超過 50%；並且 ［不限範圍］首存轉化率 小於 5%",
            CondRules.summary(ck.spec, fields, LABELS, "₹"));
        assertEquals("412（較基準 +120.5%）", CondRules.valueText(ck.spec.getConditions().get(0), fields.get("reg"),
            new BigDecimal("412"), new BigDecimal("120.5"), "₹"));
        assertEquals("1.2%", CondRules.value(fields.get("ftdr"), new BigDecimal("1.2"), "₹"));
        assertEquals("₹12,345", CondRules.value(fields.get("dep"), new BigDecimal("12345.4"), "₹"));
    }

    @Test
    void rejectsBadInput()
    {
        CondProperties p = new CondProperties();
        Map<String, CondField> fields = CondFixtures.catalog().fields();
        CondRuleBody b = body();
        b.setName("名".repeat(31));
        b.setWindow("24h");
        b.setDurationCount(3);
        b.setMuteHours(3);
        b.setNotifyChatIds(List.of());
        b.setConditions(List.of(
            c("field", "reg", "scope", c("pay", "upi"), "how", "BASE", "op", "GT", "threshold", 50),
            c("field", "wPendN", "how", "BASE", "op", "UP", "threshold", -1),
            c("field", "ftdr", "how", "CUR", "op", "OUT", "threshold", List.of(8, 3)),
            c("field", "dep", "scope", c("ch", "x:1"), "how", "CUR", "op", "GT", "threshold", 1, "minSample", 10),
            c("field", "nope", "how", "CUR", "op", "GT", "threshold", 1),
            c("field", "reg", "how", "CUR", "op", "GT", "threshold", 1)));
        Map<String, String> e = new HashMap<>();
        CondRules.check(b, fields, LABELS, p, e);
        assertTrue(e.containsKey("name"));
        assertTrue(e.get("durationCount").contains("1–2"), "24h 最多连续 2 次");
        assertTrue(e.containsKey("muteHours"));
        assertTrue(e.containsKey("notifyChatIds"));
        assertTrue(e.containsKey("conditions"), "最多 5 个条件");
        assertTrue(e.get("conditions[0].scope").contains("不能按支付通道"));
        assertTrue(e.containsKey("conditions[0].op"), "较基准只能 UP / DOWN / CHG");
        assertTrue(e.get("conditions[1].how").contains("快照"));
        assertTrue(e.containsKey("conditions[2].threshold"), "下限要小于上限");
        assertTrue(e.containsKey("conditions[3].scope"));
        assertTrue(e.containsKey("conditions[3].minSample"), "存款总额没有样本口径");
        assertTrue(e.containsKey("conditions[4].field"));
    }
}
