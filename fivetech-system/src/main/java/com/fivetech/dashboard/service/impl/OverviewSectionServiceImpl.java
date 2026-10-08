package com.fivetech.dashboard.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.domain.query.CohortQuery;
import com.fivetech.dashboard.domain.query.OverviewSectionQuery;
import com.fivetech.dashboard.domain.query.RankingBoardQuery;
import com.fivetech.dashboard.domain.query.RegChannelQuery;
import com.fivetech.dashboard.domain.vo.OverviewNoticeVO;
import com.fivetech.dashboard.domain.vo.overview.BonusBoardVO;
import com.fivetech.dashboard.domain.vo.overview.BonusItemVO;
import com.fivetech.dashboard.domain.vo.overview.CohortRowVO;
import com.fivetech.dashboard.domain.vo.overview.CohortTableVO;
import com.fivetech.dashboard.domain.vo.overview.CohortVO;
import com.fivetech.dashboard.domain.vo.overview.GameBoardVO;
import com.fivetech.dashboard.domain.vo.overview.GameItemVO;
import com.fivetech.dashboard.domain.vo.overview.OverviewSectionVO;
import com.fivetech.dashboard.domain.vo.overview.RankingBoardVO;
import com.fivetech.dashboard.domain.vo.overview.RegChannelGroupVO;
import com.fivetech.dashboard.domain.vo.overview.RegChannelItemVO;
import com.fivetech.dashboard.domain.vo.overview.RegChannelVO;
import com.fivetech.dashboard.domain.vo.overview.SectionSlotVO;
import com.fivetech.dashboard.enums.Granularity;
import com.fivetech.dashboard.gateway.BreakdownResult;
import com.fivetech.dashboard.gateway.DataFreshness;
import com.fivetech.dashboard.gateway.MetricDataGateway;
import com.fivetech.dashboard.gateway.MetricSlotRequest;
import com.fivetech.dashboard.gateway.uds.UdsQueryException;
import com.fivetech.dashboard.service.IOverviewSectionService;
import com.fivetech.dashboard.service.OrderDict;
import com.fivetech.dashboard.service.OverviewBreakdowns;

/**
 * 运营总览其余板块的实现：排行榜、留存与 LTV、注册渠道（用户与价值一期不做）。
 * <p>
 * 取数全部走 UDS {@code /v1/query}：
 * <ul>
 *   <li>和指标卡同口径的总额（赠金总额、全站投注额、注册人数）直接用 {@link MetricDataGateway#queryTotals}，
 *       保证「排行榜的分母 = 指标卡的值」；</li>
 *   <li>按维度拆开的部分用 {@link MetricDataGateway#queryBreakdown}，数据集在
 *       {@code dashboard.gateway.uds.breakdowns} 下配置。</li>
 * </ul>
 * 拆解数据集上游还没提供（见设计文档各节「待确认」），未配置时对应板块返回空结构 + notices，
 * <b>不抛异常、不填 0</b>：前端能区分「数据待接入」和「真的是 0」。
 *
 * @author fivetech
 */
@Service
public class OverviewSectionServiceImpl implements IOverviewSectionService
{
    private static final Logger log = LoggerFactory.getLogger(OverviewSectionServiceImpl.class);

    private static final DateTimeFormatter SLOT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final int MAX_SPAN_DAYS = 365;

    /** 留存与 LTV 一次最多 90 个分群日：UDS 分群查询的硬上限，超出整单返回 4001 */
    private static final int COHORT_MAX_DAYS = 90;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** 游戏榜固定 Top 20 */
    private static final int GAME_TOP_N = 20;

    /** 赠金项目固定展示 6 项：前 5 + 「其他」兜底 */
    private static final int BONUS_ITEMS = 6;

    private final DashboardProperties properties;

    private final MetricDataGateway gateway;

    public OverviewSectionServiceImpl(DashboardProperties properties, MetricDataGateway gateway)
    {
        this.properties = properties;
        this.gateway = gateway;
    }

    // =====================================================================
    // 排行榜
    // =====================================================================

