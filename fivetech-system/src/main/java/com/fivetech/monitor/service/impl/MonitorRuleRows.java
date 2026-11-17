package com.fivetech.monitor.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.monitor.cond.CondCondition;
import com.fivetech.monitor.cond.CondDims;
import com.fivetech.monitor.cond.CondField;
import com.fivetech.monitor.cond.CondRules;
import com.fivetech.monitor.cond.CondSpec;
import com.fivetech.monitor.cond.CondSupport;
import com.fivetech.monitor.cond.CondWindow;
import com.fivetech.monitor.domain.MonitorCondRow;
import com.fivetech.monitor.domain.MonitorCondState;
import com.fivetech.monitor.domain.MonitorRule;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.OrderRuleConfig;
import com.fivetech.monitor.domain.vo.MonitorRuleVO;
import com.fivetech.monitor.mapper.MonitorCondMapper;
import com.fivetech.monitor.mapper.MonitorOrderEventMapper;
import com.fivetech.monitor.service.MonitorOrderRules;
import tools.jackson.databind.ObjectMapper;

/**
 * 告警规则列表（GET /monitor/rules，设计文档 7.3.1）三类规则的公共列：类型、名称、触发条件（trigger）、检测（detect）、
 * 通知、最近触发、能否复制 / 测试。一页里的条件规则、订单规则各批量查一次。
 *
 * @author fivetech
 */
@Component
public class MonitorRuleRows
{
    private static final Logger log = LoggerFactory.getLogger(MonitorRuleRows.class);

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    static final Map<String, String> JUDGE_TEXT = Map.of("lo", "低於", "hi", "高於", "band", "超出", "drop", "較基準下跌超過");

    private final MonitorCondMapper condMapper;

    private final MonitorOrderEventMapper orderEventMapper;

    private final CondSupport cond;

    private final CondDims dims;

    private final ObjectMapper json;

    public MonitorRuleRows(MonitorCondMapper condMapper, MonitorOrderEventMapper orderEventMapper, CondSupport cond, CondDims dims,
            ObjectMapper json)
    {
        this.condMapper = condMapper;
        this.orderEventMapper = orderEventMapper;
        this.cond = cond;
        this.dims = dims;
        this.json = json;
    }

    /** rules 与 vos 一一对应（fillRuleVO 已填好指标规则的旧字段） */
    public void fill(MonitorScope scope, List<MonitorRule> rules, List<MonitorRuleVO> vos)
    {
        List<Long> condIds = new ArrayList<>();
        List<Long> orderIds = new ArrayList<>();
        for (MonitorRule r : rules)
        {
            if (CondRules.TYPE.equals(r.getRuleType()))
            {
                condIds.add(r.getRuleId());
            }
            else if (MonitorOrderRules.TYPE.equals(r.getRuleType()))
            {
                orderIds.add(r.getRuleId());
            }
        }
        List<MonitorCondRow> condRows = condIds.isEmpty() ? List.of() : condMapper.selectConditions(scope, condIds);
        Map<Long, MonitorCondState> states = new HashMap<>();
        if (!condIds.isEmpty())
        {
            condMapper.selectStates(scope, condIds).forEach(s -> states.put(s.getRuleId(), s));
        }
        Map<Long, LocalDateTime> orderLast = new HashMap<>();
        if (!orderIds.isEmpty())
        {
            for (Map<String, Object> m : orderEventMapper.selectLastTriggers(scope, orderIds))
            {
                Object id = m.get("ruleId");
                Object at = m.get("lastAt");
                if (id instanceof Number n && at != null)
                {
                    orderLast.put(n.longValue(), at instanceof LocalDateTime t ? t
                        : at instanceof java.sql.Timestamp ts ? ts.toLocalDateTime() : null);
                }
            }
        }
        CondDims.Snapshot labels = condIds.isEmpty() ? null : dims.of(scope.getSiteCode());
        String site = scope.getSiteCode();
        for (int i = 0; i < rules.size(); i++)
        {
            MonitorRule r = rules.get(i);
            MonitorRuleVO vo = vos.get(i);
            String type = r.getRuleType() == null ? "METRIC" : r.getRuleType();
            vo.setRuleType(type);
            vo.setNotifyEnabled(!Boolean.FALSE.equals(r.getNotifyEnabled()));
            vo.setChats(vo.getNotifyChats());
            vo.setCanTest(true);
            // 「MM-dd 修改」与悬停的修改人取同一次配置修改（edit_time / edit_by），启停不算
            vo.setUpdateTime(fmt(r.getEditTime() != null ? r.getEditTime() : r.getUpdateTime()));
            String editBy = StringUtils.isNotEmpty(r.getEditByEmail()) ? r.getEditByEmail() : r.getEditBy();
            if (StringUtils.isNotEmpty(editBy))
            {
                vo.setUpdateBy(editBy);
            }
            boolean paused = !Boolean.TRUE.equals(r.getEnabled());
            try
            {
                if (CondRules.TYPE.equals(type))
                {
                    condRow(vo, r, condRows, states.get(r.getRuleId()), labels, site, paused);
                }
                else if (MonitorOrderRules.TYPE.equals(type))
                {
                    orderRow(vo, r, orderLast.get(r.getRuleId()), site, paused);
                }
                else
                {
                    metricRow(vo, r, paused);
                }
            }
            catch (RuntimeException e)
            {
                log.warn("[monitor] 规则 {} 的列表摘要生成失败：{}", r.getRuleId(), e.getMessage());
            }
        }
    }

