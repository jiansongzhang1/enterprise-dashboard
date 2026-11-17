package com.fivetech.monitor.engine;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.fivetech.common.lark.LarkClient;
import com.fivetech.common.lark.LarkErrorCategory;
import com.fivetech.common.lark.LarkResult;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.monitor.domain.MonitorOrderEvent;
import com.fivetech.monitor.domain.MonitorRule;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.OrderRuleConfig;
import com.fivetech.monitor.exception.MonitorErrorCode;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.mapper.MonitorOrderEventMapper;
import com.fivetech.monitor.mapper.MonitorRuleMapper;
import com.fivetech.monitor.service.IMonitorLarkChatService;
import com.fivetech.monitor.service.MonitorChats;
import com.fivetech.monitor.service.MonitorOrderAlerts;
import com.fivetech.monitor.service.MonitorOrderRules;
import tools.jackson.databind.ObjectMapper;

/**
 * 按规则发告警：外部判定服务（如订单规则的判定服务）判定命中后，按规则 ID 把告警消息发到这条规则配置的 Lark 群。
 *
 * <ul>
 *   <li>群取规则上配置的 1–5 个群（release_next ㉑），调用方不能指定群；同一条消息以相同内容分别发到每个群；用监控告警同一个 Lark 机器人发出；</li>
 *   <li>规则须属于当前站点（请求头 X-Site-Code）、未删除、启用中，并配置了通知群；已失效的群跳过、记为失败；</li>
 *   <li>消息为纯文本（按行转成富文本发送，网址可点），开头自动加「【站点名】」（已带时不重复加）；</li>
 *   <li>订单规则须同时带订单信息（订单号、金额、当日累计笔数，见 {@link MonitorOrderAlerts}），先写订单告警事件
 *       （monitor_order_event）再发送，每个群的结果记在事件上；同一笔订单重复上报不新增，已发送成功的群不重发；
 *       规则关闭了通知或调用方传 send = false 时只记录不发送；</li>
 *   <li>全部群都发送失败时按第一个群的错误码返回（只有一个群时与原来相同）；部分失败返回 200，结果见 chats；</li>
 *   <li>指标规则、条件规则不写事件和发送记录；合并、每日上限、静默由调用方负责。</li>
 * </ul>
 *
 * @author fivetech
 */
@Service
public class MonitorRuleAlertService
{
    private static final Logger log = LoggerFactory.getLogger(MonitorRuleAlertService.class);

    /** 消息正文上限（不含自动加的站点前缀），与 Lark 文本消息的建议上限一致 */
    public static final int MAX_MESSAGE = 4000;

    /** 事件上记的发送失败原因上限 */
    static final int ERROR_MAX = 500;

    private final MonitorRuleMapper ruleMapper;

    private final MonitorOrderEventMapper eventMapper;

    private final IMonitorLarkChatService chatService;

    private final LarkClient larkClient;

    private final MonitorProperties properties;

    private final DashboardProperties dashboard;

    private final ObjectMapper json;

    public MonitorRuleAlertService(MonitorRuleMapper ruleMapper, MonitorOrderEventMapper eventMapper, IMonitorLarkChatService chatService,
            LarkClient larkClient, MonitorProperties properties, DashboardProperties dashboard, ObjectMapper json)
    {
        this.ruleMapper = ruleMapper;
        this.eventMapper = eventMapper;
        this.chatService = chatService;
        this.larkClient = larkClient;
        this.properties = properties;
        this.dashboard = dashboard;
        this.json = json;
    }

