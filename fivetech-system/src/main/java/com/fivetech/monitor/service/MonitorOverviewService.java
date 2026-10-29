package com.fivetech.monitor.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.domain.vo.MetricAlertVO;
import com.fivetech.monitor.domain.MonitorCardRule;
import com.fivetech.monitor.domain.MonitorOverviewEvent;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.vo.MonitorAnomaliesVO;
import com.fivetech.monitor.domain.vo.MonitorAnomalyItemVO;
import com.fivetech.monitor.engine.MonitorMessageBuilder;
import com.fivetech.monitor.engine.judge.JudgeRule;
import com.fivetech.monitor.engine.judge.Levels;
import com.fivetech.monitor.mapper.MonitorOverviewMapper;
import tools.jackson.databind.ObjectMapper;

/**
 * 运营总览「核心指标」下方的异常提醒条（overview 响应的 anomalies）。
 *
 * <ul>
 *   <li>本站没有任何启用规则 → null，前端不显示提醒条；</li>
 *   <li>inScope = 所选区间是否完全等于今日：true 时列出异常中的事件；false 时 items 为空，
 *       前端按 todayCount 显示「今日有 n 項異常 · 切到今日」；</li>
 *   <li>todayCount（异常中事件数）、pendingEventCount（未处理事件数）不随所选区间变化。</li>
 * </ul>
 * 阈值取自事件的 rule_snapshot（触发时的规则），窗口内的值取自规则运行状态的 recent_periods，不实时查 UDS。
 *
 * @author fivetech
 */
@Service
public class MonitorOverviewService
{
    private static final Logger log = LoggerFactory.getLogger(MonitorOverviewService.class);

    private static final DateTimeFormatter SLOT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private static final DateTimeFormatter MDHM = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm[:ss]");

    private final MonitorOverviewMapper mapper;

    private final MonitorMessageBuilder messages;

    private final ObjectMapper json;

    public MonitorOverviewService(MonitorOverviewMapper mapper, MonitorMessageBuilder messages, ObjectMapper json)
    {
        this.mapper = mapper;
        this.messages = messages;
        this.json = json;
    }

    /**
     * @param siteCode 当前站点
     * @param inScope 所选区间是否完全等于今日
     * @param today 站点时区的今天（判断 startText 写时分还是月日时分）
     */
    public MonitorAnomaliesVO anomalies(String siteCode, boolean inScope, LocalDate today)
    {
        MonitorScope scope = new MonitorScope(siteCode, null);
        if (mapper.countEnabledRules(scope) <= 0)
        {
            return null;
        }
        MonitorAnomaliesVO vo = new MonitorAnomaliesVO();
        vo.setInScope(inScope);
        LocalDateTime lastJudged = mapper.selectLastJudgedHour(scope);
        LocalDateTime asOf = lastJudged == null ? null : lastJudged.plusHours(1);
        vo.setAsOf(asOf == null ? null : asOf.format(SLOT));
        vo.setPendingEventCount(mapper.countPendingEvents(scope));

        List<MonitorOverviewEvent> events = mapper.selectOpenEvents(scope);
        vo.setTodayCount(events.size());
        if (!inScope)
        {
            return vo;
        }

        List<MonitorAnomalyItemVO> items = new ArrayList<>();
        for (MonitorOverviewEvent e : events)
        {
            try
            {
                items.add(toItem(e, asOf, today));
            }
            catch (RuntimeException ex)
            {
                // 单条事件数据异常（快照解析失败等）不影响其他事件
                log.warn("[monitor] 总览异常提醒条组装失败 eventId={}：{}", e.getEventId(), ex.getMessage());
            }
        }
        // 严重在前；同等级按持续时长从长到短
        items.sort(Comparator.comparingInt((MonitorAnomalyItemVO i) -> Levels.CRIT.equals(i.getLevel()) ? 0 : 1)
            .thenComparing(Comparator.comparingLong(MonitorAnomalyItemVO::getDurationHours).reversed()));
        vo.setItems(items);
        vo.setTotal(items.size());
        vo.setCritCount((int) items.stream().filter(i -> Levels.CRIT.equals(i.getLevel())).count());
        vo.setWarnCount((int) items.stream().filter(i -> Levels.WARN.equals(i.getLevel())).count());
        return vo;
    }

