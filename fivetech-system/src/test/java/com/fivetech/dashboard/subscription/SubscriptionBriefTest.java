package com.fivetech.dashboard.subscription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.domain.vo.MetricCardVO;
import com.fivetech.dashboard.domain.vo.MetricsBlockVO;
import com.fivetech.dashboard.domain.vo.OverviewCompareVO;
import com.fivetech.dashboard.domain.vo.OverviewSlotVO;
import com.fivetech.dashboard.domain.vo.OverviewVO;
import com.fivetech.dashboard.enums.Granularity;
import com.fivetech.dashboard.format.ValueFormat;

/** 订阅简报：统计范围、到期判断、数值格式、环比着色、卡片结构（纯函数，不连库不调接口） */
class SubscriptionBriefTest
{
    private static MetricCardVO card(String code, String label, String fmt, String value, String pct, String pt, String dir)
    {
        MetricCardVO c = new MetricCardVO();
        c.setCode(code);
        c.setLabel(label);
        c.setValueFormat(fmt);
        c.setValue(value == null ? null : new BigDecimal(value));
        c.setDeltaPct(pct == null ? null : new BigDecimal(pct));
        c.setDeltaPt(pt == null ? null : new BigDecimal(pt));
        c.setDirection(dir);
        return c;
    }

    private static OverviewVO vo(Granularity g, String from, String to, String cmpFrom, String cmpTo, MetricCardVO... cards)
    {
        OverviewVO vo = new OverviewVO();
        OverviewSlotVO slot = new OverviewSlotVO();
        slot.setFrom(from);
        slot.setTo(to);
        slot.setGranularity(g);
        vo.setSlot(slot);
        OverviewCompareVO cmp = new OverviewCompareVO();
        cmp.setFrom(cmpFrom);
        cmp.setTo(cmpTo);
        vo.setCompare(cmp);
        vo.setAsOf("2026-11-01 09:00");
        MetricsBlockVO block = new MetricsBlockVO();
        block.setItems(new ArrayList<>(List.of(cards)));
        vo.getBlocks().put("METRICS", block);
        return vo;
    }

    private static SubscriptionBrief.Context ctx(boolean test, String url)
    {
        SubscriptionBrief.Context c = new SubscriptionBrief.Context();
        c.siteName = "uWin";
        c.name = "經營日報";
        c.description = "晨會用 *重點*";
        c.currencySymbol = "₹";
        c.timezone = "Asia/Kolkata";
        c.sendHour = 9;
        c.overviewUrl = url;
        c.test = test;
        return c;
    }

    // ---------------- 统计范围 ----------------

    @Test
    void rangeWindows()
    {
        LocalDate d = LocalDate.of(2026, 11, 1);
        assertEquals(LocalDateTime.of(2026, 11, 1, 0, 0), SubscriptionRange.TODAY.window(d, 9)[0]);
        assertEquals(LocalDateTime.of(2026, 11, 1, 9, 0), SubscriptionRange.TODAY.window(d, 9)[1]);
        assertEquals(LocalDateTime.of(2026, 10, 31, 0, 0), SubscriptionRange.YESTERDAY.window(d, 9)[0]);
        assertEquals(LocalDateTime.of(2026, 11, 1, 0, 0), SubscriptionRange.YESTERDAY.window(d, 9)[1]);
        assertEquals(LocalDateTime.of(2026, 10, 25, 0, 0), SubscriptionRange.LAST_7D.window(d, 9)[0]);
        assertEquals(LocalDateTime.of(2026, 11, 1, 0, 0), SubscriptionRange.LAST_7D.window(d, 9)[1]);
        assertEquals(LocalDateTime.of(2026, 10, 2, 0, 0), SubscriptionRange.LAST_30D.window(d, 9)[0]);
        assertEquals(Granularity.HOUR, SubscriptionRange.YESTERDAY.getGranularity());
        assertEquals(Granularity.DAY, SubscriptionRange.LAST_7D.getGranularity());
        assertEquals("dashboard_overview_day_v2", SubscriptionRange.LAST_30D.getDataset());
        assertEquals(SubscriptionRange.LAST_7D, SubscriptionRange.of(" last_7d "));
        assertNull(SubscriptionRange.of("WEEK"));
    }

    // ---------------- 到期判断 ----------------

