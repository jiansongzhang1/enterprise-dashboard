package com.fivetech.promo.analysis;

import java.io.IOException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.promo.analysis.query.ChannelTableQuery;
import com.fivetech.promo.analysis.query.ChannelTableQuery.PathItem;
import com.fivetech.promo.analysis.query.DailyReportQuery;
import com.fivetech.promo.analysis.query.FissionQuery;
import com.fivetech.promo.budget.PromoChannelDictionary;

/**
 * 推广分析的三个导出：投放日报（FY-57）、渠道表现、裂变层级（《推广分析 · 导出说明》E1–E9）。
 *
 * <p>文件整份在内存里生成完再返回（生成途中出错还能回 JSON 错误体）；超过 1,000,000 行返回 4005。
 * 数据未接入：明细行与数值都为空，只有字典、日期能确定的行（渠道表现的成员行、投放日报的合计行）。</p>
 *
 * @author fivetech
 */
@Service
public class PromoExportService
{
    private static final Logger log = LoggerFactory.getLogger(PromoExportService.class);

    private static final String DOT = " · ";

    private final DashboardProperties properties;

    private final PromoAnalysisService analysis;

    public PromoExportService(DashboardProperties properties, PromoAnalysisService analysis)
    {
        this.properties = properties;
        this.analysis = analysis;
    }

    private String siteName()
    {
        String site = properties.currentSite();
        String name = properties.siteName(site);
        return name == null || name.isBlank() ? site : name;
    }

    /** 资料截至：没有数据写「—」 */
    private static String asOfText()
    {
        return "—";
    }

    private static String asOfText(com.fivetech.promo.analysis.market.MarketCube cube)
    {
        return cube == null || cube.getWatermark() == null ? "—" : cube.getWatermark().format(PromoTime.SLOT);
    }

    private static String span(LocalDate from, LocalDate to)
    {
        return from.equals(to) ? from.toString() : from + " – " + to;
    }

    private static String compact(LocalDate from, LocalDate to)
    {
        return from.format(PromoTime.COMPACT) + "-" + to.format(PromoTime.COMPACT);
    }

    private static void checkRows(long rows)
    {
        if (rows > PromoErrors.MAX_EXPORT_ROWS)
        {
            throw new ServiceException("超過 1,000,000 行，請縮小時間範圍或篩選條件", PromoErrors.TOO_MANY_ROWS);
        }
    }

    private PromoFile file(PromoSheet sheet, String baseName, String summary)
    {
        try
        {
            return new PromoFile(baseName + ".xlsx", sheet.xlsx(), false, summary);
        }
        catch (IOException | RuntimeException e)
        {
            log.warn("[promo] xlsx 生成失败，改为 csv：{}", e.getMessage());
            return new PromoFile(baseName + ".csv", sheet.csv(), true, summary);
        }
    }

    // ------------------------------------------------------------------ FY-57 投放日报

    /** 投放日报一行的消耗与成本类列（前 4 列是日期、推广商、推广渠道、投放媒体）；成本类按有效格子，行为数据按推广渠道到位前为空 */
    void fillSpend(Object[] row, PromoSpend.Cube cube, LocalDate from, LocalDate to,
        java.util.function.Predicate<com.fivetech.promo.budget.BudgetDataReader.Row> filter, java.util.function.Predicate<String> channels)
    {
        if (cube == null || cube.isFailed())
        {
            return;
        }
        for (int i = 0; i < PromoMetricCatalog.DAILY_REPORT_COLUMNS.size(); i++)
        {
            String code = PromoMetricCatalog.DAILY_REPORT_COLUMNS.get(i);
            if (PromoSpend.CODES.contains(code))
            {
                row[4 + i] = cube.sum(PromoSpend.kind(code), from, to, filter);
            }
            else if (PromoCost.CODES.contains(code))
            {
                PromoCost.Result r = PromoCost.evaluate(code, cube.cells(com.fivetech.promo.budget.BudgetKind.SPEND, from, to, filter),
                    PromoCost.filter(analysis.behavior.byChannel(PromoCost.behaviorMetric(code), from, to), from, to, channels));
                row[4 + i] = r.value();
            }
        }
    }

