package com.fivetech.dashboard.gateway.uds;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.domain.vo.BetRecordVO;
import com.fivetech.dashboard.domain.vo.MemberRecordVO;
import com.fivetech.dashboard.domain.vo.TransactionRecordVO;
import com.fivetech.dashboard.enums.Granularity;
import com.fivetech.dashboard.gateway.DataFreshness;
import com.fivetech.dashboard.gateway.MetricDataGateway;
import com.fivetech.dashboard.gateway.MetricSlotRequest;
import com.fivetech.dashboard.gateway.RecordPage;
import com.fivetech.dashboard.gateway.RecordPageRequest;

/**
 * 基于 UDS（统一数据服务）HTTP 接口的数据网关实现。
 * <p>
 * 取数分两步，与数据平台约定一致：
 * <ol>
 *   <li>先调元数据接口确认所需指标齐备（结果有缓存）；</li>
 *   <li>再调查询接口取数。</li>
 * </ol>
 * 指标编码到 UDS 数据集与指标名的映射全部走配置（{@code dashboard.gateway.uds.datasets}），
 * 代码里不出现任何业务表名 —— 口径调整时改配置即可，不用发版。
 * <p>
 * 一次请求要多个指标时，<b>按数据集分组合并</b>，而不是逐指标发一次：
 * 指标墙一次 21 个指标 × 当期+对比，逐个发就是 42 次调用，会直接打满对方配额。
 *
 * @author fivetech
 */
@Component
public class UdsMetricDataGateway implements MetricDataGateway
{
    private static final Logger log = LoggerFactory.getLogger(UdsMetricDataGateway.class);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 区间合计的行数上限。空维度理应只回一行，这里故意不设成 1：
     * 设成 1 的话，上游万一忽略了 dimensions，我们会拿到「第一个小时」还以为是合计。
     * 留出余量才能发现这种情况并拒绝采纳。
     */
    private static final int TOTAL_LIMIT = 100;

    private final UdsProperties properties;

    private final UdsClient client;

    public UdsMetricDataGateway(UdsProperties properties, UdsClient client)
    {
        this.properties = properties;
        this.client = client;
    }

    // ===================== 新鲜度 =====================

    /** 最近一次查询带回的元信息。UDS 没有独立的水位线接口，只能从查询响应里捎带 */
    private final java.util.concurrent.atomic.AtomicReference<UdsQueryResult> lastMeta =
        new java.util.concurrent.atomic.AtomicReference<>();

    private void remember(UdsQueryResult result)
    {
        if (result != null && result.getWatermark() != null)
        {
            lastMeta.set(result);
        }
    }

    @Override
    public DataFreshness getFreshness(String siteCode)
    {
        // UDS 目前没有独立的水位线接口，只能用上一次查询响应里的 meta.freshness。
        // 冷启动时还没有任何查询，这里必然返回 null——上层会退化为「当前整点」推测
        // 并在响应里如实标记为推测值，而不是假装精确。
        // 优先用退避探到的水位：它是「确实查得到数的上界」，比 meta 里的自述更可信，
        // 而且第一次请求之后就有了，下一次请求的区间就能一次夹准，不用再撞 4403。
        // 多个数据集时取最保守的那个——任一数据集没到位，整页的 asOf 都不该往前标。
        LocalDateTime probed = null;
        long now = System.currentTimeMillis();
        for (Watermark w : watermarks.values())
        {
            if (w.expireAt < now)
            {
                continue;
            }
            if (probed == null || w.end.isBefore(probed))
            {
                probed = w.end;
            }
        }
        UdsQueryResult meta = lastMeta.get();
        if (probed == null)
        {
            if (meta == null || meta.getWatermark() == null)
            {
                return null;
            }
            return DataFreshness.of(meta.getWatermark(), meta.getWatermark());
        }
        return DataFreshness.of(probed,
            meta != null && meta.getWatermark() != null ? meta.getWatermark() : probed);
    }

    /** 最近一次查询命中的绑定与数据版本，供响应与日志追溯 */
    public UdsQueryResult lastQueryMeta()
    {
        return lastMeta.get();
    }

    // ===================== 指标序列 =====================

