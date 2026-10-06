package com.fivetech.dashboard.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import com.fivetech.dashboard.service.IOverviewSectionService;
import com.fivetech.dashboard.service.IOverviewService;

/**
 * 运营总览整页导出：四个接口、六个子页面汇成一个 xlsx，一个子页面一张 sheet。
 *
 * <p>sheet 名与原型 Tab / 面板名一致，顺序与页面一致：</p>
 * <ol>
 *   <li>核心指标 —— /overview（指标卡 + 时间序列）</li>
 *   <li>注册渠道 —— /reg-channels（渠道分组 + 渠道明细）</li>
 *   <li>赠金项目结构 —— /rankingboard 的 bonus</li>
 *   <li>热销游戏 —— /rankingboard 的 games（Top 20）</li>
 *   <li>留存率 —— /cohort 的 retention</li>
 *   <li>LTV —— /cohort 的 ltv</li>
 * </ol>
 *
 * <p><b>一块失败不拖垮整份文件</b>：每个接口单独取数，失败时对应 sheet 照常创建，
 * 写明「取数失败」与原因，其余 sheet 正常导出——拿到文件的人能看出哪块缺了、为什么缺。</p>
 *
 * <p>数值一律写成数字（单位放表头），null 留空单元格、不写 0；整份在内存里生成完再返回，
 * 生成途中出错仍能回 JSON 错误体。数据量很小（最多几百行），不需要流式。</p>
 *
 * @author fivetech
 */
@Component
public class OverviewWorkbookExporter
{
    private static final Logger log = LoggerFactory.getLogger(OverviewWorkbookExporter.class);

    public static final String SHEET_CORE = "核心指标";

    public static final String SHEET_REG = "注册渠道";

    public static final String SHEET_BONUS = "赠金项目结构";

    public static final String SHEET_GAMES = "热销游戏";

    public static final String SHEET_RETENTION = "留存率";

    public static final String SHEET_LTV = "LTV";

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
            // 参数错误（ServiceException）与数据平台故障都在文件里写明，不让整份导出失败
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

