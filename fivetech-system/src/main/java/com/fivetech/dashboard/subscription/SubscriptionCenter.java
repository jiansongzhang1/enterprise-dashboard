package com.fivetech.dashboard.subscription;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.mapper.DashboardSubscriptionMapper;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.vo.MonitorLarkChatOptionVO;
import com.fivetech.monitor.domain.vo.MonitorLarkChatsVO;
import com.fivetech.monitor.domain.vo.MonitorOwnerVO;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.exception.MonitorFieldException;
import com.fivetech.monitor.service.IMonitorLarkChatService;
import com.fivetech.monitor.service.IMonitorRuleService;

/**
 * 站点页面 · 订阅管理（V0.4 原型：頁簽「訂閱規則｜發送歷史」）。所有操作限当前站点（X-Site-Code），别的站点的订阅当作不存在。
 *
 * <ul>
 *   <li>订阅规则列表：状态（啟用 / 已暫停 / 異常）与原因、负责人、接收群（失效标注）、下次发送、上次结果（按群汇总）、能否修改；
 *       筛选 状态、负责人（含「我」）、接收群，都可多选；</li>
 *   <li>发送历史：一次发送 × 一个接收群一行，新的在前，每页 20 条；筛选 时间（今日 / 昨日 / 近 7 天（默认）/ 近 30 天 / 全部 /
 *       自訂 ≤ 90 天）、订阅（含已删除）、结果、接收群（只列历史里出现过的群）。</li>
 * </ul>
 * 新增、修改、启停、删除、立即发送、测试发送委托 {@link SubscriptionService}，先校验订阅属于当前站点。
 *
 * @author fivetech
 */
@Service
public class SubscriptionCenter
{
    private static final Logger log = LoggerFactory.getLogger(SubscriptionCenter.class);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 发送历史的自訂时间最多多少天 */
    static final int HISTORY_MAX_DAYS = 90;

    static final int PAGE_SIZE = 20;

    private final SubscriptionService service;

    private final DashboardSubscriptionMapper mapper;

    private final SubscriptionSender sender;

    private final DashboardProperties dashboard;

    private final MonitorProperties monitorProperties;

    private final IMonitorLarkChatService chatService;

    private final IMonitorRuleService ruleService;

    public SubscriptionCenter(SubscriptionService service, DashboardSubscriptionMapper mapper, SubscriptionSender sender,
            DashboardProperties dashboard, MonitorProperties monitorProperties, IMonitorLarkChatService chatService,
            IMonitorRuleService ruleService)
    {
        this.service = service;
        this.mapper = mapper;
        this.sender = sender;
        this.dashboard = dashboard;
        this.monitorProperties = monitorProperties;
        this.chatService = chatService;
        this.ruleService = ruleService;
    }

    // ===================== 订阅规则 =====================

