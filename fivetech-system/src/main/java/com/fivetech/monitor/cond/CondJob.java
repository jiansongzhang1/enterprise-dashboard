package com.fivetech.monitor.cond;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.monitor.domain.MonitorCondRow;
import com.fivetech.monitor.domain.MonitorCondState;
import com.fivetech.monitor.domain.MonitorRule;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.mapper.MonitorCondMapper;

/**
 * 条件规则每小时的检测（设计文档 4.4），挂在监控判定的定时任务上（{@code MonitorEngineScheduler.poll}，每 10 分钟）：
 * <ol>
 *   <li>检测点 = 站点时区的当前整点，统计刚结束的时段；整点后 {@code monitor.cond.ready-minute} 分钟起处理，每条规则每小时只处理一次；</li>
 *   <li>服务停过时不补判更早的小时；不是该窗口检测点的小时只记已处理；</li>
 *   <li>本站全部规则按范围合并后一次批量取数（近 4 周 + 当前时段）；取数失败等下一轮，到 :50 仍失败放弃这一小时（不改连续计数）；</li>
 *   <li>每条规则一个事务写状态、事件与待发送通知（{@link CondEventWriter}）。</li>
 * </ol>
 *
 * @author fivetech
 */
@Component
public class CondJob
{
    private static final Logger log = LoggerFactory.getLogger(CondJob.class);

    private final MonitorCondMapper mapper;

    private final CondSupport support;

    private final CondEventWriter writer;

    private final CondDims dims;

    private final MonitorProperties monitorProperties;

    private final com.fivetech.monitor.engine.MonitorTestSender testSender;

    public CondJob(MonitorCondMapper mapper, CondSupport support, CondEventWriter writer, CondDims dims,
            MonitorProperties monitorProperties, com.fivetech.monitor.engine.MonitorTestSender testSender)
    {
        this.mapper = mapper;
        this.support = support;
        this.writer = writer;
        this.dims = dims;
        this.monitorProperties = monitorProperties;
        this.testSender = testSender;
    }

    /** 有启用条件规则的站点 */
    public List<String> sites()
    {
        return mapper.selectCondSites();
    }

    /**
     * 一个站点一轮。调用方负责站点上下文（SiteContext）。
     *
     * @param nowStat 统计时区的当前时间
     * @return 本轮判定的规则数
     */
    public int run(String site, LocalDateTime nowStat)
    {
        CondProperties p = support.props();
        if (!p.isEnabled() || !support.data().available())
        {
            return 0;
        }
        MonitorScope scope = new MonitorScope(site, null);
        LocalDateTime siteNow = support.toSite(site, nowStat);
        if (siteNow.getMinute() < p.getReadyMinute())
        {
            return 0;
        }
        boolean finalCheck = siteNow.getMinute() >= monitorProperties.getEngine().getLastCheckMinute();
        LocalDateTime end = siteNow.truncatedTo(ChronoUnit.HOURS);
        LocalDateTime hourStat = support.toStat(site, end.minusHours(1));

        List<MonitorRule> rules = mapper.selectEnabledCondRules(scope);
        if (rules.isEmpty())
        {
            return 0;
        }
        List<Long> ids = new ArrayList<>();
        rules.forEach(r -> ids.add(r.getRuleId()));
        List<MonitorCondRow> rows = mapper.selectConditions(scope, ids);
        Map<Long, MonitorCondState> states = new HashMap<>();
        for (MonitorCondState s : mapper.selectStates(scope, ids))
        {
            states.put(s.getRuleId(), s);
        }

        List<MonitorRule> due = new ArrayList<>();
        Map<Long, CondSpec> specs = new HashMap<>();
        LocalDateTime from = end;
        Map<String, CondDataSource.Request> requests = new LinkedHashMap<>();
        for (MonitorRule rule : rules)
        {
            MonitorCondState st = states.get(rule.getRuleId());
            if (st == null || st.getLastDataHour() == null)
            {
                // 没有状态（旧数据或状态被清）：从现在起判定
                writer.skip(scope, st, rule.getRuleId(), hourStat);
                continue;
            }
            if (!st.getLastDataHour().isBefore(hourStat))
            {
                continue;
            }
            CondSpec spec = support.spec(rule, rows);
            if (spec.getConditions().isEmpty() || !spec.getWindow().isCheckpoint(end))
            {
                writer.skip(scope, st, rule.getRuleId(), hourStat);
                continue;
            }
            specs.put(rule.getRuleId(), spec);
            due.add(rule);
            support.collect(spec, requests);
            LocalDateTime f = CondEvaluator.dataFrom(spec, end, p.getBaseWeeks());
            if (f.isBefore(from))
            {
                from = f;
            }
        }
        if (due.isEmpty())
        {
            return 0;
        }
        Map<String, CondSeries> data;
        try
        {
            data = support.data().fetch(new ArrayList<>(requests.values()), from, end);
        }
        catch (RuntimeException e)
        {
            log.error("[monitor] 站点 {} 条件规则取 {} 的数据失败：{}", site, end, e.getMessage());
            if (finalCheck)
            {
                for (MonitorRule rule : due)
                {
                    writer.skip(scope, states.get(rule.getRuleId()), rule.getRuleId(), hourStat);
                }
                log.warn("[monitor] 站点 {} 条件规则 {} 到最后一次检查仍取不到数据，本时段不判定", site, end);
            }
            return 0;
        }
        CondDims.Snapshot labels = dims.of(site);
        Map<String, CondField> fields = support.catalog().fields();
        int done = 0;
        for (MonitorRule rule : due)
        {
            CondSpec spec = specs.get(rule.getRuleId());
            Map<Integer, CondSeries> series = CondSupport.byIndex(spec, data);
            CondEvaluator.Point point = CondEvaluator.point(spec, fields, series, end, p.getBaseWeeks());
            try
            {
                Long opened = writer.apply(scope, rule, spec, point, end, states.get(rule.getRuleId()),
                    noBase(spec, point, series, p.getBaseWeeks()), labels, nowStat);
                if (opened != null)
                {
                    log.info("[monitor] 条件规则触发 site={} rule={} event={} end={}", site, rule.getRuleId(), opened, end);
                }
                done++;
            }
            catch (RuntimeException e)
            {
                log.error("[monitor] 条件规则 {} 写库失败（下一轮重试）：{}", rule.getRuleId(), e.getMessage(), e);
            }
        }
        return done;
    }