        Part<OverviewVO> core = fetch(SHEET_CORE, () -> overviewService.query(overviewQuery(query)));
        Part<RegChannelVO> reg = fetch(SHEET_REG, () -> sectionService.regChannels(regQuery(query)));
        Part<RankingBoardVO> ranking = fetch("排行榜", () -> sectionService.rankingBoard(rankingQuery(query)));
        CohortQuery cq = cohortQuery(query);
        String cohortSpan = cq.getSlotFrom() + " ~ " + cq.getSlotTo() + "（分群日，左闭右开）";
        Part<CohortVO> cohort = fetch("留存与 LTV", () -> sectionService.cohort(cq));

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream())
        {
            Styles styles = new Styles(wb);
            writeCore(wb.createSheet(SHEET_CORE), styles, query, core);
            writeReg(wb.createSheet(SHEET_REG), styles, query, reg);
            writeBonus(wb.createSheet(SHEET_BONUS), styles, query, ranking);
            writeGames(wb.createSheet(SHEET_GAMES), styles, query, ranking);
            writeCohort(wb.createSheet(SHEET_RETENTION), styles, cohort, cohortSpan,
                cohort.data == null ? null : cohort.data.getRetention(), "留存率(%)");
            writeCohort(wb.createSheet(SHEET_LTV), styles, cohort, cohortSpan,
                cohort.data == null ? null : cohort.data.getLtv(), "LTV(₹)");
            wb.write(out);
            return out.toByteArray();
        }
        catch (IOException e)
        {
            throw new ServiceException("导出文件生成失败：" + e.getMessage(), 5002);
        }
    }

    /** 文件名带上区间，导出多次之后还能分清 */
    public String fileName(OverviewExportQuery query)
    {
        return "运营总览_" + safe(query.getSlotFrom()) + "_" + safe(query.getSlotTo()) + ".xlsx";
    }

    private static String safe(String text)
    {
        return text == null ? "" : text.replace(":", "").replace(" ", "_").replace("-", "");
    }

    // ===================== 参数 =====================

    /** 只做导出特有的校验；各接口自己的校验（区间、对齐、跨度）由对应服务负责 */
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

    private OverviewQuery overviewQuery(OverviewExportQuery q)
    {
        OverviewQuery o = new OverviewQuery();
        o.setSiteCode(q.getSiteCode());
        o.setSlotFrom(q.getSlotFrom());
        o.setSlotTo(q.getSlotTo());
        o.setGranularity(StringUtils.isEmpty(q.getGranularity()) ? Granularity.HOUR : Granularity.valueOf(q.getGranularity()));
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
        r.setGranularity(StringUtils.isEmpty(q.getGranularity()) ? "HOUR" : q.getGranularity());
        return r;
    }

    /**
     * 留存与 LTV 的分群日区间（左闭右开，日期）。
     * <ul>
     *   <li>传了 cohortFrom / cohortTo 就用它们；</li>
     *   <li>否则取 slot 的日期：开始 = slotFrom 当天，结束 = slotTo 所在日（slotTo 恰为 00:00 时就是这一天，否则是次日）；</li>
     *   <li>得到的区间里没有任何已完结的分群日（如只看今天）时，改为截至昨天的最近 30 天——留存是 T-1 快照，今天没有数据；</li>
     *   <li>超过 90 天时保留最近的 90 天（UDS 分群查询上限）。</li>
     * </ul>
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

    // ===================== sheet 1 核心指标 =====================

    private void writeCore(Sheet sheet, Styles s, OverviewExportQuery q, Part<OverviewVO> part)
    {
        int r = banner(sheet, s, 0, q.getSlotFrom() + " ~ " + q.getSlotTo() + "（" + q.getGranularity() + "）",
            part.data == null ? null : part.data.getAsOf());
        if (part.error != null)
        {
            failed(sheet, s, r, part.error);
            return;
        }
        OverviewVO vo = part.data;
        Object block = vo.getBlocks().get("METRICS");
        if (!(block instanceof MetricsBlockVO))
        {
            r = kv(sheet, s, r, "说明", vo.getBlockErrors().isEmpty() ? "无指标数据" : "指标墙取数失败");
            notices(sheet, s, r + 1, vo.getNotices());
            return;
        }
        MetricsBlockVO metrics = (MetricsBlockVO) block;
        if (vo.getCompare() != null)
        {
            r = kv(sheet, s, r, "对比期", vo.getCompare().getFrom() + " ~ " + vo.getCompare().getTo());
        }
        r++;

        String[] head = { "指标", "分组", "核心", "当期值", "单位", "对比期值", "变化量", "变化率(%)", "变化(百分点)", "口径" };
        head(sheet, s, r++, head);
        List<MetricCardVO> cards = metrics.getItems();
        for (MetricCardVO c : cards)
        {
            Row row = sheet.createRow(r++);
            CellStyle vs = s.of(c.getValueFormat(), c.getDecimals());
            text(row, 0, c.getLabel(), s.body);
            text(row, 1, c.getGroupLabel() == null ? c.getGroup() : c.getGroupLabel(), s.body);
            text(row, 2, "CORE".equals(c.getEmphasis()) ? "是" : "", s.center);
            num(row, 3, c.getValue(), vs);
            text(row, 4, unitOf(c.getValueFormat()), s.center);
            num(row, 5, c.getPrevValue(), vs);
            num(row, 6, c.getDelta(), vs);
            num(row, 7, c.getDeltaPct(), s.d2);
            num(row, 8, c.getDeltaPt(), s.d2);
            text(row, 9, c.getExpression(), s.body);
        }

        // 时间序列：一行一个时间片、一列一个指标，导出后可以直接在 Excel 里画折线
        List<MetricCardVO> withSeries = new ArrayList<>();
        cards.forEach(c -> {
            if (c.getSeries() != null && !c.getSeries().isEmpty())
            {
                withSeries.add(c);
            }
        });
        if (!withSeries.isEmpty() && vo.getSlot() != null)
        {
            r++;
            Row title = sheet.createRow(r++);
            text(title, 0, "时间序列", s.head);
            String[] seriesHead = new String[withSeries.size() + 1];
            seriesHead[0] = "时间片";
            for (int i = 0; i < withSeries.size(); i++)
            {
                MetricCardVO c = withSeries.get(i);
                String unit = unitOf(c.getValueFormat());
                seriesHead[i + 1] = c.getLabel() + (unit.isEmpty() ? "" : "(" + unit + ")");
            }
            head(sheet, s, r++, seriesHead);
            List<String> labels = vo.getSlot().getLabels();
            for (int p = 0; p < vo.getSlot().getPoints(); p++)
            {
                Row row = sheet.createRow(r++);
                text(row, 0, labels != null && p < labels.size() ? labels.get(p) : String.valueOf(p + 1), s.body);
                for (int i = 0; i < withSeries.size(); i++)
                {
                    MetricCardVO c = withSeries.get(i);
                    num(row, i + 1, p < c.getSeries().size() ? c.getSeries().get(p) : null, s.of(c.getValueFormat(), c.getDecimals()));
                }
            }
            width(sheet, Math.max(head.length, seriesHead.length));
        }
        else
        {
            width(sheet, head.length);
        }
        notices(sheet, s, r + 1, vo.getNotices());
    }

    // ===================== sheet 2 注册渠道 =====================

    private void writeReg(Sheet sheet, Styles s, OverviewExportQuery q, Part<RegChannelVO> part)
    {
        int r = banner(sheet, s, 0, q.getSlotFrom() + " ~ " + q.getSlotTo(), asOf(part.data));
        if (part.error != null)
        {
            failed(sheet, s, r, part.error);
            return;
        }
        RegChannelVO vo = part.data;
        r = kvNum(sheet, s, r, "注册总数", vo.getTotalRegistrations() == null ? null : BigDecimal.valueOf(vo.getTotalRegistrations()), s.i0);
        r++;

        Row t1 = sheet.createRow(r++);
        text(t1, 0, "渠道分组", s.head);
        head(sheet, s, r++, new String[] { "分组编码", "分组名称", "注册人数", "占比(%)", "渠道数" });
        for (RegChannelGroupVO g : vo.getGroups())
        {
            Row row = sheet.createRow(r++);
            text(row, 0, g.getCode(), s.body);
            text(row, 1, g.getName(), s.body);
            num(row, 2, g.getRegistrations() == null ? null : BigDecimal.valueOf(g.getRegistrations()), s.i0);
            num(row, 3, g.getShare(), s.d1);
            num(row, 4, g.getChannelCount() == null ? null : BigDecimal.valueOf(g.getChannelCount()), s.i0);
        }
        r++;

        Row t2 = sheet.createRow(r++);
        text(t2, 0, "渠道明细", s.head);
        head(sheet, s, r++, new String[] { "分组", "渠道", "注册人数", "站点占比(%)", "组内占比(%)" });
        for (RegChannelItemVO c : vo.getChannels())
        {
            Row row = sheet.createRow(r++);
            text(row, 0, c.getGroupName() == null ? c.getGroupCode() : c.getGroupName(), s.body);
            text(row, 1, c.getName(), s.body);
            num(row, 2, c.getRegistrations() == null ? null : BigDecimal.valueOf(c.getRegistrations()), s.i0);
            num(row, 3, c.getShareOfTotal(), s.d1);
            num(row, 4, c.getShareOfGroup(), s.d1);
        }
        width(sheet, 5);
        notices(sheet, s, r + 1, vo.getNotices());
    }

    // ===================== sheet 3 赠金项目结构 =====================

    private void writeBonus(Sheet sheet, Styles s, OverviewExportQuery q, Part<RankingBoardVO> part)
    {
        int r = banner(sheet, s, 0, q.getSlotFrom() + " ~ " + q.getSlotTo(), asOf(part.data));
        if (part.error != null)
        {
            failed(sheet, s, r, part.error);
            return;
        }
        RankingBoardVO vo = part.data;
        r = kvNum(sheet, s, r, "发放赠金总额(₹)", vo.getBonus() == null ? null : vo.getBonus().getTotal(), s.money);
        r++;
        head(sheet, s, r++, new String[] { "排名", "赠金项目", "金额(₹)", "占比(%)" });
        if (vo.getBonus() != null)
        {
            for (BonusItemVO b : vo.getBonus().getItems())
            {
                Row row = sheet.createRow(r++);
                num(row, 0, b.getRank() == null ? null : BigDecimal.valueOf(b.getRank()), s.i0);
                text(row, 1, b.getName(), s.body);
                num(row, 2, b.getAmount(), s.money);
                num(row, 3, b.getShare(), s.d1);
            }
        }
        width(sheet, 4);
        notices(sheet, s, r + 1, vo.getNotices());
    }

    // ===================== sheet 4 热销游戏 =====================

    private void writeGames(Sheet sheet, Styles s, OverviewExportQuery q, Part<RankingBoardVO> part)
    {
        int r = banner(sheet, s, 0, q.getSlotFrom() + " ~ " + q.getSlotTo(), asOf(part.data));
        if (part.error != null)
        {
            failed(sheet, s, r, part.error);
            return;
        }
        RankingBoardVO vo = part.data;
        if (vo.getGames() != null)
        {
            r = kvNum(sheet, s, r, "投注总额(₹)", vo.getGames().getTotalBetAmount(), s.money);
            r = kvNum(sheet, s, r, "Top " + vo.getGames().getTopN() + " 覆盖率(%)", pct(vo.getGames().getTotalBetAmountPct()), s.d1);
        }
        r++;
        String[] head = { "排名", "游戏名称", "游戏ID", "游戏平台Code", "平台厂商名", "游戏类型",
            "投注金额(₹)", "盈利率(%)", "投注占比(%)", "投注人次", "投注笔数" };
        head(sheet, s, r++, head);
        if (vo.getGames() != null)
        {
            for (GameItemVO g : vo.getGames().getItems())
            {
                Row row = sheet.createRow(r++);
                num(row, 0, g.getRank() == null ? null : BigDecimal.valueOf(g.getRank()), s.i0);
                text(row, 1, g.getName(), s.body);
                text(row, 2, g.getGameId(), s.body);
                text(row, 3, g.getPlatformCode(), s.body);
                text(row, 4, g.getVendorName(), s.body);
                text(row, 5, g.getGameType(), s.body);
                num(row, 6, g.getBetAmount(), s.money);
                num(row, 7, pct(g.getProfitRate()), s.d1);
                num(row, 8, pct(g.getRateForBetAmount()), s.d1);
                num(row, 9, g.getBetUsers() == null ? null : BigDecimal.valueOf(g.getBetUsers()), s.i0);
                num(row, 10, g.getBetCount() == null ? null : BigDecimal.valueOf(g.getBetCount()), s.i0);
            }
        }
        width(sheet, head.length);
        notices(sheet, s, r + 1, vo.getNotices());
    }

    // ===================== sheet 5 / 6 留存率、LTV =====================

    private void writeCohort(Sheet sheet, Styles s, Part<CohortVO> part, String span, CohortTableVO table, String unitLabel)
    {
        CohortVO vo = part.data;
        int r = banner(sheet, s, 0, span, vo == null ? null : vo.getAsOf());
        if (part.error != null)
        {
            failed(sheet, s, r, part.error);
            return;
        }
        if (table == null)
        {
            kv(sheet, s, r, "说明", "无数据");
            return;
        }
        r = kv(sheet, s, r, "口径", "T-1 快照；未到观察期的格子留空");
        r++;
        List<String> columns = vo.getColumns();
        String[] head = new String[columns.size() + 2];
        head[0] = table.getCohortLabel() == null ? "分群日" : table.getCohortLabel();
        head[1] = table.getBaseLabel() == null ? "基数" : table.getBaseLabel();
        for (int i = 0; i < columns.size(); i++)
        {
            head[i + 2] = columns.get(i) + " " + unitLabel;
        }
        head(sheet, s, r++, head);
        CellStyle vs = "PCT".equalsIgnoreCase(table.getValueFormat()) ? s.d1 : s.money;
        for (CohortRowVO row : table.getRows())
        {
            Row x = sheet.createRow(r++);
            text(x, 0, row.getCohortDate(), s.body);
            num(x, 1, row.getBase() == null ? null : BigDecimal.valueOf(row.getBase()), s.i0);
            for (int i = 0; i < columns.size(); i++)
            {
                num(x, i + 2, i < row.getValues().size() ? row.getValues().get(i) : null, vs);
            }
        }
        width(sheet, head.length);
        notices(sheet, s, r + 1, vo.getNotices());
    }

    // ===================== 公共 =====================

    private static String asOf(OverviewSectionVO vo)
    {
        return vo == null ? null : vo.getAsOf();
    }

    /** "13.4%" → 13.4；解析失败或为空返回 null */
    private static BigDecimal pct(String text)
    {
        if (StringUtils.isEmpty(text))
        {
            return null;
        }
        try
        {
            return new BigDecimal(text.replace("%", "").trim());
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private int banner(Sheet sheet, Styles s, int r, String span, String asOf)
    {
        Row row = sheet.createRow(r);
        text(row, 0, "统计区间", s.head);
        text(row, 1, span, s.body);
        text(row, 3, "数据截至", s.head);
        text(row, 4, asOf == null ? "—" : asOf, s.body);
        Row tz = sheet.createRow(r + 1);
        text(tz, 0, "时区 / 币种", s.head);
        text(tz, 1, properties.getTimezone() + " / " + properties.getCurrency(), s.body);
        return r + 2;
    }

    private void failed(Sheet sheet, Styles s, int r, String error)
    {
        Row row = sheet.createRow(r + 1);
        text(row, 0, "取数失败", s.head);
        text(row, 1, error, s.body);
        width(sheet, 2);
    }

    private int kv(Sheet sheet, Styles s, int r, String key, String value)
    {
        Row row = sheet.createRow(r);
        text(row, 0, key, s.head);
        text(row, 1, value, s.body);
        return r + 1;
    }

    private int kvNum(Sheet sheet, Styles s, int r, String key, BigDecimal value, CellStyle style)
    {
        Row row = sheet.createRow(r);
        text(row, 0, key, s.head);
        num(row, 1, value, style);
        return r + 1;
    }

    /** 页面上的提示（数据未就绪、参数被忽略、口径不一致……）写在表尾，文件脱离页面流转时也看得到 */
    private void notices(Sheet sheet, Styles s, int r, List<OverviewNoticeVO> notices)
    {
        if (notices == null || notices.isEmpty())
        {
            return;
        }
        head(sheet, s, r++, new String[] { "提示", "编码", "说明", "详情" });
        for (OverviewNoticeVO n : notices)
        {
            Row row = sheet.createRow(r++);
            text(row, 0, n.getLevel(), s.center);
            text(row, 1, n.getCode(), s.body);
            text(row, 2, n.getMessage(), s.body);
            text(row, 3, n.getDetail(), s.body);
        }
    }

    private static void head(Sheet sheet, Styles s, int r, String[] titles)
    {
        Row row = sheet.createRow(r);
        for (int i = 0; i < titles.length; i++)
        {
            text(row, i, titles[i], s.head);
        }
    }

    private static void text(Row row, int column, String value, CellStyle style)
    {
        Cell cell = row.createCell(column);
        // Excel 单元格上限 32767 字符；以 = + - @ 开头的文本会被当成公式，前面加单引号前缀
        String v = value == null ? "" : value.length() > 32000 ? value.substring(0, 32000) : value;
        cell.setCellValue(v);
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0)
        {
            CellStyle quoted = row.getSheet().getWorkbook().createCellStyle();
            quoted.cloneStyleFrom(style);
            quoted.setQuotePrefixed(true);
            cell.setCellStyle(quoted);
            return;
        }
        cell.setCellStyle(style);
    }

    /** null 留空单元格，不写 0 */
    private static void num(Row row, int column, BigDecimal value, CellStyle style)
    {
        Cell cell = row.createCell(column);
        if (value != null)
        {
            cell.setCellValue(value.doubleValue());
        }
        cell.setCellStyle(style);
    }

    private static String unitOf(String valueFormat)
    {
        if (valueFormat == null)
        {
            return "";
        }
        switch (valueFormat.toUpperCase())
        {
            case "PCT":
                return "%";
            case "MIN":
                return "分钟";
            case "MULTIPLE":
                return "倍";
            case "MONEY":
                return "₹";
            default:
                return "";
        }
    }

    private static void width(Sheet sheet, int columns)
    {
        for (int i = 0; i < columns; i++)
        {
            // autoSizeColumn 不认中文字宽，固定宽度更稳定：首列宽一些放标签
            sheet.setColumnWidth(i, (i == 0 ? 22 : 16) * 256);
        }
    }

    /** 样式池：POI 样式数量有上限，必须复用 */
    private static final class Styles
    {
        private final CellStyle head;

        private final CellStyle body;

        private final CellStyle center;

        private final CellStyle i0;

        private final CellStyle d1;

        private final CellStyle d2;

        private final CellStyle money;

        private Styles(Workbook wb)
        {
            CreationHelper helper = wb.getCreationHelper();
            Font bold = wb.createFont();
            bold.setBold(true);
            head = wb.createCellStyle();
            head.setFont(bold);
            body = wb.createCellStyle();
            center = wb.createCellStyle();
            center.setAlignment(HorizontalAlignment.CENTER);
            i0 = wb.createCellStyle();
            i0.setDataFormat(helper.createDataFormat().getFormat("#,##0"));
            d1 = wb.createCellStyle();
            d1.setDataFormat(helper.createDataFormat().getFormat("#,##0.0"));
            d2 = wb.createCellStyle();
            d2.setDataFormat(helper.createDataFormat().getFormat("#,##0.00"));
            money = wb.createCellStyle();
            money.setDataFormat(helper.createDataFormat().getFormat("#,##0.00"));
        }

        /** 只决定小数位与千分位，不做数值换算（比率已在服务层 ×100） */
        private CellStyle of(String valueFormat, Integer decimals)
        {
            if (decimals != null)
            {
                return decimals <= 0 ? i0 : (decimals == 1 ? d1 : d2);
            }
            if (valueFormat == null)
            {
                return i0;
            }
            switch (valueFormat.toUpperCase())
            {
                case "PCT":
                case "MULTIPLE":
                    return d2;
                case "MIN":
                    return d1;
                case "MONEY":
                    return money;
                default:
                    return i0;
            }
        }
    }
}
