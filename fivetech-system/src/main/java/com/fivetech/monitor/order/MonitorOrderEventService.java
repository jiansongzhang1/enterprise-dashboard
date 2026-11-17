package com.fivetech.monitor.order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.monitor.domain.MonitorOrderEventQuery;
import com.fivetech.monitor.domain.MonitorOrderEventRow;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.OrderRuleConfig;
import com.fivetech.monitor.exception.MonitorErrorCode;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.exception.MonitorFieldException;
import com.fivetech.monitor.mapper.MonitorOrderEventMapper;
import com.fivetech.monitor.service.MonitorOrderAlerts;
import com.fivetech.monitor.service.MonitorOrderRules;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import tools.jackson.databind.ObjectMapper;

/**
 * 监控管理 · 「訂單告警」页签（设计文档 7.3.4）：列表、标记已处理、撤销。
 *
 * <p>订单当前状态、用户账号与注册天数不存库，按页内订单号实时查 UDS（{@link OrderLookup}）；UDS 不可用时列表照常返回、这几项为空，
 * udsOk = false。按订单状态筛选时不能在 SQL 里分页：先取最多 filter-max 条，查 UDS 过滤后在内存分页，这时 UDS 不可用直接报错。</p>
 *
 * <p>库里的时间是统计时区（dashboard.timezone），接口一律换成站点时区；range 按站点时区的自然日。</p>
 *
 * @author fivetech
 */
@Service
public class MonitorOrderEventService
{
    private static final Logger log = LoggerFactory.getLogger(MonitorOrderEventService.class);

    private static final DateTimeFormatter SECOND = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    static final int PAGE_SIZE_MAX = 100;

    static final int CUSTOM_MAX_DAYS = 90;

    static final int HANDLE_MAX_IDS = 100;

    static final int NOTE_MAX = 500;

    static final String PENDING = "PENDING";

    static final String PAID = "PAID";

    static final String REJECTED = "REJECTED";

    static final Map<String, String> STATUS_TEXT = Map.of(PENDING, "審核中", PAID, "已出款", REJECTED, "已拒絕");

    private final MonitorOrderEventMapper mapper;

    private final OrderLookup lookup;

    private final OrderLookupProperties props;

    private final DashboardProperties dashboard;

    private final ObjectMapper json;

    public MonitorOrderEventService(MonitorOrderEventMapper mapper, OrderLookup lookup, OrderLookupProperties props,
            DashboardProperties dashboard, ObjectMapper json)
    {
        this.mapper = mapper;
        this.lookup = lookup;
        this.props = props;
        this.dashboard = dashboard;
        this.json = json;
    }

    /** GET /monitor/order-events 的参数 */
    public static class ListParams
    {
        public String handle;

        public String range;

        public String from;

        public String to;

        public List<Long> ruleId;

        public List<String> orderStatus;

        public List<Long> owner;

        public Integer pageNum;

        public Integer pageSize;
    }

    /** 未处理数（GET /monitor/events 的 orderPendingTotal 用同一个数） */
    public int pendingTotal(MonitorScope scope)
    {
        return mapper.countPending(scope);
    }