    @Override
    public Map<String, List<BigDecimal>> querySeries(MetricSlotRequest request)
    {
        int points = countPoints(request.getFrom(), request.getTo(), request.getGranularity());
        Map<String, List<BigDecimal>> result = new LinkedHashMap<>();
        // 先按契约填满 null，再把查到的值覆盖进去：
        // 保证返回的每条序列长度都等于时间片数，缺的位置是 null 而不是数组变短
        for (String code : request.getMetricCodes())
        {
            result.put(code, new ArrayList<>(Collections.nCopies(points, null)));
        }
        if (points <= 0 || request.getMetricCodes().isEmpty())
        {
            return result;
        }

        forEachDataset(request.getMetricCodes(), (datasetName, dataset, codes) -> {
            UdsQueryResult queried = queryRetreating(datasetName, dataset, codes,
                request.getFrom(), request.getTo(), granularityOf(request.getGranularity()),
                new ArrayList<>(dataset.getDimensions()), points);
            if (queried == null)
            {
                // 整个区间都在水位之上：所有时间片留 null，页面显示留白，
                // 而不是把「数据还没到」渲染成「数据是 0」
                return;
            }
            List<UdsRow> rows = queried.getRows();
            String timeColumn = dataset.getTimeDimension();
            for (UdsRow row : rows)
            {
                int index = slotIndex(row.str(timeColumn), request.getFrom(),
                    request.getTo(), request.getGranularity());
                if (index < 0 || index >= points)
                {
                    continue;   // 越界的行直接丢弃，不让脏数据污染序列
                }
                for (String code : codes)
                {
                    result.get(code).set(index, row.num(dataset.getMetrics().get(code)));
                }
            }
        });
        return result;
    }

    // ===================== 区间合计 =====================

    @Override
    public Map<String, BigDecimal> queryTotals(MetricSlotRequest request)
    {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (String code : request.getMetricCodes())
        {
            result.put(code, null);
        }
        if (request.getMetricCodes().isEmpty())
        {
            return result;
        }

        forEachDataset(request.getMetricCodes(), (datasetName, dataset, codes) -> {
            // 合计必须由数据侧按整个区间重算，不能把 querySeries 的结果加总：
            // 派生指标要先聚合分子分母，去重人数由 Doris bitmap 跨小时合并，平均耗时要按笔数加权。
            //
            // 关键：dimensions 必须传空数组。带上 biz_hh 的话上游会按小时分组返回 N 行，
            // 取 rows[0] 拿到的是「第一个小时」而不是「区间合计」——不会报错，只会静默错数。
            // 注意执行顺序：服务层先调 querySeries 再调 queryTotals，
            // 上一步已把水位写进缓存，这里会夹到同一个上界，两者口径因此一致
            UdsQueryResult queried = queryRetreating(datasetName, dataset, codes,
                request.getFrom(), request.getTo(), dataset.getGrain(),
                new ArrayList<>(), TOTAL_LIMIT);
            if (queried == null)
            {
                return;
            }
            List<UdsRow> rows = queried.getRows();
            if (rows.isEmpty())
            {
                return;
            }
            if (rows.size() > 1)
            {
                // 空维度理应只回一行。多行说明上游忽略了 dimensions，
                // 此时任何一行都不是合计，宁可全留 null 也不能挑一行冒充
                log.error("[uds] 区间合计返回 {} 行，dataset={} 区间={}~{}，判定为口径异常，不采纳",
                    rows.size(), datasetName, request.getFrom(), request.getTo());
                return;
            }
            UdsRow row = rows.get(0);
            for (String code : codes)
            {
                result.put(code, row.num(dataset.getMetrics().get(code)));
            }
        });
        return result;
    }

    // ===================== 明细 =====================