    private MonitorAnomalyItemVO toItem(MonitorOverviewEvent e, LocalDateTime asOf, LocalDate today)
    {
        JudgeRule judge = judgeRuleOf(e.getRuleSnapshot());
        List<Map<String, Object>> window = windowPeriods(e.getRecentPeriods(), judge.getDurationCount());
        List<BigDecimal[]> recent = new ArrayList<>();
        for (Map<String, Object> p : window)
        {
            recent.add(new BigDecimal[] { decimal(p.get("value")), decimal(p.get("baseline")) });
        }
        LocalDateTime windowFrom = window.isEmpty() ? null : time(window.get(0).get("from"));
        LocalDateTime windowTo = window.isEmpty() ? null : time(window.get(window.size() - 1).get("to"));

        MonitorAnomalyItemVO item = new MonitorAnomalyItemVO();
        item.setEventId(e.getEventId());
        item.setLevel(e.getLevel());
        item.setEscalated(e.isEscalated());
        item.setMetricCode(e.getMetricCode());
        item.setMetricName(MonitorText.metricName(e.getMetricCode(), e.getMetricName()));
        item.setRuleCode(e.getRuleNo() == null ? null : String.format("R-%03d", e.getRuleNo()));
        item.setJudgeValue(new MonitorAnomalyItemVO.JudgeValue(
            MonitorMessageBuilder.windowText(window.size(), judge.getPeriodHours(), windowFrom, windowTo),
            messages.valueText(e.getValueFormat(), judge, e.getLevel(), e.isEscalated(), recent)));
        item.setJudgeLine(new MonitorAnomalyItemVO.JudgeLine(
            MonitorMessageBuilder.against(judge, e.getLevel(), e.isEscalated()),
            messages.lineText(e.getValueFormat(), judge, e.getLevel(), e.isEscalated())));

        LocalDateTime start = e.getStartHour();
        if (start != null)
        {
            item.setStartHour(start.format(SLOT));
            item.setStartText(start.toLocalDate().equals(today) ? start.format(HM) : start.format(MDHM));
            // 持续时长 = 最近一次判定周期的结束 − 起点（异常中事件），取整小时
            LocalDateTime end = e.getLastPeriodEnd() != null ? e.getLastPeriodEnd() : asOf;
            long hours = end == null ? 0 : Math.max(0, ChronoUnit.HOURS.between(start, end));
            item.setDurationHours(hours);
            item.setDurationText(hours + " 小時");
        }
        return item;
    }

    // ===================== 卡片 alert =====================

    /**
     * 本站各指标卡片的 alert（指标编码 → alert）。没有规则的指标不在结果里，调用方按 NOT_CONFIGURED 处理。
     * <ul>
     *   <li>规则已暂停：state = NOT_CONFIGURED，但返回 rule（门槛条的线是指标标准，暂停也要画）；</li>
     *   <li>有异常中事件：state = level = 事件当前等级，带 since / escalated / durationHours / eventId / reasonLine；</li>
     *   <li>没有事件：最近一个周期未判定（样本不足 / 数据缺失）或还没判定过 → INSUFFICIENT_SAMPLE，否则 OK。</li>
     * </ul>
     * 线取当前规则；窗口内的值取规则运行状态 recent_periods 的最后 durationCount 个周期，不实时查 UDS。
     */
    public Map<String, MetricAlertVO> cardAlerts(String siteCode)
    {
        Map<String, MetricAlertVO> result = new LinkedHashMap<>();
        for (MonitorCardRule r : mapper.selectCardRules(new MonitorScope(siteCode, null)))
        {
            if (r.getMetricCode() == null || result.containsKey(r.getMetricCode()))
            {
                continue;
            }
            try
            {
                result.put(r.getMetricCode(), cardAlert(r));
            }
            catch (RuntimeException ex)
            {
                log.warn("[monitor] 卡片 alert 组装失败 ruleId={}：{}", r.getRuleId(), ex.getMessage());
            }
        }
        return result;
    }

