package com.fivetech.dashboard.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fivetech.common.core.domain.entity.SysUser;
import com.fivetech.common.core.domain.model.LoginUser;
import com.fivetech.common.enums.BusinessType;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.common.utils.SecurityUtils;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.common.utils.TraceIdUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.domain.query.BaseDashboardQuery;
import com.fivetech.dashboard.domain.query.BetRecordQuery;
import com.fivetech.dashboard.domain.query.DepositRecordQuery;
import com.fivetech.dashboard.domain.query.MemberRecordQuery;
import com.fivetech.dashboard.domain.query.MetricSummaryQuery;
import com.fivetech.dashboard.domain.query.WithdrawRecordQuery;
import com.fivetech.dashboard.domain.vo.BetRecordVO;
import com.fivetech.dashboard.domain.vo.ColumnMetaVO;
import com.fivetech.dashboard.domain.vo.DepositRecordVO;
import com.fivetech.dashboard.domain.vo.MemberRecordVO;
import com.fivetech.dashboard.domain.vo.MetricSummaryRowVO;
import com.fivetech.dashboard.domain.vo.MetricSummaryVO;
import com.fivetech.dashboard.domain.vo.QueryContext;
import com.fivetech.dashboard.domain.vo.RecordResultVO;
import com.fivetech.dashboard.domain.vo.WithdrawRecordVO;
import com.fivetech.dashboard.service.IMetricSummaryService;
import com.fivetech.dashboard.service.IRecordQueryService;
import com.fivetech.system.domain.SysOperLog;
import com.fivetech.system.service.ISysOperLogService;

/**
 * 仪表板同步导出（XLSX）：指标汇总与四张明细表。
 * <p>
 * 导出走的是与页面<b>完全相同</b>的查询链路（权限、时间语义、筛选、白名单都复用），
 * 只是把分页换成「当前筛选条件下的全部数据」，再写成 XLSX。「看到的」和「导出的」永远是同一份数据。
 * <p>
 * 实现要点：
 * <ul>
 *   <li><b>SXSSF 流式写</b>：内存里只留最近 {@value #WINDOW} 行，其余落临时文件，10 万行也不会撑爆堆；</li>
 *   <li><b>整份生成完再返回</b>：生成途中失败（权限、区间、数据平台故障）还能回 JSON 错误体，
 *       不会让浏览器收到一个打不开的半截文件；</li>
 *   <li><b>数值写成数字</b>：金额、人数、比率是数字单元格，Excel 里能直接求和排序；
 *       单位靠单元格格式表达；无数据写空单元格，不写 0；</li>
 *   <li>两张 sheet：「数据」在前，「口径说明」在后——文件离开系统后，口径只能靠它。</li>
 * </ul>
 *
 * @author fivetech
 */
@Component
public class DashboardXlsxExporter
{
    private static final Logger log = LoggerFactory.getLogger(DashboardXlsxExporter.class);

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** SXSSF 内存窗口行数 */
    private static final int WINDOW = 200;

    /** Excel 单元格文本上限 32767 字符，超出会直接抛异常 */
    private static final int MAX_CELL_TEXT = 32000;

    /** Excel 单张 sheet 行数上限（含表头） */
    private static final int MAX_SHEET_ROWS = 1_048_575;

    private static final String SHEET_DATA = "数据";

    private static final String SHEET_NOTES = "口径说明";

    private final DashboardProperties properties;

    private final IMetricSummaryService metricSummaryService;

    private final IRecordQueryService recordQueryService;

    private final ISysOperLogService operLogService;

    public DashboardXlsxExporter(DashboardProperties properties,
            IMetricSummaryService metricSummaryService, IRecordQueryService recordQueryService,
            ISysOperLogService operLogService)
    {
        this.properties = properties;
        this.metricSummaryService = metricSummaryService;
        this.recordQueryService = recordQueryService;
        this.operLogService = operLogService;
    }

