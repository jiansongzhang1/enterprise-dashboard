package com.fivetech.dashboard.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.dashboard.domain.vo.MetricCardVO;
import com.fivetech.dashboard.domain.vo.MetricsBlockVO;
import com.fivetech.dashboard.domain.vo.OverviewBlockErrorVO;
import com.fivetech.dashboard.domain.vo.OverviewNoticeVO;
import com.fivetech.dashboard.domain.vo.OverviewVO;

/**
 * 运营总览导出：把一次查询的结果写成 XLSX 字节数组。
 *
 * <p><b>为什么整份在内存里生成完再返回</b>：总览一次最多 20 张卡 × 数百个时间片，
 * 撑死几百 KB。全量生成完再写第一个响应字节，意味着生成途中抛异常时
 * 还能回一个正常的 JSON 错误体；一旦边生成边 flush，响应头已经发出去了，
 * 前端只会收到一个半截的、打不开的文件。明细导出数据量大，仍然走任务中心。</p>
 *
 * <p><b>数值写成数字而不是字符串</b>：写成 "12,345" 或 "15.82%" 之后
 * Excel 里就没法求和、没法排序，运营拿到手第一件事就是要排序。
 * 单位用单元格格式表达，值本身保持裸数值。</p>
 *
 * @author fivetech
 */
@Component
public class OverviewXlsxExporter
{
    /** 三张 sheet 的名字，和页面上的区块名一一对应 */
    private static final String SHEET_METRICS = "主要指标";

    private static final String SHEET_SERIES = "时间序列";

    private static final String SHEET_NOTES = "口径说明";

