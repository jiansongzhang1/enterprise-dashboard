package com.fivetech.monitor.engine;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fivetech.dashboard.gateway.HourlyFetch;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.engine.domain.MonitorEngineRule;
import com.fivetech.monitor.mapper.MonitorEngineMapper;

/**
 * 一个站点一轮的处理（每 10 分钟一次）：
 * <ol>
 *   <li>本轮处理的数据小时 H = 当前整点 − 1 小时；更早仍在等待的小时（服务停过）直接标 missing，不补判；</li>
 *   <li>找出本站还没处理 H 的启用规则；没有就收尾；</li>
 *   <li>一次 UDS 请求取回全部字段（区间覆盖本小时结束的最长检测周期，最多 24 小时）；</li>
 *   <li>水位 ≥ H + 1 小时视为已落库：值不为空的指标立即判定，值为空的等下一轮；
 *       :50 最后一次检查时仍为空的指标本周期不判定（不告警、不记录、不通知）；</li>
 *   <li>写库在 {@link MonitorEventWriter#applyHour} 的事务里完成。</li>
 * </ol>
 * 取数在事务外进行，避免 UDS 请求期间占着数据库连接。调用方负责站点上下文（SiteContext）。
 *
 * @author fivetech
 */
@Component
public class MonitorHourJob
{
    private static final Logger log = LoggerFactory.getLogger(MonitorHourJob.class);

    private final MonitorEngineMapper mapper;

    private final MonitorDataFetcher fetcher;

    private final MonitorEventWriter writer;

    private final MonitorProperties properties;

    public MonitorHourJob(MonitorEngineMapper mapper, MonitorDataFetcher fetcher, MonitorEventWriter writer,
            MonitorProperties properties)
    {
        this.mapper = mapper;
        this.fetcher = fetcher;
        this.writer = writer;
        this.properties = properties;
    }

    /** @return 本轮处理的规则数 */
    public int run(String siteCode, LocalDateTime now)
    {
        MonitorScope scope = new MonitorScope(siteCode, null);
        LocalDateTime hour = now.truncatedTo(ChronoUnit.HOURS).minusHours(1);
        boolean finalCheck = now.getMinute() >= properties.getEngine().getLastCheckMinute();

        mapper.markStaleHoursMissing(scope, hour, now);
        mapper.insertHourCheck(scope, hour);
        if (!"waiting".equals(mapper.selectHourStatus(scope, hour)))
        {
            return 0;
        }

        List<MonitorEngineRule> pending = pendingRules(scope, hour);
        if (pending.isEmpty())
        {
            writer.finishHour(scope, hour, now);
            return 0;
        }

        LocalDateTime from = hour;
        for (MonitorEngineRule rule : pending)
        {
            int period = rule.getPeriodHours() == null ? 1 : Math.max(1, rule.getPeriodHours());
            if ((hour.getHour() + 1) % period == 0 && hour.minusHours(period - 1L).isBefore(from))
            {
                from = hour.minusHours(period - 1L);
            }
        }
        LocalDateTime to = hour.plusHours(1);

        HourlyFetch data = null;
        try
        {
            data = fetcher.fetch(pending, from, to);
        }
        catch (RuntimeException e)
        {
            // 两次都失败：本轮不处理；到 :50 仍失败则按数据缺失处理
            log.error("[monitor] 站点 {} 取 {} 的数据失败：{}", siteCode, hour, e.getMessage());
            if (!finalCheck)
            {
                return 0;
            }
        }
        boolean landed = data != null && !data.isNotReady() && data.getWatermark() != null
                && !data.getWatermark().isBefore(to);
        if (data != null && !data.isNotReady() && data.getWatermark() == null)
        {
            log.warn("[monitor] 站点 {} 的 UDS 响应没有带水位（meta.freshness.watermark），{} 按未落库处理", siteCode, hour);
        }
        int processed = writer.applyHour(scope, hour, now, pending, data, landed, finalCheck);
        log.info("[monitor] 站点 {} 数据小时 {}：落库={} 待处理 {} 条，本轮处理 {} 条{}", siteCode, hour, landed,
                pending.size(), processed, finalCheck ? "（最后一次检查）" : "");
        return processed;
    }

    /** 本小时还没处理、且启用时间早于本小时结束的规则；超过站点上限的部分不处理并告警 */
    private List<MonitorEngineRule> pendingRules(MonitorScope scope, LocalDateTime hour)
    {
        List<MonitorEngineRule> rules = mapper.selectEnabledRules(scope);
        int limit = properties.getMaxEnabledRulesPerSite();
        if (limit > 0 && rules.size() > limit)
        {
            log.error("[monitor] 站点 {} 启用规则 {} 条，超过上限 {}，只处理规则号最小的 {} 条",
                    scope.getSiteCode(), rules.size(), limit, limit);
            rules = rules.subList(0, limit);
        }
        List<MonitorEngineRule> pending = new ArrayList<>();
        for (MonitorEngineRule rule : rules)
        {
            // last_data_hour 在新建 / 启用时置为当时的上一小时：启用前已结束的小时不判定
            if (rule.getLastDataHour() == null || rule.getLastDataHour().isBefore(hour))
            {
                pending.add(rule);
            }
        }
        return pending;
    }
}