    private void metricRow(MonitorRuleVO vo, MonitorRule r, boolean paused)
    {
        vo.setName(vo.getMetricName());
        vo.setNotifyEnabled(true);
        vo.setCanCopy(false);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("judgeType", r.getJudgeType());
        t.put("unit", vo.getUnit());
        t.put("warn", vo.getWarn());
        t.put("crit", vo.getCrit());
        String op = JUDGE_TEXT.getOrDefault(r.getJudgeType(), "");
        String unit = vo.getUnit() == null ? "" : vo.getUnit();
        List<String> parts = new ArrayList<>();
        String w = band(r.getJudgeType(), r.getWarnLow(), r.getWarnHigh(), unit);
        String c = band(r.getJudgeType(), r.getCritLow(), r.getCritHigh(), unit);
        if (w != null)
        {
            parts.add("注意：" + op + " " + w);
        }
        if (c != null)
        {
            parts.add("嚴重：" + op + " " + c);
        }
        t.put("summary", vo.getMetricName() + " " + String.join("；", parts));
        vo.setTrigger(t);
        int period = r.getPeriodHours() == null ? 1 : r.getPeriodHours();
        vo.setDetect(detect("每 " + period + " 小時", r.getDurationCount(), false, vo.getLastJudgedAt(), paused, null));
        vo.setLastTriggerAt(vo.getLastTriggerHour());
    }

    private static String band(String judgeType, BigDecimal low, BigDecimal high, String unit)
    {
        if ("band".equals(judgeType))
        {
            return low == null && high == null ? null : plain(low) + unit + " – " + plain(high) + unit;
        }
        BigDecimal v = "hi".equals(judgeType) ? high : low;
        return v == null ? null : plain(v) + ("drop".equals(judgeType) ? "%" : unit);
    }

