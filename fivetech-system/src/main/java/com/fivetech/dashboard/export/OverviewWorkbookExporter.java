package com.fivetech.dashboard.export;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.poi.ss.usermodel.Sheet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.domain.query.CohortQuery;
import com.fivetech.dashboard.domain.query.OverviewExportQuery;
import com.fivetech.dashboard.domain.query.OverviewQuery;
import com.fivetech.dashboard.domain.query.RankingBoardQuery;
import com.fivetech.dashboard.domain.query.RegChannelQuery;
import com.fivetech.dashboard.domain.vo.MetricCardVO;
import com.fivetech.dashboard.domain.vo.MetricsBlockVO;
import com.fivetech.dashboard.domain.vo.OverviewNoticeVO;
import com.fivetech.dashboard.domain.vo.OverviewVO;
import com.fivetech.dashboard.domain.vo.overview.BonusItemVO;
import com.fivetech.dashboard.domain.vo.overview.CohortRowVO;
import com.fivetech.dashboard.domain.vo.overview.CohortTableVO;
import com.fivetech.dashboard.domain.vo.overview.CohortVO;
import com.fivetech.dashboard.domain.vo.overview.GameItemVO;
import com.fivetech.dashboard.domain.vo.overview.OverviewSectionVO;
import com.fivetech.dashboard.domain.vo.overview.RankingBoardVO;
import com.fivetech.dashboard.domain.vo.overview.RegChannelGroupVO;
import com.fivetech.dashboard.domain.vo.overview.RegChannelItemVO;
import com.fivetech.dashboard.domain.vo.overview.RegChannelVO;
import com.fivetech.dashboard.enums.CompareType;
import com.fivetech.dashboard.enums.Granularity;
import com.fivetech.dashboard.export.ExportTemplate.Book;
import com.fivetech.dashboard.export.ExportTemplate.Col;
import com.fivetech.dashboard.service.IOverviewSectionService;
import com.fivetech.dashboard.service.IOverviewService;

/**
 * 运营总览整页导出（《导出模板样例-v1_5》「總覽-」9 个工作表）：
 * <ol>
 *   <li>導出說明 —— 导出时间、导出人、站点、筛选条件、免责声明（另附本次的提示与取数失败）；</li>
 *   <li>指標快照 —— 一行一个指标：统计 / 对比起止、当前值、单位、上期值、变化率、口径；</li>
 *   <li>時間序列 —— 一行一个时间片、一列一个指标；</li>
 *   <li>註冊渠道、贈金項目結構、熱銷遊戲 —— 每行带「統計開始 / 統計結束」；</li>
 *   <li>投注留存率矩陣、LTV矩陣 —— 每行带「資料截至」；</li>
 *   <li>口徑說明 —— 每列一行，同一页面每次导出内容相同。</li>
 * </ol>
 * <p>第 1 行即表头，表头上方、表格下方都不加说明行。某块取数失败时对应工作表只留表头，
 * 原因写在「導出說明」里，其余工作表照常导出。</p>
 *
 * @author fivetech
 */
@Component
public class OverviewWorkbookExporter
{
    private static final Logger log = LoggerFactory.getLogger(OverviewWorkbookExporter.class);

    public static final String PAGE = "運營總覽";

    private static final String S_INFO = "導出說明";

    private static final String S_SNAPSHOT = "指標快照";

    private static final String S_SERIES = "時間序列";

    private static final String S_REG = "註冊渠道";

    private static final String S_BONUS = "贈金項目結構";

    private static final String S_GAMES = "熱銷遊戲";

    private static final String S_RETENTION = "投注留存率矩陣";

    private static final String S_LTV = "LTV矩陣";

    private static final String S_NOTES = "口徑說明";

    private static final DateTimeFormatter SLOT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 留存与 LTV 一次最多 90 个分群日（UDS 分群查询上限） */
    private static final int COHORT_MAX_DAYS = 90;

    private final IOverviewService overviewService;

    private final IOverviewSectionService sectionService;

    private final DashboardProperties properties;

