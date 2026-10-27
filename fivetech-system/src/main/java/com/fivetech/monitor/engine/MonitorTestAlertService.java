package com.fivetech.monitor.engine;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.fivetech.common.lark.LarkClient;
import com.fivetech.common.lark.LarkResult;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.gateway.HourlyFetch;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.monitor.domain.MonitorNotifyTemplate;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.engine.domain.MonitorEngineRule;
import com.fivetech.monitor.engine.judge.JudgeRule;
import com.fivetech.monitor.engine.judge.Levels;
import com.fivetech.monitor.engine.judge.RunState;
import com.fivetech.monitor.engine.judge.StepResult;
import com.fivetech.monitor.engine.judge.MonitorJudge;
import com.fivetech.monitor.engine.judge.PeriodPoint;
import com.fivetech.monitor.exception.MonitorErrorCode;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.mapper.MonitorEngineMapper;
import com.fivetech.monitor.service.MonitorNotifyTemplateService;

/**
 * 管理后台「手动触发测试告警」：取一条规则最近一个已结束周期的真实数据，按线上模板生成「严重」告警，
 * 开头加「【測試】」，发到 monitor.lark.test-chat-id（环境变量 MONITOR_TEST_CHAT_ID）配置的固定测试群。
 *
 * <p>只用于看消息格式和数据对不对：不写事件、不写发送记录、不改规则运行状态，不受静默期限制，
 * 也不管这一周期实际是否越线（返回里的 level 是按真实数据判定的结果，供对照）。规则暂停也可以触发。</p>
 *
 * <p>{@link #dryRun}：开发自测「规则判定逻辑」。指定规则和一个检测周期，用线上判定引擎同一套函数
 * （{@link MonitorJudge#step}：连续次数、样本量、注意升级）从该周期往前 30 天重放到这个周期，
 * 并按线上发送规则（FY-30：新触发严重 → ALERT_NEW、注意升级为严重 → ALERT_ESCALATED，同一事件同类型只发一次，静默期）
 * 判断这个周期会不会发 Lark；会发时才发到测试群（带「【測試】」）。同样不写任何表。</p>
 *
 * @author fivetech
 */
@Service
public class MonitorTestAlertService
{
    private static final Logger log = LoggerFactory.getLogger(MonitorTestAlertService.class);

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static final String TEST_PREFIX = "【測試】";

    private final MonitorEngineMapper mapper;

    private final MonitorDataFetcher fetcher;

    private final MonitorPeriodBuilder periodBuilder;

    private final MonitorMessageBuilder messages;

    private final MonitorNotifyTemplateService templates;

    private final LarkClient larkClient;

    private final MonitorProperties properties;

    private final ZoneId zone;

    public MonitorTestAlertService(MonitorEngineMapper mapper, MonitorDataFetcher fetcher, MonitorPeriodBuilder periodBuilder,
            MonitorMessageBuilder messages, MonitorNotifyTemplateService templates, LarkClient larkClient,
            MonitorProperties properties, DashboardProperties dashboard)
    {
        this.mapper = mapper;
        this.fetcher = fetcher;
        this.periodBuilder = periodBuilder;
        this.messages = messages;
        this.templates = templates;
        this.larkClient = larkClient;
        this.properties = properties;
        this.zone = ZoneId.of(dashboard.getTimezone());
    }

    /**
     * @return { success, messageId, error, chatId, ruleId, ruleCode, metricCode, periodFrom, periodTo, value, baseline,
     *         level, dataError, content, templateId, templateVersion }
     */
    public Map<String, Object> trigger(MonitorScope scope, Long ruleId, String operator)
    {
        MonitorProperties.Lark lark = properties.getLark();
        String chatId = StringUtils.trim(lark.getTestChatId());
        if (StringUtils.isEmpty(chatId))
        {
            throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "未配置測試群（環境變量 MONITOR_TEST_CHAT_ID）");
        }
        if (StringUtils.isEmpty(lark.getAppId()) || StringUtils.isEmpty(lark.getAppSecret()))
        {
            throw new MonitorException(MonitorErrorCode.LARK_CREDENTIAL_INVALID, "Lark 應用憑證未配置");
        }
        MonitorEngineRule rule = ruleId == null ? null : mapper.selectEngineRule(scope, ruleId);
        if (rule == null)
        {
            throw MonitorException.notFound("規則不存在");
        }
        JudgeRule judge = MonitorEventWriter.toJudgeRule(rule);
        LocalDateTime now = LocalDateTime.now(zone);
        LocalDateTime[] period = latestPeriod(now, judge.getPeriodHours());