    /**
     * 合计行的行为数据（註冊、首存、存款、激活及由它们算的比率）用全站值：只在「全部推廣渠道」且取消「只含付費推廣渠道」时调用，
     * 此时合计范围 = 全部有数据的推广渠道 = 全站（R2）。已有值的列（消耗、成本类）不覆盖；数据平台没有的列（UV、下載、提款人數）留空。
     */
    static void fillSiteTotals(Object[] row, com.fivetech.promo.analysis.market.MarketCube cube,
        java.util.function.Predicate<java.time.LocalDateTime> buckets)
    {
        if (cube == null || cube.isOmitted())
        {
            return;
        }
        for (int i = 0; i < PromoMetricCatalog.DAILY_REPORT_COLUMNS.size(); i++)
        {
            String code = PromoMetricCatalog.DAILY_REPORT_COLUMNS.get(i);
            if (row[4 + i] == null && com.fivetech.promo.analysis.market.MarketValues.AVAILABLE.contains(code))
            {
                row[4 + i] = com.fivetech.promo.analysis.market.MarketValues.value(code, cube,
                    com.fivetech.promo.analysis.market.MarketCube.ALL, buckets);
            }
        }
    }

    public PromoFile dailyReport(DailyReportQuery q)
    {
        String mode = q.getMode() == null ? "" : q.getMode().trim().toLowerCase(Locale.ROOT);
        LocalDate today = analysis.today();
        LocalDate yesterday = today.minusDays(1);
        LocalDate from;
        LocalDate to;
        switch (mode)
        {
            case "yest":
                from = yesterday;
                to = yesterday;
                break;
            case "p3":
                from = today.minusDays(3);
                to = yesterday;
                break;
            case "top":
                PromoTime.Range r = PromoTime.range(q.getSlotFrom(), q.getSlotTo());
                from = r.firstDay();
                to = r.lastDay();
                break;
            default:
                throw new ServiceException("mode 只能是 yest（昨日）/ p3（前 3 天）/ top（按頂部時間範圍）", PromoErrors.UNKNOWN_CODE);
        }
        PromoScope scope = analysis.scope(q.getAgents(), q.getChannels(), null);
        boolean paidOnly = q.getPaidOnly() == null || q.getPaidOnly();
        // 列出的推广渠道：paidOnly 只列所选日期内有预算或消耗记录的；否则列全部有数据的。
        // 行为数据还不能按推广渠道拆（U1），「有数据」目前也只有消耗 / 预算，两者列出的相同
        PromoSpend.Cube spendCube = analysis.spend() == null ? null : analysis.spend().load(from, to);
        List<PromoChannelDictionary.Channel> listed = spendCube == null || spendCube.isFailed() ? new ArrayList<>()
            : spendCube.listed(scope, from, to);
        Map<String, String> promoterOf = new java.util.HashMap<>();
        for (PromoChannelDictionary.Promoter p : scope.promotersInScope())
        {
            scope.channelsOf(p).forEach(c -> promoterOf.put(com.fivetech.configcenter.ConfigCodes.idKey(c.getId()), p.getName()));
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        checkRows(1 + days * (listed.size() + 1));

        // 全站市场数据（不分推广渠道）：「資料截至」取它的水位（与页头同一来源）；
        // 「全部推廣渠道 + 取消只含付費」时合计行的行为数据也用它。选了推廣商 / 推廣渠道时全站值不适用，不取
        boolean allChannels = q.getAgents().isEmpty() && q.getChannels().isEmpty();
        com.fivetech.promo.analysis.market.MarketCube market = null;
        if (allChannels)
        {
            try
            {
                market = analysis.dailyCube(from, to, PromoMetricCatalog.DAILY_REPORT_COLUMNS);
            }
            catch (RuntimeException e)
            {
                // 取数失败只少行为数据与资料截至时间，消耗照常导出
                log.warn("[promo] 投放日报取市场数据失败，合计行的行为数据留空：{}", e.getMessage());
            }
        }
        boolean siteTotals = !paidOnly && market != null;

        String title = siteName() + DOT + "推廣分析" + DOT + "投放日報" + DOT + span(from, to) + DOT + "渠道：" + scope.channelLabel()
            + DOT + (paidOnly ? "只含付費推廣渠道（所選日期內有預算或消耗記錄）" : "全部有數據的推廣渠道")
            + DOT + "人數按日累加" + DOT + "資料截至 " + asOfText(market);
        PromoSheet sheet = new PromoSheet("投放日報", title)
            .column("日期", PromoSheet.TEXT)
            .column("推廣商", PromoSheet.TEXT)
            .column("推廣渠道", PromoSheet.TEXT)
            .column("投放媒體", PromoSheet.TEXT);
        for (String code : PromoMetricCatalog.DAILY_REPORT_COLUMNS)
        {
            PromoMetricCatalog.Column c = PromoMetricCatalog.column(code);
            sheet.column(c.getName(), c.getFormat());
        }
        // 合计行：日期列写区间（单日写一天），推廣商空，推廣渠道列写「區間合計」/「當日合計」（R3、列与口径「日期」）
        Object[] total = sheet.newRow();
        total[0] = span(from, to);
        total[2] = "區間合計";
        java.util.function.Predicate<com.fivetech.promo.budget.BudgetDataReader.Row> inScope = PromoSpend.inScope(scope);
        fillSpend(total, spendCube, from, to, inScope, k -> listed.stream()
            .anyMatch(c -> com.fivetech.configcenter.ConfigCodes.idKey(c.getId()).equals(k)));
        if (siteTotals)
        {
            fillSiteTotals(total, market, null);
        }
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1))
        {
            for (PromoChannelDictionary.Channel c : listed)
            {
                Object[] row = sheet.newRow();
                String key = com.fivetech.configcenter.ConfigCodes.idKey(c.getId());
                row[0] = d.toString();
                row[1] = promoterOf.get(key);
                row[2] = c.getName();
                row[3] = c.getMedia() == null ? PromoChannelDictionary.MEDIA_UNKNOWN : c.getMedia();
                fillSpend(row, spendCube, d, d, inScope.and(PromoSpend.ofPath(null, c.getId())), key::equals);
            }
            Object[] dayTotal = sheet.newRow();
            dayTotal[0] = d.toString();
            dayTotal[2] = "當日合計";
            fillSpend(dayTotal, spendCube, d, d, inScope, k -> listed.stream()
                .anyMatch(c -> com.fivetech.configcenter.ConfigCodes.idKey(c.getId()).equals(k)));
            if (siteTotals)
            {
                LocalDate day = d;
                fillSiteTotals(dayTotal, market, b -> b.toLocalDate().equals(day));
            }
        }
        String base = siteName() + "_投放日報_" + compact(from, to);
        return file(sheet, base, "days=" + days + "&channels=" + listed.size() + "&rows=" + sheet.rows.size());
    }

    // ------------------------------------------------------------------ 渠道表现

    public PromoFile channels(ChannelTableQuery q)
    {
        analysis.applyView(q);
        PromoTime.Range range = PromoAnalysisService.wholeDays(PromoTime.range(q.getSlotFrom(), q.getSlotTo()));
        List<PromoMetricCatalog.Column> cols = PromoAnalysisService.columns(q.getColumns());
        ChannelTree tree = analysis.tree(q, range);
        PromoAnalysisService.sortOf(q.getSort(), "reg");
        long count = tree.count(0, List.of(), PromoErrors.MAX_EXPORT_ROWS);
        checkRows(count + 1);
        com.fivetech.promo.analysis.market.MarketCube cube = analysis.attachData(tree, q, range, cols);
        analysis.attachSpend(tree, range, cols);

        List<String> dims = tree.dims();
        List<String> dimNames = new ArrayList<>();
        dims.forEach(d -> dimNames.add(ChannelTree.DIM_NAMES.get(d)));
        PromoScope scope = analysis.scope(q.getAgents(), q.getChannels(), null);
        String title = siteName() + DOT + "推廣分析" + DOT + "渠道表現" + DOT + span(range.firstDay(), range.lastDay()) + DOT
            + "行維度：" + String.join(" › ", dimNames) + DOT + "渠道：" + scope.channelLabel() + DOT + "終端：全部" + DOT
            + "資料截至 " + asOfText(cube);
        PromoSheet sheet = new PromoSheet("渠道表現", title);
        dimNames.forEach(n -> sheet.column(n, PromoSheet.TEXT));
        cols.forEach(c -> sheet.column(c.getName(), c.getFormat()));

        Object[] total = sheet.newRow();
        total[0] = "合計";
        fillCells(total, dims.size(), cols, tree.cells(cols, List.of()));
        boolean channelWithoutAgent = dims.contains(ChannelTree.CHANNEL) && !dims.contains(ChannelTree.AGENT);
        walk(sheet, tree, cols, 0, new ArrayList<>(), new Object[dims.size()], channelWithoutAgent);

        String base = siteName() + "_渠道表現_" + compact(range.firstDay(), range.lastDay())
            + (tree.isDefault() ? "" : "_" + String.join("-", dimNames));
        return file(sheet, base, "rows=" + sheet.rows.size());
    }

    /** 深度优先平铺：上层行在下一层的列写「（小計）」，最后一层写完整路径 */
    private void walk(PromoSheet sheet, ChannelTree tree, List<PromoMetricCatalog.Column> cols, int level, List<PathItem> path,
        Object[] prefix, boolean channelWithoutAgent)
    {
        List<String> dims = tree.dims();
        for (ChannelTree.Member m : tree.members(level, path))
        {
            List<PathItem> p = new ArrayList<>(path);
            p.add(new PathItem(dims.get(level), m.key));
            Object[] row = sheet.newRow();
            System.arraycopy(prefix, 0, row, 0, level);
            String name = ChannelTree.CHANNEL.equals(dims.get(level)) && channelWithoutAgent && m.agent != null
                ? m.agent + " › " + m.name : m.name;
            row[level] = name;
            boolean last = level + 1 == dims.size();
            if (!last)
            {
                row[level + 1] = "（小計）";
            }
            fillCells(row, dims.size(), cols, tree.cells(cols, p));
            if (!last)
            {
                Object[] next = prefix.clone();
                next[level] = name;
                walk(sheet, tree, cols, level + 1, p, next, channelWithoutAgent);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void fillCells(Object[] row, int offset, List<PromoMetricCatalog.Column> cols, Map<String, Object> cells)
    {
        for (int i = 0; i < cols.size(); i++)
        {
            Map<String, Object> v = (Map<String, Object>) cells.get(cols.get(i).getCode());
            Object value = v == null ? null : v.get("value");
            if (value == null && v != null && "PENDING".equals(v.get("state")))
            {
                row[offset + i] = PromoSheet.PENDING_TEXT;
            }
            else
            {
                row[offset + i] = value;
            }
        }
    }

    // ------------------------------------------------------------------ 裂变层级

    public PromoFile fission(FissionQuery q)
    {
        PromoTime.Range range = PromoTime.range(q.getSlotFrom(), q.getSlotTo());
        PromoScope scope = analysis.scope(q.getAgents(), q.getChannels(), null);
        PromoAnalysisService.fissionSort(q.getSort());
        boolean top = q.getParentId() == null || q.getParentId().isBlank();
        PromoAnalysisService.Fission f = analysis.fission(q, range);
        // 层级：顶层写「一級代理」，进入某用户写他的帳號（上层路径要关系闭包，暂只有当前用户）
        String level = top ? "一級代理"
            : f.current != null && f.current.get("account") != null ? String.valueOf(f.current.get("account")) : q.getParentId().trim();
        String title = siteName() + DOT + "推廣分析" + DOT + "裂變層級" + DOT + span(range.firstDay(), range.lastDay()) + DOT
            + "層級：" + level + DOT + "渠道：" + scope.channelLabel() + DOT + "終端：全部" + DOT + "資料截至 " + asOfText();
        // 合計在第 3 行，其后是当前层全部行（已按请求的排序）
        PromoSheet sheet = new PromoSheet("裂變層級", title)
            .column("用戶帳號", PromoSheet.TEXT)
            .column("用戶 ID", PromoSheet.TEXT)
            .column("推廣商", PromoSheet.TEXT)
            .column("推廣渠道", PromoSheet.TEXT)
            .column("層級", PromoSheet.INT)
            .column("直屬下級人數（累計）", PromoSheet.INT)
            .column("新增下級", PromoSheet.INT)
            .column("首存人數", PromoSheet.INT)
            .column("存款人數", PromoSheet.INT)
            .column("提款人數", PromoSheet.INT)
            .column("存款總額", PromoSheet.MONEY)
            .column("投注總額", PromoSheet.MONEY)
            .column("會員輸贏", PromoSheet.MONEY);
        checkRows(f.rows.size() + 1);
        Object[] total = sheet.newRow();
        total[0] = "合計";
        fissionCells(total, f.total);
        for (Map<String, Object> r : f.rows)
        {
            Object[] row = sheet.newRow();
            row[0] = r.get("account");
            row[1] = r.get("userId");
            row[2] = r.get("agent");
            row[3] = r.get("channel");
            row[4] = r.get("level") == null ? null : new java.math.BigDecimal(r.get("level").toString());
            fissionCells(row, r);
        }
        String base = siteName() + "_裂變層級_" + compact(range.firstDay(), range.lastDay());
        return file(sheet, base, "rows=" + f.rows.size());
    }

    private static void fissionCells(Object[] row, Map<String, Object> v)
    {
        String[] keys = { "directCount", "newSub", "ftd", "depU", "wdU", "depAmt", "betAmt", "winLoss" };
        for (int i = 0; i < keys.length; i++)
        {
            Object x = v.get(keys[i]);
            row[5 + i] = x == null ? null : new java.math.BigDecimal(x.toString());
        }
    }
}
