package com.fivetech.monitor.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.domain.vo.MetricMonitorSlotVO;
import com.fivetech.dashboard.gateway.HourlyFetch;
import com.fivetech.monitor.domain.MonitorCardRule;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.engine.MonitorDataFetcher;
import com.fivetech.monitor.engine.MonitorMessageBuilder;
import com.fivetech.monitor.engine.MonitorPeriodBuilder;
import com.fivetech.monitor.engine.domain.MonitorEngineRule;
import com.fivetech.monitor.engine.judge.JudgeRule;
import com.fivetech.monitor.engine.judge.Levels;
import com.fivetech.monitor.engine.judge.MonitorJudge;
import com.fivetech.monitor.engine.judge.PeriodPoint;
import com.fivetech.monitor.mapper.MonitorOverviewMapper;
import tools.jackson.databind.ObjectMapper;

/**
 * 指标抽屉「逐时段明细」的监控列（原型 y20slot）：今日每个小时所在检测周期的判定结果。
 * <ul>
 *   <li>按当前规则（阈值、检测周期、最小样本量）判定，和判定引擎用同一个周期聚合与判级函数。</li>
 *   <li>样本不足、数据缺失 → SKIP（reason 区分）；多小时周期的最后一段还没结束 → PENDING。</li>
 *   <li>注意升级：引擎在 recent_periods 里记了该周期的 escalated 就按记的；否则按异常中事件推算
 *       （事件已升级、本周期越线、从触发起已满注意升级时长）。升级后的周期 level 写 CRIT。</li>
 * </ul>
 * 只取一次 UDS（本次要展示的全部指标一起取）；调用方负责站点上下文（请求线程已由拦截器设置）。
 *
 * @author fivetech
 */
@Service
public class MonitorSlotService
{
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final MonitorOverviewMapper mapper;

    private final MonitorDataFetcher fetcher;

    private final MonitorPeriodBuilder periodBuilder;

    private final ObjectMapper json;

    public MonitorSlotService(MonitorOverviewMapper mapper, MonitorDataFetcher fetcher, MonitorPeriodBuilder periodBuilder,
            ObjectMapper json)
    {
        this.mapper = mapper;
        this.fetcher = fetcher;
        this.periodBuilder = periodBuilder;
        this.json = json;
    }

    /**
     * @param metricCodes 要展示监控列的指标（卡片为注意或严重、规则启用）
     * @param from        今日 00:00
     * @param to          最近一个完整小时的结束（不含）
     * @return 指标编码 → 每小时一格（长度 = from 到 to 的小时数）；没有启用规则的指标不在结果里
     */
    public Map<String, List<MetricMonitorSlotVO>> slots(String siteCode, Set<String> metricCodes, LocalDateTime from,
            LocalDateTime to)
    {
        Map<String, List<MetricMonitorSlotVO>> result = new LinkedHashMap<>();
        if (metricCodes == null || metricCodes.isEmpty() || !to.isAfter(from))
        {
            return result;
        }
        List<MonitorCardRule> rules = new ArrayList<>();
        List<MonitorEngineRule> engineRules = new ArrayList<>();
        for (MonitorCardRule r : mapper.selectCardRules(new MonitorScope(siteCode, null)))
        {
            if (r.isEnabled() && metricCodes.contains(r.getMetricCode())
                    && rules.stream().noneMatch(x -> x.getMetricCode().equals(r.getMetricCode())))
            {
                rules.add(r);
                engineRules.add(engineRule(siteCode, r));
            }
        }
        if (rules.isEmpty())
        {
            return result;
        }
        HourlyFetch data = fetcher.fetch(engineRules, from, to);
        if (data != null && data.isNotReady())
        {
            data = null;
        }
        for (int i = 0; i < rules.size(); i++)
        {
            result.put(rules.get(i).getMetricCode(), slotsOf(rules.get(i), engineRules.get(i), data, from, to));
        }
        return result;
    }

    List<MetricMonitorSlotVO> slotsOf(MonitorCardRule r, MonitorEngineRule er, HourlyFetch data, LocalDateTime from,
            LocalDateTime to)
    {
        int period = r.getPeriodHours() == null ? 1 : Math.max(1, r.getPeriodHours());
        JudgeRule judge = new JudgeRule(r.getJudgeType(), r.getWarnLow(), r.getWarnHigh(), r.getCritLow(), r.getCritHigh(),
            period, r.getDurationCount() == null ? 1 : r.getDurationCount(), r.getEscalateHours(),
            er.getMinSampleValue(), "base".equalsIgnoreCase(er.getMinSampleKey()));
        Map<String, Boolean> stored = storedEscalated(r.getRecentPeriods());
        String fmt = r.getValueFormat();
        boolean drop = JudgeRule.DROP.equals(r.getJudgeType());

        List<MetricMonitorSlotVO> out = new ArrayList<>();
        for (LocalDateTime p = from; p.isBefore(to); p = p.plusHours(period))
        {
            LocalDateTime end = p.plusHours(period);
            MetricMonitorSlotVO slot = new MetricMonitorSlotVO();
            slot.setPeriodFrom(p.format(MINUTE));
            slot.setPeriodTo(end.format(MINUTE));
            if (end.isAfter(to))
            {
                slot.setLevel(MetricMonitorSlotVO.PENDING);
            }
            else
            {
                PeriodPoint point = periodBuilder.build(er, data, p, end);
                fill(slot, judge, point, fmt, drop, escalated(r, judge, point, stored));
            }
            // 周期内每个小时一格（同一周期的格内容相同，前端按 periodFrom 合并）
            for (LocalDateTime h = p; h.isBefore(end) && h.isBefore(to); h = h.plusHours(1))
            {
                out.add(slot);
            }
        }
        return out;
    }

