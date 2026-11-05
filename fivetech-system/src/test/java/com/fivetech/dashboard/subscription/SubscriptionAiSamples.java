package com.fivetech.dashboard.subscription;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import com.fivetech.dashboard.domain.vo.MetricAlertVO;
import com.fivetech.dashboard.domain.vo.MetricCardVO;
import com.fivetech.dashboard.domain.vo.MetricsBlockVO;
import com.fivetech.dashboard.domain.vo.OverviewCompareVO;
import com.fivetech.dashboard.domain.vo.OverviewSlotVO;
import com.fivetech.dashboard.domain.vo.OverviewVO;
import com.fivetech.dashboard.enums.Granularity;

/**
 * AI 摘要单测用的示例简报：昨日、8 个指标（本期数字参考 SITE-IN 10-20 的 UDS 联调样例，上期为构造值），
 * 存款成功率带一条严重告警。
 */
final class SubscriptionAiSamples
{
    private SubscriptionAiSamples()
    {
    }

    static MetricCardVO card(String code, String label, String fmt, String value, String prev, String pct, String pt, String dir)
    {
        MetricCardVO c = new MetricCardVO();
        c.setCode(code);
        c.setLabel(label);
        c.setValueFormat(fmt);
        c.setValue(new BigDecimal(value));
        c.setPrevValue(new BigDecimal(prev));
        c.setDeltaPct(pct == null ? null : new BigDecimal(pct));
        c.setDeltaPt(pt == null ? null : new BigDecimal(pt));
        c.setDirection(dir);
        return c;
    }

    static DashboardSubscription subscription()
    {
        DashboardSubscription s = new DashboardSubscription();
        s.setId(1L);
        s.setSiteCode("SITE-IN");
        s.setName("經營日報");
        s.setRangeCode("YESTERDAY");
        s.setAiEnabled(true);
        return s;
    }

    static SubscriptionBrief brief()
    {
        List<MetricCardVO> cards = new ArrayList<>();
        cards.add(card("reg", "註冊人數", "INT", "777", "702", "10.7", null, "up"));
        cards.add(card("ftd", "首存人數", "INT", "56", "71", "-21.1", null, "up"));
        cards.add(card("dep", "存款總額", "MONEY", "36386.99", "41210.50", "-11.7", null, "up"));
        cards.add(card("wd", "提款總額", "MONEY", "2290.41", "2105.00", "8.8", null, "flat"));
        MetricCardVO ok = card("dOkR", "存款成功率", "PCT", "81.33", "93.10", null, "-11.77", "up");
        MetricAlertVO alert = new MetricAlertVO();
        alert.setLevel("CRIT");
        alert.setReasonLine("低於嚴重線 85.00%");
        alert.setReasonValue("近 2 小時 81.33–84.20%");
        ok.setAlert(alert);
        cards.add(ok);
        cards.add(card("bet", "投注總額", "MONEY", "250451.29", "238900.00", "4.8", null, "up"));
        cards.add(card("ggr", "GGR", "MONEY", "37984.07", "29870.40", "27.2", null, "up"));
        cards.add(card("bonus", "發放贈金總額", "MONEY", "11449.27", "9830.00", "16.5", null, "flat"));

        OverviewVO vo = new OverviewVO();
        OverviewSlotVO slot = new OverviewSlotVO();
        slot.setFrom("2026-10-20 00:00");
        slot.setTo("2026-10-21 00:00");
        slot.setGranularity(Granularity.HOUR);
        vo.setSlot(slot);
        OverviewCompareVO cmp = new OverviewCompareVO();
        cmp.setFrom("2026-10-19 00:00");
        cmp.setTo("2026-10-20 00:00");
        vo.setCompare(cmp);
        vo.setAsOf("2026-10-21 09:00");
        MetricsBlockVO block = new MetricsBlockVO();
        block.setItems(cards);
        vo.getBlocks().put("METRICS", block);

        SubscriptionBrief.Context ctx = new SubscriptionBrief.Context();
        ctx.siteName = "uWin";
        ctx.name = "經營日報";
        ctx.currencySymbol = "₹";
        ctx.timezone = "Asia/Kolkata";
        ctx.sendHour = 9;
        List<String> codes = new ArrayList<>();
        cards.forEach(c -> codes.add(c.getCode()));
        return SubscriptionBrief.build(vo, codes, ctx);
    }
}