    public Map<String, Object> list(MonitorScope scope, ListParams p)
    {
        String site = scope.getSiteCode();
        Map<String, String> errors = new LinkedHashMap<>();
        MonitorOrderEventQuery q = new MonitorOrderEventQuery();
        q.setHandle(handleFilter(p.handle, errors));
        applyRange(q, p.range, p.from, p.to, LocalDate.now(dashboard.siteZone(site)), dashboard.siteZone(site), statZone(), errors);
        q.setRuleIds(ids(p.ruleId));
        q.setOwnerIds(ids(p.owner));
        Set<String> statuses = statusFilter(p.orderStatus, errors);
        int num = p.pageNum == null || p.pageNum < 1 ? 1 : p.pageNum;
        int size = p.pageSize == null || p.pageSize < 1 ? 20 : p.pageSize;
        if (size > PAGE_SIZE_MAX)
        {
            errors.put("pageSize", "每頁最多 " + PAGE_SIZE_MAX + " 條");
        }
        if (!errors.isEmpty())
        {
            throw new MonitorFieldException(errors);
        }

        List<MonitorOrderEventRow> rows;
        long total;
        Map<String, OrderLookup.OrderInfo> orders = new HashMap<>();
        boolean udsOk = lookup.available();
        if (statuses.isEmpty())
        {
            try (Page<MonitorOrderEventRow> page = PageHelper.startPage(num, size))
            {
                rows = new ArrayList<>(mapper.selectOrderEventList(scope, q));
                total = page.getTotal();
            }
            if (udsOk && !rows.isEmpty())
            {
                try
                {
                    orders = lookup.orders(site, orderNos(rows), earliest(rows));
                }
                catch (RuntimeException e)
                {
                    log.warn("[monitor] 订单告警查 UDS 订单状态失败 site={} : {}", site, e.getMessage());
                    udsOk = false;
                }
            }
        }
        else
        {
            if (!udsOk)
            {
                throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "訂單狀態暫時取不到，無法按訂單狀態篩選");
            }
            int max = Math.max(1, props.getFilterMax());
            List<MonitorOrderEventRow> all;
            try (Page<MonitorOrderEventRow> page = PageHelper.startPage(1, max + 1, false))
            {
                all = new ArrayList<>(mapper.selectOrderEventList(scope, q));
            }
            if (all.size() > max)
            {
                throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "符合條件的訂單太多，請縮小時間範圍或選擇規則後再按訂單狀態篩選");
            }
            try
            {
                orders = all.isEmpty() ? orders : lookup.orders(site, orderNos(all), earliest(all));
            }
            catch (RuntimeException e)
            {
                log.warn("[monitor] 订单告警按状态筛选查 UDS 失败 site={} : {}", site, e.getMessage());
                throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "訂單狀態暫時取不到，請稍後再試");
            }
            List<MonitorOrderEventRow> hit = new ArrayList<>();
            for (MonitorOrderEventRow r : all)
            {
                OrderLookup.OrderInfo o = orders.get(r.getOrderNo());
                if (o != null && statuses.contains(o.status()))
                {
                    hit.add(r);
                }
            }
            total = hit.size();
            int from = Math.min(hit.size(), (num - 1) * size);
            rows = hit.subList(from, Math.min(hit.size(), from + size));
        }

        Map<String, OrderLookup.UserInfo> users = new HashMap<>();
        if (udsOk && !rows.isEmpty())
        {
            Set<String> uids = new LinkedHashSet<>();
            for (MonitorOrderEventRow r : rows)
            {
                OrderLookup.OrderInfo o = orders.get(r.getOrderNo());
                if (o != null && StringUtils.isNotEmpty(o.userId()))
                {
                    uids.add(o.userId());
                }
            }
            try
            {
                users = uids.isEmpty() ? users : lookup.users(site, uids);
            }
            catch (RuntimeException e)
            {
                log.warn("[monitor] 订单告警查 UDS 用户失败 site={} : {}", site, e.getMessage());
                udsOk = false;
            }
        }

        String symbol = dashboard.siteCurrencySymbol(site);
        LocalDate today = LocalDate.now(dashboard.siteZone(site));
        List<Map<String, Object>> out = new ArrayList<>();
        for (MonitorOrderEventRow r : rows)
        {
            out.add(toRow(r, site, symbol, today, orders.get(r.getOrderNo()), users));
        }
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("total", total);
        vo.put("pendingTotal", mapper.countPending(scope));
        vo.put("udsOk", udsOk);
        vo.put("pageNum", num);
        vo.put("pageSize", size);
        vo.put("rows", out);
        return vo;
    }

    @Transactional(rollbackFor = Exception.class)
    public int handle(MonitorScope scope, List<Long> eventIds, String note)
    {
        List<Long> ids = ids(eventIds);
        if (ids == null)
        {
            throw MonitorFieldException.of("eventIds", "請選擇事件");
        }
        if (ids.size() > HANDLE_MAX_IDS)
        {
            throw MonitorFieldException.of("eventIds", "一次最多處理 " + HANDLE_MAX_IDS + " 條事件");
        }
        String text = note == null ? "" : note.trim();
        if (text.isEmpty())
        {
            throw MonitorFieldException.of("note", "請填寫處理說明");
        }
        if (text.codePointCount(0, text.length()) > NOTE_MAX)
        {
            throw MonitorFieldException.of("note", "處理說明不能超過 " + NOTE_MAX + " 字");
        }
        // 不做部分成功：有一条不属于本站（或不存在）就整体 404
        if (mapper.countByIds(scope, ids) != ids.size())
        {
            throw new MonitorException(MonitorErrorCode.NOT_FOUND, "事件不存在");
        }
        String by = scope.getUserId() == null ? null : String.valueOf(scope.getUserId());
        return mapper.markHandled(scope, ids, text, by, LocalDateTime.now(statZone()).truncatedTo(ChronoUnit.SECONDS));
    }

    @Transactional(rollbackFor = Exception.class)
    public void unhandle(MonitorScope scope, Long eventId)
    {
        if (eventId == null || mapper.unhandle(scope, eventId) == 0)
        {
            throw new MonitorException(MonitorErrorCode.NOT_FOUND, "事件不存在");
        }
    }

    // ------------------------------------------------------------------ 组装

    Map<String, Object> toRow(MonitorOrderEventRow e, String site, String symbol, LocalDate today, OrderLookup.OrderInfo order,
            Map<String, OrderLookup.UserInfo> users)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", e.getEventId());
        m.put("triggerTime", fmt(dashboard.toSiteTime(site, e.getTriggerTime()), SECOND));
        m.put("triggerPoint", e.getTriggerPoint());
        m.put("triggerLabel", MonitorOrderRules.PAID.equals(e.getTriggerPoint()) ? "出款時間" : "提交時間");
        m.put("orderNo", e.getOrderNo());

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ruleId", e.getRuleId());
        rule.put("ruleCode", MonitorOrderRules.ruleCode(e.getRuleNo()));
        rule.put("name", e.getRuleName());
        rule.put("summary", summary(e.getRuleConfig(), symbol));
        rule.put("description", e.getRuleDescription());
        rule.put("ownerName", e.getOwnerName());
        rule.put("enabled", e.getRuleEnabled());
        rule.put("deleted", Boolean.TRUE.equals(e.getRuleDeleted()));
        m.put("rule", rule);

        m.put("level", e.getLevel());
        m.put("amountMode", e.getAmountMode());
        m.put("currency", e.getCurrency());
        m.put("amount", plain(e.getAmount()));
        m.put("cumAmount", plain(e.getCumAmount()));
        m.put("cumCount", e.getCumCount());

        Map<String, Object> o = new LinkedHashMap<>();
        o.put("status", order == null ? null : order.status());
        o.put("statusText", order == null || order.status() == null ? null : STATUS_TEXT.getOrDefault(order.status(), order.status()));
        m.put("order", o);

        Map<String, Object> u = new LinkedHashMap<>();
        String uid = order == null ? null : order.userId();
        OrderLookup.UserInfo info = uid == null ? null : users.get(uid);
        u.put("uid", uid);
        u.put("account", info == null ? null : info.account());
        u.put("registerDays", info == null || info.registerTime() == null ? null
            : Math.max(0, ChronoUnit.DAYS.between(info.registerTime().toLocalDate(), today)));
        m.put("user", u);

        m.put("notify", notify(e, site));

        Map<String, Object> h = new LinkedHashMap<>();
        h.put("status", e.getHandleStatus());
        h.put("note", e.getHandleNote());
        h.put("by", "done".equals(e.getHandleStatus()) ? e.getHandleByName() : null);
        h.put("at", fmt(dashboard.toSiteTime(site, e.getHandleTime()), MINUTE));
        m.put("handle", h);
        return m;
    }

    /** 通知送達：汇总状态 + 每个群的结果；chatName 取第一个群，error 取第一个失败群的原因 */
    private Map<String, Object> notify(MonitorOrderEventRow e, String site)
    {
        List<Map<String, Object>> chats = new ArrayList<>();
        String chatName = null;
        String error = null;
        if (StringUtils.isNotEmpty(e.getNotifyChats()))
        {
            try
            {
                for (Object x : json.readValue(e.getNotifyChats(), List.class))
                {
                    if (x instanceof Map<?, ?> c)
                    {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("chatId", c.get("chatId"));
                        row.put("name", c.get("name"));
                        row.put("status", c.get("status"));
                        row.put("error", c.get("error"));
                        chats.add(row);
                        if (chatName == null && c.get("name") != null)
                        {
                            chatName = String.valueOf(c.get("name"));
                        }
                        if (error == null && MonitorOrderAlerts.FAILED.equals(c.get("status")) && c.get("error") != null)
                        {
                            error = String.valueOf(c.get("error"));
                        }
                    }
                }
            }
            catch (RuntimeException ex)
            {
                log.warn("[monitor] 订单告警事件 {} 的 notify_chats 解析失败: {}", e.getEventId(), ex.getMessage());
            }
        }
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("status", e.getNotifyStatus());
        n.put("reason", e.getNotifyReason());
        n.put("chatName", chatName);
        n.put("sentAt", fmt(dashboard.toSiteTime(site, e.getNotifiedAt()), MINUTE));
        n.put("error", error);
        n.put("chats", chats);
        return n;
    }

    private String summary(String config, String symbol)
    {
        if (StringUtils.isEmpty(config))
        {
            return null;
        }
        try
        {
            return MonitorOrderRules.summary(json.readValue(config, OrderRuleConfig.class), symbol);
        }
        catch (RuntimeException ex)
        {
            return null;
        }
    }

    // ------------------------------------------------------------------ 参数

    /** pending（默认）/ done / all */
    static String handleFilter(String handle, Map<String, String> errors)
    {
        String h = handle == null ? "" : handle.trim().toLowerCase(Locale.ROOT);
        switch (h)
        {
            case "":
            case "pending":
                return "pending";
            case "done":
                return "done";
            case "all":
                return null;
            default:
                errors.put("handle", "handle 只能是 pending / done / all");
                return null;
        }
    }

    /**
     * range → trigger_time 区间：按站点时区的自然日算，再换成统计时区（库里的时区）。custom 带 from、to（yyyy-MM-dd，含两端，最多 90 天）。
     */
    static void applyRange(MonitorOrderEventQuery q, String range, String from, String to, LocalDate today, ZoneId siteZone,
            ZoneId statZone, Map<String, String> errors)
    {
        String r = range == null ? "" : range.trim().toLowerCase(Locale.ROOT);
        LocalDate start = null;
        LocalDate end = null;
        switch (r)
        {
            case "":
            case "all":
                return;
            case "today":
                start = today;
                break;
            case "yest":
                start = today.minusDays(1);
                end = today;
                break;
            case "d7":
                start = today.minusDays(6);
                break;
            case "d30":
                start = today.minusDays(29);
                break;
            case "custom":
                LocalDate f = date(from, "from", errors);
                LocalDate t = date(to, "to", errors);
                if (f == null || t == null)
                {
                    return;
                }
                if (f.isAfter(t))
                {
                    errors.put("to", "結束日期不能早於開始日期");
                    return;
                }
                if (ChronoUnit.DAYS.between(f, t) + 1 > CUSTOM_MAX_DAYS)
                {
                    errors.put("to", "自訂範圍最多 " + CUSTOM_MAX_DAYS + " 天");
                    return;
                }
                start = f;
                end = t.plusDays(1);
                break;
            default:
                errors.put("range", "range 只能是 all / today / yest / d7 / d30 / custom");
                return;
        }
        q.setRangeFrom(toStat(start.atStartOfDay(), siteZone, statZone));
        q.setRangeTo(end == null ? null : toStat(end.atStartOfDay(), siteZone, statZone));
    }

    /** PENDING / PAID / REJECTED，多选 */
    static Set<String> statusFilter(Collection<String> values, Map<String, String> errors)
    {
        Set<String> out = new LinkedHashSet<>();
        if (values == null)
        {
            return out;
        }
        for (String v : values)
        {
            if (StringUtils.isEmpty(v))
            {
                continue;
            }
            for (String part : v.split(","))
            {
                String s = part.trim().toUpperCase(Locale.ROOT);
                if (s.isEmpty())
                {
                    continue;
                }
                if (!STATUS_TEXT.containsKey(s))
                {
                    errors.put("orderStatus", "orderStatus 只能是 PENDING / PAID / REJECTED");
                    continue;
                }
                out.add(s);
            }
        }
        return out;
    }

    private static LocalDate date(String s, String field, Map<String, String> errors)
    {
        if (StringUtils.isEmpty(s))
        {
            errors.put(field, "自訂範圍請選擇日期");
            return null;
        }
        try
        {
            return LocalDate.parse(s.trim());
        }
        catch (DateTimeParseException e)
        {
            errors.put(field, "日期格式須為 yyyy-MM-dd");
            return null;
        }
    }

    private static LocalDateTime toStat(LocalDateTime siteTime, ZoneId siteZone, ZoneId statZone)
    {
        return siteTime.atZone(siteZone).withZoneSameInstant(statZone).toLocalDateTime();
    }

    /** 去空去重；没有 = null（不筛） */
    static List<Long> ids(Collection<Long> values)
    {
        if (values == null)
        {
            return null;
        }
        Set<Long> out = new LinkedHashSet<>();
        for (Long v : values)
        {
            if (v != null)
            {
                out.add(v);
            }
        }
        return out.isEmpty() ? null : new ArrayList<>(out);
    }

    private static List<String> orderNos(List<MonitorOrderEventRow> rows)
    {
        List<String> out = new ArrayList<>();
        rows.forEach(r -> out.add(r.getOrderNo()));
        return out;
    }

    private static LocalDateTime earliest(List<MonitorOrderEventRow> rows)
    {
        LocalDateTime min = null;
        for (MonitorOrderEventRow r : rows)
        {
            if (r.getTriggerTime() != null && (min == null || r.getTriggerTime().isBefore(min)))
            {
                min = r.getTriggerTime();
            }
        }
        return min;
    }

    private ZoneId statZone()
    {
        String tz = dashboard.getTimezone();
        return ZoneId.of(StringUtils.isEmpty(tz) ? "Asia/Kolkata" : tz.trim());
    }

    private static String fmt(LocalDateTime t, DateTimeFormatter f)
    {
        return t == null ? null : t.format(f);
    }

    private static BigDecimal plain(BigDecimal v)
    {
        if (v == null)
        {
            return null;
        }
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 2 ? s.setScale(2) : s;
    }
}
