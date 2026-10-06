package com.fivetech.dashboard.gateway.uds;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.domain.vo.BetRecordVO;
import com.fivetech.dashboard.domain.vo.DepositRecordVO;
import com.fivetech.dashboard.domain.vo.MemberRecordVO;
import com.fivetech.dashboard.domain.vo.WithdrawRecordVO;
import com.fivetech.dashboard.service.OrderDict;
import com.fivetech.dashboard.enums.Granularity;
import com.fivetech.dashboard.gateway.BreakdownResult;
import com.fivetech.dashboard.gateway.DataFreshness;
import com.fivetech.dashboard.gateway.ExportJob;
import com.fivetech.dashboard.gateway.MetricDataGateway;
import com.fivetech.dashboard.gateway.MetricSlotRequest;
import com.fivetech.dashboard.gateway.RecordPage;
import com.fivetech.dashboard.gateway.RecordPageRequest;

/**
 * 基于 UDS（统一数据服务）HTTP 接口的数据网关实现。
 * <p>
 * 按《后端 Postman 接口与指标联调手册》（2026-10-05）对接 v2 数据集：
 * <ul>
 *   <li>指标：按粒度路由到 overview 的 hour / day / amount 三个数据集（{@code dashboard.gateway.uds.overview}）；
 *       net、ngr 由后端用原子量相减（UDS 不返回这两个页面字段）；</li>
 *   <li>明细：列表 + 同条件 COUNT 走 {@code /v1/query/batch}，filters 为 JSON 条件树，
 *       排序固定以 uds_rowkey 收尾，limit ≤ 1000、offset ≤ 100000；</li>
 *   <li>榜单 / 注册来源：orderBy + limit 下推，分母另查同数据集全量。</li>
 * </ul>
 * 指标编码到 UDS 数据集与字段名的映射全部走配置，代码里不出现业务表名。
 * <p>
 * 网关只做「取数 + 组装」，保持 UDS 原始量纲（比率 0～1、时长秒），换算统一在
 * {@link com.fivetech.dashboard.service.MetricValueNormalizer}。
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

    /** UDS 单次查询最多返回的行数（联调手册第 7 节） */
    private static final int MAX_LIMIT = 1000;

    /** UDS 明细分页 offset 上限 */
    private static final int MAX_OFFSET = 100000;

    /** 批量查询单次最多项数 */
    private static final int MAX_BATCH = 50;

    /** 列表与 COUNT 的 dataVersion 不一致时，整对重查的次数 */
    private static final int VERSION_RETRIES = 2;

    private final UdsProperties properties;

    private final UdsClient client;

    /** 站点上线日 / 统计时区：明细不带时间时，用来补 UDS 必填的 time 主轴 */
    private final DashboardProperties dashboard;

    public UdsMetricDataGateway(UdsProperties properties, UdsClient client, DashboardProperties dashboard)
    {
        this.properties = properties;
        this.client = client;
        this.dashboard = dashboard;
    }

    // ===================== 新鲜度 =====================

    /** 最近一次指标查询带回的元信息。UDS 没有独立的水位线接口，只能从查询响应里捎带 */
    private final java.util.concurrent.atomic.AtomicReference<UdsQueryResult> lastMeta =
        new java.util.concurrent.atomic.AtomicReference<>();

    /** dataset -> 上游自报的水位（meta.freshness.watermark），只前进不后退 */
    private final Map<String, LocalDateTime> reportedWatermarks = new java.util.concurrent.ConcurrentHashMap<>();

    private void remember(String datasetName, UdsQueryResult result)
    {
        if (result != null && result.getWatermark() != null && freshnessDatasets().contains(datasetName))
        {
            lastMeta.set(result);
        }
    }

    /**
     * 参与顶栏水位的数据集：只有 overview 的小时 / 金额两个。
     * 榜单数据集（目前只有 7 月的历史窗口）和日数据集（完整日才落）水位天然落后，
     * 混进来会把整页的 asOf 拉回到它们的位置。
     */
    private Set<String> freshnessDatasets()
    {
        Set<String> names = new LinkedHashSet<>();
        UdsProperties.Overview ov = properties.getOverview();
        for (String key : new String[] { ov.getHour(), ov.getAmount() })
        {
            UdsProperties.Dataset d = dataset(key);
            if (d != null)
            {
                names.add(nameOf(key, d));
            }
        }
        return names;
    }

    @Override
    public DataFreshness getFreshness(String siteCode)
    {
        // 水位只认上游自报的 meta.freshness，不自己推断；多个数据集取最保守的那个。
        // 注意（联调手册第 8 节）：水位是「已接收到的来源上界」，不是采集完整的证明，
        // 权威完成信号未接入前，页面只能标注「已接收到的历史来源数据」
        LocalDateTime reported = null;
        for (String name : freshnessDatasets())
        {
            LocalDateTime w = reportedWatermarks.get(name);
            if (w != null && (reported == null || w.isBefore(reported)))
            {
                reported = w;
            }
        }
        if (reported == null)
        {
            UdsQueryResult meta = lastMeta.get();
            if (meta == null || meta.getWatermark() == null)
            {
                return null;
            }
            reported = meta.getWatermark();
        }
        return DataFreshness.of(reported, reported);
    }

    /** 最近一次查询命中的绑定与数据版本，供响应与日志追溯 */
    public UdsQueryResult lastQueryMeta()
    {
        return lastMeta.get();
    }

    // ===================== 指标路由 =====================

    private UdsProperties.Dataset dataset(String key)
    {
        return StringUtils.isEmpty(key) ? null : properties.getDatasets().get(key);
    }

    private static String nameOf(String key, UdsProperties.Dataset d)
    {
        return StringUtils.isEmpty(d.getName()) ? key : d.getName();
    }

    /** codes 中在该数据集里配置了映射的那部分，保持顺序 */
    private List<String> mapped(String key, java.util.Collection<String> codes)
    {
        UdsProperties.Dataset d = dataset(key);
        List<String> result = new ArrayList<>();
        if (d == null)
        {
            return result;
        }
        for (String code : codes)
        {
            if (d.getMetrics().containsKey(code))
            {
                result.add(code);
            }
        }
        return result;
    }

    /** 把需要后端相减的指标展开成操作数，其余原样保留 */
    private Set<String> expand(List<String> codes)
    {
        Map<String, List<String>> subtract = properties.getOverview().getSubtract();
        Set<String> fetch = new LinkedHashSet<>();
        for (String code : codes)
        {
            List<String> ops = subtract.get(code);
            if (ops != null && ops.size() == 2)
            {
                fetch.addAll(ops);
            }
            else
            {
                fetch.add(code);
            }
        }
        return fetch;
    }

    /** a − b；任一为 NULL 则 NULL（不把缺项当 0），负值合法 */
    private static BigDecimal minus(BigDecimal a, BigDecimal b)
    {
        return a == null || b == null ? null : a.subtract(b);
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
            result.put(code, nulls(points));
        }
        if (points <= 0 || request.getMetricCodes().isEmpty())
        {
            return result;
        }
        Granularity g = request.getGranularity() == null ? Granularity.DAY : request.getGranularity();
        LocalDateTime from = request.getFrom();
        LocalDateTime to = request.getTo();
        Set<String> fetch = expand(request.getMetricCodes());
        Map<String, List<BigDecimal>> raw = new HashMap<>();
        UdsProperties.Overview ov = properties.getOverview();

        if (g == Granularity.HOUR)
        {
            // 小时行：全部指标（含小时去重人数 active_hour、dep_users_hour）都在小时数据集里
            seriesFrom(ov.getHour(), mapped(ov.getHour(), fetch), from, to, Granularity.HOUR, points, raw);
        }
        else if (g == Granularity.DAY)
        {
            // 金额 / 普通比率：金额数据集按 uds_hh 上卷到 DAY（能覆盖今天这种不完整的日）
            List<String> amountCodes = mapped(ov.getAmount(), fetch);
            seriesFrom(ov.getAmount(), amountCodes, from, to, Granularity.DAY, points, raw);
            // 只能按日去重的人数类（ARPPU、存款人数）：日数据集，只有完整日才有行
            List<String> rest = new ArrayList<>(fetch);
            rest.removeAll(amountCodes);
            seriesFrom(ov.getDay(), mapped(ov.getDay(), rest), from, to, Granularity.DAY, points, raw);
        }
        else
        {
            weekSeries(fetch, from, to, points, raw);
        }

        for (String code : request.getMetricCodes())
        {
            List<String> ops = ov.getSubtract().get(code);
            if (ops != null && ops.size() == 2)
            {
                List<BigDecimal> a = raw.get(ops.get(0));
                List<BigDecimal> b = raw.get(ops.get(1));
                List<BigDecimal> out = result.get(code);
                for (int i = 0; i < points; i++)
                {
                    out.set(i, minus(a == null ? null : a.get(i), b == null ? null : b.get(i)));
                }
            }
            else if (raw.containsKey(code))
            {
                result.put(code, raw.get(code));
            }
        }
        return result;
    }

    private static List<BigDecimal> nulls(int size)
    {
        return new ArrayList<>(Collections.nCopies(Math.max(0, size), null));
    }

    /**
     * 从一个数据集按时间维分组取序列，结果按时间片下标写进 out。
     * <p>小时序列超过 1000 点时按 1000 小时分段查询（UDS 单次最多 1000 行），
     * 不能把第一页当成整段结果。</p>
     */
    private void seriesFrom(String key, List<String> codes, LocalDateTime from, LocalDateTime to,
            Granularity granularity, int points, Map<String, List<BigDecimal>> out)
    {
        UdsProperties.Dataset d = dataset(key);
        if (d == null || codes.isEmpty())
        {
            return;
        }
        String name = nameOf(key, d);
        for (String code : codes)
        {
            out.computeIfAbsent(code, k -> nulls(points));
        }
        LocalDateTime chunkFrom = from;
        while (chunkFrom.isBefore(to))
        {
            LocalDateTime chunkTo = granularity == Granularity.HOUR
                ? min(chunkFrom.plusHours(MAX_LIMIT), to) : to;
            int chunkPoints = Math.max(1, countPoints(chunkFrom, chunkTo, granularity));
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("orderBy", List.of(order(d.getTimeDimension(), "ASC")));
            UdsQueryResult queried = queryRetreating(name, d, codes, chunkFrom, chunkTo,
                granularity.name(), List.of(d.getTimeDimension()), Math.min(MAX_LIMIT, chunkPoints), extras);
            if (queried != null)
            {
                for (UdsRow row : queried.getRows())
                {
                    int index = slotIndex(row.str(d.getTimeDimension()), from, to, granularity);
                    if (index < 0 || index >= points)
                    {
                        continue;   // 越界的行直接丢弃，不让脏数据污染序列
                    }
                    for (String code : codes)
                    {
                        out.get(code).set(index, row.num(d.getMetrics().get(code)));
                    }
                }
            }
            chunkFrom = chunkTo;
        }
    }

    /**
     * 周序列。
     * <p>UDS 的周桶从周一开始，而本系统的周时间片从查询起点起每 7 天一格，两者对不上，
     * 不能直接用 WEEK 粒度的行。这里按本系统的每一格发一个区间合计（dimensions=[]），
     * 用 /v1/query/batch 一次发完：比率由 UDS 按聚合分子 / 分母重算，和每格边界严格一致。</p>
     */
    private void weekSeries(Set<String> fetch, LocalDateTime from, LocalDateTime to, int points,
            Map<String, List<BigDecimal>> out)
    {
        UdsProperties.Overview ov = properties.getOverview();
        List<LocalDateTime[]> slots = new ArrayList<>();
        for (int i = 0; i < points; i++)
        {
            LocalDateTime s = from.plusWeeks(i);
            slots.add(new LocalDateTime[] { s, min(s.plusWeeks(1), to) });
        }

        List<String> amountCodes = mapped(ov.getAmount(), fetch);
        UdsProperties.Dataset d = dataset(ov.getAmount());
        if (d != null && !amountCodes.isEmpty())
        {
            String name = nameOf(ov.getAmount(), d);
            amountCodes.forEach(code -> out.computeIfAbsent(code, k -> nulls(points)));
            // 不按缓存水位预截断（见 queryRetreating）；某一格 4403 时整批失败，下面退回逐格查询各自退避
            LocalDateTime limit = to;
            List<Integer> indexes = new ArrayList<>();
            List<Map<String, Object>> bodies = new ArrayList<>();
            for (int i = 0; i < slots.size(); i++)
            {
                LocalDateTime s = slots.get(i)[0];
                LocalDateTime e = min(slots.get(i)[1], limit);
                if (!e.isAfter(s))
                {
                    continue;
                }
                indexes.add(i);
                bodies.add(baseBody(name, d, amountCodes, s, e, "DAY", new ArrayList<>(), TOTAL_LIMIT, null));
            }
            List<UdsQueryResult> results;
            try
            {
                results = batch(bodies);
                results.forEach(r -> rememberReportedWatermark(name, r));
            }
            catch (UdsQueryException e)
            {
                if (!e.isNotReady())
                {
                    throw e;
                }
                // 批量里任一项不就绪整批失败：退回逐格查询，每格各自按水位退避
                log.info("[uds] 周序列批量查询未就绪，改为逐格查询：{}", e.getMessage());
                results = new ArrayList<>();
                for (int idx : indexes)
                {
                    results.add(queryRetreating(name, d, amountCodes, slots.get(idx)[0],
                        min(slots.get(idx)[1], limit), "DAY", new ArrayList<>(), TOTAL_LIMIT, null));
                }
            }
            for (int k = 0; k < indexes.size(); k++)
            {
                UdsRow row = singleRow(results.get(k), name);
                if (row == null)
                {
                    continue;
                }
                for (String code : amountCodes)
                {
                    out.get(code).set(indexes.get(k), row.num(d.getMetrics().get(code)));
                }
            }
        }

        // 只能按日去重的人数类：每格取各日平均（联调手册 6.1）。
        // 需要该格每一天都有值；缺任何一天保持 NULL，不省略缺日后缩小分母。
        // TODO：「部分周」（最后一格不满 7 天、含今天）的日均规则数据团队仍待确认，目前只给完整 7 天的格出值
        List<String> rest = new ArrayList<>(fetch);
        rest.removeAll(amountCodes);
        List<String> dayCodes = mapped(ov.getDay(), rest);
        if (!dayCodes.isEmpty() && isMidnight(from))
        {
            int days = (int) ChronoUnit.DAYS.between(from.toLocalDate(), to.toLocalDate());
            Map<String, List<BigDecimal>> daily = new HashMap<>();
            seriesFrom(ov.getDay(), dayCodes, from, from.plusDays(days), Granularity.DAY, days, daily);
            for (String code : dayCodes)
            {
                List<BigDecimal> values = out.computeIfAbsent(code, k -> nulls(points));
                for (int i = 0; i < slots.size(); i++)
                {
                    LocalDateTime s = slots.get(i)[0];
                    LocalDateTime e = slots.get(i)[1];
                    if (ChronoUnit.DAYS.between(s, e) != 7 || !isMidnight(e))
                    {
                        continue;
                    }
                    int start = (int) ChronoUnit.DAYS.between(from, s);
                    values.set(i, average(daily.get(code), start, 7));
                }
            }
        }
    }

    /** list[start, start+len) 的算术平均；任一为 NULL 或越界返回 NULL */
    private static BigDecimal average(List<BigDecimal> list, int start, int len)
    {
        if (list == null || len <= 0 || start < 0 || start + len > list.size())
        {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = start; i < start + len; i++)
        {
            if (list.get(i) == null)
            {
                return null;
            }
            sum = sum.add(list.get(i));
        }
        // 保留 4 位：日均人数允许小数，最终显示位数由 MetricValueNormalizer 按指标格式决定
        return sum.divide(BigDecimal.valueOf(len), 4, RoundingMode.HALF_UP);
    }

    private static boolean isMidnight(LocalDateTime t)
    {
        return t.toLocalTime().equals(LocalTime.MIDNIGHT);
    }

    private static LocalDateTime min(LocalDateTime a, LocalDateTime b)
    {
        return a.isBefore(b) ? a : b;
    }

    private static Map<String, Object> order(String field, String dir)
    {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("field", field);
        o.put("dir", dir);
        return o;
    }

    /** 分块发批量查询（每批最多 50 项），结果按原顺序拼回 */
    private List<UdsQueryResult> batch(List<Map<String, Object>> bodies)
    {
        List<UdsQueryResult> results = new ArrayList<>(bodies.size());
        for (int i = 0; i < bodies.size(); i += MAX_BATCH)
        {
            results.addAll(client.queryBatch(bodies.subList(i, Math.min(bodies.size(), i + MAX_BATCH))));
        }
        return results;
    }

    /**
     * 区间合计查询的唯一一行。空维度理应只回一行：多行说明上游忽略了 dimensions，
     * 此时任何一行都不是合计，宁可全留 null 也不能挑一行冒充
     */
    private UdsRow singleRow(UdsQueryResult result, String datasetName)
    {
        if (result == null || result.getRows().isEmpty())
        {
            return null;
        }
        if (result.getRows().size() > 1)
        {
            log.error("[uds] 区间合计返回 {} 行，dataset={}，判定为口径异常，不采纳",
                result.getRows().size(), datasetName);
            return null;
        }
        return result.getRows().get(0);
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
        if (request.getMetricCodes().isEmpty() || request.getFrom() == null || request.getTo() == null
            || !request.getTo().isAfter(request.getFrom()))
        {
            return result;
        }
        LocalDateTime from = request.getFrom();
        LocalDateTime to = request.getTo();
        UdsProperties.Overview ov = properties.getOverview();
        Set<String> fetch = expand(request.getMetricCodes());
        Map<String, BigDecimal> raw = new HashMap<>();

        // 金额 / 普通比率：金额数据集 dimensions=[] 的独立总值查询（联调手册第 5 节），
        // 不依赖序列分页行的合计——比率要由 UDS 按聚合分子 / 聚合分母计算
        List<String> amountCodes = mapped(ov.getAmount(), fetch);
        totalFrom(ov.getAmount(), amountCodes, from, to, "DAY", raw);

        // 去重人数类（活跃、ARPPU、存款人数、登录）：小时人数不能相加，日人数不能用小时值替代
        List<String> rest = new ArrayList<>(fetch);
        rest.removeAll(amountCodes);
        if (!rest.isEmpty())
        {
            uniqueTotals(rest, from, to, raw);
        }

        for (String code : request.getMetricCodes())
        {
            List<String> ops = ov.getSubtract().get(code);
            if (ops != null && ops.size() == 2)
            {
                // 不能先算每小时再 SUM：某小时一项 NULL 会丢掉其他存在的金额
                result.put(code, minus(raw.get(ops.get(0)), raw.get(ops.get(1))));
            }
            else
            {
                result.put(code, raw.get(code));
            }
        }
        return result;
    }

    private void totalFrom(String key, List<String> codes, LocalDateTime from, LocalDateTime to,
            String granularity, Map<String, BigDecimal> out)
    {
        UdsProperties.Dataset d = dataset(key);
        if (d == null || codes.isEmpty())
        {
            return;
        }
        String name = nameOf(key, d);
        // 关键：dimensions 必须传空数组。带上时间维的话上游会按时间分组返回 N 行，
        // 取 rows[0] 拿到的是「第一个时间片」而不是「区间合计」——不会报错，只会静默错数
        UdsQueryResult queried = queryRetreating(name, d, codes, from, to, granularity,
            new ArrayList<>(), TOTAL_LIMIT, null);
        UdsRow row = singleRow(queried, name);
        if (row == null)
        {
            return;
        }
        for (String code : codes)
        {
            out.put(code, row.num(d.getMetrics().get(code)));
        }
    }

    /**
     * 去重人数类指标的区间值（联调手册 6.1）：
     * <ul>
     *   <li>恰好一个小时：小时数据集的该小时独立人数；</li>
     *   <li>恰好一个完整自然日：日数据集的日去重值；</li>
     *   <li>多个完整自然日：各日值的算术平均（汇总页口径；总览多日时这些卡片已被隐藏，不会走到这里）。
     *       需要每一天都有值，缺任何一天保持 NULL；</li>
     *   <li>其余（单日部分时段、非整日的跨天区间）：没有准确的去重分母来源，保持 NULL 占位。</li>
     * </ul>
     */
    private void uniqueTotals(List<String> codes, LocalDateTime from, LocalDateTime to, Map<String, BigDecimal> out)
    {
        UdsProperties.Overview ov = properties.getOverview();
        if (ChronoUnit.MINUTES.between(from, to) == 60 && from.getMinute() == 0)
        {
            totalFrom(ov.getHour(), mapped(ov.getHour(), codes), from, to, "HOUR", out);
            return;
        }
        if (!isMidnight(from) || !isMidnight(to))
        {
            log.debug("[uds] 区间 {}~{} 不是整日，去重人数类指标 {} 无准确来源，保持 NULL", from, to, codes);
            return;
        }
        List<String> dayCodes = mapped(ov.getDay(), codes);
        if (dayCodes.isEmpty())
        {
            return;
        }
        int days = (int) ChronoUnit.DAYS.between(from, to);
        if (days == 1)
        {
            totalFrom(ov.getDay(), dayCodes, from, to, "DAY", out);
            return;
        }
        Map<String, List<BigDecimal>> daily = new HashMap<>();
        seriesFrom(ov.getDay(), dayCodes, from, to, Granularity.DAY, days, daily);
        for (String code : dayCodes)
        {
            out.put(code, average(daily.get(code), 0, days));
        }
    }

    // ===================== 拆解（运营总览板块） =====================

    /**
     * 按维度分组的区间聚合（榜单、注册渠道、留存 / LTV）。
     * <p>
     * orderBy + limit 下推给 UDS，Top N 由上游截取；配置 withTotal 时再查一次同条件的全量
     * （dimensions=[]）作为占比分母——不能用分页排名的合计当分母。
     * <ul>
     *   <li>batch=true（游戏榜、赠金榜）：排名与全量用一次 /v1/query/batch 发出；</li>
     *   <li>batch=false（注册渠道）：分两次 /v1/query（reg-source、reg-total）。</li>
     * </ul>
     * 上界高于上游水位（4403）时按水位截断，水位按 UDS 数据集名记录。
     */
    @Override
    public BreakdownResult queryBreakdown(String key, LocalDateTime from, LocalDateTime to)
    {
        UdsProperties.Breakdown bd = properties.getBreakdowns().get(key);
        if (bd == null || !bd.isConfigured())
        {
            log.warn("[uds] 拆解查询未配置 dashboard.gateway.uds.breakdowns.{}，返回空结果", key);
            return BreakdownResult.notConfigured();
        }
        UdsProperties.Dataset dataset = new UdsProperties.Dataset();
        dataset.setTimeDimension(bd.getTimeDimension());
        dataset.setTimeFormat(bd.getTimeFormat());
        dataset.setGrain(bd.getGrain());
        dataset.setMetrics(new LinkedHashMap<>(bd.getMetrics()));
        dataset.setOptions(new LinkedHashMap<>(bd.getOptions()));

        List<String> codes = new ArrayList<>(bd.getMetrics().keySet());
        List<String> dims = new ArrayList<>(bd.getDimensions().values());
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("filters", null);
        if (bd.isWithTotal())
        {
            // 与联调包一致：榜单 / 注册渠道带空 having，留存 / LTV 不带
            extras.put("having", new ArrayList<>());
        }
        extras.put("orderBy", new ArrayList<>(bd.getOrderBy()));
        Map<String, Object> totalExtras = new LinkedHashMap<>(extras);
        totalExtras.put("orderBy", new ArrayList<>());
        int limit = Math.max(1, bd.getLimit());

        UdsQueryResult main = null;
        UdsQueryResult total = null;
        boolean done = false;
        if (bd.isWithTotal() && bd.isBatch())
        {
            // 排名与全量一次发出，两项必然是同一个时间范围；4403 时退回下面的逐条查询（各自按水位退避）
            LocalDateTime end = to;
            if (!end.isAfter(from))
            {
                return BreakdownResult.notReady();
            }
            try
            {
                List<UdsQueryResult> results = client.queryBatch(List.of(
                    baseBody(bd.getDataset(), dataset, codes, from, end, bd.getGrain(), dims, limit, extras),
                    baseBody(bd.getDataset(), dataset, codes, from, end, bd.getGrain(), new ArrayList<>(), 1, totalExtras)));
                main = results.get(0);
                total = results.get(1);
                rememberReportedWatermark(bd.getDataset(), main);
                done = true;
            }
            catch (UdsQueryException e)
            {
                if (!e.isNotReady())
                {
                    throw e;
                }
                // 冷启动还没拿到水位就撞上 4403：退回逐条查询，走按粒度退避的公共逻辑
                log.info("[uds] 拆解 {} 批量查询未就绪，改为逐条查询：{}", key, e.getMessage());
            }
        }
        if (!done)
        {
            main = queryRetreating(bd.getDataset(), dataset, codes, from, to, bd.getGrain(), dims, limit, extras);
            if (main == null)
            {
                return BreakdownResult.notReady();
            }
            if (bd.isWithTotal())
            {
                // 第二次会夹到第一次记下的水位，两次查询的时间范围一致
                total = queryRetreating(bd.getDataset(), dataset, codes, from, to, bd.getGrain(),
                    new ArrayList<>(), 1, totalExtras);
            }
        }

        BreakdownResult result = new BreakdownResult();
        result.setConfigured(true);
        result.setWatermark(main.getWatermark());
        for (UdsRow raw : main.getRows())
        {
            BreakdownResult.Row row = new BreakdownResult.Row();
            bd.getDimensions().forEach((code, field) -> row.getDims().put(code, raw.str(field)));
            bd.getMetrics().forEach((code, metric) -> row.getValues().put(code, scaled(bd, code, raw.num(metric))));
            result.getRows().add(row);
        }
        if (bd.getEnrich().isConfigured() && !result.getRows().isEmpty())
        {
            enrich(key, bd.getEnrich(), result.getRows(), from, to);
        }
        UdsRow totalRow = singleRow(total, bd.getDataset());
        if (totalRow != null)
        {
            bd.getMetrics().forEach((code, metric) -> result.getTotals().put(code, scaled(bd, code, totalRow.num(metric))));
        }
        if (main.getRows().size() >= limit && !bd.isWithTotal())
        {
            log.warn("[uds] 拆解查询 {} 返回行数达到上限 {}，结果可能被截断", key, limit);
        }
        return result;
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * 榜单属性补全：按榜单行的关联键，到属性数据集同一时间窗里分组查一次名称 / 类型。
     * <p>只发一次查询：dimensions = 关联键 + 属性字段，filters = 关联键的 OR（EQ），
     * 本地按全部关联键匹配回榜单行。同一个游戏有多条属性记录时取第一条。
     * 任何失败只记日志、属性留 null——榜单不能因为名称查不到而整块报错。</p>
     */
    private void enrich(String key, UdsProperties.Enrich en, List<BreakdownResult.Row> rows,
            LocalDateTime from, LocalDateTime to)
    {
        try
        {
            String filterKey = StringUtils.isNotEmpty(en.getFilterKey()) ? en.getFilterKey()
                : en.getKeys().keySet().iterator().next();
            String filterField = en.getKeys().get(filterKey);
            Set<String> values = new LinkedHashSet<>();
            rows.forEach(r -> {
                if (StringUtils.isNotEmpty(r.dim(filterKey)))
                {
                    values.add(r.dim(filterKey));
                }
            });
            if (values.isEmpty())
            {
                return;
            }
            List<Object> any = new ArrayList<>();
            values.forEach(v -> any.add(filter(filterField, "EQ", v)));
            Map<String, Object> or = new LinkedHashMap<>();
            or.put("or", any);
            Map<String, Object> filters = new LinkedHashMap<>();
            filters.put("and", List.of(or));

            Set<String> dims = new LinkedHashSet<>(en.getKeys().values());
            dims.addAll(en.getFields().values());
            Map<String, Object> time = new LinkedHashMap<>();
            time.put("dimension", en.getTimeDimension());
            time.put("granularity", "HOUR");
            LocalDateTime start = from.truncatedTo(ChronoUnit.HOURS);
            LocalDateTime end = to.minusNanos(1).truncatedTo(ChronoUnit.HOURS);
            time.put("from", start.format(DATETIME));
            time.put("to", (end.isBefore(start) ? start : end).format(DATETIME));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("dataset", en.getDataset());
            body.put("dimensions", new ArrayList<>(dims));
            body.put("metrics", List.of(en.getCountMetric()));
            body.put("time", time);
            body.put("filters", filters);
            body.put("orderBy", List.of(order(en.getCountMetric(), "DESC")));
            body.put("limit", Math.min(MAX_LIMIT, values.size() * 20));
            body.put("offset", 0);
            Map<String, Object> options = new LinkedHashMap<>(en.getOptions());
            options.putIfAbsent("channel", "ONLINE");
            body.put("options", options);

            Map<String, UdsRow> byKey = new HashMap<>();
            for (UdsRow row : client.queryWithMeta(body).getRows())
            {
                StringBuilder k = new StringBuilder();
                en.getKeys().values().forEach(f -> k.append(row.str(f)).append('\u0001'));
                byKey.putIfAbsent(k.toString(), row);   // 按笔数降序，取最常见的那条属性
            }
            int hit = 0;
            for (BreakdownResult.Row r : rows)
            {
                StringBuilder k = new StringBuilder();
                en.getKeys().keySet().forEach(c -> k.append(r.dim(c)).append('\u0001'));
                UdsRow attr = byKey.get(k.toString());
                if (attr == null)
                {
                    continue;
                }
                hit++;
                en.getFields().forEach((code, field) -> {
                    if (StringUtils.isEmpty(r.dim(code)))
                    {
                        r.getDims().put(code, attr.str(field));
                    }
                });
            }
            if (hit < rows.size())
            {
                log.info("[uds] 拆解 {} 属性补全命中 {}/{} 行（未命中的属性留 null）", key, hit, rows.size());
            }
        }
        catch (RuntimeException e)
        {
            log.warn("[uds] 拆解 {} 属性补全失败，属性留 null：{}", key, e.getMessage());
        }
    }

    /** ratio-metrics 里的指标：0～1 → 百分数（保留 2 位）；NULL 保持 NULL */
    private static BigDecimal scaled(UdsProperties.Breakdown bd, String code, BigDecimal value)
    {
        if (value == null || !bd.getRatioMetrics().contains(code))
        {
            return value;
        }
        return value.multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
    }

    // ===================== 明细 =====================

    /**
     * 会员明细（dashboard_player_detail_v2）。fields 映射 key = 列编码，value = UDS 字段名。
     * 累计类字段是当前快照，不一定是查询区间内的累计。
     */
    @Override
    public RecordPage<MemberRecordVO> queryMemberRecords(RecordPageRequest request)
    {
        return queryDetail("member", request, (row, d) -> {
            MemberRecordVO vo = new MemberRecordVO();
            vo.setUserId(str(row, d, "userId"));
            vo.setUsername(str(row, d, "username"));
            vo.setStatus(decoded(row, d, "status"));
            vo.setStatusLabel(com.fivetech.dashboard.service.MemberDict.label(
                com.fivetech.dashboard.service.MemberDict.STATUS, vo.getStatus()));
            vo.setUserType(decoded(row, d, "userType"));
            vo.setUserTypeLabel(com.fivetech.dashboard.service.MemberDict.label(
                com.fivetech.dashboard.service.MemberDict.USER_TYPE, vo.getUserType()));
            vo.setLevel(str(row, d, "level"));
            vo.setCountry(str(row, d, "country"));
            vo.setCountryLabel(com.fivetech.dashboard.service.MemberDict.label(
                com.fivetech.dashboard.service.MemberDict.COUNTRY, vo.getCountry()));
            vo.setRegisterTime(str(row, d, "registerTime"));
            vo.setRegisterChannel(str(row, d, "registerChannel"));
            vo.setFirstDepositTime(str(row, d, "firstDepositTime"));
            vo.setFirstDepositAmount(num(row, d, "firstDepositAmount"));
            vo.setFirstDepositChannel(str(row, d, "firstDepositChannel"));
            vo.setRegToFtdHours(row.intVal(d.getFields().get("regToFtdHours")));
            vo.setLastDepositTime(str(row, d, "lastDepositTime"));
            vo.setLastDepositAmount(num(row, d, "lastDepositAmount"));
            vo.setCumulativeDepositAmount(num(row, d, "cumulativeDepositAmount"));
            vo.setCumulativeDepositCount(toLong(num(row, d, "cumulativeDepositCount")));
            vo.setLastBetTime(str(row, d, "lastBetTime"));
            vo.setLastBetAmount(num(row, d, "lastBetAmount"));
            vo.setCumulativeBetAmount(num(row, d, "cumulativeBetAmount"));
            vo.setCumulativeBetCount(toLong(num(row, d, "cumulativeBetCount")));
            vo.setTurnoverMultiple(num(row, d, "turnoverMultiple"));
            vo.setLastWithdrawTime(str(row, d, "lastWithdrawTime"));
            vo.setLastWithdrawAmount(num(row, d, "lastWithdrawAmount"));
            vo.setCumulativeWithdrawAmount(num(row, d, "cumulativeWithdrawAmount"));
            vo.setCumulativeWithdrawCount(toLong(num(row, d, "cumulativeWithdrawCount")));
            vo.setCumulativeGgr(num(row, d, "cumulativeGgr"));
            vo.setCumulativeNgr(num(row, d, "cumulativeNgr"));
            // 上游没给的派生值在这里补：注册→首存时长、流水倍数。分母为空或 0 时保持 null
            if (vo.getRegToFtdHours() == null)
            {
                vo.setRegToFtdHours(hoursBetween(vo.getRegisterTime(), vo.getFirstDepositTime()));
            }
            if (vo.getTurnoverMultiple() == null && vo.getCumulativeBetAmount() != null
                && vo.getCumulativeDepositAmount() != null && vo.getCumulativeDepositAmount().signum() > 0)
            {
                vo.setTurnoverMultiple(vo.getCumulativeBetAmount()
                    .divide(vo.getCumulativeDepositAmount(), 2, RoundingMode.HALF_UP));
            }
            return vo;
        });
    }

    private static Long toLong(BigDecimal value)
    {
        return value == null ? null : Long.valueOf(value.longValue());
    }

    private static String str(UdsRow row, UdsProperties.DetailDataset d, String code)
    {
        return row.str(d.getFields().get(code));
    }

    private static BigDecimal num(UdsRow row, UdsProperties.DetailDataset d, String code)
    {
        return row.num(d.getFields().get(code));
    }

    /** 取值并按 valueMaps 反查回本系统取值；反查不到的原值保留（未知状态不改名） */
    private static String decoded(UdsRow row, UdsProperties.DetailDataset d, String code)
    {
        String raw = str(row, d, code);
        Map<String, String> map = d.getValueMaps().get(code);
        if (raw == null || map == null)
        {
            return raw;
        }
        for (Map.Entry<String, String> e : map.entrySet())
        {
            if (sameValue(raw, e.getValue()))
            {
                return e.getKey();
            }
        }
        // 配了兜底取值的列（如存款状态：非 30 一律视为失败），反查不到的都归为兜底值
        String fallback = d.getValueDefaults().get(code);
        return fallback != null ? fallback : raw;
    }

    /** "30" 与 "30.0" 这类数值文本视为相等 */
    private static boolean sameValue(String a, String b)
    {
        if (Objects.equals(a, b))
        {
            return true;
        }
        try
        {
            return new BigDecimal(a.trim()).compareTo(new BigDecimal(b.trim())) == 0;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /** 两个 yyyy-MM-dd HH:mm[:ss] 时间相差的小时数（向下取整）；任一为空或解析失败返回 null */
    private static Integer hoursBetween(String from, String to)
    {
        if (StringUtils.isEmpty(from) || StringUtils.isEmpty(to))
        {
            return null;
        }
        try
        {
            LocalDateTime a = LocalDateTime.parse(normalizeTime(from), DATETIME);
            LocalDateTime b = LocalDateTime.parse(normalizeTime(to), DATETIME);
            return (int) ChronoUnit.HOURS.between(a, b);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static String normalizeTime(String text)
    {
        String t = text.trim().replace('T', ' ');
        if (t.length() == 16)
        {
            t = t + ":00";
        }
        return t.length() > 19 ? t.substring(0, 19) : t;
    }

    /**
     * 存款明细（dashboard_deposit_detail_v2）。amount 为原币、currency 为币种；
     * 完成时间取入账时间 bill_date_india，耗时取上游 duration_min，缺失时用两时间相减补算。
     */
    @Override
    public RecordPage<DepositRecordVO> queryDepositRecords(RecordPageRequest request)
    {
        return queryDetail("deposit", request, (row, d) -> {
            DepositRecordVO vo = new DepositRecordVO();
            vo.setOrderNo(str(row, d, "orderNo"));
            vo.setUserId(str(row, d, "userId"));
            vo.setUsername(str(row, d, "username"));
            vo.setAmount(num(row, d, "amount"));
            vo.setCurrency(str(row, d, "currency"));
            vo.setStatus(decoded(row, d, "status"));
            vo.setStatusLabel(OrderDict.label(OrderDict.DEPOSIT_STATUS, vo.getStatus()));
            vo.setCreateTime(str(row, d, "createTime"));
            vo.setFinishTime(str(row, d, "finishTime"));
            BigDecimal cost = num(row, d, "costMinutes");
            vo.setCostMinutes(cost != null ? cost : minutesBetween(vo.getCreateTime(), vo.getFinishTime()));
            return vo;
        });
    }

    /**
     * 提款明细（dashboard_withdraw_detail_v2）。审核状态与支付状态是两个字段，分别映射。
     */
    @Override
    public RecordPage<WithdrawRecordVO> queryWithdrawRecords(RecordPageRequest request)
    {
        return queryDetail("withdraw", request, (row, d) -> {
            WithdrawRecordVO vo = new WithdrawRecordVO();
            vo.setOrderNo(str(row, d, "orderNo"));
            vo.setUserId(str(row, d, "userId"));
            vo.setUsername(str(row, d, "username"));
            vo.setAmount(num(row, d, "amount"));
            vo.setCurrency(str(row, d, "currency"));
            vo.setStatus(decoded(row, d, "status"));
            vo.setStatusLabel(OrderDict.label(OrderDict.WITHDRAW_STATUS, vo.getStatus()));
            vo.setAuditStatus(decoded(row, d, "auditStatus"));
            vo.setAuditStatusLabel(OrderDict.label(OrderDict.AUDIT_STATUS, vo.getAuditStatus()));
            vo.setAuditor(str(row, d, "auditor"));
            vo.setPayer(str(row, d, "payer"));
            vo.setPayTime(str(row, d, "payTime"));
            vo.setBankName(str(row, d, "bankName"));
            vo.setBankCode(str(row, d, "bankCode"));
            vo.setBankCountry(str(row, d, "bankCountry"));
            vo.setBankCountryLabel(com.fivetech.dashboard.service.MemberDict.label(
                com.fivetech.dashboard.service.MemberDict.COUNTRY, vo.getBankCountry()));
            vo.setCreateTime(str(row, d, "createTime"));
            vo.setFinishTime(str(row, d, "finishTime"));
            BigDecimal cost = num(row, d, "costMinutes");
            vo.setCostMinutes(cost != null ? cost : minutesBetween(vo.getCreateTime(), vo.getFinishTime()));
            vo.setAuditNote(str(row, d, "auditNote"));
            return vo;
        });
    }

    /**
     * 投注明细（dashboard_bet_detail_v2）。金额都取基准币（default_*）字段；
     * 输赢取平台视角的 default_ggr（= 投注 − 派彩），上游没给时按投注 − 派彩补算。
     */
    @Override
    public RecordPage<BetRecordVO> queryBetRecords(RecordPageRequest request)
    {
        return queryDetail("bet", request, (row, d) -> {
            BetRecordVO vo = new BetRecordVO();
            vo.setOrderNo(str(row, d, "orderNo"));
            vo.setUserId(str(row, d, "userId"));
            vo.setUsername(str(row, d, "username"));
            vo.setVendorCode(str(row, d, "vendorCode"));
            vo.setVendorName(str(row, d, "vendorName"));
            vo.setGameType(decoded(row, d, "gameType"));
            vo.setGameTypeLabel(OrderDict.label(OrderDict.GAME_TYPE, vo.getGameType()));
            vo.setGameId(str(row, d, "gameId"));
            vo.setGameName(str(row, d, "gameName"));
            vo.setBetAmount(num(row, d, "betAmount"));
            vo.setPayout(num(row, d, "payout"));
            BigDecimal winLoss = num(row, d, "winLoss");
            if (winLoss == null && vo.getBetAmount() != null && vo.getPayout() != null)
            {
                winLoss = vo.getBetAmount().subtract(vo.getPayout());
            }
            vo.setWinLoss(winLoss);
            vo.setSettleStatus(decoded(row, d, "settleStatus"));
            vo.setSettleStatusLabel(OrderDict.label(OrderDict.SETTLE_STATUS, vo.getSettleStatus()));
            vo.setBetTime(str(row, d, "betTime"));
            vo.setSettleTime(str(row, d, "settleTime"));
            return vo;
        });
    }

    /** 两个时间相差的分钟数（一位小数）；任一为空或解析失败返回 null */
    private static BigDecimal minutesBetween(String from, String to)
    {
        if (StringUtils.isEmpty(from) || StringUtils.isEmpty(to))
        {
            return null;
        }
        try
        {
            LocalDateTime a = LocalDateTime.parse(normalizeTime(from), DATETIME);
            LocalDateTime b = LocalDateTime.parse(normalizeTime(to), DATETIME);
            long seconds = ChronoUnit.SECONDS.between(a, b);
            return seconds < 0 ? null
                : BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private UdsProperties.DetailDataset detailOf(String tab)
    {
        UdsProperties.DetailDataset detail = properties.getDetails().get(tab);
        return detail == null || StringUtils.isEmpty(detail.getDataset()) ? null : detail;
    }

    /**
     * 明细查询的公共流程：列表 + 同条件 COUNT 用一次 /v1/query/batch 发出。
     * <p>
     * 两项的 time、filters 完全相同；返回的 dataVersion 不同时整对重查（最多 {@value #VERSION_RETRIES} 次），
     * 不把不同版本的列表和总数拼在一起。相同 dataVersion 也不代表上游事务一致，只是能做到的最好。
     */
    private <T> RecordPage<T> queryDetail(String tab, RecordPageRequest request, RowMapper<T> mapper)
    {
        UdsProperties.DetailDataset detail = detailOf(tab);
        if (detail == null)
        {
            log.warn("[uds] 明细数据集未配置 tab={}，返回空结果", tab);
            return RecordPage.empty();
        }
        int pageSize = Math.max(1, request.getPageSize());
        long offset = (long) (Math.max(1, request.getPageNum()) - 1) * pageSize;
        if (offset > MAX_OFFSET)
        {
            throw new ServiceException("最多只能翻到第 " + MAX_OFFSET + " 条，请缩小时间范围或增加筛选条件");
        }
        // 页面分页 ≤ 1000 一次取完；同步导出一页要很多行时，按 1000 行一段往后翻（UDS 单次上限 1000）
        int firstLimit = Math.min(pageSize, MAX_LIMIT);
        List<Map<String, Object>> pair = new ArrayList<>();
        pair.add(detailBody(detail, request, DetailMode.LIST, firstLimit, offset));
        pair.add(detailBody(detail, request, DetailMode.COUNT, 0, 0));

        List<UdsQueryResult> results = client.queryBatch(pair);
        for (int retry = 0; retry < VERSION_RETRIES && !sameVersion(results); retry++)
        {
            log.info("[uds] 明细 {} 列表与 COUNT 的 dataVersion 不一致（{} / {}），整对重查",
                tab, results.get(0).getDataVersion(), results.get(1).getDataVersion());
            results = client.queryBatch(pair);
        }
        if (!sameVersion(results))
        {
            log.warn("[uds] 明细 {} 重查 {} 次后 dataVersion 仍不一致，按最后一次结果返回", tab, VERSION_RETRIES);
        }

        RecordPage<T> page = new RecordPage<>();
        List<T> list = new ArrayList<>();
        for (UdsRow row : results.get(0).getRows())
        {
            list.add(mapper.map(row, detail));
        }
        String version = results.get(0).getDataVersion();
        int lastSize = results.get(0).getRows().size();
        long next = offset + lastSize;
        while (list.size() < pageSize && lastSize == firstLimit && next <= MAX_OFFSET)
        {
            int limit = (int) Math.min(MAX_LIMIT, pageSize - list.size());
            UdsQueryResult more = client.queryWithMeta(detailBody(detail, request, DetailMode.LIST, limit, next));
            if (version != null && more.getDataVersion() != null && !version.equals(more.getDataVersion()))
            {
                // 翻页途中来源版本变了：后面的行与前面不是同一份数据，停下并标记截断，不混拼
                log.warn("[uds] 明细 {} 分段读取中 dataVersion 由 {} 变为 {}，停止读取", tab, version, more.getDataVersion());
                page.setTruncated(true);
                break;
            }
            for (UdsRow row : more.getRows())
            {
                list.add(mapper.map(row, detail));
            }
            lastSize = more.getRows().size();
            next += lastSize;
            if (lastSize < limit)
            {
                break;
            }
        }
        page.setRows(list);
        UdsRow count = singleRow(results.get(1), detail.getDataset());
        BigDecimal total = count == null ? null : count.num(detail.getCountMetric());
        page.setTotal(total == null ? list.size() + offset : total.longValue());
        // 没取全（超过 offset 上限、或导出行数上限小于总数）时标记截断，让前端 / 导出提示
        if (page.getTotal() > offset + list.size()
            && ((pageSize > MAX_LIMIT && list.size() >= pageSize) || next > MAX_OFFSET))
        {
            page.setTruncated(true);
        }
        return page;
    }

    private static boolean sameVersion(List<UdsQueryResult> results)
    {
        String a = results.get(0).getDataVersion();
        String b = results.get(1).getDataVersion();
        return a == null || b == null || a.equals(b);
    }

    private enum DetailMode
    {
        LIST, COUNT, EXPORT
    }

    private static Map<String, Object> filter(String field, String op, Object value)
    {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("field", field);
        f.put("op", op);
        f.put("value", value);
        return f;
    }

    private static Object filterValue(Object value)
    {
        return value instanceof LocalDateTime ? ((LocalDateTime) value).format(DATETIME) : value;
    }

    /** 筛选 / 排序用的 UDS 字段：filterFields 优先，其次 fields */
    private static String filterField(UdsProperties.DetailDataset d, String code)
    {
        String f = d.getFilterFields().get(code);
        return StringUtils.isNotEmpty(f) ? f : d.getFields().get(code);
    }

    /**
     * 本系统取值 → UDS 取值。配了 valueMaps 的列，映射外的取值直接报错——
     * 不把 UDS 不认识的取值发出去换回一张空表，让用户误以为「没有数据」。
     */
    private static Object encode(UdsProperties.DetailDataset d, String code, Object value)
    {
        Map<String, String> map = d.getValueMaps().get(code);
        if (map == null || value == null)
        {
            return value;
        }
        String mapped = map.get(String.valueOf(value));
        if (mapped == null)
        {
            throw new ServiceException("筛选条件 " + code + "=" + value + " 暂不支持（待数据团队提供完整枚举）");
        }
        return mapped.matches("-?\\d+") ? (Object) Long.valueOf(mapped) : mapped;
    }

    /**
     * 按兜底取值筛选：排除 valueMaps 里配置的每一个 UDS 取值。
     * 整数取值写成「&lt; v 或 ≥ v+1」，只用 UDS 已验证的 LT / GTE，不依赖未确认的 NE 运算符；
     * 非整数取值才退回 NE。例：存款 fail → status &lt; 30 OR status ≥ 31
     */
    private static List<Object> excludeMapped(UdsProperties.DetailDataset d, String code, String field)
    {
        List<Object> conditions = new ArrayList<>();
        Map<String, String> map = d.getValueMaps().get(code);
        if (map == null)
        {
            return conditions;
        }
        for (String v : map.values())
        {
            if (v != null && v.trim().matches("-?\\d+"))
            {
                long n = Long.parseLong(v.trim());
                Map<String, Object> or = new LinkedHashMap<>();
                or.put("or", List.of(filter(field, "LT", n), filter(field, "GTE", n + 1)));
                conditions.add(or);
            }
            else
            {
                conditions.add(filter(field, "NE", v));
            }
        }
        return conditions;
    }

    /** 多字段「或」检索：字段名以 ~ 结尾为 LIKE 包含匹配，否则 EQ */
    private static Map<String, Object> searchGroup(List<String> fields, String text)
    {
        // % 和 _ 是 LIKE 通配符，用户输入里的去掉，避免「输入 _ 匹配全部」
        String like = "%" + text.replace("%", "").replace("_", "") + "%";
        List<Map<String, Object>> any = new ArrayList<>();
        for (String f : fields)
        {
            if (f.endsWith("~"))
            {
                any.add(filter(f.substring(0, f.length() - 1), "LIKE", like));
            }
            else
            {
                any.add(filter(f, "EQ", text));
            }
        }
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("or", any);
        return group;
    }

    /**
     * 明细请求体（联调手册第 7、9 节）。分页查询、COUNT 与异步导出共用同一份组装逻辑，
     * 保证「看到的」「总数」和「导出的」完全同源。
     * <ul>
     *   <li>时间：uds_hh 小时主轴取覆盖区间的整点（to 闭区间），再在精确时间列上加 GTE from / LT to；
     *       区间条件落在其他时间列上时（如会员按首存时间下钻）不加主轴，避免按注册小时误裁；</li>
     *   <li>filters：JSON 条件树 {"and":[...]}，不拼 SQL；</li>
     *   <li>排序：主排序字段 + uds_rowkey ASC，翻页才稳定。</li>
     * </ul>
     */
    /**
     * 明细不带时间时的主轴：站点上线日 00:00 ~ 统计时区下一个整点（右开）。
     * 上线日取 dashboard.launch-date，解析失败时退回 2026-03-01。
     */
    private LocalDateTime[] fullAxis()
    {
        LocalDate launch;
        try
        {
            launch = LocalDate.parse(dashboard.getLaunchDate().trim());
        }
        catch (Exception e)
        {
            log.warn("[uds] dashboard.launch-date 无法解析（{}），明细主轴下界退回 2026-03-01", dashboard.getLaunchDate());
            launch = LocalDate.of(2026, 3, 1);
        }
        java.time.ZoneId zone;
        try
        {
            zone = java.time.ZoneId.of(dashboard.getTimezone());
        }
        catch (Exception e)
        {
            zone = java.time.ZoneId.of("Asia/Kolkata");
        }
        LocalDateTime to = LocalDateTime.now(zone).truncatedTo(ChronoUnit.HOURS).plusHours(1);
        return new LocalDateTime[] { launch.atStartOfDay(), to };
    }

    private Map<String, Object> detailBody(UdsProperties.DetailDataset d, RecordPageRequest request, DetailMode mode,
            int limit, long offset)
    {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataset", d.getDataset());
        if (mode == DetailMode.COUNT)
        {
            body.put("dimensions", new ArrayList<>());
        }
        else
        {
            Set<String> dims = new LinkedHashSet<>();
            dims.add(d.getRowKey());
            dims.addAll(d.getFields().values());
            // 排序 / 筛选用的替代字段（如基准币 default_amount）一并投影，避免按未投影字段排序
            dims.addAll(d.getFilterFields().values());
            body.put("dimensions", new ArrayList<>(dims));
        }
        body.put("metrics", List.of(d.getCountMetric()));

        List<Object> and = new ArrayList<>();
        LocalDateTime axisFrom = null;
        LocalDateTime axisTo = null;
        if (request.getFrom() != null && request.getTo() != null && StringUtils.isNotEmpty(d.getTimeColumn()))
        {
            axisFrom = request.getFrom();
            axisTo = request.getTo();
            and.add(filter(d.getTimeColumn(), "GTE", filterValue(request.getFrom())));
            and.add(filter(d.getTimeColumn(), "LT", filterValue(request.getTo())));
        }
        for (RecordPageRequest.Range range : request.getRanges())
        {
            String field = filterField(d, range.getField());
            if (StringUtils.isEmpty(field))
            {
                log.warn("[uds] 区间条件的列 {} 未配置字段映射，已忽略", range.getField());
                continue;
            }
            if (range.getFrom() != null)
            {
                and.add(filter(field, "GTE", filterValue(range.getFrom())));
            }
            if (range.getTo() != null)
            {
                and.add(filter(field, range.isToInclusive() ? "LTE" : "LT", filterValue(range.getTo())));
            }
            // 区间落在精确时间列上、两端都有：同时收窄 uds_hh 主轴，减少扫描
            if (axisFrom == null && field.equals(d.getTimeColumn())
                && range.getFrom() instanceof LocalDateTime && range.getTo() instanceof LocalDateTime)
            {
                axisFrom = (LocalDateTime) range.getFrom();
                axisTo = (LocalDateTime) range.getTo();
            }
        }
        request.getFilters().forEach((code, value) -> {
            if (value == null)
            {
                return;
            }
            List<String> search = d.getSearchFields().get(code);
            if (search != null && !search.isEmpty())
            {
                and.add(searchGroup(search, String.valueOf(value)));
                return;
            }
            String field = filterField(d, code);
            if (StringUtils.isEmpty(field))
            {
                log.warn("[uds] 筛选条件 {} 未配置字段映射，已忽略", code);
                return;
            }
            if (value instanceof java.util.Collection)
            {
                List<Object> values = new ArrayList<>();
                for (Object v : (java.util.Collection<?>) value)
                {
                    values.add(encode(d, code, v));
                }
                and.add(filter(field, "IN", values));
            }
            else if (String.valueOf(value).equals(d.getValueDefaults().get(code)))
            {
                and.addAll(excludeMapped(d, code, field));
            }
            else
            {
                and.add(filter(field, "EQ", encode(d, code, value)));
            }
        });
        if (StringUtils.isNotEmpty(request.getKeyword()))
        {
            List<String> search = d.getSearchFields().get("keyword");
            if (search == null || search.isEmpty())
            {
                log.warn("[uds] 明细 {} 未配置 search-fields.keyword，关键字已忽略", d.getDataset());
            }
            else
            {
                and.add(searchGroup(search, request.getKeyword().trim()));
            }
        }

        if (d.isSnapshot())
        {
            // 快照数据集：time 固定 now~now，按与数据团队的约定表示「返回全部数据」；
            // 注册时间等区间已在上面落到 time-column 筛选
            Map<String, Object> time = new LinkedHashMap<>();
            time.put("dimension", d.getTimeDimension());
            time.put("granularity", "HOUR");
            time.put("from", "now");
            time.put("to", "now");
            body.put("time", time);
            axisFrom = null;
            axisTo = null;
        }
        // UDS 的 time 是必填项：请求没带时间时，主轴补成「上线日 ~ 当前整点」，
        // 只限定扫描范围，不额外加时间列筛选，等价于不限时间
        else if (axisFrom == null || axisTo == null)
        {
            LocalDateTime[] all = fullAxis();
            axisFrom = all[0];
            axisTo = all[1];
        }
        if (axisFrom != null && axisTo != null && axisTo.isAfter(axisFrom))
        {
            Map<String, Object> time = new LinkedHashMap<>();
            time.put("dimension", d.getTimeDimension());
            time.put("granularity", "HOUR");
            LocalDateTime start = axisFrom.truncatedTo(ChronoUnit.HOURS);
            LocalDateTime end = axisTo.minusNanos(1).truncatedTo(ChronoUnit.HOURS);   // 含 to 所在小时的上一个整点
            time.put("from", start.format(DATETIME));
            time.put("to", (end.isBefore(start) ? start : end).format(DATETIME));
            body.put("time", time);
        }
        if (!and.isEmpty())
        {
            Map<String, Object> filters = new LinkedHashMap<>();
            filters.put("and", and);
            body.put("filters", filters);
        }

        if (mode == DetailMode.COUNT)
        {
            body.put("orderBy", new ArrayList<>());
            body.put("limit", 1);
            body.put("offset", 0);
        }
        else
        {
            List<Map<String, Object>> orderBy = new ArrayList<>();
            String sortField = StringUtils.isEmpty(request.getSortColumn()) ? null : filterField(d, request.getSortColumn());
            if (StringUtils.isEmpty(sortField))
            {
                sortField = d.getTimeColumn();
            }
            if (StringUtils.isNotEmpty(sortField) && !sortField.equals(d.getRowKey()))
            {
                orderBy.add(order(sortField, "asc".equalsIgnoreCase(request.getSortDirection()) ? "ASC" : "DESC"));
            }
            orderBy.add(order(d.getRowKey(), "ASC"));
            body.put("orderBy", orderBy);
            if (mode == DetailMode.LIST)
            {
                body.put("limit", Math.max(1, Math.min(MAX_LIMIT, limit)));
                body.put("offset", offset);
            }
        }
        Map<String, Object> options = new LinkedHashMap<>(d.getOptions());
        options.putIfAbsent("channel", "ONLINE");
        body.put("options", options);
        return body;
    }

    // ===================== 异步导出 =====================

    @Override
    public ExportJob submitDetailExport(String tab, RecordPageRequest request, long maxRows)
    {
        UdsProperties.DetailDataset detail = detailOf(tab);
        if (detail == null)
        {
            // 与分页查询不同：导出没有「返回空结果」这条退路，必须明确告诉用户
            throw new com.fivetech.common.exception.ServiceException(
                "明细数据集未配置（dashboard.gateway.uds.details." + tab + "），暂不能导出");
        }
        Map<String, Object> body = detailBody(detail, request, DetailMode.EXPORT, 0, 0);
        return submit(body, maxRows);
    }

    @Override
    public ExportJob submitBenchmarkExport(String merchantCode, boolean detailRows, long maxRows)
    {
        UdsProperties.Benchmark bench = properties.getBenchmark();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataset", bench.getDataset());
        body.put("dimensions", new ArrayList<>(detailRows ? bench.getDetailDimensions() : bench.getSummaryDimensions()));
        body.put("metrics", new ArrayList<>(bench.getMetrics()));
        Map<String, Object> time = new LinkedHashMap<>();
        time.put("dimension", bench.getTimeDimension());
        time.put("granularity", "DAY");
        time.put("from", bench.getDate());
        time.put("to", bench.getDate());
        body.put("time", time);
        if (StringUtils.isNotEmpty(merchantCode))
        {
            Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("field", bench.getMerchantField());
            filter.put("op", "EQ");
            filter.put("value", merchantCode);
            // 与明细查询一致：filters 是 JSON 条件树
            Map<String, Object> filters = new LinkedHashMap<>();
            filters.put("and", List.of(filter));
            body.put("filters", filters);
        }
        body.put("options", new LinkedHashMap<String, Object>());
        return submit(body, maxRows);
    }

    @Override
    public ExportJob getExportJob(String jobId)
    {
        return toExportJob(client.getJob(jobId));
    }

    @Override
    public ExportJob cancelExportJob(String jobId)
    {
        return toExportJob(client.cancelJob(jobId));
    }

    /** 统一补上导出通道与导出参数后提交 */
    @SuppressWarnings("unchecked")
    private ExportJob submit(Map<String, Object> body, long maxRows)
    {
        Map<String, Object> options = (Map<String, Object>) body.computeIfAbsent("options", k -> new LinkedHashMap<>());
        // 同步查询的 channel（ONLINE/ADHOC）必须覆盖成 EXPORT，否则 UDS 返回 4403
        options.put("channel", "EXPORT");
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("format", properties.getExportFormat());
        if (StringUtils.isNotEmpty(properties.getExportCompression()))
        {
            export.put("compression", properties.getExportCompression());
        }
        if (maxRows > 0)
        {
            export.put("maxRows", maxRows);
        }
        options.put("export", export);
        // 导出总量只由 options.export.maxRows 控制，列表的 limit / offset 不生效，去掉免得误导。
        // 时间参数始终是完整时间戳（导出的「只传日期」拒绝修复尚未部署，不能指望服务端纠错）
        body.remove("limit");
        body.remove("offset");
        ExportJob job = toExportJob(client.submitJob(body));
        log.info("[uds] 导出作业已提交 jobId={} status={} deduplicated={} dataset={} costTier={}",
            job.getJobId(), job.getStatus(), job.isDeduplicated(), body.get("dataset"), job.getCostTier());
        return job;
    }

    private ExportJob toExportJob(tools.jackson.databind.JsonNode root)
    {
        ExportJob job = new ExportJob();
        job.setJobId(text(root, "jobId"));
        job.setStatus(text(root, "status"));
        job.setDeduplicated(root.path("deduplicated").asBoolean(false));
        tools.jackson.databind.JsonNode estimate = root.path("estimate");
        job.setCostTier(text(estimate, "costTier"));
        job.setScanRowsEst(num(estimate, "scanRowsEst"));
        tools.jackson.databind.JsonNode result = root.path("result");
        if (result.isObject())
        {
            job.setRowCount(num(result, "rowCount"));
            job.setBytes(num(result, "bytes"));
            job.setExpiresAt(text(result, "expiresAt"));
            for (tools.jackson.databind.JsonNode f : result.path("files"))
            {
                ExportJob.ExportFile file = new ExportJob.ExportFile();
                file.setUrl(text(f, "url"));
                file.setKey(text(f, "key"));
                file.setBytes(num(f, "bytes"));
                file.setRows(num(f, "rows"));
                file.setUrlExpiresAt(text(f, "urlExpiresAt"));
                job.getFiles().add(file);
            }
        }
        tools.jackson.databind.JsonNode error = root.path("error");
        if (error.isObject())
        {
            job.setErrorCode(text(error, "code"));
            job.setErrorMessage(text(error, "message"));
        }
        tools.jackson.databind.JsonNode times = root.path("times");
        job.setCreatedAt(text(times, "createdAt"));
        job.setStartedAt(text(times, "startedAt"));
        job.setFinishedAt(text(times, "finishedAt"));
        return job;
    }

    private static String text(tools.jackson.databind.JsonNode node, String field)
    {
        tools.jackson.databind.JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }

    private static Long num(tools.jackson.databind.JsonNode node, String field)
    {
        tools.jackson.databind.JsonNode v = node.path(field);
        return v.isNumber() ? Long.valueOf(v.asLong()) : null;
    }


    // ===================== 水位退避 =====================


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
            List<String> dimensions, int limit, Map<String, Object> extras)
    {
        // 不再按缓存的上游水位预先截断：UDS 自报的 meta.freshness.watermark 不是权威完成信号
        // （联调手册第 8 节，部分数据集是手工 / 观察源上界），实测水位停在 09-30 21:00 时
        // UDS 仍接受并返回 10-05 的数据。预截断会让请求根本不发出去，水位也就永远刷新不了，
        // 整段区间被锁死成 null。所以总是按原区间先查，只有 UDS 真的返回 4403 时才截到水位或回退。
        LocalDateTime end = to;
        int retries = Math.max(0, properties.getNotReadyRetries());
        UdsQueryException last = null;
        boolean clampedToReported = false;
        // 小时事实表（uds_hh / India_DDHH）上卷到 DAY / WEEK 时允许部分日：截到水位所在的整点，
        // 不能按整天截——否则 09-30 21:00 的水位会把 09-30 当天已有的 21 个小时一起丢掉
        String alignUnit = "DATE".equalsIgnoreCase(dataset.getTimeFormat()) ? granularity : "HOUR";

        for (int attempt = 0; attempt <= retries; attempt++)
        {
            if (!end.isAfter(from))
            {
                log.warn("[uds] 区间 {} ~ {} 全部高于上游水位，dataset={}，本次不返回任何数据",
                    from, to, datasetName);
                return null;
            }
            Map<String, Object> body = baseBody(datasetName, dataset, codes,
                from, end, granularity, dimensions, limit, extras);
            try
            {
                UdsQueryResult result = client.queryWithMeta(body);
                // 只记上游自报的水位。本次请求的上界是我们自己填进去的参数，不是水位，不记
                rememberReportedWatermark(datasetName, result);
                remember(datasetName, result);
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
                LocalDateTime clamped = reported == null ? null : alignDown(from, reported, alignUnit);
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
     * 指标 / 榜单查询的请求体。
     *
     * @param extras 额外的顶层字段（orderBy、having、filters 等），为 null 时按默认：
     *               带时间维分组则按时间升序，否则不排序
     */
    private Map<String, Object> baseBody(String datasetName, UdsProperties.Dataset dataset,
            List<String> codes, LocalDateTime from, LocalDateTime to, String granularity,
            List<String> dimensions, int limit, Map<String, Object> extras)
    {
        List<String> metrics = new ArrayList<>();
        codes.forEach(code -> metrics.add(dataset.getMetrics().get(code)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataset", datasetName);
        body.put("dimensions", dimensions);
        body.put("metrics", metrics);
        body.put("time", timeNode(dataset, from, to, granularity));
        body.put("orderBy", new ArrayList<>());
        if (extras != null)
        {
            body.putAll(extras);
        }
        // limit 必须显式给：不传会落到上游默认值，长区间就会被悄悄截断
        body.put("limit", Math.min(MAX_LIMIT, Math.max(1, limit)));
        body.put("offset", 0);
        Map<String, Object> options = new LinkedHashMap<>(dataset.getOptions());
        options.putIfAbsent("channel", "ONLINE");
        body.put("options", options);
        return body;
    }

    /**
     * 时间节点（联调手册第 7 节）。本系统区间左闭右开，UDS 的 time.to 包含最后一个时间值：
     * <ul>
     *   <li>DATETIME（uds_hh / India_DDHH 小时列）：from 原样，to 回退一个整点。
     *       上卷到 DAY / WEEK 也写完整时间戳——只写日期会漏最后一天的小时，UDS 会拒绝（4001）；</li>
     *   <li>DATE（uds_dt）：只写日期，to 为最后一个包含的日期。</li>
     * </ul>
     */
    private Map<String, Object> timeNode(UdsProperties.Dataset dataset, LocalDateTime from, LocalDateTime to,
            String granularity)
    {
        Map<String, Object> time = new LinkedHashMap<>();
        time.put("dimension", dataset.getTimeDimension());
        time.put("granularity", granularity);
        if ("DATE".equalsIgnoreCase(dataset.getTimeFormat()))
        {
            LocalDate start = from.toLocalDate();
            LocalDate end = to.minusNanos(1).toLocalDate();
            time.put("from", start.format(DATE));
            time.put("to", (end.isBefore(start) ? start : end).format(DATE));
            return time;
        }
        time.put("from", from.format(DATETIME));
        LocalDateTime end = to.minusHours(1);
        if (end.isBefore(from))
        {
            end = from;
        }
        time.put("to", end.format(DATETIME));
        return time;
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
    private interface RowMapper<T>
    {
        T map(UdsRow row, UdsProperties.DetailDataset detail);
    }
}
