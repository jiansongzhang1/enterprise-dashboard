package com.fivetech.dashboard.subscription;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import com.fivetech.copilot.CopilotGateway;
import com.fivetech.copilot.CopilotProperties;

/**
 * 订阅简报的 AI 摘要：把简报里后端算好的指标（本期、上期、变化、方向、监控状态）交给模型写 3–5 条要点。
 *
 * <ul>
 *   <li>模型只组织文字，不做计算：提示里要求引用数字时原样照抄数据；</li>
 *   <li>后端校验：摘要里出现数据里没有的数字（10 以下的整数除外，用于「3 個指標」这类计数）就不采用，卡片照常发送、不带摘要；</li>
 *   <li>每天调用次数有上限（copilot.brief.daily-limit），用完后当天的简报不带摘要。</li>
 * </ul>
 *
 * @author fivetech
 */
@Component
public class SubscriptionAiAnalyst
{
    public static final String FEATURE = "BRIEF";

    /** 摘要最多保留几行 */
    static final int MAX_LINES = 6;

    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    private final CopilotGateway gateway;

    private final CopilotProperties properties;

    public SubscriptionAiAnalyst(CopilotGateway gateway, CopilotProperties properties)
    {
        this.gateway = gateway;
        this.properties = properties;
    }

    public CopilotProperties getProperties()
    {
        return properties;
    }

    /** 生成摘要并写进简报（失败时简报不带摘要，原因写进预览） */
    public void apply(DashboardSubscription s, SubscriptionBrief brief, String operator)
    {
        String model = properties.effectiveModel(s.getAiModel());
        String label = properties.label(model);
        if (brief.getFacts().isEmpty())
        {
            brief.applyAi(null, model, label, "沒有可分析的指標");
            return;
        }
        String facts = factsText(brief, s);
        CopilotGateway.Request r = new CopilotGateway.Request();
        r.feature = FEATURE;
        r.siteCode = s.getSiteCode();
        r.refId = s.getId() == null ? "preview" : String.valueOf(s.getId());
        r.model = model;
        r.system = systemPrompt();
        r.user = facts;
        r.maxTokens = properties.getBrief().getMaxTokens();
        r.dailyLimit = properties.getBrief().getDailyLimit();
        r.operator = operator;
        r.validator = text -> {
            List<String> unknown = unknownNumbers(text, facts);
            return unknown.isEmpty() ? null : "摘要出現數據裡沒有的數字：" + String.join("、", unknown);
        };
        CopilotGateway.Outcome o = gateway.complete(r);
        brief.applyAi(o.ok ? tidy(o.text) : null, model, label, o.reason);
    }

    /** 系统提示：角色、规则、输出格式 */
    static String systemPrompt()
    {
        return String.join("\n",
            "你是 Yata 營運數據分析助手，為線上娛樂平台的營運人員撰寫每日訂閱簡報的摘要。",
            "規則：",
            "1. 只能根據提供的數據寫，不要推測數據以外的原因；需要推測時寫「可能」，並指出依據的指標。",
            "2. 引用數字時必須原樣照抄數據中的數值或變化（含單位與正負號），不要自行計算新的數字、差值或百分比。",
            "3. 指標方向：「越高越好」上升為好轉、下降為惡化；「越低越好」相反；「區間」「無方向」只描述變化，不判斷好壞。",
            "4. 監控欄有「注意」「嚴重」的指標最優先，寫在最前面。",
            "5. 輸出繁體中文，3 到 5 條要點，每條一行、以「- 」開頭、不超過 45 個字；不要標題、開場白、結語，不要使用粗體或其他 Markdown。",
            "6. 如果整體變化都不大，第一條寫「整體平穩」，再列出變化最大的 1 到 2 個指標。");
    }

    /** 用户消息：站点、订阅、周期、指标表 */
    static String factsText(SubscriptionBrief b, DashboardSubscription s)
    {
        SubscriptionRange range = SubscriptionRange.of(s.getRangeCode());
        StringBuilder sb = new StringBuilder();
        sb.append("站點：").append(nvl(b.getSiteName())).append('\n');
        sb.append("訂閱：").append(nvl(b.getName())).append('\n');
        if (b.getDescription() != null)
        {
            sb.append("訂閱說明：").append(b.getDescription()).append('\n');
        }
        sb.append("統計範圍：").append(range == null ? nvl(s.getRangeCode()) : range.getLabel()).append('\n');
        sb.append("統計週期：").append(nvl(b.getPeriod())).append("；對比週期：").append(nvl(b.getCompare())).append('\n');
        sb.append("指標（名稱｜本期｜上期｜變化｜方向｜監控）：\n");
        for (String f : b.getFacts())
        {
            sb.append("- ").append(f).append('\n');
        }
        sb.append("\n請按規則寫出摘要。");
        return sb.toString();
    }

    /**
     * 摘要里出现、但数据里找不到的数字（去掉千分位比较）；10 以下的整数不检查。
     */
    static List<String> unknownNumbers(String text, String facts)
    {
        String plainFacts = facts.replace(",", "");
        Set<String> out = new LinkedHashSet<>();
        Matcher m = NUMBER.matcher(text == null ? "" : text);
        while (m.find())
        {
            String n = m.group().replace(",", "");
            if (n.endsWith("."))
            {
                n = n.substring(0, n.length() - 1);
            }
            if (n.matches("\\d") || n.equals("10"))
            {
                continue;
            }
            if (!containsNumber(plainFacts, n))
            {
                out.add(m.group());
            }
        }
        return new ArrayList<>(out);
    }

    /** facts 里有这个数字，而且不是更长数字的一部分（例如 3 不算 13 的一部分；2.5 不算 2.55 的一部分） */
    static boolean containsNumber(String facts, String n)
    {
        int from = 0;
        while (true)
        {
            int i = facts.indexOf(n, from);
            if (i < 0)
            {
                return false;
            }
            boolean leftOk = i == 0 || !isNumChar(facts.charAt(i - 1));
            int end = i + n.length();
            boolean rightOk = end >= facts.length() || !(Character.isDigit(facts.charAt(end))
                || (facts.charAt(end) == '.' && end + 1 < facts.length() && Character.isDigit(facts.charAt(end + 1))));
            if (leftOk && rightOk)
            {
                return true;
            }
            from = i + 1;
        }
    }

    private static boolean isNumChar(char c)
    {
        return Character.isDigit(c) || c == '.';
    }

    /** 只保留要点行（最多 MAX_LINES 行），统一成「- 」开头 */
    static String tidy(String text)
    {
        List<String> lines = new ArrayList<>();
        for (String raw : text.split("\\r?\\n"))
        {
            String l = raw.trim();
            if (l.isEmpty())
            {
                continue;
            }
            l = l.replaceFirst("^([-•*·]|\\d+[.、)])\\s*", "").replace("**", "").trim();
            if (!l.isEmpty())
            {
                lines.add("- " + l);
            }
            if (lines.size() == MAX_LINES)
            {
                break;
            }
        }
        return String.join("\n", lines);
    }

    private static String nvl(String s)
    {
        return s == null ? "" : s;
    }
}
