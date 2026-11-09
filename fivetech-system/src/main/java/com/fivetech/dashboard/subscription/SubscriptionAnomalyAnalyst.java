package com.fivetech.dashboard.subscription;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import com.fivetech.copilot.CopilotGateway;
import com.fivetech.copilot.CopilotProperties;
import com.fivetech.monitor.domain.vo.MonitorEventVO;

/**
 * 订阅简报 · 数据异动分析：统计周期内有告警事件、或有指标明显波动时，让模型结合指标变化与告警事件，
 * 用不超过 100 字说明发生了什么、可能的原因。
 *
 * <ul>
 *   <li>触发条件：周期内有告警事件（含仍异常中的），或任一指标变化率绝对值 ≥ copilot.anomaly.change-pct（比率类为
 *       ≥ change-pt 个百分点）；都没有时不调用模型、卡片不加这一段；</li>
 *   <li>与 AI 摘要共用订阅上的 AI 开关与模型；每日次数单独计（功能编码 BRIEF_ANOMALY）；</li>
 *   <li>后端校验：超过 max-chars 字（不计空白）、或出现数据里没有的数字，就不采用，卡片照常发送。</li>
 * </ul>
 *
 * @author fivetech
 */
@Component
public class SubscriptionAnomalyAnalyst
{
    public static final String FEATURE = "BRIEF_ANOMALY";

    private final CopilotGateway gateway;

    private final CopilotProperties properties;

    public SubscriptionAnomalyAnalyst(CopilotGateway gateway, CopilotProperties properties)
    {
        this.gateway = gateway;
        this.properties = properties;
    }

    public CopilotProperties getProperties()
    {
        return properties;
    }

    /**
     * 生成异动分析并写进简报。
     *
     * @param events 统计周期内的告警事件（已按异常中在前、触发时间从新到旧排好）；读取失败时传 null
     */
    public void apply(DashboardSubscription s, SubscriptionBrief brief, List<MonitorEventVO> events, String operator)
    {
        CopilotProperties.Anomaly cfg = properties.getAnomaly();
        String model = properties.effectiveModel(s.getAiModel());
        String label = properties.label(model);
        List<MonitorEventVO> evs = events == null ? List.of() : events.subList(0, Math.min(events.size(), Math.max(0, cfg.getMaxEvents())));
        List<SubscriptionBrief.Change> movers = movers(brief.getChanges(), cfg.getChangePct(), cfg.getChangePt());
        if (evs.isEmpty() && movers.isEmpty())
        {
            brief.applyAnomaly(null, label, "統計週期內沒有告警事件，指標也沒有明顯波動");
            return;
        }
        String facts = factsText(brief, movers, evs, cfg);
        CopilotGateway.Request r = new CopilotGateway.Request();
        r.feature = FEATURE;
        r.siteCode = s.getSiteCode();
        r.refId = s.getId() == null ? "preview" : String.valueOf(s.getId());
        r.model = model;
        r.system = systemPrompt(cfg.getMaxChars());
        r.user = facts;
        r.maxTokens = cfg.getMaxTokens();
        r.dailyLimit = cfg.getDailyLimit();
        r.operator = operator;
        int max = cfg.getMaxChars();
        r.validator = text -> {
            String t = tidy(text);
            int n = length(t);
            if (n > max)
            {
                return "異動分析超過 " + max + " 字（" + n + " 字）";
            }
            List<String> unknown = SubscriptionAiAnalyst.unknownNumbers(t, facts);
            return unknown.isEmpty() ? null : "異動分析出現數據裡沒有的數字：" + String.join("、", unknown);
        };
        CopilotGateway.Outcome o = gateway.complete(r);
        brief.applyAnomaly(o.ok ? tidy(o.text) : null, label, o.reason);
    }

    /** 明显波动的指标：比率类看百分点，其余看变化率 */
    static List<SubscriptionBrief.Change> movers(List<SubscriptionBrief.Change> changes, BigDecimal pct, BigDecimal pt)
    {
        List<SubscriptionBrief.Change> out = new ArrayList<>();
        for (SubscriptionBrief.Change c : changes)
        {
            boolean big = c.deltaPt != null ? pt != null && c.deltaPt.abs().compareTo(pt) >= 0
                : c.deltaPct != null && pct != null && c.deltaPct.abs().compareTo(pct) >= 0;
            if (big)
            {
                out.add(c);
            }
        }
        return out;
    }