        // 取数：失败或没就绪时照样发，数值显示「—」，返回 dataError 说明原因
        PeriodPoint point;
        String dataError = null;
        try
        {
            HourlyFetch data = fetcher.fetch(List.of(rule), period[0], period[1]);
            if (data == null || data.isNotReady())
            {
                dataError = "資料平台數據未就緒";
                point = new PeriodPoint(period[0], period[1], null, null, null, true);
            }
            else
            {
                point = periodBuilder.build(rule, data, period[0], period[1]);
                if (point.isMissing())
                {
                    dataError = "該週期沒有數據";
                }
            }
        }
        catch (RuntimeException e)
        {
            dataError = "取數失敗：" + StringUtils.substring(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 0, 200);
            point = new PeriodPoint(period[0], period[1], null, null, null, true);
            log.warn("[monitor] 手动触发测试告警取数失败 site={} rule={}：{}", scope.getSiteCode(), ruleId, e.getMessage());
        }
        String level = MonitorJudge.periodLevel(judge, point);

        List<BigDecimal[]> recent = new ArrayList<>();
        recent.add(new BigDecimal[] { point.getValue(), point.getBaseline() });
        Map<String, String> values = messages.alertValues(rule, judge, false, period[0], recent, now);
        MonitorNotifyTemplateService.Rendered rendered = templates.render(scope.getSiteCode(), MonitorNotifyTemplate.ALERT_NEW, values);
        String content = TEST_PREFIX + rendered.getContent();

