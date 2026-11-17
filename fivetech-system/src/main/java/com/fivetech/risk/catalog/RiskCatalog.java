package com.fivetech.risk.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fivetech.dashboard.domain.MetricDictItem;
import com.fivetech.dashboard.mapper.MetricDictMapper;
import com.fivetech.dashboard.service.MetricDictService;
import com.fivetech.dashboard.service.MetricExt;
import com.fivetech.risk.RiskProperties;

/**
 * 风控排行榜与游戏分析的列目录：读指标字典里 page_scope = RISK_RANK / RISK_GAME 且已接入（status = '0'）的行，
 * 缓存 {@code risk.catalog-cache-seconds} 秒。列的名称、格式、UDS 字段、分组、能否排行 / 做条件都在「指标管理」里改，
 * 不在代码里；代码只认 ext 里的通用属性（聚合方式、计算公式等）。
 *
 * @author fivetech
 */
@Component
public class RiskCatalog
{
    private static final Logger log = LoggerFactory.getLogger(RiskCatalog.class);

    private final MetricDictMapper mapper;

    private final RiskProperties properties;

    private final Map<String, Cached> cache = new java.util.concurrent.ConcurrentHashMap<>();

    /** 测试注入：不读库，直接给定各范围的行 */
    private java.util.function.Function<String, List<MetricDictItem>> loader;

    private record Cached(long at, Map<String, RiskColumn> columns)
    {
    }

    public RiskCatalog(MetricDictMapper mapper, RiskProperties properties)
    {
        this.mapper = mapper;
        this.properties = properties;
    }

    /** 测试用 */
    public static RiskCatalog of(RiskProperties properties, java.util.function.Function<String, List<MetricDictItem>> loader)
    {
        RiskCatalog c = new RiskCatalog(null, properties);
        c.loader = loader;
        return c;
    }

    /** 排行榜的全部列（接口编码 → 列），按排序号 */
    public Map<String, RiskColumn> rank()
    {
        return load(MetricExt.SCOPE_RISK_RANK);
    }

    /** 游戏分析的全部列 */
    public Map<String, RiskColumn> game()
    {
        return load(MetricExt.SCOPE_RISK_GAME);
    }

    public RiskColumn rank(String code)
    {
        return code == null ? null : rank().get(code);
    }

    /** 指标管理改了之后立即生效（同实例） */
    public void invalidate()
    {
        cache.clear();
    }

    private Map<String, RiskColumn> load(String scope)
    {
        long now = System.currentTimeMillis();
        Cached c = cache.get(scope);
        long ttl = Math.max(0, properties.getCatalogCacheSeconds()) * 1000L;
        if (c != null && now - c.at() < ttl)
        {
            return c.columns();
        }
        List<MetricDictItem> rows;
        try
        {
            rows = loader != null ? loader.apply(scope) : mapper.selectByPageScope(scope);
        }
        catch (RuntimeException e)
        {
            log.error("[risk] 读取指标字典 {} 失败：{}", scope, e.getMessage());
            return c != null ? c.columns() : Map.of();
        }
        Map<String, RiskColumn> out = new LinkedHashMap<>();
        for (MetricDictItem it : rows == null ? List.<MetricDictItem>of() : rows)
        {
            if (!MetricDictService.STATUS_LIVE.equals(it.getStatus()))
            {
                continue;
            }
            Map<String, Object> ext = it.getExt() != null ? it.getExt() : MetricDictService.parseExt(it.getExtJson());
            Map<String, String> errors = new LinkedHashMap<>();
            Map<String, Object> norm = MetricExt.validate(scope, ext, errors);
            if (norm == null || !errors.isEmpty())
            {
                log.warn("[risk] 指标 {} 的页面属性无效，已跳过：{}", it.getMetricCode(), errors.isEmpty() ? "ext 为空" : errors);
                continue;
            }
            RiskColumn col = new RiskColumn(it.getMetricCode(), it.getMetricName(), it.getValueFormat(), it.getValueDecimals(),
                it.getDirection(), it.getUdsDayField(), it.getSortNo() == null ? 0 : it.getSortNo(), it.getRemark(), norm);
            if (out.containsKey(col.getCode()))
            {
                log.warn("[risk] 指标 {} 的接口编码 {} 与 {} 重复，已跳过", it.getMetricCode(), col.getCode(),
                    out.get(col.getCode()).getMetricCode());
                continue;
            }
            out.put(col.getCode(), col);
        }
        Map<String, RiskColumn> frozen = Collections.unmodifiableMap(out);
        cache.put(scope, new Cached(now, frozen));
        return frozen;
    }

    /** 名单条件可用的字段（列目录里 condition 不为空的） */
    public List<RiskColumn> conditionFields()
    {
        List<RiskColumn> out = new ArrayList<>();
        for (RiskColumn c : rank().values())
        {
            if (c.getCondition() != null)
            {
                out.add(c);
            }
        }
        return out;
    }
}
