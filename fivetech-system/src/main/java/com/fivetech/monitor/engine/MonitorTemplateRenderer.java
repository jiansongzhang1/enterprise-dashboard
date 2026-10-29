package com.fivetech.monitor.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通知模板渲染（纯函数）：
 * <ul>
 *   <li>{@code {{名字}}}：替换成值；值为空显示「—」。</li>
 *   <li>{@code {{#名字}}…{{/名字}}}：可选段落，值为空时整段不输出；整行只剩空白时连同这一行去掉。不支持嵌套。</li>
 * </ul>
 * 只认 {@link #PLACEHOLDERS} 里的名字，保存模板时用 {@link #validate} 校验。
 *
 * @author fivetech
 */
public final class MonitorTemplateRenderer
{
    /** 可用的占位符 → 说明（管理页面的占位符列表也用它，顺序即展示顺序） */
    public static final Map<String, String> PLACEHOLDERS;

    static
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("site", "站點顯示名，如 uWin");
        m.put("metric", "指標名，如 存款成功率");
        m.put("currentValue", "當前值（最近一個週期），如 81.33%");
        m.put("judgeText", "判定（FY-30）：低於嚴重閾值 90.00% / 跌幅超過嚴重閾值 20% / 超出嚴重區間 2.00% – 8.00%；升級而來的寫注意閾值並加「，已升為嚴重」");
        m.put("ruleCode", "規則編號，如 R-001");
        m.put("triggerTime", "觸發週期的開始時刻（站點時間，不寫時區）：2026-10-22 14:00；多小時週期 2026-10-22 00:00 – 12:00；24 小時 2026-10-22 全天");
        m.put("owner", "負責人郵箱（純文本，不 @）");
        m.put("level", "當前等級，如 嚴重");
        m.put("reason", "原因句，如 近 2 小時 83.60% – 84.20%，低於嚴重線 90.00%");
        m.put("reasonValue", "原因的數值部分，如 近 2 小時 83.60% – 84.20%");
        m.put("reasonLine", "原因的對照線部分，如 低於嚴重線 90.00%");
        m.put("baseline", "最近一個週期的基準（只有跌幅類），如 ₹40,000");
        m.put("warnLine", "注意線，如 < 95.00%；沒設注意線為空");
        m.put("critLine", "嚴重線，如 < 90.00%");
        m.put("periodText", "檢測週期與持續次數，如 每小時 · 連續 2 小時、每 4 小時 · 連續 2 次");
        m.put("escalateHours", "注意升級的小時數；沒設升級為空");
        m.put("eventUrl", "異常事件頁地址（MONITOR_EVENT_PAGE_URL）；沒配為空");
        m.put("sentAt", "生成消息的時間（站點時區），如 2026-10-22 11:03");
        PLACEHOLDERS = Collections.unmodifiableMap(m);
    }

    /** 渲染后最长字符数（Lark 富文本单条消息上限约 30KB，留足余量） */
    public static final int MAX_LENGTH = 4000;

    static final String EMPTY_VALUE = "—";

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([#/]?)\\s*([A-Za-z][A-Za-z0-9]*)\\s*}}");

    private static final Pattern SECTION = Pattern.compile(
        "\\{\\{\\s*#\\s*([A-Za-z][A-Za-z0-9]*)\\s*}}(.*?)\\{\\{\\s*/\\s*\\1\\s*}}", Pattern.DOTALL);

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9]*)\\s*}}");

    private MonitorTemplateRenderer()
    {
    }

    /**
     * 校验模板：占位符都认识、可选段落成对且不嵌套、不为空。
     *
     * @return 错误列表（繁体，可直接显示）；为空表示通过
     */
    public static List<String> validate(String content)
    {
        List<String> errors = new ArrayList<>();
        if (content == null || content.trim().isEmpty())
        {
            errors.add("模板內容不能為空");
            return errors;
        }
        if (content.length() > MAX_LENGTH)
        {
            errors.add("模板內容不能超過 " + MAX_LENGTH + " 字");
        }
        Matcher m = TOKEN.matcher(content);
        String open = null;
        while (m.find())
        {
            String kind = m.group(1);
            String name = m.group(2);
            if (!PLACEHOLDERS.containsKey(name))
            {
                errors.add("不認識的佔位符 {{" + kind + name + "}}");
                continue;
            }
            if ("#".equals(kind))
            {
                if (open != null)
                {
                    errors.add("可選段落不能嵌套：{{#" + open + "}} 裡面又有 {{#" + name + "}}");
                }
                open = name;
            }
            else if ("/".equals(kind))
            {
                if (!name.equals(open))
                {
                    errors.add("{{/" + name + "}} 沒有對應的 {{#" + name + "}}");
                }
                open = null;
            }
        }
        if (open != null)
        {
            errors.add("{{#" + open + "}} 沒有結束標記 {{/" + open + "}}");
        }
        // 没被识别成占位符的花括号（写错了格式）
        String stripped = TOKEN.matcher(content).replaceAll("");
        if (stripped.contains("{{") || stripped.contains("}}"))
        {
            errors.add("有格式不對的佔位符，應寫成 {{名字}}");
        }
        return errors;
    }

    /** 渲染。values 里没有或为空的占位符显示「—」；可选段落按值是否为空决定是否输出 */
    public static String render(String content, Map<String, String> values)
    {
        if (content == null)
        {
            return "";
        }
        String[] lines = content.replace("\r\n", "\n").split("\n", -1);
        List<String> out = new ArrayList<>();
        for (String line : lines)
        {
            boolean hasSection = SECTION.matcher(line).find();
            String r = renderSections(line, values);
            r = renderVars(r, values);
            if (hasSection && r.trim().isEmpty())
            {
                continue;   // 整行都在可选段落里、值为空：这一行不要
            }
            out.add(r);
        }
        // 段落跨行的情况：整体再处理一遍（逐行处理不了的）
        String joined = String.join("\n", out);
        if (SECTION.matcher(joined).find())
        {
            joined = renderVars(renderSections(joined, values), values);
        }
        return stripTrailingBlankLines(joined);
    }

    private static String renderSections(String text, Map<String, String> values)
    {
        Matcher m = SECTION.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find())
        {
            String v = values == null ? null : values.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(isEmpty(v) ? "" : m.group(2)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String renderVars(String text, Map<String, String> values)
    {
        Matcher m = VAR.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find())
        {
            String v = values == null ? null : values.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(isEmpty(v) ? EMPTY_VALUE : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String stripTrailingBlankLines(String s)
    {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == ' '))
        {
            end--;
        }
        return s.substring(0, end);
    }

    private static boolean isEmpty(String v)
    {
        return v == null || v.trim().isEmpty();
    }
}