    /**
     * 记录导出审计。
     * <p>
     * 只在真正导出时记录——把 {@code @Log} 挂在控制器上会让每次普通查询
     * 都被记成一条「导出」，很快就把操作日志淹掉。
     * 明细含个人信息，谁在什么条件下导走了多少行必须可追溯。
     */
    private void audit(String title, int rows, boolean truncated)
    {
        try
        {
            SysOperLog operLog = new SysOperLog();
            operLog.setTitle("仪表板导出(xlsx) · " + title);
            operLog.setBusinessType(BusinessType.EXPORT.ordinal());
            operLog.setOperatorType(1);
            operLog.setOperName(currentUser());
            operLog.setDeptName(currentDept());
            operLog.setRequestMethod("POST");
            operLog.setStatus(0);
            operLog.setOperTime(new Date());
            operLog.setOperParam("traceId=" + TraceIdUtils.get()
                + ", rows=" + rows + (truncated ? ", truncated=true" : ""));
            operLog.setJsonResult("导出 " + rows + " 行");
            operLogService.insertOperlog(operLog);
        }
        catch (Exception e)
        {
            // 审计失败不能让导出失败，但必须留痕
            log.warn("导出审计写入失败: {}", e.getMessage());
        }
    }

    private String currentDept()
    {
        try
        {
            LoginUser loginUser = SecurityUtils.getLoginUser();
            SysUser user = loginUser == null ? null : loginUser.getUser();
            return user == null || user.getDept() == null ? null : user.getDept().getDeptName();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /** 导出结果的文件名（不含路径） */
    public String fileName(String label)
    {
        return label + "_" + LocalDateTime.now().format(STAMP) + ".xlsx";
    }

    // ===================== 指标汇总 =====================

    public byte[] exportMetricSummary(MetricSummaryQuery query)
    {
        precheck(query);
        applyExportPaging(query);
        MetricSummaryVO vo = metricSummaryService.query(query);
        List<ColumnMetaVO> columns = vo.getColumns();
        byte[] file = write(vo.getContext(), "指标汇总", null, (sheet, styles) -> {
            int r = writeHead(sheet, styles, columns);
            for (MetricSummaryRowVO row : vo.getPage().getRows())
            {
                List<Object> line = new ArrayList<>();
                line.add(row.getLabel());
                appendMetricCells(line, columns, row.getValues());
                writeRow(sheet, styles, r++, columns, line, false);
            }
            // 区间合计：与页面同口径，由服务端重算，不是上面各行的加总
            List<Object> total = new ArrayList<>();
            total.add("区间合计");
            appendMetricCells(total, columns, vo.getTotalRow());
            writeRow(sheet, styles, r, columns, total, true);
        });
        audit("指标汇总", vo.getPage().getRows().size(), false);
        return file;
    }

    private void appendMetricCells(List<Object> line, List<ColumnMetaVO> columns, Map<String, BigDecimal> values)
    {
        for (ColumnMetaVO column : columns)
        {
            if ("time".equals(column.getCode()))
            {
                continue;
            }
            // 无数据写空，不写 0
            line.add(values == null ? null : values.get(column.getCode()));
        }
    }

    // ===================== 明细 =====================

    public byte[] exportMembers(MemberRecordQuery query)
    {
        precheck(query);
        applyExportPaging(query);
        RecordResultVO<MemberRecordVO> vo = recordQueryService.queryMembers(query);
        // 会员表 27 列由用户自选显示：传了 columns 就只导出这些列（必选列总会带上），否则导出全部
        List<ColumnMetaVO> columns = selectColumns(vo.getColumns(), query.getColumns());
        byte[] file = write(vo.getContext(), "会员明细", null, (sheet, styles) -> {
            int r = writeHead(sheet, styles, columns);
            for (MemberRecordVO row : vo.getPage().getRows())
            {
                writeRow(sheet, styles, r++, columns, memberCells(columns, row), false);
            }
        });
        audit("会员明细", vo.getPage().getRows().size(), vo.getPage().isTruncated());
        return file;
    }

    public byte[] exportDeposits(DepositRecordQuery query)
    {
        precheck(query);
        applyExportPaging(query);
        RecordResultVO<DepositRecordVO> vo = recordQueryService.queryDeposits(query);
        byte[] file = write(vo.getContext(), "存款明细", ORDER_NOTE, (sheet, styles) -> {
            int r = writeHead(sheet, styles, vo.getColumns());
            for (DepositRecordVO row : vo.getPage().getRows())
            {
                writeRow(sheet, styles, r++, vo.getColumns(), depositCells(vo.getColumns(), row), false);
            }
        });
        audit("存款明细", vo.getPage().getRows().size(), vo.getPage().isTruncated());
        return file;
    }

    public byte[] exportWithdrawals(WithdrawRecordQuery query)
    {
        precheck(query);
        applyExportPaging(query);
        RecordResultVO<WithdrawRecordVO> vo = recordQueryService.queryWithdrawals(query);
        byte[] file = write(vo.getContext(), "提款明细", ORDER_NOTE, (sheet, styles) -> {
            int r = writeHead(sheet, styles, vo.getColumns());
            for (WithdrawRecordVO row : vo.getPage().getRows())
            {
                writeRow(sheet, styles, r++, vo.getColumns(), withdrawCells(vo.getColumns(), row), false);
            }
        });
        audit("提款明细", vo.getPage().getRows().size(), vo.getPage().isTruncated());
        return file;
    }

    public byte[] exportBets(BetRecordQuery query)
    {
        precheck(query);
        applyExportPaging(query);
        RecordResultVO<BetRecordVO> vo = recordQueryService.queryBets(query);
        byte[] file = write(vo.getContext(), "投注明细", null, (sheet, styles) -> {
            int r = writeHead(sheet, styles, vo.getColumns());
            for (BetRecordVO row : vo.getPage().getRows())
            {
                writeRow(sheet, styles, r++, vo.getColumns(), betCells(vo.getColumns(), row), false);
            }
        });
        audit("投注明细", vo.getPage().getRows().size(), vo.getPage().isTruncated());
        return file;
    }

    /** 存提款金额是原币种：文件里必须写明，否则拿到文件的人会把不同币种直接相加 */
    private static final String ORDER_NOTE = "金额为订单原币种（见「币种」列），未做汇率折算，不同币种请勿直接相加";

    // ===================== 行取值 =====================

    /** 按用户选择的列编码挑列：保持注册表里的固定顺序，忽略白名单外的编码，必选列总是保留 */
    private List<ColumnMetaVO> selectColumns(List<ColumnMetaVO> all, List<String> wanted)
    {
        if (wanted == null || wanted.isEmpty())
        {
            return all;
        }
        List<ColumnMetaVO> result = new ArrayList<>();
        for (ColumnMetaVO c : all)
        {
            if (c.isLocked() || wanted.contains(c.getCode()))
            {
                result.add(c);
            }
        }
        return result;
    }

    private List<Object> memberCells(List<ColumnMetaVO> columns, MemberRecordVO r)
    {
        List<Object> line = new ArrayList<>();
        for (ColumnMetaVO c : columns)
        {
            switch (c.getCode())
            {
                case "userId": line.add(r.getUserId()); break;
                case "username": line.add(r.getUsername()); break;
                case "status": line.add(label(r.getStatusLabel(), r.getStatus())); break;
                case "userType": line.add(label(r.getUserTypeLabel(), r.getUserType())); break;
                case "level": line.add(label(r.getLevelLabel(), r.getLevel())); break;
                case "riskLevel": line.add(label(r.getRiskLevelLabel(), r.getRiskLevel())); break;
                case "country": line.add(label(r.getCountryLabel(), r.getCountry())); break;
                case "registerTime": line.add(r.getRegisterTime()); break;
                case "firstDepositTime": line.add(r.getFirstDepositTime()); break;
                case "firstDepositAmount": line.add(r.getFirstDepositAmount()); break;
                case "firstDepositChannel": line.add(r.getFirstDepositChannel()); break;
                case "regToFtdHours": line.add(r.getRegToFtdHours()); break;
                case "lastDepositTime": line.add(r.getLastDepositTime()); break;
                case "lastDepositAmount": line.add(r.getLastDepositAmount()); break;
                case "cumulativeDepositAmount": line.add(r.getCumulativeDepositAmount()); break;
                case "cumulativeDepositCount": line.add(r.getCumulativeDepositCount()); break;
                case "lastBetTime": line.add(r.getLastBetTime()); break;
                case "lastBetAmount": line.add(r.getLastBetAmount()); break;
                case "cumulativeBetAmount": line.add(r.getCumulativeBetAmount()); break;
                case "cumulativeBetCount": line.add(r.getCumulativeBetCount()); break;
                case "turnoverMultiple": line.add(r.getTurnoverMultiple()); break;
                case "lastWithdrawTime": line.add(r.getLastWithdrawTime()); break;
                case "lastWithdrawAmount": line.add(r.getLastWithdrawAmount()); break;
                case "cumulativeWithdrawAmount": line.add(r.getCumulativeWithdrawAmount()); break;
                case "cumulativeWithdrawCount": line.add(r.getCumulativeWithdrawCount()); break;
                case "cumulativeGgr": line.add(r.getCumulativeGgr()); break;
                case "cumulativeNgr": line.add(r.getCumulativeNgr()); break;
                default: line.add(null);
            }
        }
        return line;
    }

    private List<Object> depositCells(List<ColumnMetaVO> columns, DepositRecordVO r)
    {
        List<Object> line = new ArrayList<>();
        for (ColumnMetaVO c : columns)
        {
            switch (c.getCode())
            {
                case "orderNo": line.add(r.getOrderNo()); break;
                case "userId": line.add(r.getUserId()); break;
                case "username": line.add(r.getUsername()); break;
                case "amount": line.add(r.getAmount()); break;
                case "currency": line.add(r.getCurrency()); break;
                case "status": line.add(label(r.getStatusLabel(), r.getStatus())); break;
                case "createTime": line.add(r.getCreateTime()); break;
                case "finishTime": line.add(r.getFinishTime()); break;
                case "costMinutes": line.add(r.getCostMinutes()); break;
                default: line.add(null);
            }
        }
        return line;
    }

    private List<Object> withdrawCells(List<ColumnMetaVO> columns, WithdrawRecordVO r)
    {
        List<Object> line = new ArrayList<>();
        for (ColumnMetaVO c : columns)
        {
            switch (c.getCode())
            {
                case "orderNo": line.add(r.getOrderNo()); break;
                case "userId": line.add(r.getUserId()); break;
                case "username": line.add(r.getUsername()); break;
                case "amount": line.add(r.getAmount()); break;
                case "currency": line.add(r.getCurrency()); break;
                case "status": line.add(label(r.getStatusLabel(), r.getStatus())); break;
                case "auditStatus": line.add(label(r.getAuditStatusLabel(), r.getAuditStatus())); break;
                case "auditor": line.add(r.getAuditor()); break;
                case "payer": line.add(r.getPayer()); break;
                case "payTime": line.add(r.getPayTime()); break;
                case "bankName": line.add(r.getBankName()); break;
                case "bankCode": line.add(r.getBankCode()); break;
                case "bankCountry": line.add(label(r.getBankCountryLabel(), r.getBankCountry())); break;
                case "createTime": line.add(r.getCreateTime()); break;
                case "finishTime": line.add(r.getFinishTime()); break;
                case "costMinutes": line.add(r.getCostMinutes()); break;
                case "auditNote": line.add(r.getAuditNote()); break;
                default: line.add(null);
            }
        }
        return line;
    }

    private List<Object> betCells(List<ColumnMetaVO> columns, BetRecordVO r)
    {
        List<Object> line = new ArrayList<>();
        for (ColumnMetaVO c : columns)
        {
            switch (c.getCode())
            {
                case "orderNo": line.add(r.getOrderNo()); break;
                case "userId": line.add(r.getUserId()); break;
                case "username": line.add(r.getUsername()); break;
                case "vendorCode": line.add(r.getVendorCode()); break;
                case "vendorName": line.add(r.getVendorName()); break;
                case "gameType": line.add(label(r.getGameTypeLabel(), r.getGameType())); break;
                case "gameId": line.add(r.getGameId()); break;
                case "gameName": line.add(r.getGameName()); break;
                case "betAmount": line.add(r.getBetAmount()); break;
                case "payout": line.add(r.getPayout()); break;
                case "winLoss": line.add(r.getWinLoss()); break;
                case "settleStatus": line.add(label(r.getSettleStatusLabel(), r.getSettleStatus())); break;
                case "betTime": line.add(r.getBetTime()); break;
                case "settleTime": line.add(r.getSettleTime()); break;
                default: line.add(null);
            }
        }
        return line;
    }

    // ===================== 工作簿 =====================

    /**
     * 生成工作簿：先写「数据」sheet，再写「口径说明」sheet。
     *
     * @param extraNote 追加到口径说明里的一行（如订单表的币种提示），可为 null
     */
    private byte[] write(QueryContext ctx, String title, String extraNote, SheetBody body)
    {
        SXSSFWorkbook wb = new SXSSFWorkbook(WINDOW);
        wb.setCompressTempFiles(true);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream())
        {
            Styles styles = new Styles(wb);
            SXSSFSheet data = wb.createSheet(SHEET_DATA);
            data.createFreezePane(0, 1);
            body.write(data, styles);
            writeNotes(wb.createSheet(SHEET_NOTES), styles, ctx, title, extraNote);
            wb.write(out);
            return out.toByteArray();
        }
        catch (IOException e)
        {
            throw new ServiceException("导出文件生成失败：" + e.getMessage(), 5002);
        }
        finally
        {
            // SXSSF 的临时文件必须显式清理，否则会在 /tmp 越积越多
            wb.dispose();
            try
            {
                wb.close();
            }
            catch (IOException ignore)
            {
                // 关闭失败不影响已生成的字节
            }
        }
    }

    /** 表头一行，返回下一行行号；同时按列定义设好列宽 */
    private int writeHead(Sheet sheet, Styles styles, List<ColumnMetaVO> columns)
    {
        Row head = sheet.createRow(0);
        for (int i = 0; i < columns.size(); i++)
        {
            Cell cell = head.createCell(i);
            cell.setCellValue(columns.get(i).getLabel());
            cell.setCellStyle(styles.head);
            sheet.setColumnWidth(i, widthOf(columns.get(i)));
        }
        return 1;
    }

    /** 一行数据。数值按列格式写成数字单元格，其余写文本；null 写空单元格 */
    private void writeRow(Sheet sheet, Styles styles, int r, List<ColumnMetaVO> columns, List<Object> values,
            boolean bold)
    {
        if (r > MAX_SHEET_ROWS)
        {
            throw new ServiceException("导出行数超过 Excel 单表上限，请缩小筛选范围");
        }
        Row row = sheet.createRow(r);
        for (int i = 0; i < values.size(); i++)
        {
            Object v = values.get(i);
            if (v == null)
            {
                continue;
            }
            Cell cell = row.createCell(i);
            String format = i < columns.size() ? columns.get(i).getFormat() : null;
            if (v instanceof Number)
            {
                cell.setCellValue(((Number) v).doubleValue());
                cell.setCellStyle(styles.number(format, bold));
            }
            else
            {
                String text = String.valueOf(v);
                cell.setCellValue(text.length() > MAX_CELL_TEXT ? text.substring(0, MAX_CELL_TEXT) + "…" : text);
                cell.setCellStyle(bold ? styles.textBold : styles.text);
            }
        }
    }

    private static int widthOf(ColumnMetaVO column)
    {
        String f = column.getFormat() == null ? "TEXT" : column.getFormat();
        int chars;
        switch (f)
        {
            case "TIME":
                chars = 18;
                break;
            case "LONGTEXT":
                chars = 50;
                break;
            case "MONEY":
            case "MONEY_CUR":
            case "MONEY1":
                chars = 16;
                break;
            default:
                chars = Math.max(10, column.getLabel() == null ? 10 : column.getLabel().length() * 2 + 4);
        }
        return Math.min(255, chars) * 256;
    }

    /**
     * 写「口径说明」sheet。
     * <p>
     * 文件离开系统之后，这几行是它唯一的口径载体——没有它，
     * 一份表格过两天就没人说得清区间、时区和口径版本。
     */
    private void writeNotes(Sheet sheet, Styles styles, QueryContext ctx, String title, String extraNote)
    {
        sheet.setColumnWidth(0, 14 * 256);
        sheet.setColumnWidth(1, 80 * 256);
        List<String[]> lines = new ArrayList<>();
        lines.add(new String[] {"报表名称", title});
        lines.add(new String[] {"导出时间", LocalDateTime.now().format(HUMAN)});
        lines.add(new String[] {"导出人", currentUser()});
        if (properties.getExport().isIncludeMeta() && ctx != null)
        {
            lines.add(new String[] {"站点", nvl(ctx.getSiteCode())});
            lines.add(new String[] {"币别", nvl(ctx.getCurrency())});
            lines.add(new String[] {"统计时区", nvl(ctx.getTimezone()) + " · 日切 00:00"});
            lines.add(new String[] {"统计区间", nvl(ctx.getSpanFrom()) + " ~ " + nvl(ctx.getSpanTo()) + "（左闭右开）"});
            if (ctx.getGranularity() != null)
            {
                lines.add(new String[] {"粒度", ctx.getGranularity().name()});
            }
            lines.add(new String[] {"数据截止", nvl(ctx.getAsOf())});
            lines.add(new String[] {"计算完成", nvl(ctx.getUpdatedAt())});
            lines.add(new String[] {"口径版本", nvl(ctx.getRegistryVersion())});
            if (ctx.isDelayed())
            {
                lines.add(new String[] {"数据延迟", "本区间含约 " + ctx.getDelayWindowHours() + " 小时延迟数据，数值可能上调"});
            }
            if (ctx.getWarnings() != null && !ctx.getWarnings().isEmpty())
            {
                lines.add(new String[] {"提示", String.join(" / ", ctx.getWarnings())});
            }
        }
        if (extraNote != null)
        {
            lines.add(new String[] {"金额说明", extraNote});
        }
        lines.add(new String[] {"空值说明", "空单元格表示无数据，请勿当作 0 参与计算"});
        lines.add(new String[] {"免责声明", "本文件为业务参考，财务数据以财务系统为准"});
        int r = 0;
        for (String[] line : lines)
        {
            Row row = sheet.createRow(r++);
            Cell k = row.createCell(0);
            k.setCellValue(line[0]);
            k.setCellStyle(styles.textBold);
            Cell v = row.createCell(1);
            v.setCellValue(line[1]);
            v.setCellStyle(styles.text);
        }
    }

    /** 单元格样式。POI 单个工作簿的样式数有上限，必须复用，不能每个单元格 new 一个 */
    private static final class Styles
    {
        private final Workbook wb;

        private final CellStyle head;

        private final CellStyle text;

        private final CellStyle textBold;

        private final Font boldFont;

        private final Map<String, CellStyle> numbers = new HashMap<>();

        private Styles(Workbook wb)
        {
            this.wb = wb;
            boldFont = wb.createFont();
            boldFont.setBold(true);
            head = wb.createCellStyle();
            head.setFont(boldFont);
            text = wb.createCellStyle();
            textBold = wb.createCellStyle();
            textBold.setFont(boldFont);
        }

        /** 按列格式取数字样式：金额千分位两位小数、人数整数、比率两位小数…… */
        private CellStyle number(String format, boolean bold)
        {
            String pattern = patternOf(format);
            return numbers.computeIfAbsent(pattern + (bold ? "|b" : ""), k -> {
                CellStyle s = wb.createCellStyle();
                s.setDataFormat(wb.createDataFormat().getFormat(pattern));
                if (bold)
                {
                    s.setFont(boldFont);
                }
                return s;
            });
        }

        private static String patternOf(String format)
        {
            if (format == null)
            {
                return "General";
            }
            switch (format)
            {
                case "MONEY":
                case "MONEY_CUR":
                    return "#,##0.00";
                case "MONEY1":
                    return "#,##0.0";
                case "INT":
                    return "#,##0";
                case "PCT":
                case "X":
                    return "0.00";
                case "MIN":
                    return "0.0";
                case "HOUR":
                    return "0";
                default:
                    return "General";
            }
        }
    }

    @FunctionalInterface
    private interface SheetBody
    {
        void write(Sheet sheet, Styles styles);
    }

    /**
     * 导出前的校验：开关、权限、区间。
     */
    private void precheck(BaseDashboardQuery query)
    {
        if (!properties.getExport().isEnabled())
        {
            throw new ServiceException("导出功能未开启");
        }
        if (properties.getExport().isRequirePermission()
            && !SecurityUtils.hasPermi(properties.getExport().getPermission()))
        {
            // 明细含个人信息，「能看」不等于「能批量导出」
            throw new ServiceException("没有导出权限，请联系管理员授权");
        }
    }

    /**
     * 导出忽略分页，取当前筛选条件下的全部数据。
     * <p>
     * 上限由 {@code dashboard.export.max-rows} 控制；超过上限时下层会把
     * {@code truncated} 置为 true，本方法在写完后不做静默截断，
     * 而是由调用方根据该标记提示用户缩小范围。
     */
    private void applyExportPaging(BaseDashboardQuery query)
    {
        if (!properties.getExport().isExportAll())
        {
            // CURRENT_PAGE：分页参数原样保留，导出的就是这次请求返回的那一页
            return;
        }
        int max = Math.max(1, properties.getExport().getMaxRows());
        if (query instanceof MetricSummaryQuery)
        {
            MetricSummaryQuery q = (MetricSummaryQuery) query;
            q.setPageNum(1);
            // 时间片数受粒度上限约束，不会很大
            q.setPageSize(Math.min(max, properties.getMaxPointsPerGranularity() + 1));
        }
        else if (query instanceof com.fivetech.dashboard.domain.query.BaseRecordQuery)
        {
            com.fivetech.dashboard.domain.query.BaseRecordQuery q =
                (com.fivetech.dashboard.domain.query.BaseRecordQuery) query;
            q.setPageNum(1);
            q.setPageSize(max);
        }
    }

    private String currentUser()
    {
        try
        {
            return SecurityUtils.getUsername();
        }
        catch (Exception e)
        {
            return "-";
        }
    }

    private String label(String labelText, String code)
    {
        return StringUtils.isNotEmpty(labelText) ? labelText : code;
    }


    private String nvl(String value)
    {
        return StringUtils.isEmpty(value) ? "-" : value;
    }

}
