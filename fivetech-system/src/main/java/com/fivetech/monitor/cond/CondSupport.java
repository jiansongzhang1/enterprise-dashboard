package com.fivetech.monitor.cond;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.monitor.domain.MonitorCondRow;
import com.fivetech.monitor.domain.MonitorRule;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 条件规则共用的转换：库里的规则 ↔ {@link CondSpec}、按范围合并取数、站点时区与统计时区互换。
 *
 * <p>判定、预览、事件详情都在站点时区里算（今日累计按站点的 00:00，UDS 也按站点时区统计）；写进 monitor_event /
 * monitor_rule_state 的时间换成统计时区（dashboard.timezone），与指标规则的事件一致。</p>
 *
 * @author fivetech
 */
@Component
public class CondSupport
{
    static final ObjectMapper JSON = JsonMapper.builder().build();

    private final CondCatalog catalog;

    private final CondDataSource data;

    private final CondProperties props;

    private final DashboardProperties dashboard;

    public CondSupport(CondCatalog catalog, CondDataSource data, CondProperties props, DashboardProperties dashboard)
    {
        this.catalog = catalog;
        this.data = data;
        this.props = props;
        this.dashboard = dashboard;
    }

    public CondCatalog catalog() { return catalog; }
    public CondDataSource data() { return data; }
    public CondProperties props() { return props; }
    public DashboardProperties dashboard() { return dashboard; }

    // ------------------------------------------------------------------ 规则

    /** 库里的规则 + 条件行 → CondSpec */
    @SuppressWarnings("unchecked")
    public CondSpec spec(MonitorRule rule, List<MonitorCondRow> rows)
    {
        CondSpec s = new CondSpec();
        Map<String, Object> cfg = Map.of();
        if (StringUtils.isNotEmpty(rule.getConfig()))
        {
            try
            {
                cfg = JSON.readValue(rule.getConfig(), Map.class);
            }
            catch (RuntimeException e)
            {
                cfg = Map.of();
            }
        }
        s.setMatchMode("ANY".equals(cfg.get("matchMode")) ? "ANY" : "ALL");
        CondWindow w = CondWindow.of(cfg.get("window") == null ? null : String.valueOf(cfg.get("window")));
        s.setWindow(w == null ? CondWindow.H1 : w);
        Object d = cfg.get("durationCount");
        s.setDurationCount(d instanceof Number n ? Math.max(1, n.intValue()) : 1);
        s.setLevel(rule.getLevel());
        List<CondCondition> list = new ArrayList<>();
        for (MonitorCondRow r : rows)
        {
            if (!rule.getRuleId().equals(r.getRuleId()))
            {
                continue;
            }
            CondCondition c = new CondCondition();
            c.setIndex(r.getSeq());
            c.setField(r.getFieldCode());
            Map<String, String> scope = new LinkedHashMap<>();
            if (StringUtils.isNotEmpty(r.getScope()))
            {
                try
                {
                    ((Map<String, Object>) JSON.readValue(r.getScope(), Map.class)).forEach((k, v) -> {
                        if (v != null)
                        {
                            scope.put(k, String.valueOf(v));
                        }
                    });
                }
                catch (RuntimeException e)
                {
                    scope.clear();
                }
            }
            c.setScope(scope);
            c.setHow(r.getHow());
            c.setOp(r.getOp());
            c.setUnit(r.getUnit());
            c.setLow(plain(r.getThresholdLow()));
            c.setHigh(plain(r.getThresholdHigh()));
            c.setMinSample(plain(r.getMinSample()));
            list.add(c);
        }
        list.sort((a, b) -> Integer.compare(a.getIndex(), b.getIndex()));
        s.setConditions(list);
        return s;
    }

