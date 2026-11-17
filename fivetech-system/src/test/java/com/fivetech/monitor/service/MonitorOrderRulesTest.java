package com.fivetech.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fivetech.monitor.domain.MonitorOrderRuleFilter;
import com.fivetech.monitor.domain.MonitorRule;
import com.fivetech.monitor.domain.OrderRuleConfig;
import com.fivetech.monitor.domain.OrderRuleOpenQuery;
import com.fivetech.monitor.domain.dto.MonitorOrderRuleBody;
import com.fivetech.promo.budget.PromoChannelDictionary.Channel;
import com.fivetech.promo.budget.PromoChannelDictionary.Promoter;

/** 订单规则的校验、详情组装与对外查询参数（设计文档 5.1、5.3；纯函数，不连库） */
class MonitorOrderRulesTest
{
    private static final List<Promoter> DICT = List.of(
            new Promoter("1001", "ROI 推廣", List.of(new Channel("tg-roi", "Telegram ROI"), new Channel("fb-roi", "FB ROI"))),
            new Promoter("1002", "自然流量", List.of()));

    private static MonitorOrderRuleBody body()
    {
        MonitorOrderRuleBody b = new MonitorOrderRuleBody();
        b.setName(" 大額提款提醒 ");
        b.setDescription("  ");
        b.setOwnerUserId(7L);
        b.setTriggerAt("submit");
        b.setAmountMode("SINGLE");
        b.setAmountThreshold(new BigDecimal("50000.00"));
        b.setChannelScope(" c:1001|tg-roi ");
        b.setLevel("crit");
        b.setNotifyChatId(" oc_risk ");
        return b;
    }