    private void orderRow(MonitorRuleVO vo, MonitorRule r, LocalDateTime last, String site, boolean paused)
    {
        OrderRuleConfig cfg = StringUtils.isEmpty(r.getConfig()) ? new OrderRuleConfig() : json.readValue(r.getConfig(), OrderRuleConfig.class);
        String symbol = cond.dashboard().siteCurrency(site) != null && cond.dashboard().siteCurrency(site).equals(r.getCurrency())
            ? cond.symbol(site) : r.getCurrency();
        vo.setName(r.getRuleName());
        vo.setCanCopy(true);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("level", r.getLevel());
        t.put("channelScope", MonitorOrderRules.channelView(cfg.getChannelScope()));
        String what = MonitorOrderRules.DAILY_TOTAL.equals(cfg.getAmountMode()) ? "帳號當日累計提款" : "單筆提款";
        t.put("amountText", what + " ≥ " + MonitorOrderRules.money(cfg.getAmountThreshold(), symbol));
        t.put("summary", MonitorOrderRules.summary(cfg, symbol));
        vo.setTrigger(t);
        String when = MonitorOrderRules.PAID.equals(cfg.getTriggerAt()) ? "出款成功時" : "提交時";
        vo.setDetect(detect("逐筆 · " + when, null, false, null, paused, null));
        vo.setLastTriggerAt(fmt(cond.toSite(site, last)));
    }

    private void condRow(MonitorRuleVO vo, MonitorRule r, List<MonitorCondRow> rows, MonitorCondState st, CondDims.Snapshot labels,
            String site, boolean paused)
    {
        CondSpec spec = cond.spec(r, rows);
        Map<String, CondField> fields = cond.catalog().fields();
        String symbol = cond.symbol(site);
        vo.setName(r.getRuleName());
        vo.setCanCopy(true);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("level", spec.getLevel());
        t.put("matchMode", spec.getMatchMode());
        boolean anyScope = spec.getConditions().stream().anyMatch(c -> !c.getScope().isEmpty());
        List<Map<String, Object>> conds = new ArrayList<>();
        for (CondCondition c : spec.getConditions())
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", c.getIndex());
            List<Map<String, Object>> scope = CondRules.scopeItems(c.getScope(), labels);
            if (scope.isEmpty() && anyScope)
            {
                scope.add(Map.of("dim", "", "value", "", "label", "不限範圍"));
            }
            m.put("scope", scope);
            m.put("text", CondRules.conditionText(c, fields.get(c.getField()), symbol));
            conds.add(m);
        }
        t.put("conditions", conds);
        t.put("summary", CondRules.summary(spec, fields, labels, symbol));
        vo.setTrigger(t);
        Map<String, Object> noBase = null;
        if (st != null && StringUtils.isNotEmpty(st.getDetail()))
        {
            try
            {
                Map<?, ?> d = json.readValue(st.getDetail(), Map.class);
                if (d.get("noBase") instanceof List<?> l && !l.isEmpty())
                {
                    noBase = new LinkedHashMap<>();
                    noBase.put("conditions", l);
                    String from = d.get("from") == null ? null : String.valueOf(d.get("from"));
                    noBase.put("from", from == null || from.length() < 10 ? from : from.substring(5, 10));
                }
            }
            catch (RuntimeException e)
            {
                noBase = null;
            }
        }
        Map<String, Object> det = detect(spec.getWindow() == CondWindow.TODAY ? "今日累計" : spec.getWindow().getText(),
            spec.getDurationCount(), spec.getConditions().stream().allMatch(c -> {
                CondField f = fields.get(c.getField());
                return f != null && f.isSnapshot();
            }) && !spec.getConditions().isEmpty(), st == null ? null : fmt(cond.toSite(site, st.getLastPeriodEnd())), paused, noBase);
        vo.setDetect(det);
        vo.setLastTriggerAt(vo.getLastTriggerHour());
    }

    private static Map<String, Object> detect(String text, Integer duration, boolean snapshot, String lastJudgedAt, boolean paused,
            Map<String, Object> noBase)
    {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("text", text);
        d.put("durationText", duration == null || duration <= 1 ? null : "連續 " + duration + " 次");
        d.put("snapshot", snapshot);
        d.put("lastJudgedAt", lastJudgedAt);
        d.put("paused", paused);
        d.put("noBase", noBase);
        return d;
    }

    private static String plain(BigDecimal v)
    {
        if (v == null)
        {
            return "—";
        }
        BigDecimal s = v.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }

    private static String fmt(LocalDateTime t)
    {
        return t == null ? null : t.format(MINUTE);
    }
}
