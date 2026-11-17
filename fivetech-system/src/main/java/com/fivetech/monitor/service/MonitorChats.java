package com.fivetech.monitor.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fivetech.common.utils.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 规则的通知群（monitor_rule.notify_chats，release_next ㉑）：三类规则都可选 1–{@value #MAX} 个群，
 * 同一条告警以相同内容分别发到每个群（原型 2026-11-13 第五版）。
 *
 * <p>库里存 {@code [{"chatId":"oc_x","name":"風控告警群"}, …]}（保存时的群名快照，按选择顺序）；
 * 查询时 Mapper 关联群列表缓存，多带 {@code status}（ok / dead；缓存里没有为 null，按失效处理）。
 * notify_chat_id / notify_chat_name 仍写第一个群，兼容旧查询；notify_chats 为空而 notify_chat_id 有值时（㉑ 回填前的旧数据）按单个群读。</p>
 *
 * @author fivetech
 */
public final class MonitorChats
{
    /** 每条规则最多几个群 */
    public static final int MAX = 5;

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private MonitorChats()
    {
    }

    /** 一个群：status 只在查询时有（ok / dead / null） */
    public record Chat(String chatId, String name, String status)
    {
        /** 缓存里没有（从没拉到过或被清理）也按失效处理，让前端提示重新选 */
        public boolean dead()
        {
            return !"ok".equals(status);
        }
    }

    /**
     * 解析 notify_chats；json 为空时退回 notify_chat_id / notify_chat_name（单个群，状态用 fallbackStatus）。
     */
    public static List<Chat> parse(String json, String fallbackId, String fallbackName, String fallbackStatus)
    {
        List<Chat> out = new ArrayList<>();
        if (StringUtils.isNotEmpty(json))
        {
            try
            {
                JsonNode arr = JSON.readTree(json);
                if (arr != null && arr.isArray())
                {
                    Set<String> seen = new LinkedHashSet<>();
                    for (JsonNode n : arr)
                    {
                        String id = text(n, "chatId");
                        if (StringUtils.isEmpty(id) || !seen.add(id))
                        {
                            continue;
                        }
                        out.add(new Chat(id, text(n, "name"), text(n, "status")));
                    }
                }
            }
            catch (RuntimeException e)
            {
                out.clear();
            }
        }
        String id = StringUtils.trim(fallbackId);
        if (out.isEmpty() && StringUtils.isNotEmpty(id))
        {
            out.add(new Chat(id, fallbackName, fallbackStatus));
        }
        return out;
    }

    /** 只解析 json（没有回退） */
    public static List<Chat> parse(String json)
    {
        return parse(json, null, null, null);
    }

    /** 写库用：[{chatId, name}]，不带 status */
    public static String toJson(List<Chat> chats)
    {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Chat c : chats == null ? List.<Chat>of() : chats)
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chatId", c.chatId());
            m.put("name", c.name());
            list.add(m);
        }
        return JSON.writeValueAsString(list);
    }

    /**
     * 请求里的群：优先 notifyChatIds（数组），没有时用旧字段 notifyChatId（单个）；去掉空白、空值和重复，保留顺序。
     */
    public static List<String> requested(List<String> ids, String single)
    {
        Set<String> out = new LinkedHashSet<>();
        if (ids != null)
        {
            for (String s : ids)
            {
                String v = StringUtils.trim(s);
                if (StringUtils.isNotEmpty(v))
                {
                    out.add(v);
                }
            }
        }
        else
        {
            String v = StringUtils.trim(single);
            if (StringUtils.isNotEmpty(v))
            {
                out.add(v);
            }
        }
        return new ArrayList<>(out);
    }

    /** 前端展示用：[{chatId, name, status}]，status 缺省按 dead */
    public static List<Map<String, Object>> view(List<Chat> chats)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Chat c : chats)
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chatId", c.chatId());
            m.put("name", StringUtils.isEmpty(c.name()) ? c.chatId() : c.name());
            m.put("status", c.dead() ? "dead" : "ok");
            out.add(m);
        }
        return out;
    }

    private static String text(JsonNode n, String field)
    {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }
}