    @Override
    public RankingBoardVO rankingBoard(RankingBoardQuery query)
    {
        RankingBoardVO vo = new RankingBoardVO();
        List<OverviewNoticeVO> notices = vo.getNotices();
        checkExport(query, null);

        LocalDateTime from = parseTime(query.getSlotFrom(), "slotFrom");
        LocalDateTime to = parseTime(query.getSlotTo(), "slotTo");
        checkRange(from, to);
        Granularity granularity = parseGranularity(query.getGranularity(), notices);
        checkAligned(from, to, granularity);
        // 游戏榜固定 Top 20（产品已定），Top N 由 UDS 按 orderBy + limit 截取（breakdowns.hot_games.limit）
        int topN = GAME_TOP_N;
        if (query.getTopN() != null && query.getTopN() != GAME_TOP_N)
        {
            notices.add(OverviewNoticeVO.info("PARAM_IGNORED", "topN 已忽略", "遊戲榜固定為 Top " + GAME_TOP_N));
        }
        ignoreCompare(query, notices);

        fillCommon(vo, query, slot(from.format(SLOT), to.format(SLOT), granularity.name()));
        Map<String, BigDecimal> totals = totals(query, from, to, granularity, notices, "bonus", "bet");

        vo.setBonus(buildBonus(totals.get("bonus"), breakdown(OverviewBreakdowns.BONUS_ITEMS, from, to,
            "贈金項目構成", notices), notices));
        vo.setGames(buildGames(totals.get("bet"), topN, breakdown(OverviewBreakdowns.HOT_GAMES, from, to,
            "熱銷遊戲", notices)));

        vo.setEmpty(vo.getBonus().getItems().isEmpty() && vo.getGames().getItems().isEmpty()
            && totals.get("bonus") == null && totals.get("bet") == null);
        refreshAsOf(vo, query);
        return vo;
    }

    private BonusBoardVO buildBonus(BigDecimal metricTotal, BreakdownResult bd, List<OverviewNoticeVO> notices)
    {
        BonusBoardVO board = new BonusBoardVO();
        board.setTotal(metricTotal);
        if (bd == null || !bd.isReady())
        {
            return board;
        }
        // 分母：同一赠金数据集的全量（dimensions=[]），不能用分页排名的合计。
        // 赠金榜（dashboard_bonus_ranking）与指标卡（overview）是两个数据集，不一致时只提示
        BigDecimal datasetTotal = bd.total("amount");
        // 按金额降序；超过 5 项时第 6 项起合并成「其他」，与原型固定 6 项一致。
        // 上游若自己给了 other 项，也并进「其他」，避免出现两个「其他」
        List<BreakdownResult.Row> rows = new ArrayList<>(bd.getRows());
        rows.sort(Comparator.comparing((BreakdownResult.Row r) -> nz(r.num("amount"))).reversed());
        List<BonusItemVO> items = new ArrayList<>();
        BigDecimal other = null;
        BigDecimal sum = BigDecimal.ZERO;
        for (BreakdownResult.Row r : rows)
        {
            BigDecimal amount = r.num("amount");
            sum = sum.add(nz(amount));
            boolean isOther = "other".equalsIgnoreCase(r.dim("code")) || ("其他".equals(r.dim("name")) || "Others".equalsIgnoreCase(r.dim("name")));
            if (!isOther && items.size() < BONUS_ITEMS - 1)
            {
                BonusItemVO item = new BonusItemVO();
                // 赠金榜只有项目名（bonus_name），没有独立编码时用名称作编码
                item.setCode(StringUtils.isEmpty(r.dim("code")) ? r.dim("name") : r.dim("code"));
                item.setName(bonusName(StringUtils.isEmpty(r.dim("name")) ? r.dim("code") : r.dim("name")));
                item.setAmount(amount);
                items.add(item);
            }
            else
            {
                other = nz(other).add(nz(amount));
            }
        }
        if (other != null)
        {
            BonusItemVO item = new BonusItemVO();
            item.setCode("other");
            item.setName("Others");
            item.setAmount(other);
            items.add(item);
        }
        BigDecimal denominator = datasetTotal != null ? datasetTotal : sum;
        if (datasetTotal != null && datasetTotal.compareTo(sum) > 0)
        {
            // 上游只返回了前 limit 项：剩余部分并入「其他」，保证各项之和 = 全量
            BigDecimal rest = datasetTotal.subtract(sum);
            BonusItemVO last = items.isEmpty() ? null : items.get(items.size() - 1);
            if (last != null && "other".equals(last.getCode()))
            {
                last.setAmount(nz(last.getAmount()).add(rest));
            }
            else
            {
                BonusItemVO item = new BonusItemVO();
                item.setCode("other");
                item.setName("Others");
                item.setAmount(rest);
                items.add(item);
            }
        }
        if (metricTotal != null && denominator.compareTo(metricTotal) != 0)
        {
            notices.add(OverviewNoticeVO.warn("BONUS_TOTAL_MISMATCH", "贈金榜全量與贈金總額不一致",
                "贈金榜全量 " + denominator.toPlainString() + "，指標卡贈金總額 " + metricTotal.toPlainString()
                    + "（兩者來自不同資料集，請核對時間區間與來源版本）"));
        }
        board.setTotal(denominator);
        for (int i = 0; i < items.size(); i++)
        {
            items.get(i).setRank(i + 1);
            items.get(i).setShare(pct(items.get(i).getAmount(), denominator, 2));
        }
        board.setItems(items);
        return board;
    }

