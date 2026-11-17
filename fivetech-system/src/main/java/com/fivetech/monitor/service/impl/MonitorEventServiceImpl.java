package com.fivetech.monitor.service.impl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.monitor.domain.MonitorEventQuery;
import com.fivetech.monitor.domain.MonitorEventRow;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.vo.MonitorEventPageVO;
import com.fivetech.monitor.domain.vo.MonitorEventVO;
import com.fivetech.monitor.engine.MonitorMessageBuilder;
import com.fivetech.monitor.engine.judge.JudgeRule;
import com.fivetech.monitor.engine.judge.Levels;
import com.fivetech.monitor.exception.MonitorErrorCode;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.exception.MonitorFieldException;
import com.fivetech.monitor.mapper.MonitorEventMapper;
import com.fivetech.monitor.mapper.MonitorOverviewMapper;
import com.fivetech.monitor.service.IMonitorEventService;
import com.fivetech.monitor.service.MonitorChats;
import com.fivetech.monitor.service.MonitorSnapshot;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import tools.jackson.databind.ObjectMapper;

/**
 * 监控管理 · 异常事件：列表、标记已处理、撤销处理。阈值、判定类型、周期一律取事件的规则快照，不读当前规则。
 *
 * @author fivetech
 */
@Service
public class MonitorEventServiceImpl implements IMonitorEventService
{
    private static final Logger log = LoggerFactory.getLogger(MonitorEventServiceImpl.class);

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final Set<Integer> PAGE_SIZES = Set.of(20, 30, 50, 100);

    static final int HANDLE_MAX_IDS = 100;

    static final int NOTE_MAX = 500;

    private final MonitorEventMapper eventMapper;

    private final MonitorOverviewMapper overviewMapper;

    private final MonitorMessageBuilder messages;

    private final ObjectMapper json;

    private final ZoneId zone;

    public MonitorEventServiceImpl(MonitorEventMapper eventMapper, MonitorOverviewMapper overviewMapper,
            MonitorMessageBuilder messages, ObjectMapper json, DashboardProperties dashboard)
    {
        this.eventMapper = eventMapper;
        this.overviewMapper = overviewMapper;
        this.messages = messages;
        this.json = json;
        this.zone = ZoneId.of(dashboard.getTimezone());
    }

    @Override
    public MonitorEventPageVO listEvents(MonitorScope scope, String range, String handle, String metricCode, String status,
            Long ownerId, Integer pageNum, Integer pageSize)
    {
        MonitorEventQuery q = new MonitorEventQuery();
        q.setHandle(handle);
        q.setStatus(status);
        q.setMetricCode(metricCode);
        q.setOwnerId(ownerId);
        return listEvents(scope, q, range, null, null, pageNum, pageSize);
    }

    @Override
    public MonitorEventPageVO listEvents(MonitorScope scope, MonitorEventQuery in, String range, String from, String to,
            Integer pageNum, Integer pageSize)
    {
        MonitorEventQuery q = new MonitorEventQuery();
        q.setHandle(handleFilter(in.getHandle()));
        q.setStatus(statusFilter(in.getStatus()));
        q.setMetricCode(StringUtils.trim(in.getMetricCode()));
        q.setOwnerId(in.getOwnerId());
        q.setRuleTypes(upperList(in.getRuleTypes(), Set.of("METRIC", "COND")));
        q.setMetricCodes(trimList(in.getMetricCodes()));
        List<String> statuses = new ArrayList<>();
        for (String st : trimList(in.getStatuses()))
        {
            String v = statusFilter(st);
            if (v != null && !statuses.contains(v))
            {
                statuses.add(v);
            }
        }
        q.setStatuses(statuses);
        q.setOwnerIds(in.getOwnerIds() == null ? List.of() : in.getOwnerIds().stream().filter(java.util.Objects::nonNull).distinct().toList());
        if ("custom".equalsIgnoreCase(StringUtils.trim(range)))
        {
            applyCustomRange(q, from, to);
        }
        else
        {
            applyRange(q, range, LocalDate.now(zone));
        }

        int num = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize != null && PAGE_SIZES.contains(pageSize) ? pageSize : 20;

        LocalDateTime lastJudged = overviewMapper.selectLastJudgedHour(scope);
        LocalDateTime asOf = lastJudged == null ? null : lastJudged.plusHours(1);

        MonitorEventPageVO vo = new MonitorEventPageVO();
        vo.setAsOf(fmt(asOf));
        vo.setPendingTotal(overviewMapper.countPendingEvents(scope));
        vo.setPageNum(num);
        vo.setPageSize(size);
        try (Page<MonitorEventRow> page = PageHelper.startPage(num, size))
        {
            List<MonitorEventRow> rows = eventMapper.selectEventList(scope, q);
            vo.setTotal(page.getTotal());
            for (MonitorEventRow row : rows)
            {
                vo.getRows().add(withCond(toVO(row, asOf), row, scope));
            }
        }
        return vo;
    }