    /** 一个群的发送结果；code 为失败时的错误码（成功为 0） */
    record ChatResult(String chatId, String name, boolean success, String messageId, String error, int code)
    {
        Map<String, Object> view()
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chatId", chatId);
            m.put("chatName", name);
            m.put("success", success);
            m.put("messageId", messageId);
            m.put("error", error);
            return m;
        }
    }

    /** 只带消息（指标规则、条件规则）；订单规则须用 {@link #send(MonitorScope, Long, Map, String)} 带订单信息 */
    public Map<String, Object> send(MonitorScope scope, Long ruleId, String message, String operator)
    {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        return send(scope, ruleId, body, operator);
    }

    /**
     * 发一条告警到规则配置的群。
     *
     * @param body     { message, send?, orderNo?, amount?, cumCount?, cumAmount?, orders? }；message 纯文本，最多 {@value #MAX_MESSAGE} 字
     * @param operator 调用账号（API Key 绑定的账号或登录账号），只写日志和事件
     * @return { ruleId, ruleCode, chatId, chatName, messageId, content, chats[] }（chatId 等为第一个送达的群）；
     *         订单规则另有 { sent, notifyStatus, notifyReason, events[] }
     */
    public Map<String, Object> send(MonitorScope scope, Long ruleId, Map<String, Object> body, String operator)
    {
        MonitorOrderAlerts.Input in = MonitorOrderAlerts.parse(body);
        String text = null;
        if (in.send())
        {
            Object message = body == null ? null : body.get("message");
            text = message == null ? "" : String.valueOf(message).strip();
            if (text.isEmpty())
            {
                throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "message 必填");
            }
            if (text.length() > MAX_MESSAGE)
            {
                throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "message 最多 " + MAX_MESSAGE + " 字（目前 " + text.length() + " 字）");
            }
        }
        MonitorRule rule = ruleId == null ? null : ruleMapper.selectRuleById(scope, ruleId);
        if (rule == null)
        {
            throw MonitorException.notFound("規則不存在、已刪除或不屬於當前站點");
        }
        String code = ruleCode(rule.getRuleNo());
        if (!Boolean.TRUE.equals(rule.getEnabled()))
        {
            throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "規則 " + code + " 已暫停，不發送");
        }
        if (MonitorOrderRules.TYPE.equals(rule.getRuleType()))
        {
            if (!in.hasOrders())
            {
                throw new MonitorException(MonitorErrorCode.BAD_REQUEST,
                        "規則 " + code + " 是訂單規則，須帶訂單資訊：orderNo、amount、cumCount（多筆用 orders）");
            }
            return sendOrder(scope, rule, code, in, text, operator);
        }
        if (in.hasOrders())
        {
            throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "規則 " + code + " 不是訂單規則，不能帶訂單資訊");
        }
        if (!in.send())
        {
            throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "send = false 只用於訂單規則");
        }
        if (Boolean.FALSE.equals(rule.getNotifyEnabled()))
        {
            throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "規則 " + code + " 未開啟通知，不發送");
        }
        List<MonitorChats.Chat> chats = chatsOf(rule);
        requireSendable(code, chats);
        String content = withSitePrefix(dashboard.siteName(rule.getSiteCode()), text);
        List<ChatResult> results = deliver(scope, rule, chats, content, operator);
        throwIfAllFailed(results);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ruleId", rule.getRuleId());
        out.put("ruleCode", code);
        putResults(out, results, content);
        return out;
    }

    /**
     * 订单规则：先写事件（重复的订单号不新增），再决定发不发。
     * 只记录：调用方 send = false（CAPPED）、规则关闭通知（NOTIFY_OFF）；这批订单在每个群都已发送成功过时不重发。
     * 每个群的结果写回每笔订单的事件；全部失败时再按第一个群的错误码返回，调用方可原样重试（只补发失败的群）。
     */
    private Map<String, Object> sendOrder(MonitorScope scope, MonitorRule rule, String code, MonitorOrderAlerts.Input in, String text,
            String operator)
    {
        OrderRuleConfig cfg = config(rule);
        List<MonitorOrderAlerts.Hit> hits = MonitorOrderAlerts.forRule(in.orders(), cfg.getAmountMode());
        String reason = !in.send() ? MonitorOrderAlerts.CAPPED
                : Boolean.FALSE.equals(rule.getNotifyEnabled()) ? MonitorOrderAlerts.NOTIFY_OFF : null;
        List<String> nos = hits.stream().map(MonitorOrderAlerts.Hit::orderNo).toList();
        Set<String> existed = new HashSet<>();
        for (MonitorOrderEvent e : eventMapper.selectByOrderNos(scope, rule.getRuleId(), nos))
        {
            existed.add(e.getOrderNo());
        }
        LocalDateTime now = LocalDateTime.now(statZone());
        for (MonitorOrderAlerts.Hit h : hits)
        {
            if (!existed.contains(h.orderNo()))
            {
                eventMapper.insertEvent(event(rule, cfg, h, reason, now, operator));
            }
        }
        Map<String, MonitorOrderEvent> saved = new HashMap<>();
        for (MonitorOrderEvent e : eventMapper.selectByOrderNos(scope, rule.getRuleId(), nos))
        {
            saved.put(e.getOrderNo(), e);
        }
        List<Map<String, Object>> events = new ArrayList<>();
        List<MonitorOrderEvent> pending = new ArrayList<>();
        for (String no : nos)
        {
            MonitorOrderEvent e = saved.get(no);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("orderNo", no);
            row.put("eventId", e == null ? null : e.getEventId());
            row.put("duplicate", existed.contains(no));
            events.add(row);
            // 待发送：新写入的（PENDING），以及之前没全部送达的（FAILED / PARTIAL）；已发送、只记录的不再发
            if (e != null && !MonitorOrderAlerts.SENT.equals(e.getNotifyStatus()) && !MonitorOrderAlerts.LOG_ONLY.equals(e.getNotifyStatus()))
            {
                pending.add(e);
            }
        }
        List<MonitorChats.Chat> chats = chatsOf(rule);
        // 每个群：这批待发送的订单都已在这个群送达过，就不再发这个群
        List<MonitorChats.Chat> targets = new ArrayList<>();
        for (MonitorChats.Chat c : chats)
        {
            for (MonitorOrderEvent e : pending)
            {
                if (!sentTo(e, c.chatId()))
                {
                    targets.add(c);
                    break;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ruleId", rule.getRuleId());
        out.put("ruleCode", code);
        if (reason != null || pending.isEmpty() || targets.isEmpty())
        {
            // 只记录，或这批订单都已发送过（重复上报）：不发
            out.put("chatId", chats.isEmpty() ? null : chats.get(0).chatId());
            out.put("chatName", chats.isEmpty() ? null : chats.get(0).name());
            out.put("messageId", null);
            out.put("content", null);
            out.put("chats", List.of());
            out.put("sent", false);
            out.put("notifyStatus", reason != null ? MonitorOrderAlerts.LOG_ONLY : MonitorOrderAlerts.SKIPPED);
            out.put("notifyReason", reason != null ? reason : MonitorOrderAlerts.DUPLICATE);
            out.put("events", events);
            log.info("[monitor] 订单告警不发送 site={} rule={} orders={} reason={} by={}", scope.getSiteCode(), rule.getRuleId(), nos.size(),
                    reason == null ? "已发送过" : reason, operator);
            return out;
        }
        List<ChatResult> results;
        String content = null;
        try
        {
            requireSendable(code, chats);
            content = withSitePrefix(dashboard.siteName(rule.getSiteCode()), text);
            results = deliver(scope, rule, targets, content, operator);
        }
        catch (MonitorException ex)
        {
            // 没配群、凭证没配：每个目标群都记失败
            results = new ArrayList<>();
            for (MonitorChats.Chat c : targets)
            {
                results.add(new ChatResult(c.chatId(), c.name(), false, null, cut(ex.getMessage()), ex.getCode()));
            }
            if (targets.isEmpty())
            {
                results.add(new ChatResult(null, null, false, null, cut(ex.getMessage()), ex.getCode()));
            }
        }
        LocalDateTime at = LocalDateTime.now(statZone());
        String status = MonitorOrderAlerts.FAILED;
        for (MonitorOrderEvent e : pending)
        {
            List<Map<String, Object>> merged = merge(e, chats, results);
            String s = aggregate(merged, chats.size());
            status = s;
            boolean any = results.stream().anyMatch(ChatResult::success);
            eventMapper.updateNotify(scope, e.getEventId(), s, json.writeValueAsString(merged), any ? at : null);
        }
        throwIfAllFailed(results);
        putResults(out, results, content);
        out.put("sent", true);
        out.put("notifyStatus", status);
        out.put("notifyReason", null);
        out.put("events", events);
        return out;
    }

    /** 事件上这个群已经送达过 */
    private boolean sentTo(MonitorOrderEvent e, String chatId)
    {
        for (Map<String, Object> m : chatsJson(e.getNotifyChats()))
        {
            if (chatId.equals(m.get("chatId")) && MonitorOrderAlerts.SENT.equals(m.get("status")))
            {
                return true;
            }
        }
        return false;
    }

    /** 把这次的结果合进事件原有的各群结果（按规则当前的群顺序；已送达的不被覆盖） */
    private List<Map<String, Object>> merge(MonitorOrderEvent e, List<MonitorChats.Chat> chats, List<ChatResult> results)
    {
        Map<String, Map<String, Object>> old = new LinkedHashMap<>();
        for (Map<String, Object> m : chatsJson(e.getNotifyChats()))
        {
            old.put(String.valueOf(m.get("chatId")), m);
        }
        Map<String, ChatResult> now = new HashMap<>();
        for (ChatResult r : results)
        {
            if (r.chatId() != null)
            {
                now.put(r.chatId(), r);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (MonitorChats.Chat c : chats)
        {
            Map<String, Object> prev = old.get(c.chatId());
            ChatResult r = now.get(c.chatId());
            if (prev != null && MonitorOrderAlerts.SENT.equals(prev.get("status")))
            {
                out.add(prev);
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chatId", c.chatId());
            m.put("name", c.name());
            if (r != null)
            {
                m.put("status", r.success() ? MonitorOrderAlerts.SENT : MonitorOrderAlerts.FAILED);
                m.put("messageId", r.messageId());
                m.put("error", r.error());
            }
            else if (prev != null)
            {
                m.putAll(prev);
            }
            else
            {
                m.put("status", MonitorOrderAlerts.FAILED);
                m.put("error", "未發送");
            }
            out.add(m);
        }
        return out;
    }

    /** 全部群送达 SENT；部分 PARTIAL；都没送达 FAILED */
    static String aggregate(List<Map<String, Object>> chats, int total)
    {
        long ok = chats.stream().filter(m -> MonitorOrderAlerts.SENT.equals(m.get("status"))).count();
        if (ok == 0)
        {
            return MonitorOrderAlerts.FAILED;
        }
        return ok >= Math.max(1, total) ? MonitorOrderAlerts.SENT : MonitorOrderAlerts.PARTIAL;
    }

    private List<Map<String, Object>> chatsJson(String text)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        if (StringUtils.isEmpty(text))
        {
            return out;
        }
        try
        {
            for (Object o : json.readValue(text, List.class))
            {
                if (o instanceof Map<?, ?> m)
                {
                    Map<String, Object> row = new LinkedHashMap<>();
                    m.forEach((k, v) -> row.put(String.valueOf(k), v));
                    out.add(row);
                }
            }
        }
        catch (RuntimeException ex)
        {
            out.clear();
        }
        return out;
    }

    private static MonitorOrderEvent event(MonitorRule rule, OrderRuleConfig cfg, MonitorOrderAlerts.Hit h, String reason,
            LocalDateTime now, String operator)
    {
        MonitorOrderEvent e = new MonitorOrderEvent();
        e.setSiteCode(rule.getSiteCode());
        e.setRuleId(rule.getRuleId());
        e.setOrderNo(h.orderNo());
        e.setAmount(h.amount());
        e.setCumCount(h.cumCount());
        e.setCumAmount(h.cumAmount());
        e.setTriggerTime(now);
        e.setLevel(rule.getLevel());
        e.setTriggerPoint(cfg.getTriggerAt());
        e.setAmountMode(cfg.getAmountMode());
        e.setCurrency(rule.getCurrency());
        e.setNotifyStatus(reason == null ? MonitorOrderAlerts.PENDING : MonitorOrderAlerts.LOG_ONLY);
        e.setNotifyReason(reason);
        e.setNotifyChats("[]");
        e.setReportBy(StringUtils.substring(operator, 0, 64));
        e.setCreateTime(now);
        return e;
    }

    private OrderRuleConfig config(MonitorRule rule)
    {
        return StringUtils.isEmpty(rule.getConfig()) ? new OrderRuleConfig() : json.readValue(rule.getConfig(), OrderRuleConfig.class);
    }

    private ZoneId statZone()
    {
        String tz = dashboard.getTimezone();
        return ZoneId.of(StringUtils.isEmpty(tz) ? "Asia/Kolkata" : tz.trim());
    }

    /** 规则的通知群（release_next ㉑）；notify_chats 为空的旧数据退回 notify_chat_id */
    static List<MonitorChats.Chat> chatsOf(MonitorRule rule)
    {
        return MonitorChats.parse(rule.getNotifyChats(), rule.getNotifyChatId(), rule.getNotifyChatName(), rule.getChatStatus());
    }

    /** 没配群、全部群已失效、凭证没配：不调 Lark，直接按错误码返回 */
    private void requireSendable(String code, List<MonitorChats.Chat> chats)
    {
        if (chats.isEmpty())
        {
            throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "規則 " + code + " 沒有配置通知群");
        }
        if (chats.stream().allMatch(c -> "dead".equals(c.status())))
        {
            throw new MonitorException(MonitorErrorCode.CHAT_DEAD, "規則 " + code + " 的通知群已失效（機器人不在群裡），請在規則裡改選群");
        }
        MonitorProperties.Lark lark = properties.getLark();
        if (StringUtils.isEmpty(lark.getAppId()) || StringUtils.isEmpty(lark.getAppSecret()))
        {
            throw new MonitorException(MonitorErrorCode.LARK_CREDENTIAL_INVALID, "Lark 應用憑證未配置");
        }
    }

    /**
     * 按顺序发到每个群：已失效的群跳过（记失败）；某个群凭证失效后其余群不再请求 Lark（同一个应用都会失败）。
     */
    private List<ChatResult> deliver(MonitorScope scope, MonitorRule rule, List<MonitorChats.Chat> chats, String content, String operator)
    {
        MonitorProperties.Lark lark = properties.getLark();
        List<ChatResult> out = new ArrayList<>();
        ChatResult credential = null;
        for (MonitorChats.Chat c : chats)
        {
            String name = StringUtils.isEmpty(c.name()) ? c.chatId() : c.name();
            if ("dead".equals(c.status()))
            {
                out.add(new ChatResult(c.chatId(), name, false, null, "通知群已失效（機器人不在群裡），請在規則裡改選群", MonitorErrorCode.CHAT_DEAD));
                continue;
            }
            if (credential != null)
            {
                out.add(new ChatResult(c.chatId(), name, false, null, credential.error(), credential.code()));
                continue;
            }
            LarkResult<String> r = larkClient.sendPost(lark.getAppId(), lark.getAppSecret(), "chat_id", c.chatId(), "",
                    MonitorLarkPost.paragraphs(content));
            if (r.isSuccess())
            {
                log.info("[monitor] 按规则发告警 site={} rule={} chat={} by={} messageId={}", scope.getSiteCode(), rule.getRuleId(), c.chatId(),
                        operator, r.getData());
                out.add(new ChatResult(c.chatId(), name, true, r.getData(), null, 0));
                continue;
            }
            String reason = MonitorNotifySender.reason(r, false);
            log.warn("[monitor] 按规则发告警失败 site={} rule={} chat={} by={}：{}", scope.getSiteCode(), rule.getRuleId(), c.chatId(), operator, r);
            int code = MonitorErrorCode.LARK_SEND_FAILED;
            if (r.getCategory() == LarkErrorCategory.TARGET_INVALID && chatService.markDeadIfChatGone(scope, c.chatId(), r))
            {
                code = MonitorErrorCode.CHAT_DEAD;
                reason = "通知群已失效：" + reason;
            }
            else if (r.getCategory() == LarkErrorCategory.CREDENTIAL_INVALID)
            {
                code = MonitorErrorCode.LARK_CREDENTIAL_INVALID;
            }
            ChatResult failed = new ChatResult(c.chatId(), name, false, null, cut(reason), code);
            if (code == MonitorErrorCode.LARK_CREDENTIAL_INVALID)
            {
                credential = failed;
            }
            out.add(failed);
        }
        return out;
    }

    /** 全部群都失败：按第一个群的错误码返回（只有一个群时与原来一致） */
    private static void throwIfAllFailed(List<ChatResult> results)
    {
        if (results.isEmpty() || results.stream().anyMatch(ChatResult::success))
        {
            return;
        }
        ChatResult first = results.get(0);
        throw new MonitorException(first.code() == 0 ? MonitorErrorCode.LARK_SEND_FAILED : first.code(),
                (first.name() == null ? "" : "「" + first.name() + "」") + first.error());
    }

    /** chatId / chatName / messageId 为第一个送达的群；chats 为每个群的结果 */
    private static void putResults(Map<String, Object> out, List<ChatResult> results, String content)
    {
        ChatResult first = results.stream().filter(ChatResult::success).findFirst().orElse(results.isEmpty() ? null : results.get(0));
        out.put("chatId", first == null ? null : first.chatId());
        out.put("chatName", first == null ? null : first.name());
        out.put("messageId", first == null ? null : first.messageId());
        out.put("content", content);
        out.put("chats", results.stream().map(ChatResult::view).toList());
    }

    private static String cut(String s)
    {
        return s == null || s.length() <= ERROR_MAX ? s : s.substring(0, ERROR_MAX);
    }

    /** 开头加「【站点名】」；已经以它开头时不重复加 */
    static String withSitePrefix(String siteName, String text)
    {
        if (StringUtils.isEmpty(siteName))
        {
            return text;
        }
        String prefix = "【" + siteName + "】";
        return text.startsWith(prefix) ? text : prefix + text;
    }

    static String ruleCode(Integer ruleNo)
    {
        return String.format(Locale.ROOT, "R-%03d", ruleNo == null ? 0 : ruleNo);
    }
}