        LarkResult<String> r = larkClient.sendPost(lark.getAppId(), lark.getAppSecret(), "chat_id", chatId, "",
                MonitorLarkPost.paragraphs(content));
        String error = r.isSuccess() ? null : MonitorNotifySender.reason(r, false);
        log.info("[monitor] 手动触发测试告警 site={} rule={} metric={} period={}~{} level={} by={} → {}", scope.getSiteCode(),
                ruleId, rule.getMetricCode(), period[0], period[1], level, operator, r.isSuccess() ? "已发送 " + r.getData() : error);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", r.isSuccess());
        out.put("messageId", r.isSuccess() ? r.getData() : null);
        out.put("error", error);
        out.put("chatId", chatId);
        out.put("ruleId", rule.getRuleId());
        out.put("ruleCode", values.get("ruleCode"));
        out.put("metricCode", rule.getMetricCode());
        out.put("periodFrom", period[0].format(MINUTE));
        out.put("periodTo", period[1].format(MINUTE));
        out.put("value", point.getValue());
        out.put("baseline", point.getBaseline());
        out.put("level", level);
        out.put("dataError", dataError);
        out.put("content", content);
        out.put("templateId", rendered.getTemplateId());
        out.put("templateVersion", rendered.getTemplateVersion());
        return out;
    }

    // ------------------------------------------------------------------ 判定试运行（开发自测）

    static final int DRY_RUN_WINDOW_DAYS = 30;

    private static final DateTimeFormatter[] PERIOD_INPUT = {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH")
    };

    /**
     * 判定试运行：规则 + 检测周期（周期内任一时间，如「2026-10-19 18:00」，按规则的检测周期对齐到所在周期），
     * 按线上引擎重放并判断这一周期会不会发告警；会发且 send = true 时发到测试群。不写任何表。
     *
     * @return { ruleId, ruleCode, metricCode, periodHours, durationCount, periodFrom, periodTo, window, value, baseline, sample,
     *         level, event, notify{decision, type, reason}, recentPeriods, sent, success, messageId, error, chatId, content,
     *         dataError }
     */
    public Map<String, Object> dryRun(MonitorScope scope, Long ruleId, String periodText, boolean send, String operator)
    {
        MonitorEngineRule rule = ruleId == null ? null : mapper.selectEngineRule(scope, ruleId);
        if (rule == null)
        {
            throw MonitorException.notFound("規則不存在");
        }
        JudgeRule judge = MonitorEventWriter.toJudgeRule(rule);
        LocalDateTime now = LocalDateTime.now(zone);
        LocalDateTime[] period = alignPeriod(parsePeriod(periodText), judge.getPeriodHours());
        if (period[1].isAfter(now.truncatedTo(ChronoUnit.HOURS)))
        {
            throw new MonitorException(MonitorErrorCode.BAD_REQUEST,
                "該週期還沒結束：" + period[0].format(MINUTE) + " – " + period[1].format(MINUTE));
        }
        MonitorProperties.Lark lark = properties.getLark();
        String chatId = StringUtils.trim(lark.getTestChatId());
        if (send && StringUtils.isEmpty(chatId))
        {
            throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "未配置測試群（環境變量 MONITOR_TEST_CHAT_ID）；只看判定結果請傳 send = false");
        }
        if (send && (StringUtils.isEmpty(lark.getAppId()) || StringUtils.isEmpty(lark.getAppSecret())))
        {
            throw new MonitorException(MonitorErrorCode.LARK_CREDENTIAL_INVALID, "Lark 應用憑證未配置");
        }

        // 窗口：所在日往前 29 天的 00:00 起，到这个周期结束（与回测预览同样的 30 天，周期从 00:00 起对齐）
        LocalDateTime from = period[0].toLocalDate().minusDays(DRY_RUN_WINDOW_DAYS - 1L).atStartOfDay();
        String dataError = null;
        HourlyFetch data = null;
        try
        {
            data = fetcher.fetch(List.of(rule), from, period[1]);
            if (data == null || data.isNotReady())
            {
                dataError = "資料平台數據未就緒";
                data = null;
            }
        }
        catch (RuntimeException e)
        {
            dataError = "取數失敗：" + StringUtils.substring(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 0, 200);
            log.warn("[monitor] 判定试运行取数失败 site={} rule={}：{}", scope.getSiteCode(), ruleId, e.getMessage());
        }
        int p = judge.getPeriodHours();
        List<PeriodPoint> points = new ArrayList<>();
        for (LocalDateTime t = from; !t.plusHours(p).isAfter(period[1]); t = t.plusHours(p))
        {
            points.add(data == null ? new PeriodPoint(t, t.plusHours(p), null, null, null, true)
                    : periodBuilder.build(rule, data, t, t.plusHours(p)));
        }
        DryRun r = replay(judge, rule.getMuteHours(), points, properties.getEngine().getLookbackHours());
        PeriodPoint target = points.get(points.size() - 1);
        if (dataError == null && target.isMissing())
        {
            dataError = "該週期沒有數據";
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ruleId", rule.getRuleId());
        out.put("metricCode", rule.getMetricCode());
        out.put("judgeType", rule.getJudgeType());
        out.put("periodHours", p);
        out.put("durationCount", judge.getDurationCount());
        out.put("escalateHours", judge.getEscalateHours());
        out.put("muteHours", rule.getMuteHours());
        out.put("periodFrom", period[0].format(MINUTE));
        out.put("periodTo", period[1].format(MINUTE));
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("from", from.format(MINUTE));
        window.put("to", period[1].format(MINUTE));
        out.put("window", window);
        out.put("value", target.getValue());
        out.put("baseline", target.getBaseline());
        out.put("sample", target.getSample());
        out.put("level", r.level);
        out.put("dataError", dataError);
        Map<String, Object> event = null;
        if (r.eventLevel != null)
        {
            event = new LinkedHashMap<>();
            event.put("action", r.action.name());
            event.put("level", r.eventLevel);
            event.put("escalated", r.escalated);
            event.put("startHour", r.startHour == null ? null : r.startHour.format(MINUTE));
            event.put("triggerHour", r.triggerHour == null ? null : r.triggerHour.format(MINUTE));
        }
        out.put("event", event);
        Map<String, Object> notify = new LinkedHashMap<>();
        notify.put("decision", r.decision);
        notify.put("type", r.type);
        notify.put("reason", r.reason);
        notify.put("lastNotifiedAt", r.lastNotifiedAt == null ? null : r.lastNotifiedAt.format(MINUTE));
        out.put("notify", notify);
        List<Map<String, Object>> recentPeriods = new ArrayList<>();
        for (int k = Math.max(0, points.size() - judge.getDurationCount()); k < points.size(); k++)
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from", points.get(k).getFrom().format(MINUTE));
            m.put("value", points.get(k).getValue());
            m.put("baseline", points.get(k).getBaseline());
            m.put("level", r.levels.get(k));
            recentPeriods.add(m);
        }
        out.put("recentPeriods", recentPeriods);

        boolean sent = false;
        String content = null;
        if ("SEND".equals(r.decision))
        {
            List<BigDecimal[]> recent = new ArrayList<>();
            for (int k = Math.max(0, points.size() - judge.getDurationCount()); k < points.size(); k++)
            {
                recent.add(new BigDecimal[] { points.get(k).getValue(), points.get(k).getBaseline() });
            }
            Map<String, String> values = messages.alertValues(rule, judge, r.escalated, r.triggerHour, recent, period[1]);
            out.put("ruleCode", values.get("ruleCode"));
            MonitorNotifyTemplateService.Rendered rendered = templates.render(scope.getSiteCode(), r.type, values);
            content = TEST_PREFIX + rendered.getContent();
            out.put("templateId", rendered.getTemplateId());
            out.put("templateVersion", rendered.getTemplateVersion());
            if (send)
            {
                LarkResult<String> lr = larkClient.sendPost(lark.getAppId(), lark.getAppSecret(), "chat_id", chatId, "",
                        MonitorLarkPost.paragraphs(content));
                sent = lr.isSuccess();
                out.put("success", lr.isSuccess());
                out.put("messageId", lr.isSuccess() ? lr.getData() : null);
                out.put("error", lr.isSuccess() ? null : MonitorNotifySender.reason(lr, false));
            }
        }
        out.put("sent", sent);
        out.put("chatId", send ? chatId : null);
        out.put("content", content);
        log.info("[monitor] 判定试运行 site={} rule={} metric={} period={}~{} level={} event={} decision={} sent={} by={}",
            scope.getSiteCode(), ruleId, rule.getMetricCode(), period[0], period[1], r.level, r.eventLevel, r.decision, sent, operator);
        return out;
    }

    /** 重放结果：最后一个周期（目标周期）的判定与发送决定 */
    static class DryRun
    {
        List<String> levels = new ArrayList<>();
        String level;
        StepResult.Action action = StepResult.Action.NONE;
        /** 目标周期后异常中事件的等级；没有事件为 null */
        String eventLevel;
        boolean escalated;
        LocalDateTime startHour;
        LocalDateTime triggerHour;
        /** SEND 会发 / KEEP 不发 / MUTED 静默期内不发 */
        String decision;
        String type;
        String reason;
        LocalDateTime lastNotifiedAt;
    }

    /**
     * 按线上引擎逐周期重放（{@link MonitorJudge#step}，状态从空开始），并按 MonitorEventWriter 的发送规则模拟每一周期的发送：
     * 开事件即严重 → ALERT_NEW；事件由注意变严重 → 升级上来的 ALERT_ESCALATED，越过严重线的 ALERT_NEW；
     * 同一事件同一类型只发一次；距上次（模拟）发送不足静默期 → MUTED。发送时间按周期结束时刻计。
     */
    static DryRun replay(JudgeRule judge, Integer muteHours, List<PeriodPoint> points, int lookbackHours)
    {
        DryRun out = new DryRun();
        RunState state = null;
        String eventLevel = null;
        LocalDateTime eventStart = null;
        LocalDateTime eventTrigger = null;
        Set<String> sentTypes = new HashSet<>();
        LocalDateTime lastNotified = null;
        for (int i = 0; i < points.size(); i++)
        {
            PeriodPoint p = points.get(i);
            StepResult r = MonitorJudge.step(judge, state, p, lookbackHours);
            out.levels.add(r.getLevel());
            String decision = "KEEP";
            String type = null;
            String reason;
            String before = eventLevel;
            switch (r.getAction())
            {
                case OPEN:
                    eventLevel = r.getEventLevel();
                    eventStart = r.getStartHour();
                    eventTrigger = r.getTriggerHour();
                    sentTypes.clear();
                    break;
                case UPDATE:
                    eventLevel = r.getEventLevel();
                    break;
                case RESOLVE:
                    eventLevel = null;
                    eventStart = null;
                    eventTrigger = null;
                    sentTypes.clear();
                    break;
                default:
                    break;
            }
            boolean opened = r.getAction() == StepResult.Action.OPEN;
            boolean becameCrit = r.getAction() == StepResult.Action.UPDATE && !Levels.CRIT.equals(before);
            if ((opened || becameCrit) && Levels.CRIT.equals(eventLevel))
            {
                type = r.isEscalated() ? MonitorNotifyTemplate.ALERT_ESCALATED : MonitorNotifyTemplate.ALERT_NEW;
                if (sentTypes.contains(type))
                {
                    reason = "同一事件已發過這類通知，不再發送";
                }
                else if (lastNotified != null && muteHours != null && muteHours > 0 && lastNotified.plusHours(muteHours).isAfter(p.getTo()))
                {
                    decision = "MUTED";
                    reason = "靜默期內（上次發送 " + lastNotified.format(MINUTE) + "，靜默 " + muteHours + " 小時），本次不發送";
                }
                else
                {
                    decision = "SEND";
                    reason = opened ? "新觸發嚴重" : r.isEscalated() ? "注意持續，升級為嚴重" : "越過嚴重線，由注意變為嚴重";
                    sentTypes.add(type);
                    lastNotified = p.getTo();
                }
            }
            else if (r.getAction() == StepResult.Action.OPEN)
            {
                reason = "新觸發注意：注意不發送";
            }
            else if (r.getAction() == StepResult.Action.UPDATE)
            {
                reason = Levels.CRIT.equals(before) ? "嚴重持續中：未恢復期間不重複發送" : "注意持續中：注意不發送";
            }
            else if (r.getAction() == StepResult.Action.RESOLVE)
            {
                reason = "事件恢復：恢復不發送";
            }
            else if (Levels.SKIP.equals(r.getLevel()))
            {
                reason = eventLevel != null ? "本週期不判定（數據缺失 / 樣本量不足 / 缺基準），事件保持不變" : "本週期不判定（數據缺失 / 樣本量不足 / 缺基準）";
            }
            else if (Levels.isOver(r.getLevel()))
            {
                int run = r.getNext() == null ? 0 : r.getNext().getRecentLevels().size();
                reason = "越線但未連續達到 " + judge.getDurationCount() + " 次（目前連續 " + run + " 次）";
            }
            else
            {
                reason = "未越線";
            }
            state = r.getNext();
            if (i == points.size() - 1)
            {
                out.level = r.getLevel();
                out.action = r.getAction();
                out.eventLevel = eventLevel;
                out.escalated = eventLevel != null && r.isEscalated();
                out.startHour = eventStart;
                out.triggerHour = eventTrigger;
                out.decision = decision;
                out.type = type;
                out.reason = reason;
                out.lastNotifiedAt = "SEND".equals(decision) ? null : lastNotified;
            }
        }
        return out;
    }

    /** 「2026-10-19 18:00」「2026-10-19T18:00:00」「2026-10-19 18」等 */
    static LocalDateTime parsePeriod(String text)
    {
        String t = StringUtils.trim(text);
        if (StringUtils.isNotEmpty(t))
        {
            for (DateTimeFormatter f : PERIOD_INPUT)
            {
                try
                {
                    return LocalDateTime.parse(t, f);
                }
                catch (RuntimeException ignored)
                {
                    // 试下一个格式
                }
            }
        }
        throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "period 格式為 yyyy-MM-dd HH:mm，如 2026-10-19 18:00");
    }

    /** 对齐到所在检测周期 [from, to)：周期从当天 00:00 起每 periodHours 小时一段（与判定引擎一致） */
    static LocalDateTime[] alignPeriod(LocalDateTime t, int periodHours)
    {
        int p = Math.max(1, periodHours);
        LocalDateTime h = t.truncatedTo(ChronoUnit.HOURS);
        LocalDateTime start = h.withHour(h.getHour() / p * p);
        return new LocalDateTime[] { start, start.plusHours(p) };
    }

    /**
     * 最近一个已结束的检测周期 [from, to)：周期最后一个小时 H 满足 (H.hour + 1) % periodHours == 0，且 H + 1 ≤ 当前整点。
     */
    static LocalDateTime[] latestPeriod(LocalDateTime now, int periodHours)
    {
        int p = Math.max(1, periodHours);
        LocalDateTime h = now.truncatedTo(ChronoUnit.HOURS).minusHours(1);
        while ((h.getHour() + 1) % p != 0)
        {
            h = h.minusHours(1);
        }
        return new LocalDateTime[] { h.minusHours(p - 1L), h.plusHours(1) };
    }
}