    @Test
    void dueRespectsDelayGraceAndLastSlot()
    {
        LocalDateTime nine = LocalDateTime.of(2026, 11, 1, 9, 0);
        // 整点后不到 10 分钟：不发
        assertNull(SubscriptionScheduler.due(nine.plusMinutes(5), 9, 10, 3, null));
        // 过了等待分钟：发今天 09:00
        assertEquals(nine, SubscriptionScheduler.due(nine.plusMinutes(10), 9, 10, 3, null));
        // 已经发过：不发
        assertNull(SubscriptionScheduler.due(nine.plusMinutes(30), 9, 10, 3, "2026-11-01 09:00"));
        // 昨天发过，今天照发
        assertEquals(nine, SubscriptionScheduler.due(nine.plusMinutes(30), 9, 10, 3, "2026-10-31 09:00"));
        // 过了补发期：不再补
        assertNull(SubscriptionScheduler.due(nine.plusHours(3), 9, 10, 3, null));
        // 还没到今天的时刻：不发（昨天的早过了补发期）
        assertNull(SubscriptionScheduler.due(nine.minusHours(2), 9, 10, 3, null));
        // 23:00 的订阅在次日 00:30 仍在补发期内：补发昨天 23:00
        assertEquals(LocalDateTime.of(2026, 10, 31, 23, 0),
            SubscriptionScheduler.due(LocalDateTime.of(2026, 11, 1, 0, 30), 23, 10, 3, "2026-10-30 23:00"));
    }

    // ---------------- 数值与环比 ----------------

    @Test
    void valueFormats()
    {
        assertEquals("1,234,567", SubscriptionBrief.formatValue(new BigDecimal("1234567"), ValueFormat.INT, null, "₹"));
        assertEquals("₹1,234,568", SubscriptionBrief.formatValue(new BigDecimal("1234567.6"), ValueFormat.MONEY, null, "₹"));
        assertEquals("−₹500", SubscriptionBrief.formatValue(new BigDecimal("-500"), ValueFormat.MONEY, null, "₹"));
        assertEquals("95.20%", SubscriptionBrief.formatValue(new BigDecimal("95.2"), ValueFormat.PCT, null, "₹"));
        assertEquals("8.4 min", SubscriptionBrief.formatValue(new BigDecimal("8.43"), ValueFormat.MIN, null, "₹"));
        assertEquals("3.10×", SubscriptionBrief.formatValue(new BigDecimal("3.1"), ValueFormat.MULTIPLE, null, "₹"));
        assertEquals("1,234.5", SubscriptionBrief.formatValue(new BigDecimal("1234.5"), ValueFormat.INT, 1, "₹"));
        assertEquals("—", SubscriptionBrief.formatValue(null, ValueFormat.INT, null, "₹"));
    }

    @Test
    void changeAndTrendFollowDirection()
    {
        MetricCardVO reg = card("reg", "註冊", "INT", "100", "12.3", null, "up");
        assertEquals("+12.3%", SubscriptionBrief.formatChange(reg, ValueFormat.INT));
        assertEquals("good", SubscriptionBrief.trend(reg, ValueFormat.INT));
        MetricCardVO dT = card("dT", "平均到帳時間", "MIN", "8", "5.0", null, "down");
        assertEquals("+5%", SubscriptionBrief.formatChange(dT, ValueFormat.MIN));
        assertEquals("bad", SubscriptionBrief.trend(dT, ValueFormat.MIN));
        // 百分比类写百分点
        MetricCardVO dOkR = card("dOkR", "存款成功率", "PCT", "95", null, "-1.52", "up");
        assertEquals("−1.52pt", SubscriptionBrief.formatChange(dOkR, ValueFormat.PCT));
        assertEquals("bad", SubscriptionBrief.trend(dOkR, ValueFormat.PCT));
        // 区间型、无对比值
        assertEquals("flat", SubscriptionBrief.trend(card("killR", "殺率", "PCT", "4", null, "1", "range"), ValueFormat.PCT));
        assertEquals("—", SubscriptionBrief.formatChange(card("x", "x", "INT", "1", null, null, "up"), ValueFormat.INT));
    }

    @Test
    void spanText()
    {
        assertEquals("11-01 00:00 – 11-01 09:00", SubscriptionBrief.span("2026-11-01 00:00", "2026-11-01 09:00", Granularity.HOUR));
        assertEquals("10-25 – 10-31", SubscriptionBrief.span("2026-10-25 00:00", "2026-11-01 00:00", Granularity.DAY));
        assertEquals("10-31", SubscriptionBrief.span("2026-10-31 00:00", "2026-11-01 00:00", Granularity.DAY));
    }

    // ---------------- 卡片 ----------------

