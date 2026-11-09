package com.fivetech.copilot.chat;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.common.utils.SiteContext;
import com.fivetech.copilot.AiClient;
import com.fivetech.copilot.CopilotGateway;
import com.fivetech.copilot.CopilotProperties;
import com.fivetech.copilot.mapper.CopilotUsageMapper;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.domain.query.OverviewQuery;
import com.fivetech.dashboard.domain.vo.OverviewVO;
import com.fivetech.dashboard.enums.CompareType;
import com.fivetech.dashboard.enums.DashboardPage;
import com.fivetech.dashboard.enums.Granularity;
import com.fivetech.dashboard.service.IOverviewService;
import com.fivetech.dashboard.service.MetricRegistry;
import com.fivetech.dashboard.subscription.SubscriptionAnomalyAnalyst;
import com.fivetech.dashboard.subscription.SubscriptionBrief;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.vo.MonitorEventVO;
import com.fivetech.monitor.service.IMonitorEventService;

/**
 * 管理后台 · AI 助手（右下角聊天）。
 *
 * <ul>
 *   <li>每次提问自动带上当前站点（X-Site-Code）的数据：今日截至当前整点、昨日全天的运营总览指标（本期、上期、变化、方向、监控状态），
 *       以及昨日 0 点以来的告警事件（含仍异常中的）。同一站点的数据缓存几分钟（copilot.chat.context-cache-seconds）；</li>
 *   <li>模型只能根据这些数据回答，不能查库、不能改任何东西；</li>
 *   <li>对话内容不落库：前端存在浏览器里，每次把最近几条一起发来；后端只记一行调用记录（功能 CHAT、人、站点、token 用量）；</li>
 *   <li>每人每天有次数上限（copilot.chat.daily-limit-per-user）。</li>
 * </ul>
 *
 * @author fivetech
 */
@Service
public class CopilotChatService
{
    private static final Logger log = LoggerFactory.getLogger(CopilotChatService.class);

    public static final String FEATURE = "CHAT";

    private static final DateTimeFormatter SLOT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final CopilotGateway gateway;

    private final CopilotProperties properties;

    private final CopilotUsageMapper usageMapper;

    private final DashboardProperties dashboard;

    private final IOverviewService overviewService;

    private final MetricRegistry registry;

    private final IMonitorEventService monitorEvents;

    /** 站点 → 数据上下文 */
    private final Map<String, Context> cache = new ConcurrentHashMap<>();

    public CopilotChatService(CopilotGateway gateway, CopilotProperties properties, CopilotUsageMapper usageMapper,
        DashboardProperties dashboard, IOverviewService overviewService, MetricRegistry registry, IMonitorEventService monitorEvents)
    {
        this.gateway = gateway;
        this.properties = properties;
        this.usageMapper = usageMapper;
        this.dashboard = dashboard;
        this.overviewService = overviewService;
        this.registry = registry;
        this.monitorEvents = monitorEvents;
    }

    /** 站点数据上下文 */
    static final class Context
    {
        final String text;
        final String asOf;
        final long expiresAt;

        Context(String text, String asOf, long expiresAt)
        {
            this.text = text;
            this.asOf = asOf;
            this.expiresAt = expiresAt;
        }
    }

    /** 面板打开时取一次：能否使用、模型、今日剩余次数、数据说明 */
    public Map<String, Object> meta(String operator)
    {
        CopilotProperties.Chat cfg = properties.getChat();
        String model = properties.effectiveModel(cfg.getModel());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", properties.isEnabled() && properties.isConfigured());
        m.put("reason", !properties.isEnabled() ? "AI 功能已關閉" : !properties.isConfigured() ? "未配置 ANTHROPIC_API_KEY" : null);
        m.put("model", model);
        m.put("modelLabel", properties.label(model));
        m.put("dailyLimit", cfg.getDailyLimitPerUser());
        m.put("usedToday", used(operator));
        m.put("maxChars", cfg.getMaxChars());
        String site = dashboard.currentSite();
        m.put("siteCode", site);
        m.put("siteName", dashboard.siteName(site));
        m.put("contextNote", "回答會參考本站今日（截至當前整點）與昨日的運營總覽指標，以及昨日以來的告警事件");
        return m;
    }

    private int used(String operator)
    {
        try
        {
            return usageMapper.countTodayByOperator(FEATURE, operator);
        }
        catch (RuntimeException e)
        {
            return 0;
        }
    }