    /** CondSpec → config（jsonb）：条件关系、窗口、连续次数 */
    public String config(CondSpec s)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("matchMode", s.getMatchMode());
        m.put("window", s.getWindow().getCode());
        m.put("durationCount", s.getDurationCount());
        return JSON.writeValueAsString(m);
    }

    /** CondSpec → monitor_rule_condition 行 */
    public List<MonitorCondRow> rows(Long ruleId, CondSpec s)
    {
        List<MonitorCondRow> out = new ArrayList<>();
        for (CondCondition c : s.getConditions())
        {
            MonitorCondRow r = new MonitorCondRow();
            r.setRuleId(ruleId);
            r.setSeq(c.getIndex());
            r.setFieldCode(c.getField());
            r.setScope(JSON.writeValueAsString(c.getScope()));
            r.setHow(c.getHow());
            r.setOp(c.getOp());
            r.setUnit(c.getUnit());
            r.setThresholdLow(c.getLow());
            r.setThresholdHigh(c.getHigh());
            r.setMinSample(c.getMinSample());
            out.add(r);
        }
        return out;
    }

    /** 条件 → 接口里的样子（详情、快照） */
    public static Map<String, Object> conditionView(CondCondition c, String scopeLabel)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", c.getIndex());
        m.put("field", c.getField());
        m.put("scope", c.getScope());
        m.put("scopeLabel", scopeLabel);
        m.put("how", c.getHow());
        m.put("op", c.getOp());
        m.put("unit", c.getUnit());
        m.put("threshold", c.threshold());
        m.put("minSample", c.getMinSample());
        return m;
    }

    // ------------------------------------------------------------------ 取数

    /** 范围的键（同一范围的条件合并成一次取数） */
    public static String scopeKey(Map<String, String> scope)
    {
        return scope == null || scope.isEmpty() ? "*" : new TreeMap<>(scope).toString();
    }

    /** 收集要取的范围与列：scopeKey → (scope, columns) */
    public void collect(CondSpec spec, Map<String, CondDataSource.Request> into)
    {
        Map<String, CondField> fields = catalog.fields();
        for (CondCondition c : spec.getConditions())
        {
            CondField f = fields.get(c.getField());
            if (f == null)
            {
                continue;
            }
            String key = scopeKey(c.getScope());
            CondDataSource.Request old = into.get(key);
            Set<String> cols = new LinkedHashSet<>(old == null ? List.of() : old.columns());
            cols.addAll(f.udsColumns());
            into.put(key, new CondDataSource.Request(key, c.getScope(), new ArrayList<>(cols)));
        }
    }

    /** 条件序号 → 序列 */
    public static Map<Integer, CondSeries> byIndex(CondSpec spec, Map<String, CondSeries> byScope)
    {
        Map<Integer, CondSeries> out = new LinkedHashMap<>();
        for (CondCondition c : spec.getConditions())
        {
            CondSeries s = byScope.get(scopeKey(c.getScope()));
            if (s != null)
            {
                out.put(c.getIndex(), s);
            }
        }
        return out;
    }

    /** 一条规则在 [from, to) 的数据，按条件序号 */
    public Map<Integer, CondSeries> fetch(CondSpec spec, LocalDateTime from, LocalDateTime to)
    {
        Map<String, CondDataSource.Request> req = new LinkedHashMap<>();
        collect(spec, req);
        return byIndex(spec, data.fetch(new ArrayList<>(req.values()), from, to));
    }

    // ------------------------------------------------------------------ 时间

    public ZoneId statZone()
    {
        String tz = dashboard.getTimezone();
        return ZoneId.of(StringUtils.isEmpty(tz) ? "Asia/Kolkata" : tz.trim());
    }

    public LocalDateTime siteNow(String site)
    {
        return LocalDateTime.now(dashboard.siteZone(site));
    }

    public LocalDateTime toStat(String site, LocalDateTime siteTime)
    {
        return siteTime == null ? null
            : siteTime.atZone(dashboard.siteZone(site)).withZoneSameInstant(statZone()).toLocalDateTime();
    }

    public LocalDateTime toSite(String site, LocalDateTime statTime)
    {
        return dashboard.toSiteTime(site, statTime);
    }

    public String symbol(String site)
    {
        return dashboard.siteCurrencySymbol(site);
    }

    /** 去掉 NUMERIC(20,4) 的尾零（50.0000 → 50） */
    static BigDecimal plain(BigDecimal v)
    {
        if (v == null)
        {
            return null;
        }
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    static BigDecimal round(BigDecimal v)
    {
        if (v == null)
        {
            return null;
        }
        BigDecimal s = v.setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }
}
