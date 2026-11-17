package com.fivetech.monitor.cond;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.fivetech.monitor.domain.MonitorCondEvent;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.exception.MonitorErrorCode;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.mapper.MonitorCondMapper;

/**
 * 条件规则的事件详情（GET /monitor/events/{eventId}，设计文档 7.3.3）：状态、条件明细（触发时 / 最近一次）、每个条件的走势、
 * 触发时的规则快照。走势按快照里的设置重新取数计算；取不到时 series 为空并带 seriesError，其余照常返回。
 *
 * @author fivetech
 */
@Service
public class CondEventService
{
    private static final Logger log = LoggerFactory.getLogger(CondEventService.class);

    private static final DateTimeFormatter MD_HM = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final MonitorCondMapper mapper;

    private final CondSupport support;

    public CondEventService(MonitorCondMapper mapper, CondSupport support)
    {
        this.mapper = mapper;
        this.support = support;
    }

    public Map<String, Object> detail(MonitorScope scope, Long eventId)
    {
        MonitorCondEvent e = eventId == null ? null : mapper.selectEvent(scope, eventId);
        if (e == null)
        {
            throw MonitorException.notFound("事件不存在");
        }
        if (!CondRules.TYPE.equals(e.getRuleType()))
        {
            throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "指標規則的事件請到營運總覽查看");
        }
        String site = scope.getSiteCode();
        String symbol = support.symbol(site);
        Map<String, CondField> fields = support.catalog().fields();
        CondEventView.Snap snap = CondEventView.snap(e.getRuleSnapshot());
        CondSpec spec = snap.spec();
        CondWindow w = spec.getWindow();
        Map<String, Object> detail = CondEventWriter.parse(e.getDetail());
        Map<Integer, Map<String, Object>> trig = CondEventView.outcomes(detail, "triggered");
        Map<Integer, Map<String, Object>> latest = CondEventView.outcomes(detail, "latest");
        boolean open = "open".equals(e.getStatus());

        LocalDateTime start = support.toSite(site, e.getStartHour());
        LocalDateTime end = support.toSite(site, e.getEndHour());
        LocalDateTime stopped = support.toSite(site, e.getStoppedAt());
        LocalDateTime now = support.siteNow(site);
        LocalDateTime until = open ? now : (end != null ? end : stopped != null ? stopped : now);
        String dur = durationText(start, until);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", e.getEventId());
        m.put("ruleId", e.getRuleId());
        m.put("ruleCode", snap.raw().get("ruleCode"));
        m.put("name", snap.raw().get("name"));
        m.put("level", e.getLevel());
        m.put("status", e.getStatus());
        m.put("statusText", statusText(e, start, end, stopped, dur));
        m.put("startAt", fmt(start));
        m.put("triggerAt", fmt(support.toSite(site, e.getTriggerHour())));
        m.put("judgedAt", detail.get("judgedAt"));
        m.put("endAt", fmt(end));
        m.put("stoppedAt", fmt(stopped));
        m.put("durationText", dur);
        Map<String, Object> handle = new LinkedHashMap<>();
        handle.put("status", e.getHandleStatus());
        handle.put("note", e.getHandleNote());
        handle.put("by", e.getHandledBy());
        handle.put("at", fmt(support.toSite(site, e.getHandledAt())));
        m.put("handle", handle);
        m.put("notifyStatus", e.getNotifyStatus());
        m.put("matchMode", spec.getMatchMode());
        String latestLabel = open ? "最近一次" : "結束前最後一次";
        m.put("latestLabel", latestLabel);
        LocalDateTime judged = CondEventView.time(detail.get("judgedAt"));
        m.put("detectNote", (w == CondWindow.TODAY ? "每小時檢測一次，統計今日 00:00 至檢測時的累計" : w.getText() + "檢測一次，統計該時段的資料")
            + "；觸發時＝" + (judged == null ? "觸發時" : judged.format(CondEventView.HM)) + " 那次檢測，"
            + latestLabel + "＝" + (open ? "最近一次檢測" : "回到正常前的最後一次檢測") + "。");