    /** 「语言:文本」片段，语言形如 en、en-US、zh-CN、hi-IN */
    private static final java.util.regex.Pattern LOCALE_PART =
        java.util.regex.Pattern.compile("^([a-zA-Z]{2,3}(?:[-_][A-Za-z0-9]{2,4})?):(.*)$", java.util.regex.Pattern.DOTALL);

    /**
     * 赠金项目名：上游是多语言串「en-US:Sign-Up Bonus ₹500|hi-IN:साइन-अप बोनस ₹500」，
     * 只保留英文和中文，按「英文|中文」返回（例 happy|快乐）；缺哪种就只返回另一种，
     * 两种都没有时取第一段文本。不是「语言:文本」格式的名称原样返回。
     */
    static String bonusName(String raw)
    {
        if (StringUtils.isEmpty(raw) || !raw.contains(":"))
        {
            return raw;
        }
        String en = null;
        String zh = null;
        String first = null;
        for (String part : raw.split("\\|"))
        {
            java.util.regex.Matcher m = LOCALE_PART.matcher(part.trim());
            if (!m.matches())
            {
                continue;
            }
            String lang = m.group(1).toLowerCase(java.util.Locale.ROOT);
            String text = m.group(2).trim();
            if (text.isEmpty())
            {
                continue;
            }
            if (first == null)
            {
                first = text;
            }
            if (en == null && lang.startsWith("en"))
            {
                en = text;
            }
            else if (zh == null && lang.startsWith("zh"))
            {
                zh = text;
            }
        }
        if (en == null && zh == null)
        {
            return first != null ? first : raw;
        }
        if (en != null && zh != null)
        {
            return en + "|" + zh;
        }
        return en != null ? en : zh;
    }

    private GameBoardVO buildGames(BigDecimal totalBet, int topN, BreakdownResult bd)
    {
        GameBoardVO board = new GameBoardVO();
        board.setTopN(topN);
        board.setTotalBetAmount(totalBet);
        if (bd == null || !bd.isReady())
        {
            return board;
        }
        // Top N 由 UDS 按 bet_amount DESC + 稳定键排序、limit 截取；本地再排一次只是防御。
        // 分母：同一游戏数据集的全量投注额（不是指标卡的投注总额，两者数据集不同）
        if (bd.total("betAmount") != null)
        {
            totalBet = bd.total("betAmount");
            board.setTotalBetAmount(totalBet);
        }
        List<BreakdownResult.Row> rows = new ArrayList<>(bd.getRows());
        rows.sort(Comparator.comparing((BreakdownResult.Row r) -> nz(r.num("betAmount"))).reversed());
        BigDecimal topSum = BigDecimal.ZERO;
        List<GameItemVO> items = new ArrayList<>();
        for (BreakdownResult.Row r : rows)
        {
            if (items.size() >= topN)
            {
                break;
            }
            GameItemVO g = new GameItemVO();
            g.setRank(items.size() + 1);
            // 游戏名：上游（属性补全）没有值时用 game_id 填充，保证榜单每行都有可读的名称
            g.setName(StringUtils.isEmpty(r.dim("name")) ? r.dim("gameId") : r.dim("name"));
            g.setPlatformCode(r.dim("platformCode"));
            g.setVendorName(r.dim("vendorName"));
            g.setGameId(r.dim("gameId"));
            g.setGameTypeCode(r.dim("gameType"));
            g.setGameType(OrderDict.label(OrderDict.GAME_TYPE, r.dim("gameType")));
            g.setBetAmount(r.num("betAmount"));
            // 盈利率 = GGR ÷ 投注额（平台视角）
            g.setProfitRate(pctText(pct(r.num("ggr"), r.num("betAmount"), 2)));
            g.setRateForBetAmount(pctText(pct(r.num("betAmount"), totalBet, 2)));
            // bet_player_visits 是小时人数累加的「人次」，不是区间独立人数
            g.setBetUsers(toLong(r.num("betUsers")));
            g.setBetCount(toLong(r.num("betCount")));
            topSum = topSum.add(nz(r.num("betAmount")));
            items.add(g);
        }
        board.setItems(items);
        // Top N 覆盖率 = Top N 投注额之和 ÷ 全量投注额
        board.setTotalBetAmountPct(items.isEmpty() ? null : pctText(pct(topSum, totalBet, 2)));
        return board;
    }