    @Override
    public List<MonitorEventVO> eventsInWindow(MonitorScope scope, LocalDateTime from, LocalDateTime to, int limit)
    {
        MonitorEventQuery q = new MonitorEventQuery();
        q.setRangeFrom(from);
        q.setRangeTo(to);
        LocalDateTime lastJudged = overviewMapper.selectLastJudgedHour(scope);
        LocalDateTime asOf = lastJudged == null ? null : lastJudged.plusHours(1);
        List<MonitorEventVO> out = new ArrayList<>();
        try (Page<MonitorEventRow> page = PageHelper.startPage(1, Math.max(1, limit), false))
        {
            for (MonitorEventRow row : eventMapper.selectEventList(scope, q))
            {
                MonitorEventVO vo = withCond(toVO(row, asOf), row, scope);
                vo.setCurrentText(vo.getCond() != null ? vo.getCurrentText() : row.getCurrentValue() == null ? null
                    : MonitorMessageBuilder.display(row.getValueFormat(), row.getCurrentValue()));
                out.add(vo);
            }
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int handle(MonitorScope scope, List<Long> eventIds, String note)
    {
        Set<Long> ids = new LinkedHashSet<>();
        if (eventIds != null)
        {
            for (Long id : eventIds)
            {
                if (id != null)
                {
                    ids.add(id);
                }
            }
        }
        if (ids.isEmpty())
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
        List<Long> list = new ArrayList<>(ids);
        // 不做部分成功：有一条不属于本站（或不存在）就整体 404
        if (eventMapper.countEvents(scope, list) != list.size())
        {
            throw new MonitorException(MonitorErrorCode.NOT_FOUND, "事件不存在");
        }
        return eventMapper.markHandled(scope, list, text, scope.getUserId(), now());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unhandle(MonitorScope scope, Long eventId)
    {
        if (eventId == null || eventMapper.unhandle(scope, eventId, now()) == 0)
        {
            throw new MonitorException(MonitorErrorCode.NOT_FOUND, "事件不存在");
        }
    }

    // ------------------------------------------------------------------ 组装

    MonitorEventVO toVO(MonitorEventRow e, LocalDateTime asOf)
    {
        MonitorEventVO vo = new MonitorEventVO();
        vo.setEventId(e.getEventId());
        vo.setRuleId(e.getRuleId());
        vo.setRuleNo(e.getRuleNo());
        vo.setRuleCode(MonitorRuleServiceImpl.ruleCode(e.getRuleNo()));
        vo.setRuleDeleted(e.isRuleDeleted());
        vo.setMetricCode(e.getMetricCode());
        vo.setMetricName(com.fivetech.monitor.service.MonitorText.metricName(e.getMetricCode(), e.getMetricName()));
        vo.setUnit(eventUnit(e.getValueFormat()));
        vo.setLevel(e.getLevel());
        vo.setEscalated(e.isEscalated());
        vo.setStatus(e.getStatus());
        vo.setCurrentValue(num(e.getCurrentValue()));
        vo.setBaselineValue(num(e.getBaselineValue()));

        JudgeRule judge = null;
        try
        {
            judge = MonitorSnapshot.parse(json, e.getRuleSnapshot());
        }
        catch (RuntimeException ex)
        {
            log.warn("[monitor] 事件 {} 的规则快照无法解析：{}", e.getEventId(), ex.getMessage());
        }
        if (judge != null)
        {
            vo.setJudgeType(judge.getJudgeType());
            vo.setPeriodHours(judge.getPeriodHours());
            Integer esc = judge.getEscalateHours();
            vo.setEscalateHours(esc == null || esc <= 0 ? null : esc);
            String against = MonitorMessageBuilder.against(judge, e.getLevel(), e.isEscalated());
            boolean crit = Levels.CRIT.equals(against);
            vo.setThreshold(new MonitorEventVO.ThresholdVO(against,
                num(crit ? judge.getCritLow() : judge.getWarnLow()), num(crit ? judge.getCritHigh() : judge.getWarnHigh())));
            vo.setJudgeBasis(messages.judgeBasisParts(e.getValueFormat(), judge, e.getLevel(), e.isEscalated(),
                e.getCurrentValue(), e.getBaselineValue()));
        }
        else
        {
            vo.setPeriodHours(1);
            vo.setJudgeBasis(List.of());
        }

        vo.setStartHour(fmt(e.getStartHour()));
        vo.setTriggerHour(fmt(e.getTriggerHour()));
        boolean open = "open".equals(e.getStatus());
        vo.setEndHour(open ? null : fmt(e.getEndHour()));
        vo.setStoppedAt("stopped".equals(e.getStatus()) ? fmt(e.getStoppedAt()) : null);
        LocalDateTime end = open ? (e.getLastPeriodEnd() != null ? e.getLastPeriodEnd() : asOf)
                : (e.getEndHour() != null ? e.getEndHour() : e.getLastPeriodEnd());
        vo.setDurationHours(e.getStartHour() == null || end == null ? 0
                : Math.max(0, ChronoUnit.HOURS.between(e.getStartHour(), end)));
        vo.setDurationTip(MonitorMessageBuilder.durationTip(e.getStatus(), e.isRuleDeleted(), e.getStopReason(),
            e.getStartHour(), e.getEndHour(), e.getStoppedAt()));

        vo.setOwner(e.getOwnerUserId() == null ? null : new MonitorEventVO.OwnerVO(e.getOwnerUserId(), e.getOwnerEmail()));

        MonitorEventVO.NotifyVO notify = new MonitorEventVO.NotifyVO();
        notify.setStatus(e.getNotifyStatus());
        notify.setSentAt(fmt(e.getNotifiedAt()));
        notify.setChatId(e.getChatId());
        notify.setChatName(e.getChatName());
        // 缓存里没有这个群时按失效处理（与规则列表一致）
        notify.setChatStatus(StringUtils.isEmpty(e.getChatId()) ? null : (e.getChatStatus() == null ? "dead" : e.getChatStatus()));
        if ("failed".equals(e.getNotifyStatus()))
        {
            notify.setError(StringUtils.isEmpty(e.getNotifyError()) ? "發送失敗" : e.getNotifyError());
        }
        if ("paused".equals(e.getNotifyStatus()))
        {
            notify.setPausedReason(com.fivetech.monitor.engine.MonitorNotifySender.PAUSED_REASON);
        }
        if ("muted".equals(e.getNotifyStatus()))
        {
            notify.setMuted(new MonitorEventVO.MutedVO(fmt(e.getMuteSentAt()), e.getMuteHours()));
        }
        applyChats(notify, e.getNotifyBatch(), snapshotChats(e.getRuleSnapshot()));
        vo.setNotify(notify);

        MonitorEventVO.HandleVO handle = new MonitorEventVO.HandleVO();
        handle.setStatus(e.getHandleStatus());
        handle.setNote(e.getHandleNote());
        handle.setBy(e.getHandledBy());
        handle.setAt(fmt(e.getHandledAt()));
        handle.setReset(e.isHandleReset());
        vo.setHandle(handle);
        return vo;
    }

    /** 条件规则的行：规则名、满足 n/m、判定基准、判定说明（release_next ㉓） */
    private MonitorEventVO withCond(MonitorEventVO vo, MonitorEventRow e, MonitorScope scope)
    {
        if (com.fivetech.monitor.cond.CondRules.TYPE.equals(e.getRuleType()))
        {
            com.fivetech.monitor.cond.CondEventView.applyListRow(vo, e.getRuleName(), e.getRuleSnapshot(), e.getDetail(),
                condSupport == null ? java.util.Map.of() : condSupport.catalog().fields(),
                condSupport == null ? null : condSupport.symbol(scope.getSiteCode()));
        }
        return vo;
    }

    private com.fivetech.monitor.cond.CondSupport condSupport;

    /** 条件规则的字段目录（名称、格式）；setter 注入，避免改构造器 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCondSupport(com.fivetech.monitor.cond.CondSupport condSupport)
    {
        this.condSupport = condSupport;
    }

    // ------------------------------------------------------------------ 筛选

    /** 默认 pending；all = 不筛 */
    static String handleFilter(String handle)
    {
        String h = handle == null ? "" : handle.trim().toLowerCase(Locale.ROOT);
        if ("all".equals(h))
        {
            return null;
        }
        return "done".equals(h) ? "done" : "pending";
    }

    static String statusFilter(String status)
    {
        String s = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        return "open".equals(s) || "resolved".equals(s) || "stopped".equals(s) ? s : null;
    }

    /**
     * 异常事件列表的单位：金额返回 "money"（多站点币种不同，前端按当前站点配置的币种显示符号），
     * 其余同告警规则（% / x / min / 空）
     */
    static String eventUnit(String valueFormat)
    {
        return "MONEY".equalsIgnoreCase(valueFormat) ? "money" : MonitorRuleServiceImpl.unit(valueFormat);
    }

    /** 自定义区间：from、to 为 yyyy-MM-dd，含两端，最多 90 天 */
    static void applyCustomRange(MonitorEventQuery q, String from, String to)
    {
        LocalDate f;
        LocalDate t;
        try
        {
            f = LocalDate.parse(StringUtils.trim(from));
            t = LocalDate.parse(StringUtils.trim(to));
        }
        catch (RuntimeException e)
        {
            throw MonitorFieldException.of("from", "自訂範圍請選擇開始與結束日期（yyyy-MM-dd）");
        }
        if (f.isAfter(t))
        {
            throw MonitorFieldException.of("to", "結束日期不能早於開始日期");
        }
        if (ChronoUnit.DAYS.between(f, t) + 1 > CUSTOM_MAX_DAYS)
        {
            throw MonitorFieldException.of("to", "自訂範圍最多 " + CUSTOM_MAX_DAYS + " 天");
        }
        q.setRangeFrom(f.atStartOfDay());
        q.setRangeTo(t.plusDays(1).atStartOfDay());
    }

    static final int CUSTOM_MAX_DAYS = 90;

    private static List<String> trimList(List<String> in)
    {
        List<String> out = new ArrayList<>();
        if (in != null)
        {
            for (String v : in)
            {
                if (v == null)
                {
                    continue;
                }
                for (String part : v.split(","))
                {
                    String t = part.trim();
                    if (!t.isEmpty() && !out.contains(t))
                    {
                        out.add(t);
                    }
                }
            }
        }
        return out;
    }

    private static List<String> upperList(List<String> in, Set<String> allowed)
    {
        List<String> out = new ArrayList<>();
        for (String v : trimList(in))
        {
            String u = v.toUpperCase(Locale.ROOT);
            if (allowed.contains(u) && !out.contains(u))
            {
                out.add(u);
            }
        }
        return out;
    }

    /** range → trigger_hour 区间（IST 自然日）；all / 未知值 = 不限 */
    static void applyRange(MonitorEventQuery q, String range, LocalDate today)
    {
        String r = range == null ? "" : range.trim().toLowerCase(Locale.ROOT);
        LocalDateTime start = today.atStartOfDay();
        switch (r)
        {
            case "today":
                q.setRangeFrom(start);
                break;
            case "yest":
                q.setRangeFrom(start.minusDays(1));
                q.setRangeTo(start);
                break;
            case "d7":
                q.setRangeFrom(start.minusDays(6));
                break;
            case "d30":
                q.setRangeFrom(start.minusDays(29));
                break;
            default:
                break;
        }
    }

    private LocalDateTime now()
    {
        return LocalDateTime.now(zone).truncatedTo(ChronoUnit.SECONDS);
    }

    /** 触发时规则快照里的通知群（release_next ㉑ 之后的事件才有 notifyChats） */
    private List<MonitorChats.Chat> snapshotChats(String snapshot)
    {
        if (StringUtils.isEmpty(snapshot))
        {
            return List.of();
        }
        try
        {
            tools.jackson.databind.JsonNode n = json.readTree(snapshot);
            tools.jackson.databind.JsonNode chats = n == null ? null : n.get("notifyChats");
            return chats == null ? List.of() : MonitorChats.parse(chats.toString());
        }
        catch (RuntimeException ex)
        {
            return List.of();
        }
    }

    /**
     * 「通知送達」按群汇总（规则可选多个群，release_next ㉑）：batch 是最近一次发送里每个群一条的结果
     * [{chatId, name, chatStatus, status: pending/sent/failed, sentAt, error}]。
     * 事件已进入发送（pending / sent / failed）时按 batch 汇总：还有待发 → pending；全部送达 → sent；全部失败 → failed；其余 partial。
     * 静默、未配置、暂停、注意只记录等状态不变，群列表取触发时的快照。旧字段 chatId / chatName / error 取第一个失败的群（没有失败取第一个群）。
     */
    static void applyChats(MonitorEventVO.NotifyVO notify, String batch, List<MonitorChats.Chat> snapshot)
    {
        List<MonitorEventVO.ChatResultVO> rows = new ArrayList<>();
        java.util.Map<String, String> names = new java.util.HashMap<>();
        for (MonitorChats.Chat c : snapshot)
        {
            names.put(c.chatId(), c.name());
        }
        if (StringUtils.isNotEmpty(batch))
        {
            try
            {
                for (tools.jackson.databind.JsonNode n : JSON.readTree(batch))
                {
                    MonitorEventVO.ChatResultVO r = new MonitorEventVO.ChatResultVO();
                    r.setChatId(text(n, "chatId"));
                    String name = text(n, "name");
                    r.setName(StringUtils.isNotEmpty(name) ? name : names.getOrDefault(r.getChatId(), r.getChatId()));
                    r.setChatStatus(text(n, "chatStatus") == null ? "dead" : text(n, "chatStatus"));
                    r.setStatus(text(n, "status"));
                    String at = text(n, "sentAt");
                    r.setSentAt(at == null ? null : fmt(LocalDateTime.parse(at)));
                    r.setError("failed".equals(r.getStatus()) ? (StringUtils.isEmpty(text(n, "error")) ? "發送失敗" : text(n, "error")) : null);
                    rows.add(r);
                }
            }
            catch (RuntimeException ex)
            {
                rows.clear();
            }
        }
        String status = notify.getStatus();
        boolean sending = "pending".equals(status) || "sent".equals(status) || "failed".equals(status);
        if (rows.isEmpty() || !sending)
        {
            rows.clear();
            for (MonitorChats.Chat c : snapshot)
            {
                MonitorEventVO.ChatResultVO r = new MonitorEventVO.ChatResultVO();
                r.setChatId(c.chatId());
                r.setName(StringUtils.isEmpty(c.name()) ? c.chatId() : c.name());
                rows.add(r);
            }
            if (rows.isEmpty() && StringUtils.isNotEmpty(notify.getChatId()))
            {
                MonitorEventVO.ChatResultVO r = new MonitorEventVO.ChatResultVO();
                r.setChatId(notify.getChatId());
                r.setName(notify.getChatName());
                r.setChatStatus(notify.getChatStatus());
                r.setStatus(sending ? status : null);
                r.setError(notify.getError());
                rows.add(r);
            }
            notify.setChats(rows);
            notify.setTotal(rows.size());
            notify.setSentCount("sent".equals(status) ? rows.size() : 0);
            return;
        }
        int sent = 0;
        int failed = 0;
        int pending = 0;
        MonitorEventVO.ChatResultVO firstFailed = null;
        for (MonitorEventVO.ChatResultVO r : rows)
        {
            if ("sent".equals(r.getStatus()))
            {
                sent++;
            }
            else if ("failed".equals(r.getStatus()))
            {
                failed++;
                firstFailed = firstFailed == null ? r : firstFailed;
            }
            else
            {
                pending++;
            }
        }
        String agg = pending > 0 ? "pending" : failed == 0 ? "sent" : sent == 0 ? "failed" : "partial";
        notify.setStatus(agg);
        notify.setChats(rows);
        notify.setTotal(rows.size());
        notify.setSentCount(sent);
        MonitorEventVO.ChatResultVO head = firstFailed != null ? firstFailed : rows.get(0);
        notify.setChatId(head.getChatId());
        notify.setChatName(head.getName());
        notify.setChatStatus(head.getChatStatus());
        notify.setError(firstFailed == null ? null : firstFailed.getError());
    }

    private static final tools.jackson.databind.ObjectMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    private static String text(tools.jackson.databind.JsonNode n, String field)
    {
        tools.jackson.databind.JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static String fmt(LocalDateTime t)
    {
        return t == null ? null : t.format(MINUTE);
    }

    /** 去掉 NUMERIC(18,4) 的尾零 */
    private static BigDecimal num(BigDecimal v)
    {
        if (v == null)
        {
            return null;
        }
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }
}