    @Test
    @SuppressWarnings("unchecked")
    void cardFollowsGivenOrderAndSkipsMissing()
    {
        OverviewVO vo = vo(Granularity.HOUR, "2026-10-31 00:00", "2026-11-01 00:00", "2026-10-30 00:00", "2026-10-31 00:00",
            card("reg", "註冊人數", "INT", "1200", "10", null, "up"),
            card("dep", "存款金額", "MONEY", "50000", "-20", null, "up"),
            card("dT", "平均到帳時間", "MIN", "8", "-5", null, "down"));
        SubscriptionBrief b = SubscriptionBrief.build(vo, List.of("dT", "dep", "nope", "reg"), ctx(false, "https://yata.example/overview"));
        List<String> order = new ArrayList<>();
        b.getRows().forEach(r -> order.add((String) r.get("code")));
        assertEquals(List.of("dT", "dep", "reg"), order);
        assertEquals("₹50,000", b.getRows().get(1).get("value"));
        assertEquals("good", b.getRows().get(0).get("trend"));      // 到账时间降了是好事
        assertEquals("bad", b.getRows().get(1).get("trend"));
        assertEquals("uWin · 經營日報", b.getTitle());
        assertEquals("10-31 00:00 – 11-01 00:00", b.getPeriod());

        Map<String, Object> card = b.getCard();
        Map<String, Object> header = (Map<String, Object>) card.get("header");
        assertEquals("blue", header.get("template"));
        List<Map<String, Object>> el = (List<Map<String, Object>>) card.get("elements");
        // 说明里的 * 被转义
        String desc = (String) ((Map<String, Object>) el.get(0).get("text")).get("content");
        assertTrue(desc.contains("\\*重點\\*"));
        String all = el.toString();
        assertTrue(all.contains("<font color='green'>−5%</font>"));
        assertTrue(all.contains("<font color='red'>−20%</font>"));
        assertTrue(all.contains("資料截至 11-01 09:00 · 每日 09:00 發送 · Asia/Kolkata"));
        assertTrue(all.contains("在 Yata 中查看"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void snapshotImageGoesBeforeFooterAndFailureOnlyShowsNote()
    {
        OverviewVO vo = vo(Granularity.HOUR, "2026-10-31 00:00", "2026-11-01 00:00", "2026-10-30 00:00", "2026-10-31 00:00",
            card("reg", "註冊人數", "INT", "1200", "10", null, "up"));
        SubscriptionBrief b = SubscriptionBrief.build(vo, List.of("reg"), ctx(false, ""));
        b.applySnapshot("img_v3_abc", null, "https://x/zh-TW/overview?range=today", null);
        List<Map<String, Object>> el = (List<Map<String, Object>>) b.getCard().get("elements");
        Map<String, Object> img = el.stream().filter(e -> "img".equals(e.get("tag"))).findFirst().orElseThrow();
        assertEquals("img_v3_abc", img.get("img_key"));
        assertEquals("fit_horizontal", img.get("mode"));
        // 图在底注前面
        int imgAt = el.indexOf(img);
        assertTrue(el.get(el.size() - 1).toString().contains("資料截至") && imgAt < el.size() - 1);
        Map<String, Object> preview = b.toPreview();
        assertEquals(null, ((Map<String, Object>) preview.get("snapshot")).get("error"));

        b.applySnapshot(null, null, "https://x/zh-TW/overview", "等待頁面超時");
        String all = b.getCard().get("elements").toString();
        assertFalse(all.contains("img_key"));
        assertTrue(all.contains("運營總覽截圖生成失敗"));
        // 原因只在预览里，不进卡片
        assertFalse(all.contains("等待頁面超時"));
        assertEquals("等待頁面超時", ((Map<String, Object>) b.toPreview().get("snapshot")).get("error"));

        b.applySnapshot(null, new byte[] { 1, 2, 3 }, "u", null);
        assertEquals("data:image/png;base64,AQID", ((Map<String, Object>) b.toPreview().get("snapshot")).get("image"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testCardIsGreyAndNoButtonWithoutUrl()
    {
        MetricCardVO active = card("active", "活躍人數", "INT", null, null, null, "up");
        active.setNoValueTitle("僅支援單日");
        OverviewVO vo = vo(Granularity.DAY, "2026-10-25 00:00", "2026-11-01 00:00", "2026-10-18 00:00", "2026-10-25 00:00", active);
        SubscriptionBrief b = SubscriptionBrief.build(vo, List.of("active"), ctx(true, ""));
        assertEquals("【測試】uWin · 經營日報", b.getTitle());
        assertEquals("grey", ((Map<String, Object>) b.getCard().get("header")).get("template"));
        assertFalse(b.getCard().get("elements").toString().contains("在 Yata 中查看"));
        assertEquals("10-25 – 10-31", b.getPeriod());
        assertEquals("—", b.getRows().get(0).get("value"));
        assertEquals("僅支援單日", b.getRows().get(0).get("change"));
        assertEquals("10-18 – 10-24", b.toPreview().get("compare"));
    }
}