    // =====================================================================
    // 留存与 LTV
    // =====================================================================

    @Override
    public CohortVO cohort(CohortQuery query)
    {
        CohortVO vo = new CohortVO();
        List<OverviewNoticeVO> notices = vo.getNotices();
        String type = StringUtils.isEmpty(query.getType()) ? "BOTH" : query.getType().trim().toUpperCase();
        if (!"BOTH".equals(type) && !"RETENTION".equals(type) && !"LTV".equals(type))
        {
            throw new ServiceException("type 取值应为 RETENTION / LTV / BOTH", 4005);
        }
        checkExport(query, "BOTH".equals(type) ? "导出时 type 只能是 RETENTION 或 LTV" : null);

        LocalDate from = parseDate(query.getSlotFrom(), "slotFrom");
        LocalDate to = parseDate(query.getSlotTo(), "slotTo");
        checkRange(from.atStartOfDay(), to.atStartOfDay());
        // slotTo 右开，分群日个数 = 两者相差的天数
        if (ChronoUnit.DAYS.between(from, to) > COHORT_MAX_DAYS)
        {
            throw new ServiceException("留存与 LTV 最多查询 " + COHORT_MAX_DAYS + " 天", 4001);
        }
        ignoreGranularity(query, notices);
        ignoreCompare(query, notices);

        // 队列是 T-1 快照：分群日最多到昨天，今天的分群还没有任何观察值
        LocalDate lastDay = today().minusDays(1);
        LocalDate end = to.isAfter(lastDay.plusDays(1)) ? lastDay.plusDays(1) : to;   // 右开
        fillCommon(vo, query, slot(from.format(YMD), to.format(YMD), null));
        vo.setAsOf(lastDay.plusDays(1).atStartOfDay().format(SLOT));
        if (end.isBefore(to))
        {
            notices.add(OverviewNoticeVO.info("SLOT_CLAMPED", "分群日截至 " + lastDay.format(YMD),
                "佇列為 T-1 快照，今日及以後的分群日沒有資料"));
        }

        boolean any = false;
        if (!"LTV".equals(type))
        {
            CohortTableVO t = cohortTable(OverviewBreakdowns.COHORT_RETENTION, "投注留存率", "FIRST_BET_DATE",
                "投注日", "投注人數", "PCT", OverviewBreakdowns.RETENTION_COLUMNS, from, end, lastDay, false, notices);
            vo.setRetention(t);
            vo.setRetentionColumns(new ArrayList<>(OverviewBreakdowns.RETENTION_COLUMNS.keySet()));
            any |= hasBase(t);
        }
        if (!"RETENTION".equals(type))
        {
            CohortTableVO t = cohortTable(OverviewBreakdowns.COHORT_LTV, "LTV", "FIRST_DEPOSIT_DATE",
                "首存日", "首存人數", "MONEY", OverviewBreakdowns.LTV_COLUMNS, from, end, lastDay, true, notices);
            vo.setLtv(t);
            vo.setLtvColumns(new ArrayList<>(OverviewBreakdowns.LTV_COLUMNS.keySet()));
            any |= hasBase(t);
        }
        vo.setEmpty(!any);
        return vo;
    }