    @Override
    public RecordPage<MemberRecordVO> queryMemberRecords(RecordPageRequest request)
    {
        return queryDetail("member", request, (row, fields) -> {
            MemberRecordVO vo = new MemberRecordVO();
            vo.setAccount(row.str(fields.get("account")));
            vo.setMemberId(row.str(fields.get("memberId")));
            vo.setRegisterTime(row.str(fields.get("registerTime")));
            vo.setRegisterChannel(row.str(fields.get("registerChannel")));
            vo.setDevice(row.str(fields.get("device")));
            vo.setFirstDepositTime(row.str(fields.get("firstDepositTime")));
            vo.setFirstDepositAmount(row.num(fields.get("firstDepositAmount")));
            vo.setFirstDepositChannel(row.str(fields.get("firstDepositChannel")));
            vo.setRegToFtdHours(row.intVal(fields.get("regToFtdHours")));
            vo.setCumulativeDeposit(row.num(fields.get("cumulativeDeposit")));
            vo.setCumulativeWithdraw(row.num(fields.get("cumulativeWithdraw")));
            vo.setCumulativeBet(row.num(fields.get("cumulativeBet")));
            vo.setCumulativeNgr(row.num(fields.get("cumulativeNgr")));
            vo.setTurnoverMultiple(row.num(fields.get("turnoverMultiple")));
            vo.setTier(row.str(fields.get("tier")));
            vo.setTierLabel(row.str(fields.get("tierLabel")));
            vo.setStage(row.str(fields.get("stage")));
            vo.setStageLabel(row.str(fields.get("stageLabel")));
            vo.setLastActiveTime(row.str(fields.get("lastActiveTime")));
            vo.setLastBetGap(row.str(fields.get("lastBetGap")));
            // 首存时间有值才算已首存；没有明细字段时保持 null 而不是硬填 false
            vo.setHasFirstDeposit(vo.getFirstDepositTime() == null ? null : Boolean.TRUE);
            return vo;
        });
    }

    @Override
    public RecordPage<TransactionRecordVO> queryTransactionRecords(RecordPageRequest request)
    {
        return queryDetail("transaction", request, (row, fields) -> {
            TransactionRecordVO vo = new TransactionRecordVO();
            vo.setOrderNo(row.str(fields.get("orderNo")));
            vo.setType(row.str(fields.get("type")));
            vo.setAccount(row.str(fields.get("account")));
            vo.setAmount(row.num(fields.get("amount")));
            vo.setChannel(row.str(fields.get("channel")));
            vo.setStatus(row.str(fields.get("status")));
            vo.setStatusLabel(row.str(fields.get("statusLabel")));
            vo.setAuditStatus(row.str(fields.get("auditStatus")));
            vo.setAuditStatusLabel(row.str(fields.get("auditStatusLabel")));
            vo.setAuditor(row.str(fields.get("auditor")));
            vo.setCreateTime(row.str(fields.get("createTime")));
            vo.setFinishTime(row.str(fields.get("finishTime")));
            vo.setCostMinutes(row.num(fields.get("costMinutes")));
            return vo;
        });
    }

    @Override
    public RecordPage<BetRecordVO> queryBetRecords(RecordPageRequest request)
    {
        return queryDetail("bet", request, (row, fields) -> {
            BetRecordVO vo = new BetRecordVO();
            vo.setOrderNo(row.str(fields.get("orderNo")));
            vo.setAccount(row.str(fields.get("account")));
            vo.setVendor(row.str(fields.get("vendor")));
            vo.setGameType(row.str(fields.get("gameType")));
            vo.setGameTypeLabel(row.str(fields.get("gameTypeLabel")));
            vo.setGame(row.str(fields.get("game")));
            vo.setBetAmount(row.num(fields.get("betAmount")));
            vo.setPayout(row.num(fields.get("payout")));
            BigDecimal winLoss = row.num(fields.get("winLoss"));
            if (winLoss == null && vo.getBetAmount() != null && vo.getPayout() != null)
            {
                winLoss = vo.getBetAmount().subtract(vo.getPayout());
            }
            vo.setWinLoss(winLoss);
            vo.setCreateTime(row.str(fields.get("createTime")));
            return vo;
        });
    }