    public OverviewWorkbookExporter(IOverviewService overviewService, IOverviewSectionService sectionService,
            DashboardProperties properties)
    {
        this.overviewService = overviewService;
        this.sectionService = sectionService;
        this.properties = properties;
    }

    /** 一个接口的取数结果：成功时 data 有值，失败时 error 有值 */
    private static final class Part<T>
    {
        private T data;

        private String error;
    }

    private <T> Part<T> fetch(String name, Supplier<T> supplier)
    {
        Part<T> part = new Part<>();
        try
        {
            part.data = supplier.get();
        }
        catch (RuntimeException e)
        {
            log.warn("[overview-export] {} 取数失败：{}", name, e.getMessage());
            part.error = e instanceof com.fivetech.dashboard.gateway.uds.UdsQueryException
                ? ((com.fivetech.dashboard.gateway.uds.UdsQueryException) e).userMessage() : e.getMessage();
        }
        return part;
    }

    /**
     * 取数并生成工作簿。
     */
    public byte[] export(OverviewExportQuery query)
    {
        validate(query);

        Part<OverviewVO> core = fetch(S_SNAPSHOT, () -> overviewService.query(overviewQuery(query)));
        Part<RegChannelVO> reg = fetch(S_REG, () -> sectionService.regChannels(regQuery(query)));
        Part<RankingBoardVO> ranking = fetch("排行榜", () -> sectionService.rankingBoard(rankingQuery(query)));
        CohortQuery cq = cohortQuery(query);
        Part<CohortVO> cohort = fetch("留存與 LTV", () -> sectionService.cohort(cq));

        try (Book book = new Book(false))
        {
            book.info(S_INFO, info(query, cq, core, reg, ranking, cohort));
            writeSnapshot(book, core);
            writeSeries(book, core);
            writeReg(book, query, reg);
            writeBonus(book, query, ranking);
            writeGames(book, query, ranking);
            writeCohort(book, S_RETENTION, "投注日", "投注人數", "(%)", "0.00", cohort,
                cohort.data == null ? null : cohort.data.getRetention(),
                cohort.data == null ? null : cohort.data.getRetentionColumns());
            writeCohort(book, S_LTV, "首存日", "首存人數", "(INR)", "0.00", cohort,
                cohort.data == null ? null : cohort.data.getLtv(),
                cohort.data == null ? null : cohort.data.getLtvColumns());
            book.notes(S_NOTES, notes(cohort.data));
            return book.toBytes();
        }
    }

    /** 文件名：運營總覽-yyyyMMdd-HHmmss.xlsx */
    public String fileName(OverviewExportQuery query)
    {
        return ExportTemplate.fileName(properties, PAGE);
    }

    // ===================== 参数 =====================

    private void validate(OverviewExportQuery query)
    {
        LocalDateTime from = parseSlot(query.getSlotFrom(), "slotFrom");
        LocalDateTime to = parseSlot(query.getSlotTo(), "slotTo");
        if (!to.isAfter(from))
        {
            throw new ServiceException("slotTo 必须晚于 slotFrom（左闭右开）", 4001);
        }
    }

    private static LocalDateTime parseSlot(String text, String field)
    {
        try
        {
            return LocalDateTime.parse(text.trim(), SLOT);
        }
        catch (Exception e)
        {
            throw new ServiceException(field + " 格式应为 yyyy-MM-dd HH:mm，实际为：" + text, 4001);
        }
    }

    private static Granularity granularity(OverviewExportQuery q)
    {
        return StringUtils.isEmpty(q.getGranularity()) ? Granularity.HOUR : Granularity.valueOf(q.getGranularity());
    }

    private OverviewQuery overviewQuery(OverviewExportQuery q)
    {
        OverviewQuery o = new OverviewQuery();
        o.setSiteCode(q.getSiteCode());
        o.setSlotFrom(q.getSlotFrom());
        o.setSlotTo(q.getSlotTo());
        o.setGranularity(granularity(q));
        o.setCompareType(StringUtils.isEmpty(q.getCompareType()) ? CompareType.PREV_PERIOD : CompareType.valueOf(q.getCompareType()));
        o.setCompareFrom(q.getCompareFrom());
        o.setCompareTo(q.getCompareTo());
        o.setMetrics(new ArrayList<>(q.getMetrics()));
        o.setBlocks(new ArrayList<>(List.of("METRICS")));
        o.setIncludeSeries(true);
        return o;
    }