    static String systemPrompt(int maxChars)
    {
        int target = Math.max(30, maxChars - 10);
        return String.join("\n",
            "你是 Yata 營運數據分析助手，為線上娛樂平台的營運人員寫訂閱簡報裡的「數據異動分析」。",
            "任務：結合指標變化與監控告警事件，說明這個週期最主要的異動，以及可能的原因。",
            "規則：",
            "1. 只能根據提供的數據寫；原因只能從指標之間的關係推斷（例如註冊與首存同時下降，可能是流量減少；存款成功率下降而嘗試次數正常，可能是支付通道問題），推斷時寫「可能」，不要編造活動、節假日、系統故障等數據裡沒有的事件。",
            "2. 告警事件優先：先講嚴重、異常中的告警，再講其他明顯波動。",
            "3. 引用數字時必須原樣照抄數據中的數值或變化（含單位與正負號），不要自行計算新的數字。",
            "4. 指標方向：「越高越好」上升為好轉、下降為惡化；「越低越好」相反；「區間」「無方向」只描述變化。",
            "5. 輸出繁體中文一段話，不超過 " + target + " 個字；不要標題、項目符號、開場白，不要使用 Markdown。");
    }

    static String factsText(SubscriptionBrief b, List<SubscriptionBrief.Change> movers, List<MonitorEventVO> events,
        CopilotProperties.Anomaly cfg)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("站點：").append(nvl(b.getSiteName())).append('\n');
        sb.append("訂閱：").append(nvl(b.getName())).append('\n');
        sb.append("統計週期：").append(nvl(b.getPeriod())).append("；對比週期：").append(nvl(b.getCompare())).append('\n');
        sb.append("全部指標（名稱｜本期｜上期｜變化｜方向｜監控）：\n");
        for (String f : b.getFacts())
        {
            sb.append("- ").append(f).append('\n');
        }
        sb.append("明顯波動的指標（變化率 ≥ ").append(plain(cfg.getChangePct())).append("% 或比率變化 ≥ ")
            .append(plain(cfg.getChangePt())).append("pt）：");
        if (movers.isEmpty())
        {
            sb.append("無\n");
        }
        else
        {
            sb.append('\n');
            movers.forEach(m -> sb.append("- ").append(m.label).append(" ").append(m.text).append('\n'));
        }
        sb.append("統計週期內的告警事件（觸發時間｜指標｜級別｜狀態｜觸發值｜判定依據）：");
        if (events.isEmpty())
        {
            sb.append("無\n");
        }
        else
        {
            sb.append('\n');
            for (MonitorEventVO e : events)
            {
                sb.append("- ").append(nvl(e.getTriggerHour())).append("｜").append(nvl(e.getMetricName())).append("｜")
                    .append(levelText(e.getLevel(), e.isEscalated())).append("｜").append(statusText(e)).append("｜")
                    .append(e.getCurrentText() == null ? SubscriptionBrief.DASH : e.getCurrentText()).append("｜")
                    .append(e.getJudgeBasis() == null || e.getJudgeBasis().isEmpty() ? SubscriptionBrief.DASH : String.join("，", e.getJudgeBasis()))
                    .append('\n');
            }
        }
        sb.append("\n請按規則寫出數據異動分析。");
        return sb.toString();
    }

    public static String levelText(String level, boolean escalated)
    {
        String lv = level == null ? "" : level.trim().toUpperCase(java.util.Locale.ROOT);
        String t = "CRIT".equals(lv) ? "嚴重" : "WARN".equals(lv) ? "注意" : nvl(level);
        return escalated ? t + "（已升級）" : t;
    }

    public static String statusText(MonitorEventVO e)
    {
        String st = e.getStatus() == null ? "" : e.getStatus();
        switch (st)
        {
            case "open":
                return "異常中";
            case "closed":
                return e.getEndHour() == null ? "已恢復" : "已恢復（" + e.getEndHour() + "）";
            case "stopped":
                return "已停止監控";
            default:
                return st;
        }
    }

    /** 合成一段：去掉项目符号、粗体、换行 */
    static String tidy(String text)
    {
        if (text == null)
        {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String raw : text.split("\\r?\\n"))
        {
            String l = raw.replace("**", "").trim().replaceFirst("^([-•*·]|\\d+[.、)])\\s*", "").trim();
            if (!l.isEmpty() && !l.matches("^#+.*") && !l.startsWith("數據異動分析"))
            {
                sb.append(l);
            }
        }
        return sb.toString().trim();
    }

    /** 字数：不计空白 */
    static int length(String text)
    {
        return text == null ? 0 : text.replaceAll("\\s+", "").codePointCount(0, text.replaceAll("\\s+", "").length());
    }

    private static String plain(BigDecimal v)
    {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    private static String nvl(String s)
    {
        return s == null ? "" : s;
    }
}