    @Test
    void validBodyIsNormalized()
    {
        Map<String, String> errors = new LinkedHashMap<>();
        MonitorOrderRules.Checked c = MonitorOrderRules.check(body(), DICT, null, errors);
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals("大額提款提醒", c.name);
        assertNull(c.description, "空白说明存 null");
        assertEquals("CRIT", c.level);
        assertTrue(c.notifyEnabled, "不传 = 开启通知");
        assertEquals(List.of("oc_risk"), c.notifyChatIds, "旧字段 notifyChatId 也接受，去掉空白");
        OrderRuleConfig cfg = c.config;
        assertEquals("WITHDRAW", cfg.getOrderType(), "不传订单类型 = 提款");
        assertEquals("SUBMIT", cfg.getTriggerAt());
        assertEquals("SINGLE", cfg.getAmountMode());
        assertEquals("50000", cfg.getAmountThreshold().toPlainString(), "去掉多余的 0");
        assertEquals(50, cfg.getDailyCap(), "每日上限默认 50");
        assertEquals("CHANNEL", cfg.getChannelScope().getType());
        assertEquals("ROI 推廣", cfg.getChannelScope().getPromoterName());
        assertEquals("Telegram ROI", cfg.getChannelScope().getChannelName());
        assertEquals("c:1001|tg-roi", MonitorOrderRules.channelCode(cfg.getChannelScope()));

        MonitorOrderRuleBody g = body();
        g.setChannelScope("G:1002");
        assertEquals("PROMOTER", MonitorOrderRules.check(g, DICT, null, errors).config.getChannelScope().getType());
        g.setChannelScope("");
        assertNull(MonitorOrderRules.check(g, DICT, null, errors).config.getChannelScope(), "空 = 全站");
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void invalidFieldsAreReportedPerField()
    {
        Map<String, String> errors = new LinkedHashMap<>();
        MonitorOrderRules.check(new MonitorOrderRuleBody(), DICT, null, errors);
        assertEquals(List.of("name", "triggerAt", "amountMode", "amountThreshold", "level", "notifyChatIds", "notifyChatId"),
                List.copyOf(errors.keySet()), "必填项");

        assertEquals("規則名稱最多 30 字", err(b -> b.setName("規".repeat(31)), "name"));
        assertNull(err(b -> b.setName("規".repeat(30)), "name"));
        assertEquals("說明最多 200 字", err(b -> b.setDescription("x".repeat(201)), "description"));
        assertEquals("訂單類型本期只支持提款（WITHDRAW）", err(b -> b.setOrderType("DEPOSIT"), "orderType"));
        assertEquals("金額門檻須大於 0", err(b -> b.setAmountThreshold(BigDecimal.ZERO), "amountThreshold"));
        assertEquals("金額門檻最多 100,000,000", err(b -> b.setAmountThreshold(new BigDecimal("100000000.01")), "amountThreshold"));
        assertNull(err(b -> b.setAmountThreshold(new BigDecimal("100000000")), "amountThreshold"));
        assertEquals("金額門檻最多 2 位小數", err(b -> b.setAmountThreshold(new BigDecimal("1.005")), "amountThreshold"));
        assertEquals("每日上限只能選 10、20、50、100 或不限（0）", err(b -> b.setDailyCap(30), "dailyCap"));
        assertNull(err(b -> b.setDailyCap(0), "dailyCap"), "0 = 不限");
        assertTrue(err(b -> b.setChannelScope("x:1001"), "channelScope").startsWith("渠道範圍格式不對"));
        assertTrue(err(b -> b.setChannelScope("c:1001|"), "channelScope").startsWith("渠道範圍格式不對"));
        assertEquals("推廣商不存在，請重新選擇", err(b -> b.setChannelScope("g:9999"), "channelScope"));
        assertEquals("推廣渠道不存在，請重新選擇", err(b -> b.setChannelScope("c:1001|nope"), "channelScope"));
        assertNull(err(b -> {
            b.setNotifyEnabled(false);
            b.setNotifyChatId(null);
        }, "notifyChatId"), "关闭通知时群可以不填");
    }

    @Test
    void notifyChatsAreMultiSelect()
    {
        Map<String, String> errors = new LinkedHashMap<>();
        MonitorOrderRuleBody b = body();
        b.setNotifyChatIds(List.of(" oc_a ", "oc_b", "oc_a", ""));
        assertEquals(List.of("oc_a", "oc_b"), MonitorOrderRules.check(b, DICT, null, errors).notifyChatIds, "数组优先，去空白和重复");
        assertTrue(errors.isEmpty());
        assertEquals("通知群最多 5 個", err(x -> x.setNotifyChatIds(List.of("a", "b", "c", "d", "e", "f")), "notifyChatIds"));
        assertNull(err(x -> x.setNotifyChatIds(List.of("a", "b", "c", "d", "e")), "notifyChatIds"));
        assertEquals("開啟通知時請選擇通知群", err(x -> x.setNotifyChatIds(List.of()), "notifyChatIds"), "空数组 = 没选，不回退到旧字段");
    }

    @Test
    void editKeepsUnchangedChannelEvenIfDictionaryDroppedIt()
    {
        Map<String, String> errors = new LinkedHashMap<>();
        OrderRuleConfig existing = MonitorOrderRules.check(body(), DICT, null, errors).config;
        MonitorOrderRules.Checked c = MonitorOrderRules.check(body(), List.of(), existing, errors);
        assertTrue(errors.isEmpty(), errors.toString());
        assertSame(existing.getChannelScope(), c.config.getChannelScope(), "范围没改：保留原来的名称");

        MonitorOrderRuleBody changed = body();
        changed.setChannelScope("c:1001|fb-roi");
        MonitorOrderRules.check(changed, List.of(), existing, errors);
        assertEquals("推廣商不存在，請重新選擇", errors.get("channelScope"), "改了范围要按当前字典校验");
    }

    @Test
    void summaryAndMoney()
    {
        OrderRuleConfig cfg = MonitorOrderRules.check(body(), DICT, null, new LinkedHashMap<>()).config;
        assertEquals("［ROI 推廣 › Telegram ROI］單筆提款 ≥ ₹50,000（提交時）", MonitorOrderRules.summary(cfg, "₹"));
        cfg.setTriggerAt("PAID");
        cfg.setAmountMode("DAILY_TOTAL");
        cfg.setAmountThreshold(new BigDecimal("1234.5"));
        cfg.setChannelScope(null);
        assertEquals("帳號當日累計提款 ≥ ₹1,234.5（出款成功時）", MonitorOrderRules.summary(cfg, "₹"), "不限範圍時不寫範圍");
    }

    private static MonitorRule rule(OrderRuleConfig cfg)
    {
        MonitorRule r = new MonitorRule();
        r.setRuleId(1023L);
        r.setRuleNo(23);
        r.setSiteCode("SITE-IN");
        r.setRuleName("大額提款提醒");
        r.setLevel("CRIT");
        r.setEnabled(true);
        r.setOwnerUserId(7L);
        r.setOwnerEmail("owner@example.com");
        r.setNotifyEnabled(true);
        r.setNotifyChatId("oc_risk");
        r.setNotifyChatName("風控群（舊名）");
        r.setChatName("風控告警群");
        r.setChatStatus("ok");
        r.setCurrency("INR");
        r.setVersion(37L);
        r.setUpdateBy("ops");
        r.setUpdateByEmail("ops@example.com");
        r.setUpdateTime(LocalDateTime.of(2026, 11, 13, 10, 0, 0));
        return r;
    }

    private static MonitorOrderRules.Site site()
    {
        MonitorOrderRules.Site s = new MonitorOrderRules.Site();
        s.siteCode = "SITE-IN";
        s.siteName = "uWin";
        s.timezone = "Asia/Kolkata";
        s.currency = "INR";
        s.currencySymbol = "₹";
        s.toSiteTime = t -> t.minusMinutes(150);
        return s;
    }

    @Test
    @SuppressWarnings("unchecked")
    void viewMatchesTheDesignedFields()
    {
        OrderRuleConfig cfg = MonitorOrderRules.check(body(), DICT, null, new LinkedHashMap<>()).config;
        cfg.setAmountMode("DAILY_TOTAL");
        Map<String, Object> v = MonitorOrderRules.view(rule(cfg), cfg, site());
        assertEquals(1023L, v.get("ruleId"));
        assertEquals("R-023", v.get("ruleNo"));
        assertEquals("uWin", v.get("siteName"));
        assertEquals(true, v.get("enabled"));

        Map<String, Object> metric = (Map<String, Object>) v.get("metric");
        assertEquals("WITHDRAW_AMOUNT_DAILY_TOTAL", metric.get("code"));
        assertEquals("₹", metric.get("unit"));
        assertEquals("INR", metric.get("currency"));

        Map<String, Object> th = (Map<String, Object>) v.get("threshold");
        assertEquals("GE", th.get("op"));
        assertEquals(0, new BigDecimal("50000").compareTo((BigDecimal) th.get("value")));

        Map<String, Object> trigger = (Map<String, Object>) v.get("trigger");
        assertEquals("WITHDRAW", trigger.get("orderType"));
        assertEquals("SUBMIT", trigger.get("triggerAt"));
        Map<String, Object> scope = (Map<String, Object>) trigger.get("channelScope");
        assertEquals("CHANNEL", scope.get("type"));
        assertEquals("1001", scope.get("promoterId"));
        assertEquals("tg-roi", scope.get("channelId"));
        Map<String, Object> daily = (Map<String, Object>) trigger.get("dailyTotalRule");
        assertEquals(List.of("PAID", "PENDING"), daily.get("includeStatuses"), "提交时：已出款 + 审核中");
        assertEquals(List.of("REJECTED"), daily.get("excludeStatuses"));
        assertEquals("Asia/Kolkata", daily.get("dayResetTimezone"));
        assertEquals(true, daily.get("onePerAccountPerDay"));
        assertEquals("［ROI 推廣 › Telegram ROI］帳號當日累計提款 ≥ ₹50,000（提交時）", trigger.get("summary"));

        Map<String, Object> notify = (Map<String, Object>) v.get("notify");
        assertEquals("LARK", notify.get("platform"));
        assertEquals("風控告警群", notify.get("chatName"), "群名取群列表缓存里的最新名称");
        assertEquals("NORMAL", notify.get("chatStatus"));
        List<Map<String, Object>> chats = (List<Map<String, Object>>) notify.get("chats");
        assertEquals(1, chats.size(), "notify_chats 为空的旧数据按 notify_chat_id 读成一个群");
        assertEquals("風控告警群", chats.get(0).get("chatName"));
        assertEquals(60, notify.get("mergeSeconds"));
        assertEquals(50, notify.get("dailyCap"));
        assertEquals("【uWin】", notify.get("titlePrefix"));

        Map<String, Object> owner = (Map<String, Object>) v.get("owner");
        assertEquals("owner@example.com", owner.get("name"), "没有昵称时用邮箱");
        assertEquals(false, owner.get("valid"), "库里没查到有效标志按无效（同条件规则详情的 owner.valid）");
        assertEquals(false, v.get("currencyChanged"));
        assertEquals(37L, v.get("version"));
        assertEquals("2026-11-13 07:30:00", v.get("updateTime"), "时间换成站点时区");
        assertEquals("ops@example.com", v.get("updateBy"));
        assertFalse(v.containsKey("config"), "不直接返回库里的 jsonb");
    }

    @Test
    @SuppressWarnings("unchecked")
    void viewForSingleWholeSiteRuleAndDeadChat()
    {
        OrderRuleConfig cfg = MonitorOrderRules.check(body(), DICT, null, new LinkedHashMap<>()).config;
        cfg.setTriggerAt("PAID");
        cfg.setChannelScope(null);
        MonitorRule r = rule(cfg);
        r.setChatStatus("dead");
        r.setCurrency("CNY");
        Map<String, Object> v = MonitorOrderRules.view(r, cfg, site());
        Map<String, Object> trigger = (Map<String, Object>) v.get("trigger");
        assertNull(trigger.get("channelScope"), "全站为 null");
        assertFalse(trigger.containsKey("dailyTotalRule"), "单笔规则不返回当日累计口径");
        assertEquals("BOT_REMOVED", ((Map<String, Object>) v.get("notify")).get("chatStatus"));

        r.setNotifyChats("[{\"chatId\":\"oc_a\",\"name\":\"A 群\",\"status\":\"ok\"},{\"chatId\":\"oc_b\",\"name\":\"B 群\",\"status\":\"dead\"}]");
        Map<String, Object> multi = (Map<String, Object>) MonitorOrderRules.view(r, cfg, site()).get("notify");
        List<Map<String, Object>> chats = (List<Map<String, Object>>) multi.get("chats");
        assertEquals(List.of("oc_a", "oc_b"), chats.stream().map(c -> c.get("chatId")).toList(), "按选择顺序");
        assertEquals("BOT_REMOVED", chats.get(1).get("chatStatus"));
        assertEquals("oc_a", multi.get("chatId"), "旧字段为第一个群");
        assertEquals("NORMAL", multi.get("chatStatus"));
        assertEquals(true, v.get("currencyChanged"), "站点币种改了：提示复核门槛");
        assertEquals("CNY", ((Map<String, Object>) v.get("metric")).get("unit"), "币种不同时单位写币种代码");

        Map<String, Object> paid = MonitorOrderRules.dailyTotalRule("PAID", "Asia/Kolkata");
        assertEquals(List.of("PAID"), paid.get("includeStatuses"), "出款成功时：只算已出款");
    }

    @Test
    void openQueryFiltersAreParsed()
    {
        OrderRuleOpenQuery q = new OrderRuleOpenQuery();
        q.setRuleIds("1023, 1024,1023");
        q.setRuleNos("R-023,r24");
        q.setKeyword(" 大額 ");
        q.setEnabled("TRUE");
        q.setMetric("withdraw_amount_daily_total");
        q.setTriggerAt("SUBMIT,PAID");
        q.setLevel("crit");
        q.setChannel("c:1001|tg-roi");
        q.setChatId("oc_a,oc_b");
        q.setOwnerEmail(" Owner@Example.com ");
        q.setAmountMin("10000");
        q.setAmountMax("50000.5");
        q.setSinceVersion("36");
        q.setUpdatedFrom("2026-11-13 00:00:00");
        q.setUpdatedTo("2026-11-14");
        q.setOrderBy("UPDATETIME");
        q.setPageSize("500");
        Map<String, String> errors = new LinkedHashMap<>();
        MonitorOrderRuleFilter f = MonitorOrderRules.filter(q, t -> t.plusMinutes(150), errors);
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(List.of(1023L, 1024L), f.ruleIds, "去重");
        assertEquals(List.of(23, 24), f.ruleNos);
        assertEquals("大額", f.keyword);
        assertEquals(Boolean.TRUE, f.enabled);
        assertEquals(List.of("DAILY_TOTAL"), f.amountModes, "指标编码换成金额口径");
        assertEquals(List.of("SUBMIT", "PAID"), f.triggerAts);
        assertEquals(List.of("CRIT"), f.levels);
        assertEquals("CHANNEL", f.channelMode);
        assertEquals("1001", f.promoterId);
        assertEquals("tg-roi", f.channelId);
        assertEquals(List.of("oc_a", "oc_b"), f.chatIds);
        assertEquals("owner@example.com", f.ownerEmail);
        assertEquals(36L, f.sinceVersion);
        assertEquals(LocalDateTime.of(2026, 11, 13, 2, 30), f.updatedFrom, "站点时区换成统计时区");
        assertEquals(LocalDateTime.of(2026, 11, 14, 2, 30), f.updatedTo, "只给日期 = 当天 0 点");
        assertEquals("updateTime", f.orderBy);
        assertEquals(1, f.pageNum);
        assertEquals(500, f.pageSize);

        MonitorOrderRuleFilter none = MonitorOrderRules.filter(new OrderRuleOpenQuery(), t -> t, errors);
        assertEquals("version", none.orderBy, "默认按 version 升序");
        assertEquals(100, none.pageSize);
        assertNull(none.channelMode);
        OrderRuleOpenQuery all = new OrderRuleOpenQuery();
        all.setChannel("all");
        assertEquals("ALL", MonitorOrderRules.filter(all, t -> t, errors).channelMode);
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void badOpenQueryParamsAreReportedPerField()
    {
        OrderRuleOpenQuery q = new OrderRuleOpenQuery();
        q.setRuleIds("1,x");
        q.setRuleNos("23");
        q.setEnabled("yes");
        q.setOrderType("DEPOSIT");
        q.setMetric("GGR");
        q.setChannel("1001");
        q.setAmountMin("100");
        q.setAmountMax("1");
        q.setSinceVersion("-1");
        q.setUpdatedFrom("2026/11/13");
        q.setOrderBy("name");
        q.setPageNum("0");
        q.setPageSize("501");
        Map<String, String> errors = new LinkedHashMap<>();
        MonitorOrderRules.filter(q, t -> t, errors);
        assertEquals(List.of("ruleIds", "ruleNos", "enabled", "orderType", "metric", "channel", "amountMin",
                "sinceVersion", "updatedFrom", "orderBy", "pageNum", "pageSize"), List.copyOf(errors.keySet()));

        OrderRuleOpenQuery range = new OrderRuleOpenQuery();
        range.setUpdatedFrom("2026-11-14 00:00:00");
        range.setUpdatedTo("2026-11-13 00:00:00");
        errors.clear();
        MonitorOrderRules.filter(range, t -> t, errors);
        assertEquals("updatedFrom 須早於 updatedTo", errors.get("updatedFrom"));
    }

    @Test
    void ruleRefs()
    {
        assertEquals(23L, MonitorOrderRules.ruleIdOf(" 23 "));
        assertNull(MonitorOrderRules.ruleIdOf("R-023"));
        assertEquals(23, MonitorOrderRules.ruleNoOf("R-023"));
        assertEquals(23, MonitorOrderRules.ruleNoOf("r23"));
        assertNull(MonitorOrderRules.ruleNoOf("23"));
        assertNull(MonitorOrderRules.ruleNoOf("X-1"));
        assertEquals("R-007", MonitorOrderRules.ruleCode(7));
        assertEquals("R-1234", MonitorOrderRules.ruleCode(1234));
    }

    private interface Edit
    {
        void apply(MonitorOrderRuleBody b);
    }

    private static String err(Edit edit, String field)
    {
        MonitorOrderRuleBody b = body();
        edit.apply(b);
        Map<String, String> errors = new LinkedHashMap<>();
        MonitorOrderRules.check(b, DICT, null, errors);
        return errors.get(field);
    }
}