    /**
     * 回答一轮。
     *
     * @param messages 最近的对话（时间顺序，最后一条是这次的提问）
     * @return { reply, model, modelLabel, contextAsOf, usedToday, dailyLimit }
     */
    public Map<String, Object> chat(List<Map<String, Object>> messages, String page, String operator)
    {
        CopilotProperties.Chat cfg = properties.getChat();
        List<AiClient.Message> list = normalize(messages, cfg);
        String site = dashboard.currentSite();
        Context ctx = context(site);
        String model = properties.effectiveModel(cfg.getModel());

        CopilotGateway.Request r = new CopilotGateway.Request();
        r.feature = FEATURE;
        r.siteCode = site;
        r.refId = page == null ? null : page.length() > 64 ? page.substring(0, 64) : page;
        r.model = model;
        r.system = systemPrompt(dashboard.siteName(site), site, dashboard.siteTimezone(site), page, ctx.text);
        r.messages = list;
        r.maxTokens = cfg.getMaxTokens();
        r.dailyLimit = cfg.getDailyLimitPerUser();
        r.limitPerOperator = true;
        r.operator = operator;
        CopilotGateway.Outcome o = gateway.complete(r);
        if (!o.ok)
        {
            throw new ServiceException(o.reason);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reply", o.text);
        out.put("model", model);
        out.put("modelLabel", properties.label(model));
        out.put("contextAsOf", ctx.asOf);
        out.put("usedToday", used(operator));
        out.put("dailyLimit", cfg.getDailyLimitPerUser());
        return out;
    }

    /** 校验并整理对话：只留 user / assistant、去掉空的、合并连续同角色、最后一条必须是 user、只保留最近 maxMessages 条 */
    static List<AiClient.Message> normalize(List<Map<String, Object>> messages, CopilotProperties.Chat cfg)
    {
        if (messages == null || messages.isEmpty())
        {
            throw new ServiceException("請輸入問題");
        }
        List<AiClient.Message> out = new ArrayList<>();
        for (Map<String, Object> m : messages)
        {
            if (m == null)
            {
                continue;
            }
            String role = "assistant".equals(String.valueOf(m.get("role"))) ? "assistant" : "user";
            String content = m.get("content") == null ? "" : String.valueOf(m.get("content")).trim();
            if (content.isEmpty())
            {
                continue;
            }
            if (!out.isEmpty() && out.get(out.size() - 1).getRole().equals(role))
            {
                AiClient.Message last = out.remove(out.size() - 1);
                content = last.getContent() + "\n\n" + content;
            }
            out.add(new AiClient.Message(role, content));
        }
        if (out.isEmpty() || !"user".equals(out.get(out.size() - 1).getRole()))
        {
            throw new ServiceException("請輸入問題");
        }
        String question = out.get(out.size() - 1).getContent();
        if (question.codePointCount(0, question.length()) > cfg.getMaxChars())
        {
            throw new ServiceException("問題不能超過 " + cfg.getMaxChars() + " 個字");
        }
        int max = Math.max(1, cfg.getMaxMessages());
        List<AiClient.Message> tail = new ArrayList<>(out.subList(Math.max(0, out.size() - max), out.size()));
        // 第一条必须是 user（Claude API 要求）
        while (!tail.isEmpty() && !"user".equals(tail.get(0).getRole()))
        {
            tail.remove(0);
        }
        return tail;
    }

    static String systemPrompt(String siteName, String siteCode, String timezone, String page, String context)
    {
        return String.join("\n",
            "你是 Yata 營運後台的 AI 助手，服務線上娛樂平台的營運人員。",
            "目前站點：" + nvl(siteName) + "（" + nvl(siteCode) + "），時區 " + nvl(timezone) + "。"
                + (page == null || page.isBlank() ? "" : "使用者正在看的頁面：" + page + "。"),
            "規則：",
            "1. 涉及本站數據的問題，只能根據下面「本站數據」回答；引用數字時原樣照抄，不要自行推算新的數字。",
            "2. 數據裡沒有的（例如其他日期、明細名單、推廣渠道），直接說目前取不到，並建議到後台對應頁面查看；不要猜測或編造。",
            "3. 分析原因時只能從指標之間的關係推斷，推斷要寫「可能」；不要編造活動、節假日、系統故障等數據裡沒有的事件。",
            "4. 你不能查詢資料庫、不能修改任何設定，也不能代替使用者執行操作。",
            "5. 用使用者的語言回答（預設繁體中文），簡潔，先給結論；需要時用短的條列。",
            "",
            "本站數據：",
            context);
    }

    /** 站点数据上下文（缓存） */
    Context context(String site)
    {
        long now = System.currentTimeMillis();
        Context c = cache.get(site);
        if (c != null && c.expiresAt > now)
        {
            return c;
        }
        Context fresh = build(site, now + Math.max(0, properties.getChat().getContextCacheSeconds()) * 1000L);
        cache.put(site, fresh);
        return fresh;
    }

    private Context build(String site, long expiresAt)
    {
        LocalDateTime nowHour = LocalDateTime.now(dashboard.currentZone()).truncatedTo(ChronoUnit.HOURS);
        LocalDateTime today = nowHour.toLocalDate().atStartOfDay();
        StringBuilder sb = new StringBuilder();
        String asOf = null;
        List<String> codes = registry.columnsOf(DashboardPage.OVERVIEW, site);
        String symbol = dashboard.siteCurrencySymbol(site);
        try
        {
            if (nowHour.isAfter(today))
            {
                SubscriptionBrief b = brief(site, codes, today, nowHour, symbol);
                asOf = b.toPreview().get("asOf") == null ? null : String.valueOf(b.toPreview().get("asOf"));
                section(sb, "今日（" + today.format(SLOT) + " – " + nowHour.format(SLOT) + "，對比昨日同一時段）", b);
            }
            SubscriptionBrief y = brief(site, codes, today.minusDays(1), today, symbol);
            section(sb, "昨日全天（" + today.minusDays(1).toLocalDate() + "，對比前日）", y);
            if (asOf == null && y.toPreview().get("asOf") != null)
            {
                asOf = String.valueOf(y.toPreview().get("asOf"));
            }
        }
        catch (RuntimeException e)
        {
            log.warn("[copilot-chat] 取运营总览失败 site={}：{}", site, e.getMessage());
            sb.append("（暫時取不到運營總覽數據：").append(e.getMessage()).append("）\n");
        }
        try
        {
            List<MonitorEventVO> events = monitorEvents.eventsInWindow(new MonitorScope(site, null), today.minusDays(1), nowHour.plusHours(1), 15);
            sb.append("\n告警事件（昨日 0 點以來，含仍異常中的；觸發時間｜指標｜級別｜狀態｜觸發值｜判定依據）：");
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
                        .append(SubscriptionAnomalyAnalyst.levelText(e.getLevel(), e.isEscalated())).append("｜")
                        .append(SubscriptionAnomalyAnalyst.statusText(e)).append("｜")
                        .append(e.getCurrentText() == null ? "—" : e.getCurrentText()).append("｜")
                        .append(e.getJudgeBasis() == null || e.getJudgeBasis().isEmpty() ? "—" : String.join("，", e.getJudgeBasis()))
                        .append('\n');
                }
            }
        }
        catch (RuntimeException e)
        {
            log.warn("[copilot-chat] 取告警事件失败 site={}：{}", site, e.getMessage());
            sb.append("\n（暫時取不到告警事件）\n");
        }
        sb.append("\n資料截至：").append(asOf == null ? "—" : asOf);
        return new Context(sb.toString(), asOf, expiresAt);
    }

    private SubscriptionBrief brief(String site, List<String> codes, LocalDateTime from, LocalDateTime to, String symbol)
    {
        OverviewQuery q = new OverviewQuery();
        q.setGranularity(Granularity.HOUR);
        q.setSlotFrom(from.format(SLOT));
        q.setSlotTo(to.format(SLOT));
        q.setCompareType(CompareType.PREV_PERIOD);
        q.setBlocks(List.of("METRICS"));
        q.setMetrics(codes);
        q.setIncludeSeries(false);
        OverviewVO vo = SiteContext.callWith(site, dashboard.udsSite(site), () -> overviewService.query(q));
        SubscriptionBrief.Context c = new SubscriptionBrief.Context();
        c.siteName = dashboard.siteName(site);
        c.name = "AI 助手";
        c.currencySymbol = symbol;
        c.timezone = dashboard.siteTimezone(site);
        return SubscriptionBrief.build(vo, codes, c);
    }

    private static void section(StringBuilder sb, String title, SubscriptionBrief b)
    {
        sb.append(title).append("（名稱｜本期｜上期｜變化｜方向｜監控）：\n");
        if (b.getFacts().isEmpty())
        {
            sb.append("（沒有數據）\n");
        }
        b.getFacts().forEach(f -> sb.append("- ").append(f).append('\n'));
        sb.append('\n');
    }

    private static String nvl(String s)
    {
        return s == null ? "" : s;
    }
}
