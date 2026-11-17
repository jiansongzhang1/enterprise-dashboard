package com.fivetech.monitor.order;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.gateway.uds.UdsClient;
import com.fivetech.dashboard.gateway.uds.UdsRow;

/**
 * 向 UDS 按订单号、用户 ID 批量查当前状态与用户信息（每次最多 batch-size 个）。已出款、已拒绝是终态，缓存 10 分钟；審核中缓存 30 秒。
 *
 * @author fivetech
 */
@Component
public class UdsOrderLookup implements OrderLookup
{
    private static final java.time.format.DateTimeFormatter DATETIME = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UdsClient client;

    private final OrderLookupProperties props;

    private final Map<String, Cached<OrderInfo>> orderCache = new ConcurrentHashMap<>();

    private final Map<String, Cached<UserInfo>> userCache = new ConcurrentHashMap<>();

    private record Cached<T>(long until, T value)
    {
    }

    public UdsOrderLookup(UdsClient client, OrderLookupProperties props)
    {
        this.client = client;
        this.props = props;
    }

    @Override
    public boolean available()
    {
        return StringUtils.isNotEmpty(props.getOrderDataset());
    }

    @Override
    public Map<String, OrderInfo> orders(String siteCode, Collection<String> orderNos, LocalDateTime since)
    {
        Map<String, OrderInfo> out = new LinkedHashMap<>();
        if (!available() || orderNos == null)
        {
            return out;
        }
        long now = System.currentTimeMillis();
        List<String> miss = new ArrayList<>();
        for (String no : new LinkedHashSet<>(orderNos))
        {
            Cached<OrderInfo> c = orderCache.get(siteCode + "|" + no);
            if (c != null && c.until() > now)
            {
                out.put(no, c.value());
            }
            else
            {
                miss.add(no);
            }
        }
        LocalDateTime from = (since == null ? LocalDateTime.now().minusDays(90) : since).minusDays(1).withMinute(0).withSecond(0).withNano(0);
        Map<String, Object> time = new LinkedHashMap<>();
        time.put("dimension", props.getOrderTimeField());
        time.put("granularity", "HOUR");
        time.put("from", from.format(DATETIME));
        time.put("to", "now");
        for (List<String> batch : batches(miss))
        {
            for (UdsRow r : query(props.getOrderDataset(), siteCode, time,
                List.of(props.getOrderNoField(), props.getOrderStatusField(), props.getOrderUserField()), props.getOrderNoField(), batch))
            {
                String no = r.str(props.getOrderNoField());
                if (no == null)
                {
                    continue;
                }
                OrderInfo info = new OrderInfo(status(r.str(props.getOrderStatusField())), r.str(props.getOrderUserField()));
                out.put(no, info);
                int ttl = "PENDING".equals(info.status()) ? props.getPendingCacheSeconds() : props.getFinalCacheSeconds();
                orderCache.put(siteCode + "|" + no, new Cached<>(now + ttl * 1000L, info));
            }
        }
        trim();
        return out;
    }

    @Override
    public Map<String, UserInfo> users(String siteCode, Collection<String> userIds)
    {
        Map<String, UserInfo> out = new LinkedHashMap<>();
        if (StringUtils.isEmpty(props.getUserDataset()) || userIds == null)
        {
            return out;
        }
        long now = System.currentTimeMillis();
        List<String> miss = new ArrayList<>();
        for (String id : new LinkedHashSet<>(userIds))
        {
            Cached<UserInfo> c = userCache.get(siteCode + "|" + id);
            if (c != null && c.until() > now)
            {
                out.put(id, c.value());
            }
            else
            {
                miss.add(id);
            }
        }
        Map<String, Object> time = new LinkedHashMap<>();
        time.put("dimension", "snapshot_at");
        time.put("granularity", "HOUR");
        time.put("from", "now");
        time.put("to", "now");
        for (List<String> batch : batches(miss))
        {
            for (UdsRow r : query(props.getUserDataset(), siteCode, time, List.of(props.getUserIdField(), props.getAccountField(), props.getRegTimeField()),
                props.getUserIdField(), batch))
            {
                String id = r.str(props.getUserIdField());
                if (id == null)
                {
                    continue;
                }
                UserInfo info = new UserInfo(r.str(props.getAccountField()), time(r.str(props.getRegTimeField())));
                out.put(id, info);
                userCache.put(siteCode + "|" + id, new Cached<>(now + props.getFinalCacheSeconds() * 1000L, info));
            }
        }
        return out;
    }

    /** UDS 的状态值换成 PENDING / PAID / REJECTED */
    String status(String raw)
    {
        if (raw == null)
        {
            return null;
        }
        String mapped = props.getStatusMap().get(raw.trim());
        return (mapped != null ? mapped : raw).trim().toUpperCase(Locale.ROOT);
    }

    private List<List<String>> batches(List<String> all)
    {
        List<List<String>> out = new ArrayList<>();
        int size = Math.max(1, props.getBatchSize());
        for (int i = 0; i < all.size(); i += size)
        {
            out.add(all.subList(i, Math.min(all.size(), i + size)));
        }
        return out;
    }

    private List<UdsRow> query(String dataset, String siteCode, Map<String, Object> time, List<String> dims, String keyField,
        List<String> keys)
    {
        List<Map<String, Object>> and = new ArrayList<>();
        and.add(cond(keyField, "IN", keys));
        if (StringUtils.isNotEmpty(props.getSiteField()) && StringUtils.isNotEmpty(siteCode))
        {
            and.add(cond(props.getSiteField(), "EQ", siteCode));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataset", dataset);
        body.put("dimensions", dims);
        body.put("metrics", List.of(props.getCountMetric()));
        body.put("time", time);
        body.put("filters", Map.of("and", and));
        body.put("limit", Math.max(keys.size(), 1));
        body.put("offset", 0);
        body.put("options", Map.of("channel", "ONLINE"));
        return client.query(body);
    }

    private static Map<String, Object> cond(String field, String op, Object value)
    {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("field", field);
        c.put("op", op);
        c.put("value", value);
        return c;
    }

    private static LocalDateTime time(String s)
    {
        if (s == null || s.length() < 10)
        {
            return null;
        }
        try
        {
            String t = s.trim().replace('T', ' ');
            return t.length() >= 19 ? LocalDateTime.parse(t.substring(0, 19).replace(' ', 'T'))
                : java.time.LocalDate.parse(t.substring(0, 10)).atStartOfDay();
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private void trim()
    {
        if (orderCache.size() > 50_000)
        {
            orderCache.clear();
        }
        if (userCache.size() > 50_000)
        {
            userCache.clear();
        }
    }

    /** 测试用 */
    Set<String> cachedOrders()
    {
        return orderCache.keySet();
    }
}