    private CohortTableVO cohortTable(String key, String title, String cohortBy, String cohortLabel,
            String baseLabel, String format, Map<String, String> columns, LocalDate from, LocalDate end, LocalDate lastDay,
            boolean checkMonotonic, List<OverviewNoticeVO> notices)
    {
        CohortTableVO t = new CohortTableVO();
        t.setTitle(title);
        t.setCohortBy(cohortBy);
        t.setCohortLabel(cohortLabel);
        t.setBaseLabel(baseLabel);
        t.setValueFormat(format);
        if (!end.isAfter(from))
        {
            return t;
        }
        // TODO：队列数据集的时间维度是否就是分群日、DAY 粒度的 to 写法，待 UDS 提供数据集后确认
        BreakdownResult bd = breakdown(key, from.atStartOfDay(), end.atStartOfDay(), title, notices);
        Map<String, BreakdownResult.Row> byDate = new HashMap<>();
        if (bd != null && bd.isReady())
        {
            for (BreakdownResult.Row r : bd.getRows())
            {
                String d = r.dim("cohortDate");
                if (d != null)
                {
                    byDate.put(d.length() > 10 ? d.substring(0, 10) : d, r);
                }
            }
        }
        boolean monotonicBroken = false;
        // 区间内每一个分群日都出一行，上游缺的行 base 与值都是 null —— 行数稳定，前端矩阵不跳动。
        // 按分群日倒序（最近的在最前），与原型一致。行是按日期逐天生成、再按日期去上游结果里取值的，
        // 所以上游返回的行序不影响这里，顺序完全由这个循环决定
        for (LocalDate d = end.minusDays(1); !d.isBefore(from); d = d.minusDays(1))
        {
            CohortRowVO row = new CohortRowVO();
            row.setCohortDate(d.format(YMD));
            BreakdownResult.Row r = byDate.get(row.getCohortDate());
            row.setBase(r == null ? null : toLong(r.num("base")));
            List<BigDecimal> values = new ArrayList<>();
            BigDecimal prev = null;
            for (Map.Entry<String, String> col : columns.entrySet())
            {
                int days = OverviewBreakdowns.COHORT_DAYS.get(col.getKey());
                // 未到观察期一律 null：上游即使给了 0 也不采纳，0 会让留存曲线多出一段贴地的尾巴
                BigDecimal v = (r == null || d.plusDays(days).isAfter(lastDay)) ? null : r.num(col.getValue());
                // 服务层保留两位（导出用），页面接口在 Controller 里再按展示精度取整 / 1 位小数
                v = "MONEY".equals(format) ? com.fivetech.dashboard.format.MoneyScale.of(v)
                    : com.fivetech.dashboard.format.MoneyScale.pct2(v);
                if (checkMonotonic && v != null && prev != null && v.compareTo(prev) < 0)
                {
                    monotonicBroken = true;
                }
                if (v != null)
                {
                    prev = v;
                }
                values.add(v);
            }
            row.setValues(values);
            t.getRows().add(row);
        }
        if (monotonicBroken)
        {
            // LTV 是累计值，行内应单调不减；出现下降是上游数据问题，标出来但不自行修正
            notices.add(OverviewNoticeVO.warn("LTV_NOT_MONOTONIC", "LTV 出現下降", "累計 LTV 行內應單調不減，請聯絡資料平台核對"));
        }
        return t;
    }

    private static boolean hasBase(CohortTableVO t)
    {
        return t != null && t.getRows().stream().anyMatch(r -> r.getBase() != null);
    }

    // =====================================================================
    // 注册渠道
    // =====================================================================

