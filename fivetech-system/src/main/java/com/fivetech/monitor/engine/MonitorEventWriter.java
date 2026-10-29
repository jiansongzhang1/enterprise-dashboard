package com.fivetech.monitor.engine;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.gateway.HourlyFetch;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.engine.domain.MonitorEngineRule;
import com.fivetech.monitor.engine.domain.MonitorEventRecord;
import com.fivetech.monitor.engine.domain.MonitorNotifyRecord;
import com.fivetech.monitor.engine.domain.MonitorRuleStateRecord;
import com.fivetech.monitor.engine.judge.JudgeRule;
import com.fivetech.monitor.engine.judge.Levels;
import com.fivetech.monitor.engine.judge.MonitorJudge;
import com.fivetech.monitor.engine.judge.PeriodPoint;
import com.fivetech.monitor.engine.judge.RunState;
import com.fivetech.monitor.engine.judge.StepResult;
import com.fivetech.monitor.mapper.MonitorEngineMapper;
import tools.jackson.databind.ObjectMapper;

/**
 * 判定引擎的写库部分：一个站点一个数据小时一个事务。
 * <p>monitor_rule_state、monitor_event、待发送的 monitor_notify_log、monitor_hour_check、
 * monitor_rule.last_judged_at / judge_error 一起提交；任一写库失败整站回滚，该小时保持 waiting，下一轮重试。</p>
 * <p>单条规则计算出错（指标配置缺失、跌幅基准未配置等）只给这条规则写 judge_error，其他规则照常判定。</p>
 * <p>数据缺失（:50 时指标值仍为空、或检测时段内有小时没有数据）：本周期不判定（SKIP，连续计数中断），
 * 不产生告警、不写事件记录、不发通知（2026-10-22 与产品确认）。</p>
 *
 * @author fivetech
 */