    /** 用到基准的条件历史不够时：{ noBase: [1, 3], from: "2026-11-20" }（预计开始判定的日期）；没有为空 */
    static Map<String, Object> noBase(CondSpec spec, CondEvaluator.Point point, Map<Integer, CondSeries> series, int baseWeeks)
    {
        List<Integer> idx = new ArrayList<>();
        LocalDate from = null;
        for (CondEvaluator.Outcome o : point.outcomes())
        {
            if (o.status() != CondEvaluator.Status.NO_BASE)
            {
                continue;
            }
            idx.add(o.index());
            CondCondition c = spec.getConditions().stream().filter(x -> x.getIndex() == o.index()).findFirst().orElse(null);
            CondSeries s = series.get(o.index());
            LocalDateTime first = s == null ? null : s.getFirstHour();
            LocalDate d = (first == null ? point.end() : first).toLocalDate()
                .plusDays(c != null && "YD".equals(c.getHow()) ? 1 : 7L * Math.max(1, baseWeeks));
            if (from == null || d.isAfter(from))
            {
                from = d;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        if (!idx.isEmpty())
        {
            m.put("noBase", idx);
            m.put("from", from == null ? null : from.toString());
        }
        return m;
    }

    // ------------------------------------------------------------------ 测试告警

    /**
     * 手动发一条测试告警（FY-91 R9）：按最近一个检测点取数，正文加「【測試】」，发到规则当前所有正常的群；不写事件。
     */
    public Map<String, Object> testAlert(MonitorScope scope, MonitorRule rule, List<MonitorCondRow> rows, String operator)
    {
        String site = scope.getSiteCode();
        CondSpec spec = support.spec(rule, rows);
        LocalDateTime end = support.siteNow(site).truncatedTo(ChronoUnit.HOURS);
        while (!spec.getWindow().isCheckpoint(end))
        {
            end = end.minusHours(1);
        }
        List<CondEvaluator.Outcome> outcomes = new ArrayList<>();
        String dataError = null;
        try
        {
            Map<Integer, CondSeries> series = support.fetch(spec, CondEvaluator.dataFrom(spec, end, support.props().getBaseWeeks()), end);
            outcomes = CondEvaluator.point(spec, support.catalog().fields(), series, end, support.props().getBaseWeeks()).outcomes();
        }
        catch (RuntimeException e)
        {
            dataError = "取數失敗：" + StringUtils.substring(String.valueOf(e.getMessage()), 0, 200);
        }
        CondDims.Snapshot labels = dims.of(site);
        String content = com.fivetech.monitor.engine.MonitorTestSender.TEST_PREFIX + CondMessages.alert(support.dashboard().siteName(site), rule.getRuleName(),
            CondEventWriter.ruleCode(rule.getRuleNo()), spec, support.catalog().fields(), labels, support.symbol(site), end, outcomes, null);
        Map<String, Object> sent = testSender.send(scope, rule.getNotifyChats(), rule.getNotifyChatId(), rule.getNotifyChatName(), content);
        log.info("[monitor] 条件规则测试告警 site={} rule={} by={} → success={}", site, rule.getRuleId(), operator, sent.get("success"));
        Map<String, Object> out = new LinkedHashMap<>(sent);
        out.put("ruleId", rule.getRuleId());
        out.put("ruleCode", CondEventWriter.ruleCode(rule.getRuleNo()));
        out.put("period", spec.getWindow().label(end));
        out.put("dataError", dataError);
        out.put("content", content);
        return out;
    }
}