    /**
     * 明细查询的公共流程。
     * <p>
     * UDS 是聚合型接口，是否支持明细待确认；未配置对应数据集时返回空结果并告警，
     * 而不是抛异常把页面打挂。
     */
    private <T> RecordPage<T> queryDetail(String tab, RecordPageRequest request, RowMapper<T> mapper)
    {
        UdsProperties.DetailDataset detail = properties.getDetails().get(tab);
        if (detail == null || StringUtils.isEmpty(detail.getDataset()))
        {
            log.warn("[uds] 明细数据集未配置 tab={}，返回空结果", tab);
            return RecordPage.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataset", detail.getDataset());
        body.put("dimensions", new ArrayList<>(detail.getFields().values()));
        body.put("metrics", new ArrayList<String>());
        if (request.getFrom() != null && request.getTo() != null)
        {
            body.put("time", timeNode(detail.getTimeDimension(), request.getFrom(), request.getTo(), "DAY"));
        }
        Map<String, Object> options = new LinkedHashMap<>(detail.getOptions());
        // 筛选条件按列编码下推；具体键名由 UDS 的 options 约定，待确认
        request.getFilters().forEach((k, v) -> options.put(k, v));
        if (StringUtils.isNotEmpty(request.getKeyword()))
        {
            options.put("keyword", request.getKeyword());
        }
        options.put("pageNum", request.getPageNum());
        options.put("pageSize", request.getPageSize());
        if (StringUtils.isNotEmpty(request.getSortColumn()))
        {
            options.put("orderBy", detail.getFields().getOrDefault(
                request.getSortColumn(), request.getSortColumn()));
            options.put("orderDir", request.getSortDirection());
        }
        body.put("options", options);

        List<UdsRow> rows = client.query(body);
        RecordPage<T> page = new RecordPage<>();
        List<T> list = new ArrayList<>(rows.size());
        for (UdsRow row : rows)
        {
            list.add(mapper.map(row, detail.getFields()));
        }
        page.setRows(list);
        page.setTotal(list.size());
        page.setTruncated(request.getRowLimit() > 0 && list.size() >= request.getRowLimit());
        return page;
    }

    // ===================== 组装与映射 =====================

    /**
     * 把请求的指标编码按数据集分组，逐个数据集发一次查询。
     * 没有配置映射的指标编码直接跳过（对应列返回 null）。
     */
    private void forEachDataset(List<String> metricCodes, DatasetConsumer consumer)
    {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String code : metricCodes)
        {
            properties.getDatasets().forEach((name, dataset) -> {
                if (dataset.getMetrics().containsKey(code))
                {
                    grouped.computeIfAbsent(name, k -> new ArrayList<>()).add(code);
                }
            });
        }
        if (grouped.isEmpty())
        {
            log.warn("[uds] 请求的指标均未配置映射 metrics={}", metricCodes);
            return;
        }
        grouped.forEach((name, codes) -> {
            UdsProperties.Dataset dataset = properties.getDatasets().get(name);
            consumer.accept(name, dataset, codes);
        });
    }

    /**
     * 带「水位退避」的查询。
     * <p>
     * UDS 在请求区间上界高于已落库水位时，会整单拒绝：
     * <pre>
     * HTTP 400 code=4403 数据集 ops_hourly 没有满足条件的 binding
     *   excludedReasons: ["ops_hourly_rt：数据未就绪，水位低于请求区间上界"]
     * </pre>
     * 这不是故障，是「最后一格还没落库」。错误体里<b>不带水位的具体值</b>，处理顺序：
     * <ol>
     *   <li>之前的成功响应里拿到过该数据集的 {@code meta.freshness.watermark}：
     *       直接把上界截到水位（按粒度对齐）再查一次；仍不就绪就放弃，<b>不再逐格回退</b>——
     *       水位落后一天多时逐小时回退毫无意义，只会把请求拖慢几十秒；</li>
     *   <li>还没拿到过水位（冷启动）：才按粒度逐格回退试探，最多 {@code not-ready-retries} 格。</li>
     * </ol>
     * 试出来的上界会缓存起来，同一请求里后续的查询（对比期、合计）直接复用。
     *
     * @return 查询结果；整个区间都在水位之上时返回 null（调用方应保留 null 而不是填 0）
     */
    private UdsQueryResult queryRetreating(String datasetName, UdsProperties.Dataset dataset,
            List<String> codes, LocalDateTime from, LocalDateTime to, String granularity,
            List<String> dimensions, int limit)
    {
        LocalDateTime end = clampByWatermark(datasetName, to);
        int retries = Math.max(0, properties.getNotReadyRetries());
        UdsQueryException last = null;
        boolean clampedToReported = false;

        for (int attempt = 0; attempt <= retries; attempt++)
        {
            if (!end.isAfter(from))
            {
                log.warn("[uds] 区间 {} ~ {} 全部高于上游水位，dataset={}，本次不返回任何数据",
                    from, to, datasetName);
                return null;
            }
            Map<String, Object> body = baseBody(datasetName, dataset, codes,
                from, end, granularity, dimensions, limit);
            try
            {
                UdsQueryResult result = client.queryWithMeta(body);
                rememberWatermark(datasetName, end);
                rememberReportedWatermark(datasetName, result);
                remember(result);
                if (end.isBefore(to))
                {
                    // 数据滞后是运营会追问「为什么最后两小时是空的」的直接原因，必须留痕
                    log.warn("[uds] 上游数据滞后：请求上界 {}，实际取到 {}，dataset={} binding={}",
                        to, end, datasetName, result.getBinding());
                }
                return result;
            }
            catch (UdsQueryException e)
            {
                if (!e.isNotReady())
                {
                    throw e;    // 真正的故障照常抛出，不要被退避逻辑吞掉
                }
                last = e;
                if (clampedToReported)
                {
                    // 已按上游自报的水位截过一次仍不就绪：多半是水位口径与预期不符，
                    // 继续逐格试探只会拖慢请求，直接放弃并留痕，交给数据平台核对
                    log.error("[uds] 已截到上游水位 {} 仍未就绪，放弃本次查询，dataset={} 区间={}~{}：{}",
                        end, datasetName, from, to, e.getMessage());
                    return null;
                }
                LocalDateTime reported = reportedWatermarks.get(datasetName);
                LocalDateTime clamped = reported == null ? null : alignDown(from, reported, granularity);
                if (clamped != null && clamped.isBefore(end))
                {
                    log.info("[uds] 数据未就绪，按上游水位 {} 把上界 {} 截到 {}，dataset={}",
                        reported, end, clamped, datasetName);
                    end = clamped;
                    clampedToReported = true;
                    attempt--;  // 截断是一次性的，不占用逐格回退的次数
                    continue;
                }
                end = stepBack(end, granularity);
            }
        }
        log.error("[uds] 回退 {} 格后仍未就绪，dataset={} 区间={}~{}：{}",
            retries, datasetName, from, to, last == null ? "" : last.getMessage());
        return null;
    }

