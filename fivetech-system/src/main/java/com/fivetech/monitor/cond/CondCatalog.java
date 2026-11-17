package com.fivetech.monitor.cond;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fivetech.dashboard.domain.MetricDictItem;
import com.fivetech.dashboard.mapper.MetricDictMapper;
import com.fivetech.dashboard.service.MetricDictService;
import com.fivetech.dashboard.service.MetricExt;

/**
 * 条件规则的字段目录：读指标字典里 page_scope = MONITOR_COND 且已接入（status = '0'）的行，缓存
 * {@code monitor.cond.catalog-cache-seconds} 秒。新指标在「指标管理」里加一行即可出现在条件规则的字段下拉，不改代码。
 *
 * @author fivetech
 */
@Component
public class CondCatalog
{
    private static final Logger log = LoggerFactory.getLogger(CondCatalog.class);

    private final MetricDictMapper mapper;

    private final CondProperties properties;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private Supplier<List<MetricDictItem>> loader;

    private record Cached(long at, Map<String, CondField> fields)
    {
    }

    public CondCatalog(MetricDictMapper mapper, CondProperties properties)
    {
        this.mapper = mapper;
        this.properties = properties;
    }

    /** 测试用：不读库 */
    public static CondCatalog of(CondProperties properties, Supplier<List<MetricDictItem>> loader)
    {
        CondCatalog c = new CondCatalog(null, properties);
        c.loader = loader;
        return c;
    }

    /** 接口编码 → 字段，按排序号 */
    public Map<String, CondField> fields()
    {
        long now = System.currentTimeMillis();
        Cached c = cache.get("all");
        if (c != null && now - c.at() < Math.max(0, properties.getCatalogCacheSeconds()) * 1000L)
        {
            return c.fields();
        }
        List<MetricDictItem> rows;
        try
        {
            rows = loader != null ? loader.get() : mapper.selectByPageScope(MetricExt.SCOPE_MONITOR_COND);
        }
        catch (RuntimeException e)
        {
            log.error("[monitor] 读取条件规则字段目录失败：{}", e.getMessage());
            return c != null ? c.fields() : Map.of();
        }
        Map<String, CondField> out = new LinkedHashMap<>();
        for (MetricDictItem it : rows == null ? List.<MetricDictItem>of() : rows)
        {
            if (!MetricDictService.STATUS_LIVE.equals(it.getStatus()))
            {
                continue;
            }
            Map<String, Object> ext = it.getExt() != null ? it.getExt() : MetricDictService.parseExt(it.getExtJson());
            Map<String, String> errors = new LinkedHashMap<>();
            Map<String, Object> norm = MetricExt.validate(MetricExt.SCOPE_MONITOR_COND, ext, errors);
            if (norm == null || !errors.isEmpty())
            {
                log.warn("[monitor] 条件字段 {} 的页面属性无效，已跳过：{}", it.getMetricCode(), errors.isEmpty() ? "ext 为空" : errors);
                continue;
            }
            CondField f = new CondField(it.getMetricCode(), it.getMetricName(), it.getValueFormat(), it.getValueDecimals(),
                it.getUdsHourField(), it.getRemark(), it.getSortNo() == null ? 0 : it.getSortNo(), it.getGroupCode(), norm);
            if (!f.isRatio() && f.getUdsField() == null)
            {
                log.warn("[monitor] 条件字段 {} 没有 uds_hour_field 也不是比率，已跳过", it.getMetricCode());
                continue;
            }
            if (out.putIfAbsent(f.getCode(), f) != null)
            {
                log.warn("[monitor] 条件字段 {} 的接口编码 {} 重复，已跳过", it.getMetricCode(), f.getCode());
            }
        }
        Map<String, CondField> frozen = Collections.unmodifiableMap(out);
        cache.put("all", new Cached(now, frozen));
        return frozen;
    }

    public CondField field(String code)
    {
        return code == null ? null : fields().get(code);
    }

    public void invalidate()
    {
        cache.clear();
    }
}