    private MetricAlertVO cardAlert(MonitorCardRule r)
    {
        String fmt = r.getValueFormat();
        JudgeRule judge = new JudgeRule(r.getJudgeType(), r.getWarnLow(), r.getWarnHigh(), r.getCritLow(), r.getCritHigh(),
            r.getPeriodHours() == null ? 1 : r.getPeriodHours(), r.getDurationCount() == null ? 1 : r.getDurationCount(),
            r.getEscalateHours(), null, false);

        MetricAlertVO alert = new MetricAlertVO();
        alert.setRuleId(r.getRuleId() == null ? null : String.valueOf(r.getRuleId()));
        MetricAlertVO.RuleVO rule = new MetricAlertVO.RuleVO();
        rule.setRuleCode(r.getRuleNo() == null ? null : String.format("R-%03d", r.getRuleNo()));
        rule.setJudgeType(r.getJudgeType());
        rule.setEnabled(r.isEnabled());
        rule.setWarn(judge.hasWarn() ? threshold(fmt, r.getJudgeType(), r.getWarnLow(), r.getWarnHigh()) : null);
        rule.setCrit(threshold(fmt, r.getJudgeType(), r.getCritLow(), r.getCritHigh()));
        rule.setPeriodHours(judge.getPeriodHours());
        rule.setDurationCount(judge.getDurationCount());
        rule.setEscalateHours(r.getEscalateHours() == null || r.getEscalateHours() <= 0 ? null : r.getEscalateHours());
        alert.setRule(rule);
        if (!r.isEnabled())
        {
            alert.setState(MetricAlertVO.STATE_NOT_CONFIGURED);
            return alert;
        }

        boolean hasEvent = r.getEventId() != null && r.getEventLevel() != null;
        boolean escalated = hasEvent && Boolean.TRUE.equals(r.getEventEscalated());
        // 有异常中事件：原因句、判定线按事件快照写，与提醒条、事件列表同一句话（MON-19）；没有事件按当前规则
        JudgeRule text = judge;
        if (hasEvent && StringUtils.isNotEmpty(r.getEventRuleSnapshot()))
        {
            try
            {
                text = judgeRuleOf(r.getEventRuleSnapshot());
            }
            catch (RuntimeException ex)
            {
                log.warn("[monitor] 事件规则快照解析失败，原因句按当前规则 eventId={}：{}", r.getEventId(), ex.getMessage());
            }
        }
        List<Map<String, Object>> window = windowPeriods(r.getRecentPeriods(), text.getDurationCount());
        boolean drop = JudgeRule.DROP.equals(r.getJudgeType());

        List<MetricAlertVO.RecentVO> recent = new ArrayList<>();
        List<BigDecimal[]> values = new ArrayList<>();
        Map<String, Object> lastJudged = null;
        for (Map<String, Object> p : window)
        {
            BigDecimal value = decimal(p.get("value"));
            BigDecimal baseline = decimal(p.get("baseline"));
            String level = p.get("level") == null ? Levels.SKIP : String.valueOf(p.get("level"));
            MetricAlertVO.RecentVO item = new MetricAlertVO.RecentVO();
            item.setFrom(text(p.get("from")));
            item.setTo(text(p.get("to")));
            item.setValue(value);
            item.setBaseline(drop ? baseline : null);
            item.setLevel(level);
            boolean periodEscalated = periodEscalated(p, level, r, judge, escalated);
            item.setEscalated(periodEscalated);
            if (!Levels.SKIP.equals(level))
            {
                MonitorMessageBuilder.Deviation d = messages.deviation(fmt, text, level, periodEscalated, value, baseline);
                item.setDeviation(d == null ? null : d.getValue());
                lastJudged = p;
            }
            recent.add(item);
            values.add(new BigDecimal[] { value, baseline });
        }
        alert.setRecent(recent);
        alert.setEscalated(escalated);

        if (hasEvent)
        {
            alert.setState(r.getEventLevel());
            alert.setLevel(r.getEventLevel());
            alert.setEventId(r.getEventId());
            LocalDateTime start = r.getEventStartHour();
            if (start != null)
            {
                alert.setSince(start.format(SLOT));
                LocalDateTime end = r.getEventLastPeriodEnd();
                alert.setDurationHours(end == null ? null : Math.max(0, ChronoUnit.HOURS.between(start, end)));
            }
            alert.setReasonLine(messages.cardLineText(fmt, text, r.getEventLevel(), escalated));
        }
        else
        {
            // 没有事件：状态看最新一个周期（未判定 → 样本不足），原因句仍取最近已判定的窗口
            String last = com.fivetech.monitor.engine.MonitorPeriods.lastLevel(allPeriods(r.getRecentPeriods()));
            alert.setState(last == null || Levels.SKIP.equals(last) || "null".equals(last)
                ? MetricAlertVO.STATE_INSUFFICIENT_SAMPLE : MetricAlertVO.STATE_OK);
        }
        if (!window.isEmpty())
        {
            // 原型 ytagParts（MON-22）：「近 2 小時 83.60–84.20%」；跌幅类「較基準跌 100.0%」不写近 N 小時
            LocalDateTime from = time(window.get(0).get("from"));
            LocalDateTime to = time(window.get(window.size() - 1).get("to"));
            alert.setReasonValue(messages.cardValueText(fmt, text, hasEvent ? r.getEventLevel() : Levels.WARN, escalated, values,
                MonitorMessageBuilder.windowText(window.size(), text.getPeriodHours(), from, to)));
        }
        if (lastJudged != null)
        {
            String level = String.valueOf(lastJudged.get("level"));
            MonitorMessageBuilder.Deviation d = messages.deviation(fmt, text, level, escalated,
                decimal(lastJudged.get("value")), decimal(lastJudged.get("baseline")));
            if (d != null)
            {
                alert.setDeviation(new MetricAlertVO.DeviationVO(d.getValue(), drop ? "%" : unit(fmt), d.getAgainst(), d.getText()));
            }
        }
        return alert;
    }