    /**
     * 订阅规则列表。
     *
     * @param states 状态：ok / off / err，空 = 全部
     * @param owners 负责人用户 ID，或 "me"
     * @param chats  接收群 chat_id
     */
    public List<Map<String, Object>> list(String site, List<String> states, List<String> owners, List<String> chats,
            SubscriptionActor actor)
    {
        Context ctx = context(site);
        List<Map<String, Object>> out = new ArrayList<>();
        for (DashboardSubscription s : mapper.selectList(site, null))
        {
            Map<String, Object> m = row(s, ctx, actor);
            if (!states.isEmpty() && !states.contains(String.valueOf(m.get("state"))))
            {
                continue;
            }
            if (!owners.isEmpty() && !owners.stream().anyMatch(o -> "me".equals(o)
                ? actor.getUserId() != null && actor.getUserId().equals(s.getOwnerUserId())
                : o.equals(String.valueOf(s.getOwnerUserId()))))
            {
                continue;
            }
            if (!chats.isEmpty())
            {
                Set<String> mine = sender.receivers(s).stream().map(SubscriptionReceiver::getId).collect(Collectors.toSet());
                if (chats.stream().noneMatch(mine::contains))
                {
                    continue;
                }
            }
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> get(String site, Long id, SubscriptionActor actor)
    {
        return row(requireSite(site, id), context(site), actor);
    }

    /** 列表、筛选要用的本站状况：群状态、负责人候选、各订阅上次结果 */
    static final class Context
    {
        /** chat_id → ok / dead；null = 拉取失败且没有上次结果（不判断群失效） */
        Map<String, String> chatStatus;

        Map<String, String> chatNames = new HashMap<>();

        Map<Long, String> ownerEmails = new HashMap<>();

        Map<Long, DashboardSubscriptionRun> lastRuns = new HashMap<>();

        boolean botConfigured;
    }

    Context context(String site)
    {
        Context ctx = new Context();
        MonitorScope scope = new MonitorScope(site, null);
        if (chatService != null)
        {
            try
            {
                MonitorLarkChatsVO vo = chatService.listChats(scope);
                ctx.chatStatus = new HashMap<>();
                for (MonitorLarkChatOptionVO c : vo.getChats())
                {
                    ctx.chatStatus.put(c.getChatId(), c.getStatus());
                    ctx.chatNames.put(c.getChatId(), c.getName());
                }
            }
            catch (RuntimeException e)
            {
                log.warn("[subscription] 读取本站群状态失败 site={}：{}", site, e.getMessage());
            }
        }
        if (ruleService != null)
        {
            for (MonitorOwnerVO o : ruleService.listOwners(scope))
            {
                ctx.ownerEmails.put(o.getUserId(), o.getEmail());
            }
        }
        try
        {
            for (DashboardSubscriptionRun r : mapper.selectLastRuns(site))
            {
                ctx.lastRuns.put(r.getSubscriptionId(), r);
            }
        }
        catch (RuntimeException e)
        {
            log.warn("[subscription] 读取上次发送结果失败 site={}（确认已执行 release_next ⑭）：{}", site, e.getMessage());
        }
        MonitorProperties.Lark lark = monitorProperties == null ? null : monitorProperties.getLark();
        ctx.botConfigured = lark != null && notBlank(lark.getAppId()) && notBlank(lark.getAppSecret());
        return ctx;
    }

    /** 列表一行：订阅视图 + 状态与原因 + 接收群状态 + 负责人 + 上次结果 + 能否修改 */
    Map<String, Object> row(DashboardSubscription s, Context ctx, SubscriptionActor actor)
    {
        Map<String, Object> m = service.view(s);
        List<Map<String, Object>> groups = new ArrayList<>();
        List<String> dead = new ArrayList<>();
        int okGroups = 0;
        for (SubscriptionReceiver r : sender.receivers(s))
        {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("chatId", r.getId());
            String name = ctx.chatNames.getOrDefault(r.getId(), r.getName());
            g.put("name", name);
            String st = ctx.chatStatus == null ? "ok" : "ok".equals(ctx.chatStatus.get(r.getId())) ? "ok" : "dead";
            g.put("status", st);
            if ("ok".equals(st))
            {
                okGroups++;
            }
            else
            {
                dead.add(name);
            }
            groups.add(g);
        }
        m.put("chats", groups);
        m.put("okChats", okGroups);
        String ownerEmail = s.getOwnerUserId() == null ? null : ctx.ownerEmails.get(s.getOwnerUserId());
        m.put("ownerEmail", ownerEmail);
        m.put("ownerValid", s.getOwnerUserId() == null || ownerEmail != null);
        DashboardSubscriptionRun last = ctx.lastRuns.get(s.getId());
        if (last != null)
        {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("runId", last.getId());
            l.put("at", last.getSentAt() == null ? null : last.getSentAt().format(TIME));
            l.put("result", last.getResult());
            l.put("resultText", SubscriptionReason.resultText(last.getResult()));
            SubscriptionReason why = SubscriptionReason.of(last.getReasonCode());
            l.put("reasonText", why != null ? why.getText() : last.getError());
            m.put("lastRun", l);
        }
        else
        {
            m.put("lastRun", null);
        }
        List<String> reasons = reasons(s, ctx, dead, okGroups, ownerEmail, last);
        String state = !s.isOn() ? "off" : reasons.isEmpty() ? "ok" : "err";
        m.put("state", state);
        m.put("stateText", "off".equals(state) ? "已暫停" : "err".equals(state) ? "異常" : "啟用");
        m.put("stateReasons", reasons);
        boolean canEdit = actor == null || actor.canEdit(s);
        m.put("canEdit", canEdit);
        m.put("canSend", canEdit && s.isOn() && okGroups > 0);
        m.put("sendDisabledReason", !s.isOn() ? "訂閱已暫停" : okGroups == 0 ? "沒有正常的接收群，無法發送" : null);
        return m;
    }

    /** 「異常」的原因（V0.4 原型 pStat）：群失效、机器人、负责人、最近一次发送失败 / 部分失败 */
    static List<String> reasons(DashboardSubscription s, Context ctx, List<String> dead, int okGroups, String ownerEmail,
            DashboardSubscriptionRun last)
    {
        List<String> out = new ArrayList<>();
        if (!ctx.botConfigured)
        {
            out.add("本站尚未接入 Lark 機器人，不會發送");
        }
        else if (last != null && SubscriptionReason.SKIP.equals(last.getResult()))
        {
            out.add("Lark 機器人憑證失效，暫停發送；恢復後自動發送");
        }
        if (okGroups == 0)
        {
            out.add("接收群全部失效，不會發送");
        }
        else if (!dead.isEmpty())
        {
            out.add("接收群「" + String.join("、", dead) + "」失效，其餘照常發送");
        }
        if (s.getOwnerUserId() != null && ownerEmail == null)
        {
            out.add("負責人的帳號已停用或沒有本站「訂閱管理」權限，請轉交");
        }
        if (last != null && (SubscriptionReason.FAIL.equals(last.getResult()) || SubscriptionReason.PART.equals(last.getResult()))
            && dead.isEmpty())
        {
            SubscriptionReason why = SubscriptionReason.of(last.getReasonCode());
            String text = why != null ? why.getText() : last.getError();
            out.add("最近一次發送" + SubscriptionReason.resultText(last.getResult()) + (text == null ? "" : "：" + text));
        }
        return out;
    }

    // ===================== 写入（校验站点与权限后委托） =====================

    public Map<String, Object> create(String site, Map<String, Object> body, SubscriptionActor actor)
    {
        Map<String, Object> b = new LinkedHashMap<>(body);
        b.put("siteCode", site);
        Map<String, Object> v = service.create(b, actor);
        return get(site, ((Number) v.get("id")).longValue(), actor);
    }

    public Map<String, Object> update(String site, Long id, Map<String, Object> body, SubscriptionActor actor)
    {
        requireSite(site, id);
        Map<String, Object> b = new LinkedHashMap<>(body);
        b.remove("siteCode");
        service.update(id, b, actor);
        return get(site, id, actor);
    }

    public Map<String, Object> setEnabled(String site, Long id, boolean on, SubscriptionActor actor)
    {
        requireSite(site, id);
        service.setEnabled(id, on, actor);
        return get(site, id, actor);
    }

    public void delete(String site, Long id, SubscriptionActor actor)
    {
        requireSite(site, id);
        service.delete(id, actor);
    }

    public Map<String, Object> sendNow(String site, Long id, SubscriptionActor actor)
    {
        requireSite(site, id);
        return service.sendNow(id, actor);
    }

    public Map<String, Object> test(String site, Map<String, Object> body, SubscriptionActor actor)
    {
        Map<String, Object> b = new LinkedHashMap<>(body);
        b.put("siteCode", site);
        return service.testForm(b, actor);
    }

    public Map<String, Object> preview(String site, Map<String, Object> body, String operator)
    {
        Map<String, Object> b = new LinkedHashMap<>(body);
        b.put("siteCode", site);
        return service.preview(b, operator);
    }

    DashboardSubscription requireSite(String site, Long id)
    {
        DashboardSubscription s = service.require(id);
        if (site == null || !site.equalsIgnoreCase(s.getSiteCode()))
        {
            throw MonitorException.notFound("訂閱不存在或已刪除");
        }
        return s;
    }

    // ===================== 发送历史 =====================

    /**
     * 发送历史。
     *
     * @param range today / yesterday / d7（默认）/ d30 / all / custom（from、to 为 yyyy-MM-dd，含两端，最多 90 天）
     * @return { total, pageNum, pageSize, rows: [...] }
     */
    public Map<String, Object> history(String site, String range, String from, String to, List<Long> subscriptionIds,
            List<String> results, List<String> chatIds, int pageNum, int pageSize)
    {
        SubscriptionHistoryQuery q = new SubscriptionHistoryQuery();
        q.setSiteCode(site);
        LocalDate today = LocalDate.now(sender.zone(site));
        LocalDateTime[] w = historyWindow(range, from, to, today);
        q.setFrom(w[0]);
        q.setTo(w[1]);
        q.setSubscriptionIds(subscriptionIds);
        List<String> rs = new ArrayList<>();
        for (String r : results)
        {
            String x = r == null ? "" : r.trim().toUpperCase(java.util.Locale.ROOT);
            if (List.of(SubscriptionReason.OK, SubscriptionReason.PART, SubscriptionReason.FAIL, SubscriptionReason.SKIP).contains(x))
            {
                rs.add(x);
            }
        }
        q.setResults(rs);
        q.setChatIds(chatIds);
        int size = pageSize <= 0 ? PAGE_SIZE : Math.min(pageSize, 100);
        int page = Math.max(1, pageNum);
        q.setLimit(size);
        q.setOffset((page - 1) * size);
        long total = mapper.countHistory(q);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SubscriptionHistoryRow r : mapper.selectHistory(q))
        {
            rows.add(historyRow(r));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("pageNum", page);
        out.put("pageSize", size);
        out.put("from", w[0] == null ? null : w[0].toLocalDate().toString());
        out.put("to", w[1] == null ? null : w[1].toLocalDate().minusDays(1).toString());
        out.put("rows", rows);
        return out;
    }

    /** 时间范围 → [from, to)（站点时区）；全部 = 不限 */
    static LocalDateTime[] historyWindow(String range, String from, String to, LocalDate today)
    {
        String r = range == null || range.isBlank() ? "d7" : range.trim().toLowerCase(java.util.Locale.ROOT);
        switch (r)
        {
            case "today":
                return new LocalDateTime[] { today.atStartOfDay(), today.plusDays(1).atStartOfDay() };
            case "yesterday":
                return new LocalDateTime[] { today.minusDays(1).atStartOfDay(), today.atStartOfDay() };
            case "d7":
                return new LocalDateTime[] { today.minusDays(6).atStartOfDay(), today.plusDays(1).atStartOfDay() };
            case "d30":
                return new LocalDateTime[] { today.minusDays(29).atStartOfDay(), today.plusDays(1).atStartOfDay() };
            case "all":
                return new LocalDateTime[] { null, null };
            case "custom":
                LocalDate f = SubscriptionService.dateOrNull(from);
                LocalDate t = SubscriptionService.dateOrNull(to);
                if (f == null || t == null || t.isBefore(f))
                {
                    throw MonitorFieldException.of("from", "自訂時間請填起止日期（yyyy-MM-dd），結束不能早於開始");
                }
                if (ChronoUnit.DAYS.between(f, t) + 1 > HISTORY_MAX_DAYS)
                {
                    throw MonitorFieldException.of("to", "單次最多 " + HISTORY_MAX_DAYS + " 天");
                }
                if (t.isAfter(today))
                {
                    throw MonitorFieldException.of("to", "最晚到今天");
                }
                return new LocalDateTime[] { f.atStartOfDay(), t.plusDays(1).atStartOfDay() };
            default:
                throw MonitorFieldException.of("range", "時間只能是 today / yesterday / d7 / d30 / all / custom");
        }
    }

    static Map<String, Object> historyRow(SubscriptionHistoryRow r)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("logId", r.getLogId());
        m.put("runId", r.getRunId());
        m.put("sentAt", r.getSentAt() == null ? null : r.getSentAt().format(TIME));
        m.put("planAt", r.getPlanAt() == null ? null : r.getPlanAt().format(TIME));
        boolean manual = SubscriptionSender.MANUAL.equals(r.getTriggerType());
        m.put("manual", manual);
        m.put("operator", manual ? r.getOperator() : null);
        m.put("subscriptionId", r.getSubscriptionId());
        m.put("subscriptionName", r.getSubscriptionName());
        m.put("deleted", Boolean.TRUE.equals(r.getDeleted()));
        m.put("chatId", r.getReceiverId());
        m.put("chatName", r.getReceiverName());
        m.put("period", r.getPeriod());
        m.put("comparePeriod", r.getComparePeriod());
        String result = r.getResult() == null ? SubscriptionReason.FAIL : r.getResult();
        m.put("result", result);
        m.put("resultText", SubscriptionReason.resultText(result));
        SubscriptionReason why = SubscriptionReason.of(r.getReasonCode());
        m.put("reasonCode", why == null ? null : why.getCode());
        m.put("reasonText", why == null ? null : why.getText());
        m.put("reasonNote", why == null ? null : why.getNote());
        m.put("attempts", r.getAttempts());
        return m;
    }

    /** 发送历史的筛选项：订阅（含已删除）、接收群（历史里出现过的）、结果 */
    public Map<String, Object> historyOptions(String site)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subscriptions", mapper.selectAllWithDeleted(site));
        out.put("chats", mapper.selectHistoryChats(site));
        List<Map<String, Object>> results = new ArrayList<>();
        for (String r : List.of(SubscriptionReason.OK, SubscriptionReason.PART, SubscriptionReason.FAIL, SubscriptionReason.SKIP))
        {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("code", r);
            x.put("label", SubscriptionReason.resultText(r));
            results.add(x);
        }
        out.put("results", results);
        List<Map<String, Object>> reasons = new ArrayList<>();
        for (SubscriptionReason r : SubscriptionReason.values())
        {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("code", r.getCode());
            x.put("result", r.getResult());
            x.put("text", r.getText());
            x.put("note", r.getNote());
            reasons.add(x);
        }
        out.put("reasons", reasons);
        return out;
    }

    /** 当前站点规范写法；不存在 404 */
    public String site(String raw)
    {
        String code = raw == null ? null : dashboard.canonical(raw.trim());
        if (code == null)
        {
            throw MonitorException.notFound("站點不存在");
        }
        return code;
    }

    private static boolean notBlank(String s)
    {
        return s != null && !s.trim().isEmpty();
    }
}