    /**
     * 把水位按粒度对齐到不晚于它的最后一个时间片边界（以 from 为起点计格）。
     * <p>
     * 水位按右开上界处理：{@code watermark=22:00} 时查询截到 22:00，取的是 22 点之前的数据。
     * 这是保守取法——即使上游的实际含义是「22 点这一格也已完整」，最多少显示一格，不会查到半截数据。
     */
    private LocalDateTime alignDown(LocalDateTime from, LocalDateTime watermark, String granularity)
    {
        if (!watermark.isAfter(from))
        {
            return from;
        }
        if ("HOUR".equalsIgnoreCase(granularity))
        {
            return from.plusHours(ChronoUnit.HOURS.between(from, watermark));
        }
        if ("WEEK".equalsIgnoreCase(granularity))
        {
            return from.plusWeeks(ChronoUnit.WEEKS.between(from, watermark));
        }
        return from.plusDays(ChronoUnit.DAYS.between(from, watermark));
    }

    /** 记录上游在响应里自报的水位；只前进不后退 */
    private void rememberReportedWatermark(String datasetName, UdsQueryResult result)
    {
        if (result == null || result.getWatermark() == null)
        {
            return;
        }
        reportedWatermarks.merge(datasetName, result.getWatermark(),
            (old, now) -> now.isAfter(old) ? now : old);
    }

    /** dataset -> 上游自报的水位（meta.freshness.watermark） */
    private final Map<String, LocalDateTime> reportedWatermarks = new java.util.concurrent.ConcurrentHashMap<>();

    /** 按粒度回退一格 */
    private LocalDateTime stepBack(LocalDateTime end, String granularity)
    {
        if ("HOUR".equalsIgnoreCase(granularity))
        {
            return end.minusHours(1);
        }
        if ("WEEK".equalsIgnoreCase(granularity))
        {
            return end.minusWeeks(1);
        }
        return end.minusDays(1);
    }

    /**
     * 用缓存的水位夹住上界，省掉一次必然失败的请求。
     * 缓存过期后会重新试满区间，上游追上进度时自动恢复，不需要重启。
     */
    private LocalDateTime clampByWatermark(String datasetName, LocalDateTime to)
    {
        Watermark cached = watermarks.get(datasetName);
        if (cached == null || cached.expireAt < System.currentTimeMillis())
        {
            return to;
        }
        return cached.end.isBefore(to) ? cached.end : to;
    }

