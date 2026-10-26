package com.fivetech.monitor.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把渲染好的纯文本转成 Lark 富文本（post）的段落：每行一段；{@code [文字](网址)} 和裸露的 http(s) 网址转成链接（tag = a），
 * 其余为文本（tag = text）。Lark 纯文本消息里的网址能不能点开取决于客户端识别，转成 post 后一定能点开。
 *
 * @author fivetech
 */
public final class MonitorLarkPost
{
    /** [文字](http…) 或 裸网址；网址到空白、中文标点或行尾为止 */
    private static final Pattern LINK = Pattern.compile(
        "\\[([^\\]\\n]+)]\\((https?://[^\\s)]+)\\)|(https?://[^\\s，。、；！？）」』<>\"']+)");

    private MonitorLarkPost()
    {
    }

    /** @return 段落列表，每段是若干个 {tag, text, href?}；空行为一个空文本元素 */
    public static List<List<Map<String, String>>> paragraphs(String text)
    {
        List<List<Map<String, String>>> out = new ArrayList<>();
        String src = text == null ? "" : text.replace("\r\n", "\n");
        for (String line : src.split("\n", -1))
        {
            List<Map<String, String>> p = new ArrayList<>();
            Matcher m = LINK.matcher(line);
            int at = 0;
            while (m.find())
            {
                if (m.start() > at)
                {
                    p.add(text(line.substring(at, m.start())));
                }
                if (m.group(1) != null)
                {
                    p.add(link(m.group(1), m.group(2)));
                }
                else
                {
                    p.add(link(m.group(3), m.group(3)));
                }
                at = m.end();
            }
            if (at < line.length() || p.isEmpty())
            {
                p.add(text(line.substring(at)));
            }
            out.add(p);
        }
        return out;
    }

    private static Map<String, String> text(String s)
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("tag", "text");
        m.put("text", s);
        return m;
    }

    private static Map<String, String> link(String label, String href)
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("tag", "a");
        m.put("text", label);
        m.put("href", href);
        return m;
    }
}