    private RegChannelQuery regQuery(OverviewExportQuery q)
    {
        RegChannelQuery r = new RegChannelQuery();
        r.setSiteCode(q.getSiteCode());
        r.setSlotFrom(q.getSlotFrom());
        r.setSlotTo(q.getSlotTo());
        return r;
    }

    private RankingBoardQuery rankingQuery(OverviewExportQuery q)
    {
        RankingBoardQuery r = new RankingBoardQuery();
        r.setSiteCode(q.getSiteCode());
        r.setSlotFrom(q.getSlotFrom());
        r.setSlotTo(q.getSlotTo());
        r.setGranularity(granularity(q).name());
        return r;
    }

    /**
     * 留存与 LTV 的分群日区间（左闭右开，日期）：传了 cohortFrom / cohortTo 就用它们；否则取 slot 的日期；
     * 没有已完结的分群日时改为截至昨天的最近 30 天；超过 90 天时保留最近 90 天。
     */
    private CohortQuery cohortQuery(OverviewExportQuery q)
    {
        LocalDate from;
        LocalDate to;
        if (StringUtils.isNotEmpty(q.getCohortFrom()) && StringUtils.isNotEmpty(q.getCohortTo()))
        {
            from = LocalDate.parse(q.getCohortFrom().trim(), YMD);
            to = LocalDate.parse(q.getCohortTo().trim(), YMD);
        }
        else
        {
            LocalDateTime slotFrom = LocalDateTime.parse(q.getSlotFrom().trim(), SLOT);
            LocalDateTime slotTo = LocalDateTime.parse(q.getSlotTo().trim(), SLOT);
            from = slotFrom.toLocalDate();
            to = slotTo.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)
                ? slotTo.toLocalDate() : slotTo.toLocalDate().plusDays(1);
            LocalDate today = LocalDate.now(ZoneId.of(properties.getTimezone()));
            if (!from.isBefore(today))
            {
                to = today;
                from = today.minusDays(30);
            }
        }
        if (ChronoUnit.DAYS.between(from, to) > COHORT_MAX_DAYS)
        {
            from = to.minusDays(COHORT_MAX_DAYS);
        }
        CohortQuery c = new CohortQuery();
        c.setSiteCode(q.getSiteCode());
        c.setSlotFrom(from.format(YMD));
        c.setSlotTo(to.format(YMD));
        c.setType("BOTH");
        return c;
    }

    // ===================== 導出說明 =====================

    private Map<String, String> info(OverviewExportQuery q, CohortQuery cq, Part<OverviewVO> core,
            Part<RegChannelVO> reg, Part<RankingBoardVO> ranking, Part<CohortVO> cohort)
    {
        StringBuilder filter = new StringBuilder();
        filter.append("時間範圍 ").append(ExportTemplate.range(q.getSlotFrom(), q.getSlotTo()));
        filter.append(" · 粒度 ").append(granularityText(granularity(q)));
        if (core.data != null && core.data.getCompare() != null)
        {
            filter.append(" · 對比 ").append(compareText(core.data.getCompare().getType()))
                .append("（").append(ExportTemplate.range(core.data.getCompare().getFrom(), core.data.getCompare().getTo()))
                .append("）");
        }
        else
        {
            CompareType type = StringUtils.isEmpty(q.getCompareType()) ? CompareType.PREV_PERIOD
                : CompareType.valueOf(q.getCompareType());
            filter.append(" · 對比 ").append(compareText(type));
        }
        LocalDate cohortEnd = LocalDate.parse(cq.getSlotTo(), YMD).minusDays(1);
        filter.append(" · 留存與 LTV 分群日 ").append(cq.getSlotFrom()).append(" – ").append(cohortEnd.format(YMD));
        if (!q.getMetrics().isEmpty())
        {
            filter.append(" · 指標 ").append(q.getMetrics().size()).append(" 項");
        }

        Map<String, String> extra = new LinkedHashMap<>();
        List<String> failed = new ArrayList<>();
        addFailure(failed, S_SNAPSHOT + "、" + S_SERIES, core.error);
        addFailure(failed, S_REG, reg.error);
        addFailure(failed, S_BONUS + "、" + S_GAMES, ranking.error);
        addFailure(failed, S_RETENTION + "、" + S_LTV, cohort.error);
        if (!failed.isEmpty())
        {
            extra.put("取數失敗", String.join("；", failed));
        }
        Set<String> tips = new LinkedHashSet<>();
        collect(tips, core.data == null ? null : core.data.getNotices());
        collect(tips, sectionNotices(reg.data));
        collect(tips, sectionNotices(ranking.data));
        collect(tips, sectionNotices(cohort.data));
        if (!tips.isEmpty())
        {
            extra.put("提示", String.join("；", tips));
        }
        return ExportTemplate.exportInfo(properties, q.getSiteCode(), filter.toString(), extra);
    }

    private static void addFailure(List<String> out, String sheets, String error)
    {
        if (error != null)
        {
            out.add(sheets + "：" + ExportTemplate.t(error));
        }
    }

    private static List<OverviewNoticeVO> sectionNotices(OverviewSectionVO vo)
    {
        return vo == null ? null : vo.getNotices();
    }

    private static void collect(Set<String> out, List<OverviewNoticeVO> notices)
    {
        if (notices == null)
        {
            return;
        }
        for (OverviewNoticeVO n : notices)
        {
            if (StringUtils.isNotEmpty(n.getMessage()))
            {
                out.add(ExportTemplate.t(n.getMessage()));
            }
        }
    }

    private static String granularityText(Granularity g)
    {
        if (g == null)
        {
            return "小時";
        }
        switch (g)
        {
            case DAY:
                return "日";
            case HOUR:
                return "小時";
            default:
                return "週";
        }
    }

    private static String compareText(CompareType type)
    {
        if (type == null)
        {
            return "無";
        }
        switch (type)
        {
            case PREV_PERIOD:
                return "上一週期";
            case LAST_YEAR:
                return "去年同期";
            case CUSTOM:
                return "自訂";
            default:
                return "無";
        }
    }

    // ===================== 指標快照 / 時間序列 =====================

    /** 指标卡按模板顺序排列；模板之外的指标排在后面 */
    private static List<MetricCardVO> cards(OverviewVO vo)
    {
        if (vo == null || !(vo.getBlocks().get("METRICS") instanceof MetricsBlockVO))
        {
            return new ArrayList<>();
        }
        List<MetricCardVO> cards = new ArrayList<>(((MetricsBlockVO) vo.getBlocks().get("METRICS")).getItems());
        List<String> order = ExportMetricText.order();
        cards.sort(Comparator.comparingInt(c -> {
            int i = order.indexOf(c.getCode());
            return i < 0 ? Integer.MAX_VALUE : i;
        }));
        return cards;
    }

    private static ExportMetricText.M text(MetricCardVO c)
    {
        return ExportMetricText.of(c.getCode(), c.getLabel(), c.getGroupLabel() == null ? c.getGroup() : c.getGroupLabel(),
            c.getExpression(), c.getValueFormat());
    }

    private void writeSnapshot(Book book, Part<OverviewVO> part)
    {
        Sheet sheet = book.sheet(S_SNAPSHOT);
        List<Col> cols = List.of(Col.slot("統計開始"), Col.slot("統計結束"), Col.slot("對比開始"), Col.slot("對比結束"),
            Col.text("指標", 12), Col.text("分組", 11), Col.num("當前值", null), Col.text("單位", 6),
            Col.num("上期值", null), Col.num("變化率(%)", "0.0"), Col.text("口徑", 60));
        int r = book.header(sheet, cols);
        OverviewVO vo = part.data;
        if (vo == null)
        {
            return;
        }
        String from = vo.getSlot() == null ? null : vo.getSlot().getFrom();
        String to = vo.getSlot() == null ? null : vo.getSlot().getTo();
        String cmpFrom = vo.getCompare() == null ? null : vo.getCompare().getFrom();
        String cmpTo = vo.getCompare() == null ? null : vo.getCompare().getTo();
        for (MetricCardVO c : cards(vo))
        {
            ExportMetricText.M m = text(c);
            book.row(sheet, r++, cols, Arrays.asList(from, to, cmpFrom, cmpTo, m.label, m.group, c.getValue(),
                m.unit, c.getPrevValue(), ExportTemplate.changePct(c.getValue(), c.getPrevValue()), m.note));
        }
    }

    private void writeSeries(Book book, Part<OverviewVO> part)
    {
        Sheet sheet = book.sheet(S_SERIES);
        OverviewVO vo = part.data;
        List<MetricCardVO> cards = cards(vo);
        List<Col> cols = new ArrayList<>();
        cols.add(Col.slot("時間片開始"));
        cols.add(Col.slot("時間片結束"));
        for (MetricCardVO c : cards)
        {
            ExportMetricText.M m = text(c);
            cols.add(Col.num(m.header(), m.format));
        }
        int r = book.header(sheet, cols);
        if (vo == null || vo.getSlot() == null)
        {
            return;
        }
        LocalDateTime from = ExportTemplate.toDateTime(vo.getSlot().getFrom());
        LocalDateTime to = ExportTemplate.toDateTime(vo.getSlot().getTo());
        if (from == null || to == null)
        {
            return;
        }
        Granularity g = vo.getSlot().getGranularity() == null ? Granularity.HOUR : vo.getSlot().getGranularity();
        int points = vo.getSlot().getPoints();
        for (int p = 0; p < points; p++)
        {
            LocalDateTime start = slotStart(from, g, p);
            LocalDateTime end = slotStart(from, g, p + 1);
            if (end.isAfter(to))
            {
                end = to;
            }
            List<Object> line = new ArrayList<>();
            line.add(start);
            line.add(end);
            for (MetricCardVO c : cards)
            {
                line.add(c.getSeries() != null && p < c.getSeries().size() ? c.getSeries().get(p) : null);
            }
            book.row(sheet, r++, cols, line);
        }
    }

    /** 第 p 个时间片的开始：小时按整点、日按 0 点、周按 7 天推进；第 0 片从区间起点开始 */
    private static LocalDateTime slotStart(LocalDateTime from, Granularity g, int p)
    {
        if (p == 0)
        {
            return from;
        }
        switch (g)
        {
            case DAY:
                return from.toLocalDate().atStartOfDay().plusDays(p);
            case HOUR:
                return from.truncatedTo(ChronoUnit.HOURS).plusHours(p);
            default:
                return from.toLocalDate().atStartOfDay().plusWeeks(p);
        }
    }

    // ===================== 註冊渠道 =====================

    private static String[] span(OverviewSectionVO vo, OverviewExportQuery q)
    {
        if (vo != null && vo.getSlot() != null && StringUtils.isNotEmpty(vo.getSlot().getFrom()))
        {
            return new String[] { vo.getSlot().getFrom(), vo.getSlot().getTo() };
        }
        return new String[] { q.getSlotFrom(), q.getSlotTo() };
    }

    private void writeReg(Book book, OverviewExportQuery q, Part<RegChannelVO> part)
    {
        Sheet sheet = book.sheet(S_REG);
        List<Col> cols = List.of(Col.slot("統計開始"), Col.slot("統計結束"), Col.text("渠道分組", 12),
            Col.text("渠道", 22), Col.num("註冊人數", null), Col.num("佔總註冊(%)", "0.00"), Col.num("佔分組(%)", "0.00"));
        int r = book.header(sheet, cols);
        RegChannelVO vo = part.data;
        if (vo == null)
        {
            return;
        }
        String[] s = span(vo, q);
        // 按分组（分组注册数降序）聚在一起，组内按注册数降序
        Map<String, String> groupNames = new LinkedHashMap<>();
        for (RegChannelGroupVO g : vo.getGroups())
        {
            groupNames.put(g.getCode(), ExportTemplate.t(g.getName() == null ? g.getCode() : g.getName()));
        }
        List<RegChannelItemVO> channels = new ArrayList<>(vo.getChannels());
        List<String> order = new ArrayList<>(groupNames.keySet());
        channels.sort(Comparator
            .comparingInt((RegChannelItemVO c) -> {
                int i = order.indexOf(c.getGroupCode());
                return i < 0 ? Integer.MAX_VALUE : i;
            })
            .thenComparing((RegChannelItemVO c) -> c.getRegistrations() == null ? 0L : c.getRegistrations(),
                Comparator.reverseOrder()));
        for (RegChannelItemVO c : channels)
        {
            String group = groupNames.getOrDefault(c.getGroupCode(),
                ExportTemplate.t(c.getGroupName() == null ? c.getGroupCode() : c.getGroupName()));
            book.row(sheet, r++, cols, Arrays.asList(s[0], s[1], group, ExportTemplate.t(c.getName()),
                c.getRegistrations(), c.getShareOfTotal(), c.getShareOfGroup()));
        }
    }

    // ===================== 贈金項目結構 =====================

    private void writeBonus(Book book, OverviewExportQuery q, Part<RankingBoardVO> part)
    {
        Sheet sheet = book.sheet(S_BONUS);
        List<Col> cols = List.of(Col.slot("統計開始"), Col.slot("統計結束"), Col.text("贈金項目", 40),
            Col.num("金額(INR)", "0.00"), Col.num("佔比(%)", "0.00"));
        int r = book.header(sheet, cols);
        RankingBoardVO vo = part.data;
        if (vo == null || vo.getBonus() == null)
        {
            return;
        }
        String[] s = span(vo, q);
        BonusItemVO others = null;
        for (BonusItemVO b : vo.getBonus().getItems())
        {
            if (isOthers(b))
            {
                others = b;
                continue;
            }
            book.row(sheet, r++, cols, Arrays.asList(s[0], s[1], bonusName(b.getName()), b.getAmount(), b.getShare()));
        }
        // 「Others」固定最后
        if (others != null)
        {
            book.row(sheet, r, cols, Arrays.asList(s[0], s[1], "Others", others.getAmount(), others.getShare()));
        }
    }

    private static boolean isOthers(BonusItemVO b)
    {
        return "other".equalsIgnoreCase(b.getCode()) || "其他".equals(b.getName()) || "Others".equalsIgnoreCase(b.getName());
    }

    /** 赠金项目写完整的英文项目名：服务端名称是「英文|中文」，取英文一段 */
    private static String bonusName(String name)
    {
        if (StringUtils.isEmpty(name))
        {
            return name;
        }
        int bar = name.indexOf('|');
        return bar > 0 ? name.substring(0, bar).trim() : name;
    }

    // ===================== 熱銷遊戲 =====================

    private void writeGames(Book book, OverviewExportQuery q, Part<RankingBoardVO> part)
    {
        Sheet sheet = book.sheet(S_GAMES);
        List<Col> cols = List.of(Col.slot("統計開始"), Col.slot("統計結束"), Col.num("序", null), Col.text("遊戲名稱", 24),
            Col.text("平台廠商名", 16), Col.text("遊戲平台Code", 14), Col.text("遊戲類型", 10), Col.text("遊戲ID", 18),
            Col.num("投注額(INR)", "0.00"), Col.num("盈利率(%)", "0.00"), Col.num("投注額佔比(%)", "0.00"));
        int r = book.header(sheet, cols);
        RankingBoardVO vo = part.data;
        if (vo == null || vo.getGames() == null)
        {
            return;
        }
        String[] s = span(vo, q);
        for (GameItemVO g : vo.getGames().getItems())
        {
            String type = StringUtils.isNotEmpty(g.getGameType()) ? g.getGameType() : g.getGameTypeCode();
            book.row(sheet, r++, cols, Arrays.asList(s[0], s[1], g.getRank(), g.getName(), g.getVendorName(),
                g.getPlatformCode(), ExportTemplate.t(type), g.getGameId(), g.getBetAmount(),
                ExportTemplate.toDecimal(g.getProfitRate()), ExportTemplate.toDecimal(g.getRateForBetAmount())));
        }
    }

    // ===================== 留存 / LTV 矩陣 =====================

    private void writeCohort(Book book, String sheetName, String dateHead, String baseHead, String unit,
            String format, Part<CohortVO> part, CohortTableVO table, List<String> columns)
    {
        Sheet sheet = book.sheet(sheetName);
        List<String> heads = columns == null || columns.isEmpty() ? defaultCohortColumns(sheetName) : columns;
        List<Col> cols = new ArrayList<>();
        cols.add(Col.date(dateHead));
        cols.add(Col.date("資料截至"));
        cols.add(Col.num(baseHead, null));
        for (String h : heads)
        {
            cols.add(Col.num(h + unit, format));
        }
        int r = book.header(sheet, cols);
        if (part.data == null || table == null)
        {
            return;
        }
        LocalDate asOf = dataThrough(part.data);
        List<CohortRowVO> rows = new ArrayList<>(table.getRows());
        // 最近的分群日在上
        rows.sort(Comparator.comparing(CohortRowVO::getCohortDate, Comparator.nullsLast(Comparator.reverseOrder())));
        for (CohortRowVO row : rows)
        {
            List<Object> line = new ArrayList<>();
            line.add(row.getCohortDate());
            line.add(asOf);
            line.add(row.getBase());
            for (int i = 0; i < heads.size(); i++)
            {
                line.add(row.getValues() != null && i < row.getValues().size() ? row.getValues().get(i) : null);
            }
            book.row(sheet, r++, cols, line);
        }
    }

    private static List<String> defaultCohortColumns(String sheetName)
    {
        List<String> base = new ArrayList<>(List.of("D+1", "D+2", "D+3", "D+4", "D+5", "D+6", "D+7", "D+15", "D+30"));
        if (S_LTV.equals(sheetName))
        {
            base.add(0, "Pre D+0");
        }
        return base;
    }

    /** 资料截至 = 昨天：队列是 T-1 快照，asOf 为「昨天 + 1 天」的 00:00 */
    private LocalDate dataThrough(CohortVO vo)
    {
        LocalDateTime asOf = ExportTemplate.toDateTime(vo.getAsOf());
        if (asOf != null)
        {
            return asOf.toLocalDate().minusDays(1);
        }
        return LocalDate.now(ZoneId.of(properties.getTimezone())).minusDays(1);
    }

    // ===================== 口徑說明 =====================

    private List<String[]> notes(CohortVO cohort)
    {
        List<String[]> l = ExportTemplate.commonNotes(false);
        String slotUnit = "YYYY-MM-DD HH:mm";
        l.add(new String[] { S_SNAPSHOT, "統計開始 / 統計結束", "當前值的統計區間", slotUnit });
        l.add(new String[] { S_SNAPSHOT, "對比開始 / 對比結束", "上期值的統計區間，按頁面「對比」設定（上一週期 / 去年同期 / 自訂）", slotUnit });
        l.add(new String[] { S_SNAPSHOT, "指標、分組", "固定指標與所屬分組；跨多個自然日時，僅支持單日的指標（活躍人數、ARPPU）不導出", "" });
        l.add(new String[] { S_SNAPSHOT, "當前值 / 上期值", "按「口徑」列計算的區間值；比率、時長、倍數、去重人數按整個區間重算，不是逐小時相加", "見「單位」列" });
        l.add(new String[] { S_SNAPSHOT, "變化率(%)", "(當前值 − 上期值) ÷ 上期值 × 100；上期值為 0 或無資料時為空", "百分數值，1 位小數" });
        l.add(new String[] { S_SNAPSHOT, "口徑", "該指標的定義，與下方各指標列相同", "" });
        l.add(new String[] { S_SERIES, "時間片開始 / 時間片結束", "一行一個時間片，粒度按頁面「粒度」設定", slotUnit });
        for (ExportMetricText.M m : ExportMetricText.ALL.values())
        {
            l.add(new String[] { S_SERIES, m.header(), m.note, m.unitNote });
        }
        String sectionSpan = "本表數據的統計區間，與指標卡相同（小時級）；零點後今日尚無完整小時時為昨日全天";
        l.add(new String[] { S_REG, "統計開始 / 統計結束", sectionSpan, slotUnit });
        l.add(new String[] { S_REG, "渠道分組", "渠道所屬的分組（註冊來源的上級分類）", "" });
        l.add(new String[] { S_REG, "渠道", "按註冊時的渠道歸因，註冊後不再改變；空渠道歸為「未知來源」", "" });
        l.add(new String[] { S_REG, "註冊人數", "統計區間內在該渠道完成註冊的人數；各渠道相加＝指標「註冊人數」", "人數，整數" });
        l.add(new String[] { S_REG, "佔總註冊(%)", "渠道註冊人數 ÷ 統計區間註冊人數 × 100", "百分數值，1 位小數" });
        l.add(new String[] { S_REG, "佔分組(%)", "渠道註冊人數 ÷ 所屬分組註冊人數 × 100", "百分數值，1 位小數" });
        l.add(new String[] { S_BONUS, "統計開始 / 統計結束", sectionSpan, slotUnit });
        l.add(new String[] { S_BONUS, "贈金項目", "業務配置的贈金類目，寫完整的英文項目名，不截斷；「Others」固定最後", "" });
        l.add(new String[] { S_BONUS, "金額(INR)", "統計區間內該項目實際發放的贈金；各項相加＝指標「發放贈金總額」", "INR，純數值" });
        l.add(new String[] { S_BONUS, "佔比(%)", "該項金額 ÷ 發放贈金總額 × 100", "百分數值，1 位小數" });
        l.add(new String[] { S_GAMES, "統計開始 / 統計結束", sectionSpan, slotUnit });
        l.add(new String[] { S_GAMES, "序", "按投注額取前 20 的名次", "" });
        l.add(new String[] { S_GAMES, "遊戲名稱 … 遊戲ID", "遊戲與所屬平台、類型的識別資訊；遊戲名稱查不到時以遊戲ID填充", "" });
        l.add(new String[] { S_GAMES, "投注額(INR)", "統計區間內該遊戲的投注金額加總", "INR，純數值" });
        l.add(new String[] { S_GAMES, "盈利率(%)", "該遊戲 GGR ÷ 該遊戲投注額 × 100，可為負", "百分數值，1 位小數" });
        l.add(new String[] { S_GAMES, "投注額佔比(%)", "該遊戲投注額 ÷ 全站投注總額 × 100；20 行相加不等於 100", "百分數值，1 位小數" });
        l.add(new String[] { S_RETENTION, "投注日", "分群日：當日有投注的用戶為一群", "YYYY-MM-DD" });
        l.add(new String[] { S_RETENTION, "資料截至", "每日計算（T+1），資料截至昨日", "YYYY-MM-DD" });
        l.add(new String[] { S_RETENTION, "投注人數", "分群日有投注的去重用戶數", "人數，整數" });
        l.add(new String[] { S_RETENTION, "D+1 … D+30(%)", "該群第 N 日仍有投注的比例；分群日 + N 天晚於資料截至時為空（未到觀察期），不是 0", "百分數值，1 位小數" });
        l.add(new String[] { S_LTV, "首存日", "分群日：當日完成首存的用戶為一群", "YYYY-MM-DD" });
        l.add(new String[] { S_LTV, "資料截至", "每日計算（T+1），資料截至昨日", "YYYY-MM-DD" });
        l.add(new String[] { S_LTV, "首存人數", "分群日完成首存的人數", "人數，整數" });
        boolean pre = cohort == null || cohort.getLtvColumns() == null || cohort.getLtvColumns().contains("Pre D+0");
        if (pre)
        {
            l.add(new String[] { S_LTV, "Pre D+0(INR)", "首存當日累計 NGR ÷ 首存人數", "INR，整數" });
        }
        l.add(new String[] { S_LTV, "D+1 … D+30(INR)", "首存後 N 日內累計 NGR ÷ 首存人數；未到觀察期為空，不是 0", "INR，整數" });
        return l;
    }

    /** 取数用的数字（保留给测试） */
    static BigDecimal pct(String text)
    {
        return ExportTemplate.toDecimal(text);
    }
}
