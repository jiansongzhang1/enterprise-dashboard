package com.fivetech.monitor.cond;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.gateway.uds.UdsClient;
import com.fivetech.dashboard.gateway.uds.UdsQueryResult;
import com.fivetech.dashboard.gateway.uds.UdsRow;

/**
 * 向 UDS 取条件规则的小时数据：数据集、时间维度、维度字段都来自 {@code monitor.cond}；一批最多 batch-size 项（/v1/query/batch），
 * 某项满 1000 行时单独翻页。站点由请求上下文（X-Uds-Site）决定。
 *
 * @author fivetech
 */
@Component
public class UdsCondDataSource implements CondDataSource
{
    static final int PAGE = 1000;

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UdsClient client;

    private final CondProperties props;

    public UdsCondDataSource(UdsClient client, CondProperties props)
    {
        this.client = client;
        this.props = props;
    }

    @Override
    public boolean available()
    {
        return StringUtils.isNotEmpty(props.getHourDataset());
    }

    @Override
    public Map<String, CondSeries> fetch(List<Request> requests, LocalDateTime from, LocalDateTime to)
    {
        if (!available())
        {
            throw new CondDataException("條件規則的小時數據尚未接入", null);
        }
        Map<String, CondSeries> out = new LinkedHashMap<>();
        List<Request> list = new ArrayList<>(requests);
        int size = Math.max(1, Math.min(50, props.getBatchSize()));
        try
        {
            for (int i = 0; i < list.size(); i += size)
            {
                List<Request> batch = list.subList(i, Math.min(list.size(), i + size));
                List<Map<String, Object>> bodies = new ArrayList<>();
                for (Request r : batch)
                {
                    bodies.add(body(r, from, to, 0));
                }
                List<UdsQueryResult> results = client.queryBatch(bodies);
                for (int k = 0; k < batch.size(); k++)
                {
                    Request r = batch.get(k);
                    CondSeries s = new CondSeries(from);
                    List<UdsRow> rows = k < results.size() ? results.get(k).getRows() : List.of();
                    add(s, rows, r);
                    int offset = PAGE;
                    while (rows.size() >= PAGE)
                    {
                        rows = client.query(body(r, from, to, offset));
                        add(s, rows, r);
                        offset += PAGE;
                    }
                    out.put(r.key(), s);
                }
            }
        }
        catch (CondDataException e)
        {
            throw e;
        }
        catch (RuntimeException e)
        {
            throw new CondDataException("UDS 取數失敗：" + e.getMessage(), e);
        }
        return out;
    }

    Map<String, Object> body(Request r, LocalDateTime from, LocalDateTime to, int offset)
    {
        Map<String, Object> time = new LinkedHashMap<>();
        time.put("dimension", props.getTimeField());
        time.put("granularity", "HOUR");
        time.put("from", from.format(DATETIME));
        time.put("to", to.minusSeconds(1).format(DATETIME));
        Set<String> metrics = new LinkedHashSet<>(r.columns());
        if (metrics.isEmpty())
        {
            metrics.add(props.getCountMetric());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataset", props.getHourDataset());
        body.put("dimensions", List.of(props.getTimeField()));
        body.put("metrics", new ArrayList<>(metrics));
        body.put("time", time);
        List<Map<String, Object>> and = filters(r.scope());
        if (!and.isEmpty())
        {
            body.put("filters", Map.of("and", and));
        }
        body.put("orderBy", List.of(Map.of("field", props.getTimeField(), "dir", "ASC")));
        body.put("limit", PAGE);
        body.put("offset", offset);
        body.put("options", Map.of("channel", "ONLINE"));
        return body;
    }

    /** 范围 → UDS 条件；渠道 g:推广商 / c:推广商|渠道 拆成两个字段 */
    List<Map<String, Object>> filters(Map<String, String> scope)
    {
        List<Map<String, Object>> and = new ArrayList<>();
        if (scope == null)
        {
            return and;
        }
        for (Map.Entry<String, String> e : scope.entrySet())
        {
            String v = e.getValue();
            if ("ch".equals(e.getKey()))
            {
                String body = v.substring(2);
                int bar = body.indexOf('|');
                eq(and, "chPromoter", bar < 0 ? body : body.substring(0, bar));
                if (v.startsWith("c:") && bar >= 0)
                {
                    eq(and, "chChannel", body.substring(bar + 1));
                }
            }
            else
            {
                eq(and, e.getKey(), v);
            }
        }
        return and;
    }

    private void eq(List<Map<String, Object>> and, String dim, String value)
    {
        String field = props.dimField(dim);
        if (field == null)
        {
            throw new CondDataException("維度 " + dim + " 未配置 UDS 字段（monitor.cond.dim-fields）", null);
        }
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("field", field);
        c.put("op", "EQ");
        c.put("value", value);
        and.add(c);
    }

    private void add(CondSeries s, List<UdsRow> rows, Request r)
    {
        for (UdsRow row : rows)
        {
            LocalDateTime h = hour(row.str(props.getTimeField()));
            if (h == null)
            {
                continue;
            }
            Map<String, BigDecimal> vals = new LinkedHashMap<>();
            for (String c : r.columns())
            {
                BigDecimal v = row.num(c);
                if (v != null)
                {
                    vals.put(c, v);
                }
            }
            s.put(h, vals);
        }
    }

    static LocalDateTime hour(String s)
    {
        if (s == null || s.length() < 10)
        {
            return null;
        }
        try
        {
            String t = s.trim().replace('T', ' ');
            if (t.length() >= 13)
            {
                return LocalDateTime.parse(t.substring(0, 13) + ":00:00", DATETIME);
            }
            return LocalDate.parse(t.substring(0, 10)).atStartOfDay();
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }
}