    @Override
    public RegChannelVO regChannels(RegChannelQuery query)
    {
        RegChannelVO vo = new RegChannelVO();
        List<OverviewNoticeVO> notices = vo.getNotices();
        checkExport(query, null);

        LocalDateTime from = parseTime(query.getSlotFrom(), "slotFrom");
        LocalDateTime to = parseTime(query.getSlotTo(), "slotTo");
        checkRange(from, to);
        ignoreGranularity(query, notices);
        ignoreCompare(query, notices);
        fillCommon(vo, query, slot(from.format(SLOT), to.format(SLOT), null));

        BreakdownResult bd = breakdown(OverviewBreakdowns.REG_CHANNELS, from, to, "註冊渠道", notices);
        boolean ready = bd != null && bd.isReady();

        // 渠道：上游一行一个渠道。TODO：0 注册的渠道需要渠道维表补齐，上游只回有注册的渠道时这些渠道会缺席
        List<RegChannelItemVO> channels = new ArrayList<>();
        Map<String, Long> groupSum = new LinkedHashMap<>();
        Map<String, Integer> groupCount = new LinkedHashMap<>();
        // 分组完全以上游 Reg_channel 为准（目前为 official / agent / market），不预置固定分组：
        // 预置的分组上游不存在时只会多出注册数为 0 的空组
        Map<String, String> groupName = new LinkedHashMap<>();
        long total = 0;
        if (ready)
        {
            for (BreakdownResult.Row r : bd.getRows())
            {
                // NULL / 空渠道保留为「未知来源」，不映射成自然流量（联调手册第 10 节）
                String g = StringUtils.isEmpty(r.dim("groupCode")) ? "unknown" : r.dim("groupCode");
                String ch = StringUtils.isEmpty(r.dim("channelCode")) ? "unknown" : r.dim("channelCode");
                long n = nzLong(toLong(r.num("registrations")));
                if (!groupName.containsKey(g))
                {
                    groupName.put(g, "unknown".equals(g) ? "未知來源"
                        : StringUtils.isEmpty(r.dim("groupName")) ? g : r.dim("groupName"));
                }
                RegChannelItemVO item = new RegChannelItemVO();
                item.setCode(g + "|" + ch);
                item.setName(StringUtils.isEmpty(r.dim("channelName")) ? ("unknown".equals(ch) ? "未標記" : ch)
                    : r.dim("channelName"));
                item.setGroupCode(g);
                item.setRegistrations(n);
                channels.add(item);
                groupSum.merge(g, n, Long::sum);
                groupCount.merge(g, 1, Integer::sum);
                total += n;
            }
        }
        // 站点占比的分母用同数据集的全量注册（reg-total），子渠道筛选 / 行数截断都不改变它
        if (ready && bd.total("registrations") != null)
        {
            long full = bd.total("registrations").longValue();
            if (full != total)
            {
                notices.add(OverviewNoticeVO.info("REG_SOURCE_PARTIAL", "渠道明細之和小於全量註冊",
                    "渠道合計 " + total + "，全量 " + full + "（超出行數上限的渠道未列出）"));
            }
            total = full;
        }
        final long tot = total;
        channels.forEach(c -> {
            c.setGroupName(groupName.get(c.getGroupCode()));
            c.setShareOfTotal(pctOrZero(c.getRegistrations(), tot));
            c.setShareOfGroup(pctOrZero(c.getRegistrations(), groupSum.get(c.getGroupCode())));
        });
        channels.sort(Comparator.comparing(RegChannelItemVO::getRegistrations).reversed()
            .thenComparing(RegChannelItemVO::getName, Comparator.nullsLast(Comparator.naturalOrder())));

        List<RegChannelGroupVO> groups = new ArrayList<>();
        groupName.forEach((code, name) -> {
            RegChannelGroupVO g = new RegChannelGroupVO();
            g.setCode(code);
            g.setName(name);
            g.setRegistrations(ready ? groupSum.getOrDefault(code, 0L) : null);
            g.setShare(ready ? pctOrZero(groupSum.getOrDefault(code, 0L), tot) : null);
            g.setChannelCount(groupCount.getOrDefault(code, 0));
            groups.add(g);
        });
        groups.sort(Comparator.comparing((RegChannelGroupVO g) -> g.getRegistrations() == null ? 0L : g.getRegistrations())
            .reversed());

        vo.setChannels(channels);
        vo.setGroups(groups);
        vo.setTotalRegistrations(ready ? Long.valueOf(total) : null);

        // 恒等校验：各渠道之和应等于指标卡「注册人数」。不等只提示，不改数
        if (ready)
        {
            BigDecimal reg = totals(query, from, to, Granularity.HOUR, notices, "reg").get("reg");
            if (reg != null && reg.longValue() != total)
            {
                notices.add(OverviewNoticeVO.warn("REG_TOTAL_MISMATCH", "渠道註冊數之和與註冊人數不一致",
                    "渠道合計 " + total + "，指標卡註冊人數 " + reg.toPlainString()));
            }
        }
        vo.setEmpty(!ready || total == 0);
        refreshAsOf(vo, query);
        return vo;
    }

    // =====================================================================
    // 公共
    // =====================================================================

    /**
     * 导出开关。TODO：四个板块的导出（文档写的是「返回导出任务」）尚未实现，
     * 待确认走同步 xlsx（与主要指标一致）还是任务中心后补齐。
     */
    private void checkExport(OverviewSectionQuery query, String invalidReason)
    {
        if (!query.isExport())
        {
            return;
        }
        if (invalidReason != null)
        {
            throw new ServiceException(invalidReason, 4005);
        }
        throw new ServiceException("该板块的导出功能暂未开放", 5010);
    }