    private void rememberWatermark(String datasetName, LocalDateTime end)
    {
        watermarks.put(datasetName, new Watermark(end,
            System.currentTimeMillis() + Math.max(1, properties.getWatermarkCacheSeconds()) * 1000L));
    }

    /** dataset -> 已确认可查到的区间上界（右开） */
    private final Map<String, Watermark> watermarks = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class Watermark
    {
        private final LocalDateTime end;

        private final long expireAt;

        private Watermark(LocalDateTime end, long expireAt)
        {
            this.end = end;
            this.expireAt = expireAt;
        }
    }

    private Map<String, Object> baseBody(String datasetName, UdsProperties.Dataset dataset,
            List<String> codes, LocalDateTime from, LocalDateTime to, String granularity,
            List<String> dimensions, int limit)
    {
        List<String> metrics = new ArrayList<>();
        codes.forEach(code -> metrics.add(dataset.getMetrics().get(code)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataset", datasetName);
        body.put("dimensions", dimensions);
        body.put("metrics", metrics);
        body.put("time", timeNode(dataset.getTimeDimension(), from, to, granularity));
        // limit 必须显式给：不传会落到上游默认值，长区间就会被悄悄截断，
        // 表现为「近 30 天只有前 20 天有数」这种最难查的故障
        body.put("limit", Math.max(1, limit));
        if (!dataset.getOptions().isEmpty())
        {
            body.put("options", new LinkedHashMap<>(dataset.getOptions()));
        }
        return body;
    }

    private Map<String, Object> timeNode(String dimension, LocalDateTime from, LocalDateTime to, String granularity)
    {
        Map<String, Object> time = new LinkedHashMap<>();
        time.put("dimension", dimension);
        time.put("granularity", granularity);
        boolean hourly = "HOUR".equalsIgnoreCase(granularity);
        time.put("from", hourly ? from.format(DATETIME) : from.toLocalDate().format(DATE));
        // 本系统区间是左闭右开，UDS 的 to 是闭区间（Postman 03 用 from == to 查单小时），
        // 所以末端回退一整格。回退一秒会得到 13:59:59 这种非整点值，biz_hh 匹配不上。
        LocalDateTime end = hourly ? to.minusHours(1) : to.minusDays(1);
        if (end.isBefore(from))
        {
            end = from;
        }
        time.put("to", hourly ? end.format(DATETIME) : end.toLocalDate().format(DATE));
        return time;
    }

    private String granularityOf(Granularity granularity)
    {
        return granularity == null ? "DAY" : granularity.name();
    }

    /**
     * 把返回行的时间值定位到第几个时间片。识别不了返回 -1。
     */
    private int slotIndex(String value, LocalDateTime from, LocalDateTime to, Granularity granularity)
    {
        if (StringUtils.isEmpty(value) || from == null)
        {
            return -1;
        }
        try
        {
            String text = value.trim();
            if (granularity == Granularity.HOUR)
            {
                LocalDateTime point = text.length() <= 10
                    ? LocalDate.parse(text, DATE).atStartOfDay()
                    : LocalDateTime.parse(text.substring(0, 19).replace('T', ' '), DATETIME);
                return (int) ChronoUnit.HOURS.between(from, point);
            }
            LocalDate day = LocalDate.parse(text.substring(0, 10), DATE);
            long days = ChronoUnit.DAYS.between(from.toLocalDate(), day);
            return granularity == Granularity.WEEK ? (int) (days / 7) : (int) days;
        }
        catch (Exception e)
        {
            log.debug("[uds] 无法解析时间值: {}", value);
            return -1;
        }
    }

    private int countPoints(LocalDateTime from, LocalDateTime to, Granularity granularity)
    {
        if (from == null || to == null || granularity == null)
        {
            return 0;
        }
        switch (granularity)
        {
            case HOUR:
                return (int) Math.max(0, ChronoUnit.HOURS.between(from, to));
            case WEEK:
                return (int) Math.max(0, Math.ceil(ChronoUnit.DAYS.between(from, to) / 7.0));
            case DAY:
            default:
                return (int) Math.max(0, ChronoUnit.DAYS.between(from, to));
        }
    }

    @FunctionalInterface
    private interface DatasetConsumer
    {
        void accept(String datasetName, UdsProperties.Dataset dataset, List<String> codes);
    }

    @FunctionalInterface
    private interface RowMapper<T>
    {
        T map(UdsRow row, Map<String, String> fields);
    }
}