        List<Map<String, Object>> conds = new ArrayList<>();
        for (CondCondition c : spec.getConditions())
        {
            CondField f = fields.get(c.getField());
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("index", c.getIndex());
            x.put("scopeLabel", snap.scopeLabels().get(c.getIndex()));
            x.put("fieldName", f == null ? c.getField() : f.getName());
            x.put("howText", CondRules.howText(c));
            x.put("thresholdText", CondRules.thresholdText(c, f, symbol));
            x.put("triggered", CondEventView.outcomeView(c, f, trig.get(c.getIndex()), symbol));
            x.put("latest", CondEventView.outcomeView(c, f, latest.get(c.getIndex()), symbol));
            conds.add(x);
        }
        m.put("conditions", conds);
        series(m, site, spec, fields, detail, open, start, end);

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("name", snap.raw().get("name"));
        rule.put("ruleCode", snap.raw().get("ruleCode"));
        rule.put("ownerEmail", snap.raw().get("ownerEmail"));
        rule.put("description", snap.raw().get("description"));
        rule.put("summary", snap.raw().get("summary"));
        rule.put("detectText", (w == CondWindow.TODAY ? "每小時檢測今日累計" : w.getText() + "檢測")
            + (spec.getDurationCount() > 1 ? "，連續 " + spec.getDurationCount() + " 次" : "") + "，記為"
            + CondRules.LEVEL_TEXT.getOrDefault(spec.getLevel(), ""));
        rule.put("notifyText", notifyText(snap.raw()));
        Object v = snap.raw().get("version");
        rule.put("changedAfter", v instanceof Number n && e.getRuleVersion() != null && e.getRuleVersion() > n.longValue());
        rule.put("deleted", Boolean.TRUE.equals(e.getRuleDeleted()));
        m.put("rule", rule);
        return m;
    }

    /** 每个条件一条走势：事件开始前 seriesBefore 个检测点到结束（异常中到最近一次检测） */
    private void series(Map<String, Object> m, String site, CondSpec spec, Map<String, CondField> fields, Map<String, Object> detail,
            boolean open, LocalDateTime start, LocalDateTime end)
    {
        CondWindow w = spec.getWindow();
        LocalDateTime trigEnd = detail.get("triggered") instanceof Map<?, ?> t ? CondEventView.time(t.get("end")) : null;
        LocalDateTime lastEnd = detail.get("latest") instanceof Map<?, ?> l ? CondEventView.time(l.get("end")) : null;
        if (trigEnd == null)
        {
            m.put("series", List.of());
            return;
        }
        int step = Math.max(1, w.getHours());
        LocalDateTime first = trigEnd.minusHours((long) step * (support.props().getSeriesBefore() + spec.getDurationCount() - 1));
        LocalDateTime last = open ? support.siteNow(site).truncatedTo(ChronoUnit.HOURS)
            : (lastEnd == null ? trigEnd : lastEnd).plusHours(step);
        List<LocalDateTime> cps = CondEvaluator.checkpoints(w, first, last);
        List<Map<String, Object>> out = new ArrayList<>();
        if (cps.isEmpty() || !support.data().available())
        {
            m.put("series", out);
            m.put("seriesError", support.data().available() ? null : "條件規則的小時數據尚未接入");
            return;
        }
        Map<Integer, CondSeries> data;
        try
        {
            data = support.fetch(spec, CondEvaluator.dataFrom(spec, cps.get(0), support.props().getBaseWeeks()), last);
        }
        catch (RuntimeException ex)
        {
            log.warn("[monitor] 条件规则事件走势取数失败 site={}：{}", site, ex.getMessage());
            m.put("series", out);
            m.put("seriesError", "走勢取數失敗，請稍後再試");
            return;
        }
        for (CondCondition c : spec.getConditions())
        {
            CondField f = fields.get(c.getField());
            CondSeries s = data.get(c.getIndex());
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("index", c.getIndex());
            x.put("fieldName", f == null ? c.getField() : f.getName());
            x.put("subtitle", w.subtitle(f != null && f.isSnapshot()));
            x.put("valueFormat", f == null ? null : f.getValueFormat());
            putCompare(x, c);
            List<Map<String, Object>> pts = new ArrayList<>();
            if (f != null && s != null)
            {
                for (LocalDateTime cp : cps)
                {
                    CondEvaluator.Outcome o = CondEvaluator.judge(c, f, s, w, cp, support.props().getBaseWeeks());
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("at", w.start(cp).format(CondEventView.MINUTE));
                    p.put("label", w.label(cp));
                    p.put("value", CondSupport.round(o.value()));
                    if (c.isChange())
                    {
                        p.put("base", CondSupport.round(o.base()));
                        p.put("change", CondSupport.round(o.change()));
                    }
                    p.put("met", o.met());
                    pts.add(p);
                }
            }
            x.put("points", pts);
            Map<String, Object> hl = new LinkedHashMap<>();
            hl.put("from", start == null ? null : start.format(CondEventView.MINUTE));
            hl.put("to", open || end == null ? null : end.format(CondEventView.MINUTE));
            x.put("highlight", hl);
            out.add(x);
        }
        m.put("series", out);
    }

    static String statusText(MonitorCondEvent e, LocalDateTime start, LocalDateTime end, LocalDateTime stopped, String dur)
    {
        String s = start == null ? "—" : start.format(MD_HM);
        switch (e.getStatus())
        {
            case "open":
                return "異常中 · " + s + " 起 · 已持續 " + dur;
            case "resolved":
                return "已結束 · " + s + " – " + (end == null ? "—" : end.format(MD_HM)) + " · 持續 " + dur;
            default:
                return "已停止判定 · " + (stopped == null ? "—" : stopped.format(MD_HM)) + " 規則"
                    + ("delete".equals(e.getStopReason()) ? "刪除" : "update".equals(e.getStopReason()) ? "修改" : "暫停");
        }
    }

    static String durationText(LocalDateTime from, LocalDateTime to)
    {
        if (from == null || to == null || !to.isAfter(from))
        {
            return "不足 1 小時";
        }
        long hours = Duration.between(from, to).toHours();
        if (hours < 1)
        {
            return "不足 1 小時";
        }
        return hours < 24 ? hours + " 小時" : (hours / 24) + " 天" + (hours % 24 == 0 ? "" : " " + (hours % 24) + " 小時");
    }

    @SuppressWarnings("unchecked")
    static String notifyText(Map<String, Object> raw)
    {
        if (Boolean.FALSE.equals(raw.get("notifyEnabled")))
        {
            return "僅記錄，不發送通知";
        }
        List<String> names = new ArrayList<>();
        if (raw.get("chats") instanceof List<?> l)
        {
            for (Object o : l)
            {
                if (o instanceof Map<?, ?> c)
                {
                    names.add(String.valueOf(c.get("name") != null ? c.get("name") : c.get("chatId")));
                }
            }
        }
        return names.isEmpty() ? "未設置通知群" : "發送到 " + String.join("、", names);
    }

    private static String fmt(LocalDateTime t)
    {
        return t == null ? null : t.format(CondEventView.MINUTE);
    }

    /**
     * 走势的比较方式：how、op、unit、threshold，前端据此画阈值线。
     * unit 只有变化类（BASE / YD）才有，当前值为 null；OUT 时 threshold 为 [下限, 上限]，其余为一个数字。
     */
    static void putCompare(Map<String, Object> x, CondCondition c)
    {
        x.put("how", c.getHow());
        x.put("op", c.getOp());
        x.put("unit", c.isChange() ? c.getUnit() : null);
        x.put("threshold", c.threshold());
    }
}