    private void fillCommon(OverviewSectionVO vo, OverviewSectionQuery query, SectionSlotVO slot)
    {
        vo.setSlot(slot);
        vo.setCurrency(properties.getCurrency());
        vo.setTimezone(properties.getTimezone());
        refreshAsOf(vo, query);
    }

    /** 区间聚合类板块的 asOf 取上游水位；取数后水位才有值，所以取数结束再刷新一次 */
    private void refreshAsOf(OverviewSectionVO vo, OverviewSectionQuery query)
    {
        DataFreshness f = gateway.getFreshness(site(query));
        if (f != null)
        {
            vo.setAsOf(f.getAsOf() == null ? null : f.getAsOf().format(SLOT));
            vo.setUpdatedAt(f.getUpdatedAt() == null ? null : f.getUpdatedAt().format(SLOT));
        }
    }

    /** 拆解查询。未配置 / 未就绪 / 上游故障都转成 notices，返回 null 或未就绪结果，不让整页失败 */
    private BreakdownResult breakdown(String key, LocalDateTime from, LocalDateTime to, String label,
            List<OverviewNoticeVO> notices)
    {
        try
        {
            BreakdownResult r = gateway.queryBreakdown(key, from, to);
            if (!r.isConfigured())
            {
                notices.add(OverviewNoticeVO.warn("DATA_NOT_CONNECTED", label + "資料待接入",
                    "資料平台尚未提供該資料集（dashboard.gateway.uds.breakdowns." + key + "）"));
            }
            else if (!r.isReady())
            {
                notices.add(OverviewNoticeVO.warn("DATA_NOT_READY", label + "資料尚未就緒",
                    "所選區間高於資料平台水位"));
            }
            return r;
        }
        catch (UdsQueryException e)
        {
            log.error("[overview-section] 拆解查询失败 key={} 区间={}~{}：{}", key, from, to, e.getMessage(), e);
            notices.add(OverviewNoticeVO.warn("DATA_LAGGING", label + "資料暫時無法取得", e.getMessage()));
            return null;
        }
    }

    /** 与指标卡同口径的区间合计；失败时对应值为 null 并进 notices */
    private Map<String, BigDecimal> totals(OverviewSectionQuery query, LocalDateTime from, LocalDateTime to,
            Granularity granularity, List<OverviewNoticeVO> notices, String... codes)
    {
        Map<String, BigDecimal> empty = new HashMap<>();
        try
        {
            MetricSlotRequest req = new MetricSlotRequest();
            req.setSiteCode(site(query));
            req.setFrom(from);
            req.setTo(to);
            req.setGranularity(granularity);
            List<String> list = new ArrayList<>();
            Collections.addAll(list, codes);
            req.setMetricCodes(list);
            Map<String, BigDecimal> result = gateway.queryTotals(req);
            return result == null ? empty : result;
        }
        catch (UdsQueryException e)
        {
            log.error("[overview-section] 合计查询失败 codes={} 区间={}~{}：{}", String.join(",", codes), from, to,
                e.getMessage(), e);
            notices.add(OverviewNoticeVO.warn("DATA_LAGGING", "資料平台暫時無法使用", e.getMessage()));
            return empty;
        }
    }

    private void ignoreCompare(OverviewSectionQuery query, List<OverviewNoticeVO> notices)
    {
        if (StringUtils.isNotEmpty(query.getCompareType()) || StringUtils.isNotEmpty(query.getCompareFrom())
            || StringUtils.isNotEmpty(query.getCompareTo()))
        {
            notices.add(OverviewNoticeVO.info("PARAM_IGNORED", "已忽略對比區間", "該板塊不做環比"));
        }
    }

    private void ignoreGranularity(OverviewSectionQuery query, List<OverviewNoticeVO> notices)
    {
        if (StringUtils.isNotEmpty(query.getGranularity()))
        {
            notices.add(OverviewNoticeVO.info("PARAM_IGNORED", "已忽略 granularity", "該板塊返回區間聚合或快照，沒有粒度可選"));
        }
    }

    private Granularity parseGranularity(String text, List<OverviewNoticeVO> notices)
    {
        if (StringUtils.isEmpty(text))
        {
            return Granularity.HOUR;
        }
        try
        {
            return Granularity.valueOf(text.trim().toUpperCase());
        }
        catch (IllegalArgumentException e)
        {
            throw new ServiceException("granularity 取值应为 HOUR / DAY / WEEK，实际为：" + text, 4001);
        }
    }