@Component
public class MonitorEventWriter
{
    private static final Logger log = LoggerFactory.getLogger(MonitorEventWriter.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static final String HOUR_JUDGED = "judged";

    public static final String HOUR_MISSING = "missing";

    private final MonitorEngineMapper mapper;

    private final MonitorPeriodBuilder periodBuilder;

    private final MonitorDataFetcher fetcher;

    private final MonitorMessageBuilder messages;

    private final MonitorProperties properties;

    private final ObjectMapper json;

    /** 告警通知模板（站点专用 → 全站默认 → 内置文案） */
    private final com.fivetech.monitor.service.MonitorNotifyTemplateService templates;

    public MonitorEventWriter(MonitorEngineMapper mapper, MonitorPeriodBuilder periodBuilder, MonitorDataFetcher fetcher,
            MonitorMessageBuilder messages, MonitorProperties properties, ObjectMapper json,
            com.fivetech.monitor.service.MonitorNotifyTemplateService templates)
    {
        this.mapper = mapper;
        this.periodBuilder = periodBuilder;
        this.fetcher = fetcher;
        this.messages = messages;
        this.properties = properties;
        this.json = json;
        this.templates = templates;
    }

    /**
     * 处理一个站点的一个数据小时。
     *
     * @param pending    本小时还没处理的启用规则
     * @param data       本轮取回的数据；没取到（未就绪、失败）为 null
     * @param landed     水位已到 hour + 1 小时
     * @param finalCheck 本小时最后一次检查（:50）：值仍为空的指标按缺失处理，本周期不判定（不记录、不通知）
     * @return 本轮处理了几条规则
     */
    @Transactional(rollbackFor = Exception.class)
    public int applyHour(MonitorScope scope, LocalDateTime hour, LocalDateTime now, List<MonitorEngineRule> pending,
            HourlyFetch data, boolean landed, boolean finalCheck)
    {
        mapper.touchHourCheck(scope, hour, now);
        int processed = 0;
        for (MonitorEngineRule rule : pending)
        {
            BigDecimal value = landed && data != null ? data.at(rule.getMetricCode(), hour) : null;
            boolean missing = value == null;
            if (missing && !finalCheck)
            {
                continue;   // 还没到 :50，下一轮再取
            }
            processRule(scope, rule, hour, now, landed ? data : null, missing);
            processed++;
        }
        if (processed == pending.size())
        {
            mapper.finishHourCheck(scope, hour, landed ? HOUR_JUDGED : HOUR_MISSING, now);
        }
        return processed;
    }

    /** 本小时全部规则都已处理过（没有待处理规则时直接收尾） */
    @Transactional(rollbackFor = Exception.class)
    public void finishHour(MonitorScope scope, LocalDateTime hour, LocalDateTime now)
    {
        mapper.finishHourCheck(scope, hour, HOUR_JUDGED, now);
    }

    // ------------------------------------------------------------------ 单条规则

    private void processRule(MonitorScope scope, MonitorEngineRule rule, LocalDateTime hour, LocalDateTime now,
            HourlyFetch data, boolean missing)
    {
        JudgeRule judge = toJudgeRule(rule);
        int period = judge.getPeriodHours();
        boolean periodEnd = (hour.getHour() + 1) % period == 0;
        RunState prev = loadState(rule);

        String error = null;
        PeriodPoint point = null;
        StepResult step = null;
        if (periodEnd)
        {
            try
            {
                if (JudgeRule.DROP.equals(judge.getJudgeType()))
                {
                    String column = fetcher.baselineColumn(rule.getMetricCode());
                    if (column == null)
                    {
                        throw new IllegalStateException("跌幅基準字段未配置（monitor.engine.baseline-fields." + rule.getMetricCode() + "）");
                    }
                    if (data != null && !data.has(MonitorDataFetcher.baseKey(rule.getMetricCode())))
                    {
                        throw new IllegalStateException("UDS 未提供跌幅基準字段 " + column);
                    }
                }
                LocalDateTime from = hour.minusHours(period - 1L);
                LocalDateTime to = hour.plusHours(1);
                // 每小时检测：本小时缺数据即不判定。多小时周期（MON-10）：本小时为空也按时段重算，
                // 时段内全部小时都没有数才算缺失（periodBuilder 判断；取数窗口覆盖整个时段）
                point = missing && period == 1 ? new PeriodPoint(from, to, null, null, null, true)
                        : periodBuilder.build(rule, data, from, to);
                step = MonitorJudge.step(judge, prev, point, properties.getEngine().getLookbackHours());
            }
            catch (RuntimeException e)
            {
                error = StringUtils.substring(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 0, 250);
                point = null;
                step = null;
                log.warn("[monitor] 规则 {} 判定失败 site={} hour={}：{}", rule.getRuleId(), scope.getSiteCode(), hour, error);
            }
        }

        List<Map<String, Object>> periods = loadPeriods(rule);
        Long openEventId = rule.getEventId();
        RunState next = prev;
        if (step != null)
        {
            // 本周期判定后事件是否「已升级」：只有开事件 / 更新事件时才有事件等级
            boolean periodEscalated = (step.getAction() == StepResult.Action.OPEN
                    || step.getAction() == StepResult.Action.UPDATE) && step.isEscalated();
            periods.add(periodJson(point, step.getLevel(), periodEscalated));
            MonitorPeriods.trim(periods, judge.getDurationCount());
            openEventId = applyStep(scope, rule, judge, step, point, periods, hour, now,
                    MonitorJudge.reCrossedCrit(judge, prev, step));
            next = step.getNext();
        }

        MonitorRuleStateRecord st = new MonitorRuleStateRecord();
        st.setRuleId(rule.getRuleId());
        st.setSiteCode(scope.getSiteCode());
        st.setRunStartHour(next.getRunStartHour());
        st.setRecentLevels(write(next.getRecentLevels()));
        st.setRecentPeriods(write(periods));
        st.setOpenEventId(openEventId);
        st.setLastPeriodEnd(next.getLastPeriodEnd());
        st.setLastDataHour(hour);
        st.setUpdateTime(now);
        mapper.upsertRuleState(scope, st);
        mapper.updateRuleJudged(scope, rule.getRuleId(), now, error);
    }

    /** @return 处理后仍异常中的事件 id（恢复后为 null） */
    /**
     * @param reCrit 严重事件进行中，本周期在回落到注意之后又越过严重线：同一事件再发一次「新触发」（ALERT_NEW）
     */
    private Long applyStep(MonitorScope scope, MonitorEngineRule rule, JudgeRule judge, StepResult step,
            PeriodPoint point, List<Map<String, Object>> periods, LocalDateTime hour, LocalDateTime now, boolean reCrit)
    {
        switch (step.getAction())
        {
            case OPEN:
            {
                MonitorEventRecord e = new MonitorEventRecord();
                e.setSiteCode(scope.getSiteCode());
                e.setRuleId(rule.getRuleId());
                e.setMetricCode(rule.getMetricCode());
                e.setEventType("alert");
                e.setLevel(step.getEventLevel());
                e.setPeakLevel(step.getEventLevel());
                e.setEscalated(step.isEscalated());
                e.setStatus("open");
                e.setStartHour(step.getStartHour());
                e.setTriggerHour(step.getTriggerHour());
                e.setLastPeriodEnd(point.getTo());
                e.setCurrentValue(point.getValue());
                e.setBaselineValue(point.getBaseline());
                e.setRuleSnapshot(snapshot(judge, rule));
                e.setHandleStatus("pending");
                e.setNow(now);
                // FY-30 R1/R2：事件一开出来就是严重 → 发一次「新触发」；注意事件不发
                Notify decision = decide(scope, rule, e, null, notifyType(e), now);
                e.setNotifyStatus(decision == Notify.SEND ? "pending" : decision == Notify.UNSET ? "unset"
                        : decision == Notify.MUTED ? "muted" : "none");
                if (decision == Notify.MUTED)
                {
                    markMuted(e, rule);
                }
                mapper.insertEvent(e);
                if (decision == Notify.SEND)
                {
                    enqueue(scope, rule, judge, e, periods, hour, now, notifyType(e), e.isEscalated());
                }
                return e.getEventId();
            }
            case UPDATE:
            {
                MonitorEventRecord e = current(scope, rule, now);
                String before = rule.getEventLevel();
                boolean wasCrit = Levels.CRIT.equals(before);
                // 只升不降（FY-23 R23 / MON-01）：到过严重就保持严重直到结束；已是严重时保留当初是否由注意升级而来
                String level = Levels.higher(before, step.getEventLevel());
                e.setLevel(level);
                e.setPeakLevel(higher(rule.getEventPeakLevel(), level));
                e.setEscalated(wasCrit ? Boolean.TRUE.equals(rule.getEventEscalated()) : step.isEscalated());
                e.setLastPeriodEnd(point.getTo());
                // 当前值、基准 = 触发周期的值（FY-23 R16 / MON-03），之后不再覆盖（updateEvent 对 null 保留库里的值）
                // 标记已处理后由注意升为严重：自动退回未处理，保留原处理人和说明，前端提示需重新处理
                if ("done".equals(e.getHandleStatus()) && !wasCrit && Levels.CRIT.equals(level)
                        && !Levels.CRIT.equals(rule.getEventHandledLevel()))
                {
                    e.setHandleStatus("pending");
                    e.setHandleReset(true);
                }
                // FY-30 R2：持续严重不重复提醒。发送时机：
                //  ① 本周期由注意变成严重（注意升级 → ALERT_ESCALATED；越过严重线 → ALERT_NEW），同一事件同一类型一次；
                //  ② 严重事件中途回落到注意（事件等级仍为严重，只升不降），之后再次越过严重线 → 再发一次 ALERT_NEW
                //     （2026-10-27 与产品确认；按发生的数据小时幂等，静默期照常生效）
                Notify decision;
                if (!wasCrit)
                {
                    decision = decide(scope, rule, e, rule.getEventId(), notifyType(e), now);
                }
                else if (reCrit)
                {
                    decision = decideAgain(scope, rule, e, rule.getEventId(), hour, now);
                }
                else
                {
                    decision = Notify.KEEP;
                }
                if (decision == Notify.SEND)
                {
                    e.setNotifyStatus("pending");
                }
                else if (decision == Notify.UNSET)
                {
                    e.setNotifyStatus("unset");
                }
                else if (decision == Notify.MUTED && neverSent(rule.getEventNotifyStatus()))
                {
                    // 这个事件还没发过通知（注意升为严重、或一直在静默期内）：标为静默期内未发送，记下压住它的那次发送
                    e.setNotifyStatus("muted");
                    markMuted(e, rule);
                }
                mapper.updateEvent(scope, e);
                if (decision == Notify.SEND)
                {
                    if (reCrit)
                    {
                        // 再次越过严重线：按「新触发」发，判定行写严重线
                        enqueue(scope, rule, judge, e, periods, hour, now,
                                com.fivetech.monitor.domain.MonitorNotifyTemplate.ALERT_NEW, false);
                    }
                    else
                    {
                        enqueue(scope, rule, judge, e, periods, hour, now, notifyType(e), e.isEscalated());
                    }
                }
                return rule.getEventId();
            }
            case RESOLVE:
            {
                MonitorEventRecord e = current(scope, rule, now);
                e.setStatus("resolved");
                e.setEndHour(step.getEndHour());
                e.setLastPeriodEnd(step.getEndHour());
                mapper.updateEvent(scope, e);
                return null;
            }
            default:
                return rule.getEventId();
        }
    }

    private enum Notify
    {
        SEND, UNSET, KEEP,
        /** 严重、未处理，但距本规则上次发送不足静默期：本次不发送 */
        MUTED
    }

    /** 事件还没有发出过通知（静默期内的提示只对这种事件有意义；已发送过的事件仍显示已发送） */
    private static boolean neverSent(String notifyStatus)
    {
        return notifyStatus == null || "none".equals(notifyStatus) || "muted".equals(notifyStatus);
    }

    /** 记下压住本次发送的那次发送时间和当时的静默时长（异常事件「通知」列悬停：上次發送 …，靜默 N 小時） */
    private static void markMuted(MonitorEventRecord e, MonitorEngineRule rule)
    {
        e.setMuteSentAt(rule.getLastNotifiedAt());
        e.setMuteHours(rule.getMuteHours());
    }

    /** 通知类型：注意升级上来的严重 → ALERT_ESCALATED；其余（一触发就严重、越过严重线）→ ALERT_NEW */
    private static String notifyType(MonitorEventRecord e)
    {
        return e.isEscalated() ? com.fivetech.monitor.domain.MonitorNotifyTemplate.ALERT_ESCALATED
                : com.fivetech.monitor.domain.MonitorNotifyTemplate.ALERT_NEW;
    }

    /**
     * 是否写待发送记录（FY-30 R1–R4）：本周期后事件为严重且未处理；
     * 同一事件同一通知类型已经有过发送记录（不论成败）→ 不再发（幂等，补跑判定也不重复）；
     * 规则没有通知群 → unset；距规则上次发送不足静默期 → MUTED（本次不发送，事件标 muted 并记下原因）。
     */
    private Notify decide(MonitorScope scope, MonitorEngineRule rule, MonitorEventRecord e, Long eventId, String type,
            LocalDateTime now)
    {
        if (!Levels.CRIT.equals(e.getLevel()) || "done".equals(e.getHandleStatus()))
        {
            return Notify.KEEP;
        }
        if (eventId != null && mapper.countNotifyOfType(scope, eventId, type) > 0)
        {
            return Notify.KEEP;
        }
        // 同一事件只发送一次（FY-30 R2 / MON-02）：已发过「新触发」的事件不再发「注意升级」
        if (eventId != null && com.fivetech.monitor.domain.MonitorNotifyTemplate.ALERT_ESCALATED.equals(type)
                && mapper.countNotifyOfType(scope, eventId, com.fivetech.monitor.domain.MonitorNotifyTemplate.ALERT_NEW) > 0)
        {
            return Notify.KEEP;
        }
        if (StringUtils.isEmpty(rule.getNotifyChatId()))
        {
            return Notify.UNSET;
        }
        Integer mute = rule.getMuteHours();
        if (rule.getLastNotifiedAt() != null && mute != null && mute > 0
                && rule.getLastNotifiedAt().plusHours(mute).isAfter(now))
        {
            return Notify.MUTED;
        }
        return Notify.SEND;
    }

    /**
     * 严重事件回落注意后再次越过严重线时是否再发（ALERT_NEW）：已处理的事件不发；同一数据小时已有记录不发（补跑幂等）；
     * 规则没有通知群不改通知状态；距规则上次发送不足静默期 → MUTED（事件已发送过，通知状态不变）。
     */
    private Notify decideAgain(MonitorScope scope, MonitorEngineRule rule, MonitorEventRecord e, Long eventId,
            LocalDateTime hour, LocalDateTime now)
    {
        if ("done".equals(e.getHandleStatus()) || eventId == null)
        {
            return Notify.KEEP;
        }
        if (mapper.countNotifyAtHour(scope, eventId, com.fivetech.monitor.domain.MonitorNotifyTemplate.ALERT_NEW, hour) > 0)
        {
            return Notify.KEEP;
        }
        if (StringUtils.isEmpty(rule.getNotifyChatId()))
        {
            return Notify.KEEP;
        }
        Integer mute = rule.getMuteHours();
        if (rule.getLastNotifiedAt() != null && mute != null && mute > 0
                && rule.getLastNotifiedAt().plusHours(mute).isAfter(now))
        {
            return Notify.MUTED;
        }
        return Notify.SEND;
    }

    private void enqueue(MonitorScope scope, MonitorEngineRule rule, JudgeRule judge, MonitorEventRecord e,
            List<Map<String, Object>> periods, LocalDateTime hour, LocalDateTime now, String type, boolean escalated)
    {
        List<BigDecimal[]> recent = new ArrayList<>();
        for (Map<String, Object> p : MonitorPeriods.window(periods, judge.getDurationCount()))
        {
            recent.add(new BigDecimal[] { decimal(p.get("value")), decimal(p.get("baseline")) });
        }
        LocalDateTime trigger = e.getTriggerHour() != null ? e.getTriggerHour() : rule.getEventTriggerHour();
        MonitorNotifyRecord n = new MonitorNotifyRecord();
        n.setSiteCode(scope.getSiteCode());
        n.setMsgType("alert");
        n.setNotifyType(type);
        n.setEventId(e.getEventId() != null ? e.getEventId() : rule.getEventId());
        n.setDataHour(hour);
        n.setChatId(rule.getNotifyChatId());
        // 正文：站点专用模板 → 全站默认模板 → 内置默认模板（FY-30 格式）
        java.util.Map<String, String> values = messages.alertValues(rule, judge, escalated, trigger, recent,
            messages.siteTime(scope.getSiteCode(), now));
        com.fivetech.monitor.service.MonitorNotifyTemplateService.Rendered rendered = templates.render(scope.getSiteCode(), type,
                values);
        n.setContent(rendered.getContent());
        n.setTemplateId(rendered.getTemplateId());
        n.setTemplateVersion(rendered.getTemplateVersion());
        n.setNow(now);
        mapper.insertNotifyLog(n);
        // 提交后由发送任务（MonitorNotifySender，每分钟）投递；成功后更新事件 notify_status / notified_at / notify_count
        // 和规则 last_notified_at（静默期从这里算）
    }

    /** 以异常中事件的当前值为底，供 UPDATE / RESOLVE 改动 */
    private static MonitorEventRecord current(MonitorScope scope, MonitorEngineRule rule, LocalDateTime now)
    {
        MonitorEventRecord e = new MonitorEventRecord();
        e.setEventId(rule.getEventId());
        e.setSiteCode(scope.getSiteCode());
        e.setRuleId(rule.getRuleId());
        e.setMetricCode(rule.getMetricCode());
        e.setEventType("alert");
        e.setLevel(rule.getEventLevel());
        e.setPeakLevel(rule.getEventPeakLevel());
        e.setEscalated(Boolean.TRUE.equals(rule.getEventEscalated()));
        e.setStatus("open");
        e.setStartHour(rule.getEventStartHour());
        e.setTriggerHour(rule.getEventTriggerHour());
        e.setLastPeriodEnd(rule.getLastPeriodEnd());
        // 不带出通知状态：发送任务可能在本轮判定期间已改成 sent / failed，回写旧值会覆盖它。
        // 为 null 时 updateEvent 保留库里的值，只有本周期决定发送（pending）或没配群（unset）时才改
        e.setNotifyStatus(null);
        e.setHandleStatus(rule.getEventHandleStatus() == null ? "pending" : rule.getEventHandleStatus());
        e.setHandleReset(Boolean.TRUE.equals(rule.getEventHandleReset()));
        e.setNow(now);
        return e;
    }

    // ------------------------------------------------------------------ 转换

    static JudgeRule toJudgeRule(MonitorEngineRule r)
    {
        return new JudgeRule(r.getJudgeType(), r.getWarnLow(), r.getWarnHigh(), r.getCritLow(), r.getCritHigh(),
                r.getPeriodHours() == null ? 1 : r.getPeriodHours(),
                r.getDurationCount() == null ? 1 : r.getDurationCount(),
                r.getEscalateHours(), r.getMinSampleValue(), "base".equalsIgnoreCase(r.getMinSampleKey()));
    }

    /** 运行状态 + 异常中的事件（事件以 monitor_event 为准） */
    private RunState loadState(MonitorEngineRule rule)
    {
        RunState s = RunState.empty();
        if (rule.getStateRuleId() != null)
        {
            s.setRunStartHour(rule.getRunStartHour());
            s.setRecentLevels(readLevels(rule.getRecentLevels()));
            s.setLastPeriodEnd(rule.getLastPeriodEnd());
        }
        s.setEventOpen(rule.getEventId() != null);
        s.setEventTriggerHour(rule.getEventTriggerHour());
        return s;
    }

    private List<String> readLevels(String text)
    {
        List<String> levels = new ArrayList<>();
        if (StringUtils.isEmpty(text))
        {
            return levels;
        }
        try
        {
            for (Object o : json.readValue(text, List.class))
            {
                if (o != null)
                {
                    levels.add(o.toString());
                }
            }
        }
        catch (RuntimeException e)
        {
            log.warn("[monitor] recent_levels 无法解析，按空处理：{}", text);
        }
        return levels;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadPeriods(MonitorEngineRule rule)
    {
        List<Map<String, Object>> list = new ArrayList<>();
        if (rule.getStateRuleId() == null || StringUtils.isEmpty(rule.getRecentPeriods()))
        {
            return list;
        }
        try
        {
            for (Object o : json.readValue(rule.getRecentPeriods(), List.class))
            {
                if (o instanceof Map)
                {
                    list.add(new LinkedHashMap<>((Map<String, Object>) o));
                }
            }
        }
        catch (RuntimeException e)
        {
            log.warn("[monitor] recent_periods 无法解析，按空处理：{}", rule.getRecentPeriods());
        }
        return list;
    }

    private static Map<String, Object> periodJson(PeriodPoint p, String level, boolean escalated)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", p.getFrom().format(TS));
        m.put("to", p.getTo().format(TS));
        m.put("value", p.getValue());
        m.put("baseline", p.getBaseline());
        m.put("level", level);
        m.put("escalated", escalated);
        return m;
    }

    private String snapshot(JudgeRule j, MonitorEngineRule rule)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("judgeType", j.getJudgeType());
        Map<String, Object> warn = new LinkedHashMap<>();
        warn.put("low", j.getWarnLow());
        warn.put("high", j.getWarnHigh());
        Map<String, Object> crit = new LinkedHashMap<>();
        crit.put("low", j.getCritLow());
        crit.put("high", j.getCritHigh());
        m.put("warn", warn);
        m.put("crit", crit);
        m.put("periodHours", j.getPeriodHours());
        m.put("durationCount", j.getDurationCount());
        m.put("escalateHours", j.getEscalateHours());
        m.put("minSample", j.getMinSample());
        // 触发时的通知群：异常事件「通知送達」按快照显示，不随之后改规则变化（FY-27 R10 / MON-32）
        m.put("notifyChatId", rule == null ? null : rule.getNotifyChatId());
        m.put("notifyChatName", rule == null ? null : rule.getNotifyChatName());
        return write(m);
    }

    private String write(Object value)
    {
        return json.writeValueAsString(value);
    }

    private static BigDecimal decimal(Object o)
    {
        if (o == null)
        {
            return null;
        }
        return o instanceof BigDecimal ? (BigDecimal) o : new BigDecimal(o.toString());
    }

    private static String higher(String a, String b)
    {
        return Levels.CRIT.equals(a) || Levels.CRIT.equals(b) ? Levels.CRIT : (a != null ? a : b);
    }
}