    private static void fill(MetricMonitorSlotVO slot, JudgeRule judge, PeriodPoint p, String fmt, boolean drop,
            boolean escalated)
    {
        String raw = MonitorJudge.periodLevel(judge, p);
        slot.setValue(p.getValue());
        if (drop)
        {
            slot.setBaseline(p.getBaseline());
            BigDecimal pct = MonitorJudge.dropPct(p.getValue(), p.getBaseline());
            slot.setDropPct(pct == null ? null : pct.setScale(1, RoundingMode.HALF_UP));
            if (pct != null)
            {
                String sign = pct.signum() >= 0 ? "−" : "+";
                slot.setLineText("較基準 " + sign + pct.abs().setScale(1, RoundingMode.HALF_UP).toPlainString() + "%");
                slot.setBaselineText("基準 " + MonitorMessageBuilder.display(fmt, p.getBaseline()));
            }
        }
        if (Levels.SKIP.equals(raw))
        {
            slot.setLevel(MetricMonitorSlotVO.SKIP);
            boolean missing = p.isMissing() || p.getValue() == null
                    || (drop && (p.getBaseline() == null || p.getBaseline().signum() <= 0));
            slot.setReason(missing ? "MISSING" : "SAMPLE");
            if (missing)
            {
                slot.setLineText(null);
                slot.setBaselineText(null);
            }
            return;
        }
        if (!Levels.isOver(raw))
        {
            slot.setLevel(MetricMonitorSlotVO.OK);
            return;
        }
        slot.setAgainst(raw);
        boolean up = escalated && Levels.WARN.equals(raw);
        slot.setLevel(up ? MetricMonitorSlotVO.CRIT : raw);
        slot.setEscalated(up);
        if (!drop)
        {
            slot.setLineText(lineText(judge, raw, p.getValue(), fmt));
        }
    }

    /** 越过的那条线：下限「< X」、上限「> X」、区间只写越过的一侧 */
    static String lineText(JudgeRule judge, String raw, BigDecimal v, String fmt)
    {
        boolean crit = Levels.CRIT.equals(raw);
        BigDecimal low = crit ? judge.getCritLow() : judge.getWarnLow();
        BigDecimal high = crit ? judge.getCritHigh() : judge.getWarnHigh();
        switch (judge.getJudgeType())
        {
            case JudgeRule.LO:
                return "< " + MonitorMessageBuilder.display(fmt, low);
            case JudgeRule.HI:
                return "> " + MonitorMessageBuilder.display(fmt, high);
            case JudgeRule.BAND:
                return low != null && v != null && v.compareTo(low) < 0 ? "< " + MonitorMessageBuilder.display(fmt, low)
                        : "> " + MonitorMessageBuilder.display(fmt, high);
            default:
                return null;
        }
    }

    /** 引擎记过就按记的；否则按异常中事件推算 */
    private static boolean escalated(MonitorCardRule r, JudgeRule judge, PeriodPoint p, Map<String, Boolean> stored)
    {
        Boolean s = stored.get(p.getFrom().format(MINUTE));
        if (s != null)
        {
            return s;
        }
        if (r.getEventId() == null || !Boolean.TRUE.equals(r.getEventEscalated()) || r.getEventTriggerHour() == null)
        {
            return false;
        }
        int periods = judge.escalatePeriods();
        return periods > 0 && !p.getFrom().isBefore(r.getEventTriggerHour())
                && ChronoUnit.HOURS.between(r.getEventTriggerHour(), p.getFrom()) >= (long) periods * judge.getPeriodHours();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Boolean> storedEscalated(String recentPeriods)
    {
        Map<String, Boolean> m = new HashMap<>();
        if (StringUtils.isEmpty(recentPeriods))
        {
            return m;
        }
        try
        {
            for (Object o : json.readValue(recentPeriods, List.class))
            {
                if (o instanceof Map)
                {
                    Map<String, Object> p = (Map<String, Object>) o;
                    if (p.get("from") != null && p.get("escalated") != null)
                    {
                        m.put(String.valueOf(p.get("from")).replace('T', ' '), Boolean.parseBoolean(String.valueOf(p.get("escalated"))));
                    }
                }
            }
        }
        catch (RuntimeException e)
        {
            // 解析不了按推算
        }
        return m;
    }

    private static MonitorEngineRule engineRule(String siteCode, MonitorCardRule r)
    {
        MonitorEngineRule e = new MonitorEngineRule();
        e.setRuleId(r.getRuleId());
        e.setSiteCode(siteCode);
        e.setMetricCode(r.getMetricCode());
        e.setJudgeType(r.getJudgeType());
        e.setMinSampleKey(StringUtils.isEmpty(r.getMinSampleKey()) ? "base" : r.getMinSampleKey());
        e.setMinSampleValue(r.getMinSampleValue() == null ? BigDecimal.ZERO : r.getMinSampleValue());
        e.setPeriodHours(r.getPeriodHours());
        e.setDurationCount(r.getDurationCount());
        e.setEscalateHours(r.getEscalateHours());
        return e;
    }
}