    /** 区间边界须落在粒度的整格上：HOUR 为整点，DAY / WEEK 为 00:00 */
    private void checkAligned(LocalDateTime from, LocalDateTime to, Granularity g)
    {
        boolean ok = g == Granularity.HOUR
            ? from.getMinute() == 0 && to.getMinute() == 0
            : from.toLocalTime().equals(java.time.LocalTime.MIDNIGHT) && to.toLocalTime().equals(java.time.LocalTime.MIDNIGHT);
        if (!ok)
        {
            throw new ServiceException("区间边界与粒度 " + g.name() + " 不对齐", 4001);
        }
    }

    private void checkRange(LocalDateTime from, LocalDateTime to)
    {
        if (!to.isAfter(from))
        {
            throw new ServiceException("slotFrom 必须早于 slotTo（区间左闭右开）", 4001);
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_SPAN_DAYS)
        {
            throw new ServiceException("查询区间不得超过 " + MAX_SPAN_DAYS + " 天", 4001);
        }
        if (to.toLocalDate().isBefore(launchDate()))
        {
            throw new ServiceException("区间不能早于站点上线日 " + properties.getLaunchDate(), 4001);
        }
    }

    private LocalDate launchDate()
    {
        try
        {
            return LocalDate.parse(properties.getLaunchDate(), YMD);
        }
        catch (Exception e)
        {
            return LocalDate.MIN;
        }
    }

    private LocalDate today()
    {
        return LocalDate.now(ZoneId.of(properties.getTimezone()));
    }

    /** 接受 yyyy-MM-dd HH:mm 或 yyyy-MM-dd（按 00:00） */
    private static LocalDateTime parseTime(String text, String field)
    {
        if (StringUtils.isEmpty(text))
        {
            throw new ServiceException(field + " 必填，格式 yyyy-MM-dd HH:mm", 4001);
        }
        String t = text.trim();
        try
        {
            return t.length() <= 10 ? LocalDate.parse(t, YMD).atStartOfDay() : LocalDateTime.parse(t, SLOT);
        }
        catch (DateTimeParseException e)
        {
            throw new ServiceException(field + " 格式错误，应为 yyyy-MM-dd HH:mm，实际为：" + text, 4001);
        }
    }

    /** 接受 yyyy-MM-dd；传了时间部分时只取日期 */
    private static LocalDate parseDate(String text, String field)
    {
        if (StringUtils.isEmpty(text))
        {
            throw new ServiceException(field + " 必填，格式 yyyy-MM-dd", 4001);
        }
        String t = text.trim();
        try
        {
            return LocalDate.parse(t.length() > 10 ? t.substring(0, 10) : t, YMD);
        }
        catch (DateTimeParseException e)
        {
            throw new ServiceException(field + " 格式错误，应为 yyyy-MM-dd，实际为：" + text, 4001);
        }
    }

    private static SectionSlotVO slot(String from, String to, String granularity)
    {
        SectionSlotVO s = new SectionSlotVO();
        s.setFrom(from);
        s.setTo(to);
        s.setGranularity(granularity);
        return s;
    }

    private String site(OverviewSectionQuery query)
    {
        return StringUtils.isEmpty(query.getSiteCode()) ? properties.getDefaultSite() : query.getSiteCode();
    }

    /** 百分比，分子或分母为空、分母为 0 时返回 null */
    private static BigDecimal pct(Number a, Number b, int scale)
    {
        if (a == null || b == null)
        {
            return null;
        }
        BigDecimal den = new BigDecimal(b.toString());
        if (den.signum() == 0)
        {
            return null;
        }
        return new BigDecimal(a.toString()).multiply(HUNDRED).divide(den, scale, RoundingMode.HALF_UP);
    }

    /** 注册渠道的占比：分母为 0 时返回 0.0（区间内确实没人注册） */
    private static BigDecimal pctOrZero(Long a, Long b)
    {
        BigDecimal v = pct(a, b, 2);
        return v == null ? BigDecimal.ZERO.setScale(2) : v;
    }

    private static String pctText(BigDecimal v)
    {
        return v == null ? null : v.toPlainString() + "%";
    }

    private static BigDecimal nz(BigDecimal v)
    {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static long nzLong(Long v)
    {
        return v == null ? 0L : v;
    }

    private static Long toLong(BigDecimal v)
    {
        return v == null ? null : Long.valueOf(v.setScale(0, RoundingMode.HALF_UP).longValue());
    }
}