    /**
     * 生成工作簿。
     *
     * @param vo 已查好的总览数据，值必须是已归一化的展示量纲（比率已 ×100、时长已 ÷60）
     * @return xlsx 字节
     */
    public byte[] export(OverviewVO vo)
    {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream())
        {
            Styles styles = new Styles(wb);

            MetricsBlockVO metrics = block(vo, "METRICS", MetricsBlockVO.class);

            if (metrics != null)
            {
                writeMetrics(wb, styles, vo, metrics);
                writeSeries(wb, styles, vo, metrics);
            }
            // 口径说明永远写：导出的文件会脱离页面流转，看到数的人多半没看过顶栏的提示
            writeNotes(wb, styles, vo);

            wb.write(out);
            return out.toByteArray();
        }
        catch (IOException e)
        {
            throw new ServiceException("导出文件生成失败：" + e.getMessage(), 5002);
        }
    }

    /** 文件名带上区间与粒度，导出多次之后还能分清哪个是哪个 */
    public String fileName(OverviewVO vo)
    {
        String from = vo.getSlot() == null ? "" : safe(vo.getSlot().getFrom());
        String to = vo.getSlot() == null ? "" : safe(vo.getSlot().getTo());
        return "运营总览_" + from + "_" + to + ".xlsx";
    }

    private static String safe(String text)
    {
        return text == null ? "" : text.replace(":", "").replace(" ", "_").replace("-", "");
    }

    @SuppressWarnings("unchecked")
    private static <T> T block(OverviewVO vo, String key, Class<T> type)
    {
        Object value = vo.getBlocks().get(key);
        return type.isInstance(value) ? (T) value : null;
    }

    // ===================== sheet 1 主要指标 =====================

    private void writeMetrics(Workbook wb, Styles styles, OverviewVO vo, MetricsBlockVO metrics)
    {
        Sheet sheet = wb.createSheet(SHEET_METRICS);
        int r = 0;
        r = writeHeaderBanner(sheet, styles, vo, r);

        String[] head = { "指标编码", "指标名称", "分组", "是否核心", "当期值", "单位",
            "对比期值", "变化量", "变化率(%)", "变化(百分点)", "口径" };
        writeHead(sheet, styles, r++, head);

        for (MetricCardVO card : metrics.getItems())
        {
            Row row = sheet.createRow(r++);
            int c = 0;
            text(row, c++, card.getCode(), styles.body);
            text(row, c++, card.getLabel(), styles.body);
            text(row, c++, card.getGroupLabel() == null ? card.getGroup() : card.getGroupLabel(), styles.body);
            text(row, c++, "CORE".equals(card.getEmphasis()) ? "是" : "", styles.center);
            number(row, c++, card.getValue(), styles.of(card.getValueFormat(), card.getDecimals()));
            text(row, c++, unitOf(card.getValueFormat()), styles.center);
            number(row, c++, card.getPrevValue(), styles.of(card.getValueFormat(), card.getDecimals()));
            number(row, c++, card.getDelta(), styles.of(card.getValueFormat(), card.getDecimals()));
            number(row, c++, card.getDeltaPct(), styles.decimal2);
            number(row, c++, card.getDeltaPt(), styles.decimal2);
            text(row, c, card.getExpression(), styles.body);
        }
        autoSize(sheet, head.length);
    }

    // ===================== sheet 2 时间序列 =====================

    /**
     * 时间序列按「一行一个时间片、一列一个指标」排。
     *
     * <p>反过来（一行一个指标）在 Excel 里画不出折线图，而导出这份表的人
     * 十有八九就是要自己画图。</p>
     */
    private void writeSeries(Workbook wb, Styles styles, OverviewVO vo, MetricsBlockVO metrics)
    {
        List<MetricCardVO> cards = new ArrayList<>();
        for (MetricCardVO card : metrics.getItems())
        {
            if (card.getSeries() != null && !card.getSeries().isEmpty())
            {
                cards.add(card);
            }
        }
        if (cards.isEmpty())
        {
            // includeSeries=false 时整张 sheet 没有意义，不建空表
            return;
        }

        Sheet sheet = wb.createSheet(SHEET_SERIES);
        int r = 0;
        List<String> labels = vo.getSlot() == null ? new ArrayList<>() : vo.getSlot().getLabels();

        String[] head = new String[cards.size() + 1];
        head[0] = "时间片";
        for (int i = 0; i < cards.size(); i++)
        {
            head[i + 1] = cards.get(i).getLabel();
        }
        writeHead(sheet, styles, r++, head);

        int points = vo.getSlot() == null ? 0 : vo.getSlot().getPoints();
        for (int i = 0; i < points; i++)
        {
            Row row = sheet.createRow(r++);
            text(row, 0, i < labels.size() ? labels.get(i) : String.valueOf(i + 1), styles.body);
            for (int j = 0; j < cards.size(); j++)
            {
                MetricCardVO card = cards.get(j);
                List<BigDecimal> series = card.getSeries();
                BigDecimal value = i < series.size() ? series.get(i) : null;
                number(row, j + 1, value, styles.of(card.getValueFormat(), card.getDecimals()));
            }
        }
        autoSize(sheet, head.length);
    }

    // ===================== sheet 3 口径说明 =====================

    private void writeNotes(Workbook wb, Styles styles, OverviewVO vo)
    {
        Sheet sheet = wb.createSheet(SHEET_NOTES);
        int r = 0;

        r = kv(sheet, styles, r, "统计区间", slotText(vo));
        r = kv(sheet, styles, r, "时间语义", "左闭右开，末端时间片不含在内");
        r = kv(sheet, styles, r, "时区", vo.getTimezone());
        r = kv(sheet, styles, r, "币种", vo.getCurrency());
        r = kv(sheet, styles, r, "数据截至", vo.getAsOf() == null ? "—" : vo.getAsOf());
        r = kv(sheet, styles, r, "更新时间", vo.getUpdatedAt() == null ? "—" : vo.getUpdatedAt());
        r = kv(sheet, styles, r, "对比期",
            vo.getCompare() == null ? "未启用" : vo.getCompare().getFrom() + " ~ " + vo.getCompare().getTo());
        r = kv(sheet, styles, r, "数据版本", vo.getDataVersion());
        r++;

        if (!vo.getNotices().isEmpty())
        {
            writeHead(sheet, styles, r++, new String[] { "提示等级", "编码", "说明", "详情" });
            for (OverviewNoticeVO notice : vo.getNotices())
            {
                Row row = sheet.createRow(r++);
                text(row, 0, notice.getLevel(), styles.center);
                text(row, 1, notice.getCode(), styles.body);
                text(row, 2, notice.getMessage(), styles.body);
                text(row, 3, notice.getDetail(), styles.body);
            }
            r++;
        }

        if (!vo.getBlockErrors().isEmpty())
        {
            // 某一块取数失败时导出的文件里会整块缺失，必须在文件里说清楚，
            // 否则拿到文件的人会以为那块「本来就没数」
            writeHead(sheet, styles, r++, new String[] { "失败区块", "错误码", "原因" });
            for (java.util.Map.Entry<String, OverviewBlockErrorVO> entry : vo.getBlockErrors().entrySet())
            {
                Row row = sheet.createRow(r++);
                text(row, 0, entry.getKey(), styles.body);
                text(row, 1, String.valueOf(entry.getValue().getCode()), styles.center);
                text(row, 2, entry.getValue().getMessage(), styles.body);
            }
        }
        autoSize(sheet, 4);
    }

    private static String slotText(OverviewVO vo)
    {
        if (vo.getSlot() == null)
        {
            return "";
        }
        return vo.getSlot().getFrom() + " ~ " + vo.getSlot().getTo()
            + "（" + vo.getSlot().getGranularity() + "，" + vo.getSlot().getPoints() + " 个时间片）";
    }

    private int kv(Sheet sheet, Styles styles, int r, String key, String value)
    {
        Row row = sheet.createRow(r);
        text(row, 0, key, styles.head);
        text(row, 1, value == null ? "" : value, styles.body);
        return r + 1;
    }

    // ===================== 通用写单元格 =====================

    private int writeHeaderBanner(Sheet sheet, Styles styles, OverviewVO vo, int r)
    {
        Row row = sheet.createRow(r);
        text(row, 0, "统计区间", styles.head);
        text(row, 1, slotText(vo), styles.body);
        text(row, 3, "数据截至", styles.head);
        text(row, 4, vo.getAsOf() == null ? "—" : vo.getAsOf(), styles.body);
        return r + 2;
    }

    private void writeHead(Sheet sheet, Styles styles, int r, String[] titles)
    {
        Row row = sheet.createRow(r);
        for (int i = 0; i < titles.length; i++)
        {
            text(row, i, titles[i], styles.head);
        }
    }

    private static void text(Row row, int column, String value, CellStyle style)
    {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    /**
     * 写数值。<b>null 留空单元格，不写 0</b>——0 是一个结论，空是「没有这个数」，
     * 在导出的文件里混起来没人能再区分。
     */
    private static void number(Row row, int column, BigDecimal value, CellStyle style)
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
                return "元";
            default:
                return "";
        }
    }

    private static void autoSize(Sheet sheet, int columns)
    {
        for (int i = 0; i < columns; i++)
        {
            sheet.autoSizeColumn(i);
            int width = sheet.getColumnWidth(i);
            // autoSizeColumn 不认中文字宽，统一加一点余量并夹上限，避免出现超宽列
            sheet.setColumnWidth(i, Math.min(60 * 256, (int) (width * 1.3) + 512));
        }
    }

    /** 单元格样式池。POI 的样式数量有上限，必须复用，不能每个单元格 new 一个 */
    private static final class Styles
    {
        private final CellStyle head;

        private final CellStyle body;

        private final CellStyle center;

        private final CellStyle integer;

        private final CellStyle decimal1;

        private final CellStyle decimal2;

        private final CellStyle money;

        private Styles(Workbook wb)
        {
            CreationHelper helper = wb.getCreationHelper();

            Font bold = wb.createFont();
            bold.setBold(true);
            head = wb.createCellStyle();
            head.setFont(bold);
            head.setAlignment(HorizontalAlignment.CENTER);

            body = wb.createCellStyle();

            center = wb.createCellStyle();
            center.setAlignment(HorizontalAlignment.CENTER);

            integer = wb.createCellStyle();
            integer.setDataFormat(helper.createDataFormat().getFormat("#,##0"));

            decimal1 = wb.createCellStyle();
            decimal1.setDataFormat(helper.createDataFormat().getFormat("#,##0.0"));

            decimal2 = wb.createCellStyle();
            decimal2.setDataFormat(helper.createDataFormat().getFormat("#,##0.00"));

            money = wb.createCellStyle();
            money.setDataFormat(helper.createDataFormat().getFormat("#,##0.00"));
        }

        /**
         * 按指标的展示格式挑样式。
         *
         * <p>这里只决定小数位与千分位，<b>不做任何数值换算</b>——比率已经在
         * MetricValueNormalizer 里 ×100 过了，如果这里再套 Excel 的百分比格式，
         * 15.82 会显示成 1582%。</p>
         */
        private CellStyle of(String valueFormat, Integer decimals)
        {
            if (decimals != null)
            {
                return decimals <= 0 ? integer : (decimals == 1 ? decimal1 : decimal2);
            }
            if (valueFormat == null)
            {
                return integer;
            }
            switch (valueFormat.toUpperCase())
            {
                case "PCT":
                case "MULTIPLE":
                    return decimal2;
                case "MIN":
                    return decimal1;
                case "MONEY":
                    return money;
                default:
                    return integer;
            }
        }
    }
}