    private static MetricAlertVO.Threshold threshold(String fmt, String judgeType, BigDecimal low, BigDecimal high)
    {
        return new MetricAlertVO.Threshold(MonitorMessageBuilder.thresholdText(fmt, judgeType, low),
            MonitorMessageBuilder.thresholdText(fmt, judgeType, high));
    }

    /** 与监控管理接口一致：百分比 %、分钟 min、倍数 x、金额 ₹、人数 / 笔数为空串 */
    private static String unit(String valueFormat)
    {
        String f = valueFormat == null ? "" : valueFormat.toUpperCase(Locale.ROOT);
        switch (f)
        {
            case "PCT":
                return "%";
            case "MIN":
                return "min";
            case "MULTIPLE":
                return "x";
            case "MONEY":
                return "₹";
            default:
                return "";
        }
    }

    /**
     * 这个周期判定后事件是否「已升级」。判定引擎从 2026-10-22 起在 recent_periods 里记 escalated，按记的值；
     * 之前写入的周期没有这个字段，按规则推算：异常中的事件已升级、本周期越线、且从触发起已满注意升级时长。
     */
    static boolean periodEscalated(Map<String, Object> p, String level, MonitorCardRule r, JudgeRule judge,
            boolean eventEscalated)
    {
        Object stored = p.get("escalated");
        if (stored != null)
        {
            return Boolean.parseBoolean(String.valueOf(stored));
        }
        if (!eventEscalated || !Levels.isOver(level) || r.getEventTriggerHour() == null)
        {
            return false;
        }
        LocalDateTime from = parseHour(p.get("from"));
        int periods = judge.escalatePeriods();
        return from != null && periods > 0 && !from.isBefore(r.getEventTriggerHour())
                && ChronoUnit.HOURS.between(r.getEventTriggerHour(), from) >= (long) periods * judge.getPeriodHours();
    }

    private static LocalDateTime parseHour(Object o)
    {
        String t = text(o);
        if (StringUtils.isEmpty(t))
        {
            return null;
        }
        try
        {
            return LocalDateTime.parse(t.length() > 16 ? t.substring(0, 16) : t, SLOT);
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static String text(Object o)
    {
        return o == null ? null : o.toString().replace('T', ' ');
    }

    /** 事件的规则快照 → JudgeRule（阈值以触发时为准，之后改规则不影响） */
    @SuppressWarnings("unchecked")
    private JudgeRule judgeRuleOf(String snapshot)
    {
        Map<String, Object> m = StringUtils.isEmpty(snapshot) ? Map.of() : json.readValue(snapshot, Map.class);
        Map<String, Object> warn = m.get("warn") instanceof Map ? (Map<String, Object>) m.get("warn") : Map.of();
        Map<String, Object> crit = m.get("crit") instanceof Map ? (Map<String, Object>) m.get("crit") : Map.of();
        Object escalate = m.get("escalateHours");
        return new JudgeRule(String.valueOf(m.get("judgeType")),
            decimal(warn.get("low")), decimal(warn.get("high")), decimal(crit.get("low")), decimal(crit.get("high")),
            intOf(m.get("periodHours"), 1), intOf(m.get("durationCount"), 1),
            escalate == null ? null : intOf(escalate, 0), decimal(m.get("minSample")), false);
    }

    /**
     * 判定窗口：recent_periods 里最近 durationCount 个已判定的周期（时间正序）。
     * 最新周期未判定时停在上一次有效判定，原因句不出现「—」（FY-23 R15 / MON-18）。
     */
    private List<Map<String, Object>> windowPeriods(String recentPeriods, int durationCount)
    {
        return com.fivetech.monitor.engine.MonitorPeriods.window(allPeriods(recentPeriods), durationCount);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> allPeriods(String recentPeriods)
    {
        List<Map<String, Object>> all = new ArrayList<>();
        if (StringUtils.isEmpty(recentPeriods))
        {
            return all;
        }
        for (Object o : json.readValue(recentPeriods, List.class))
        {
            if (o instanceof Map)
            {
                all.add((Map<String, Object>) o);
            }
        }
        return all;
    }

    private static BigDecimal decimal(Object o)
    {
        if (o == null || "null".equals(String.valueOf(o)))
        {
            return null;
        }
        return o instanceof BigDecimal ? (BigDecimal) o : new BigDecimal(o.toString());
    }

    private static int intOf(Object o, int fallback)
    {
        if (o == null)
        {
            return fallback;
        }
        try
        {
            return new BigDecimal(o.toString()).intValue();
        }
        catch (NumberFormatException e)
        {
            return fallback;
        }
    }

    private static LocalDateTime time(Object o)
    {
        if (o == null)
        {
            return null;
        }
        try
        {
            return LocalDateTime.parse(o.toString().replace(' ', 'T'), TS);
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }
}
