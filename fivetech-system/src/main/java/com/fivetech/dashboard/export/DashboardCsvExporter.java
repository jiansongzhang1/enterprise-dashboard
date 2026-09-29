package com.fivetech.dashboard.export;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.common.utils.SecurityUtils;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.domain.query.BaseDashboardQuery;
import com.fivetech.dashboard.domain.query.BetRecordQuery;
import com.fivetech.dashboard.domain.query.MemberRecordQuery;
import com.fivetech.dashboard.domain.query.MetricSummaryQuery;
import com.fivetech.dashboard.domain.query.TransactionRecordQuery;
import com.fivetech.dashboard.domain.vo.BetRecordVO;
import com.fivetech.dashboard.domain.vo.ColumnMetaVO;
import com.fivetech.dashboard.domain.vo.MemberRecordVO;
import com.fivetech.dashboard.domain.vo.MetricSummaryRowVO;
import com.fivetech.dashboard.domain.vo.MetricSummaryVO;
import com.fivetech.dashboard.domain.vo.QueryContext;
import com.fivetech.dashboard.domain.vo.RecordResultVO;
import com.fivetech.dashboard.domain.vo.TransactionRecordVO;
import com.fivetech.dashboard.service.IMetricSummaryService;
import com.fivetech.dashboard.service.IRecordQueryService;
import com.fivetech.common.core.domain.entity.SysUser;
import com.fivetech.common.core.domain.model.LoginUser;
import com.fivetech.common.utils.TraceIdUtils;
import com.fivetech.common.enums.BusinessType;
import com.fivetech.system.domain.SysOperLog;
import com.fivetech.system.service.ISysOperLogService;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 仪表板 CSV 导出。
 * <p>
 * 导出走的是与页面<b>完全相同</b>的查询链路（权限、时间语义、筛选、白名单都复用），
 * 只是把分页换成「当前筛选条件下的全部数据」，再把结果写成 CSV。
 * 这样能保证「看到的」和「导出的」永远是同一份数据。
 *
 * @author fivetech
 */
@Component
public class DashboardCsvExporter
{
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Logger log = LoggerFactory.getLogger(DashboardCsvExporter.class);

    private final DashboardProperties properties;

    private final IMetricSummaryService metricSummaryService;

    private final IRecordQueryService recordQueryService;

    private final ISysOperLogService operLogService;

    public DashboardCsvExporter(DashboardProperties properties,
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
            operLog.setTitle("仪表板导出 · " + title);
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
        return label + "_" + LocalDateTime.now().format(STAMP) + ".csv";
    }

    // ===================== 指标汇总 =====================

    public String exportMetricSummary(MetricSummaryQuery query, OutputStream out)
    {
        precheck(query);
        applyExportPaging(query);
        MetricSummaryVO vo = metricSummaryService.query(query);

        write(out, csv -> {
            writeMeta(csv, vo.getContext(), "指标汇总");
            List<ColumnMetaVO> columns = vo.getColumns();
            // 表头
            List<Object> head = new ArrayList<>();
            for (ColumnMetaVO column : columns)
            {
                head.add(column.getLabel());
            }
            csv.writeRow(head);
            // 数据行：第一列是时间片标签，其余按列顺序取指标值
            for (MetricSummaryRowVO row : vo.getPage().getRows())
            {
                List<Object> line = new ArrayList<>();
                line.add(row.getLabel());
                appendMetricCells(line, columns, row.getValues());
                csv.writeRow(line);
            }
            // 区间合计：与页面同口径，由服务端重算，不是上面各行的加总
            List<Object> total = new ArrayList<>();
            total.add("区间合计");
            appendMetricCells(total, columns, vo.getTotalRow());
            csv.writeRow(total);
        });
        audit("指标汇总", vo.getPage().getRows().size(), false);
        return fileName("指标汇总");
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

    public String exportMembers(MemberRecordQuery query, OutputStream out)
    {
        precheck(query);
        applyExportPaging(query);
        RecordResultVO<MemberRecordVO> vo = recordQueryService.queryMembers(query);
        write(out, csv -> {
            writeMeta(csv, vo.getContext(), "会员明细");
            writeColumns(csv, vo.getColumns());
            for (MemberRecordVO row : vo.getPage().getRows())
            {
                csv.writeRow(memberCells(vo.getColumns(), row));
            }
        });
        audit("会员明细", vo.getPage().getRows().size(), vo.getPage().isTruncated());
        return fileName("会员明细");
    }

    public String exportTransactions(TransactionRecordQuery query, OutputStream out)
    {
        precheck(query);
        applyExportPaging(query);
        RecordResultVO<TransactionRecordVO> vo = recordQueryService.queryTransactions(query);
        write(out, csv -> {
            writeMeta(csv, vo.getContext(), "交易明细");
            if (StringUtils.isNotEmpty(vo.getSummaryNote()))
            {
                csv.writeRow(List.of("合计口径", vo.getSummaryNote()));
                csv.writeBlankLine();
            }
            writeColumns(csv, vo.getColumns());
            for (TransactionRecordVO row : vo.getPage().getRows())
            {
                csv.writeRow(transactionCells(vo.getColumns(), row));
            }
        });
        audit("交易明细", vo.getPage().getRows().size(), vo.getPage().isTruncated());
        return fileName("交易明细");
    }

    public String exportBets(BetRecordQuery query, OutputStream out)
    {
        precheck(query);
        applyExportPaging(query);
        RecordResultVO<BetRecordVO> vo = recordQueryService.queryBets(query);
        write(out, csv -> {
            writeMeta(csv, vo.getContext(), "投注明细");
            writeColumns(csv, vo.getColumns());
            for (BetRecordVO row : vo.getPage().getRows())
            {
                csv.writeRow(betCells(vo.getColumns(), row));
            }
        });
        audit("投注明细", vo.getPage().getRows().size(), vo.getPage().isTruncated());
        return fileName("投注明细");
    }

    // ===================== 行取值 =====================

    private List<Object> memberCells(List<ColumnMetaVO> columns, MemberRecordVO r)
    {
        List<Object> line = new ArrayList<>();
        for (ColumnMetaVO c : columns)
        {
            switch (c.getCode())
            {
                case "account": line.add(r.getAccount()); break;
                case "registerTime": line.add(r.getRegisterTime()); break;
                case "registerChannel": line.add(r.getRegisterChannel()); break;
                case "device": line.add(r.getDevice()); break;
                case "hasFirstDeposit": line.add(bool(r.getHasFirstDeposit())); break;
                case "firstDepositTime": line.add(r.getFirstDepositTime()); break;
                case "firstDepositAmount": line.add(r.getFirstDepositAmount()); break;
                case "firstDepositChannel": line.add(r.getFirstDepositChannel()); break;
                case "regToFtdHours": line.add(r.getRegToFtdHours()); break;
                case "cumulativeDeposit": line.add(r.getCumulativeDeposit()); break;
                case "cumulativeWithdraw": line.add(r.getCumulativeWithdraw()); break;
                case "cumulativeBet": line.add(r.getCumulativeBet()); break;
                case "cumulativeNgr": line.add(r.getCumulativeNgr()); break;
                case "turnoverMultiple": line.add(r.getTurnoverMultiple()); break;
                case "tier": line.add(label(r.getTierLabel(), r.getTier())); break;
                case "stage": line.add(label(r.getStageLabel(), r.getStage())); break;
                case "lastActiveTime": line.add(r.getLastActiveTime()); break;
                case "lastBetGap": line.add(r.getLastBetGap()); break;
                default: line.add(null);
            }
        }
        return line;
    }

    private List<Object> transactionCells(List<ColumnMetaVO> columns, TransactionRecordVO r)
    {
        List<Object> line = new ArrayList<>();
        for (ColumnMetaVO c : columns)
        {
            switch (c.getCode())
            {
                case "orderNo": line.add(r.getOrderNo()); break;
                case "type": line.add(r.getType()); break;
                case "account": line.add(r.getAccount()); break;
                case "amount": line.add(r.getAmount()); break;
                case "channel": line.add(r.getChannel()); break;
                case "status": line.add(label(r.getStatusLabel(), r.getStatus())); break;
                case "auditStatus": line.add(label(r.getAuditStatusLabel(), r.getAuditStatus())); break;
                case "auditor": line.add(r.getAuditor()); break;
                case "createTime": line.add(r.getCreateTime()); break;
                case "finishTime": line.add(r.getFinishTime()); break;
                case "costMinutes": line.add(r.getCostMinutes()); break;
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
                case "account": line.add(r.getAccount()); break;
                case "vendor": line.add(r.getVendor()); break;
                case "gameType": line.add(label(r.getGameTypeLabel(), r.getGameType())); break;
                case "game": line.add(r.getGame()); break;
                case "betAmount": line.add(r.getBetAmount()); break;
                case "payout": line.add(r.getPayout()); break;
                case "winLoss": line.add(r.getWinLoss()); break;
                case "createTime": line.add(r.getCreateTime()); break;
                default: line.add(null);
            }
        }
        return line;
    }

    // ===================== 公共 =====================

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

    /**
     * 写口径说明区。
     * <p>
     * 文件离开系统之后，这几行是它唯一的口径载体——没有它，
     * 一份 CSV 过两天就没人说得清区间、时区和口径版本。
     */
    private void writeMeta(CsvWriter csv, QueryContext ctx, String title) throws IOException
    {
        if (!properties.getExport().isIncludeMeta() || ctx == null)
        {
            return;
        }
        csv.writeRow(List.of("报表名称", title));
        csv.writeRow(List.of("导出时间", LocalDateTime.now().format(HUMAN)));
        csv.writeRow(List.of("导出人", currentUser()));
        csv.writeRow(List.of("站点", nvl(ctx.getSiteCode())));
        csv.writeRow(List.of("币别", nvl(ctx.getCurrency()) + " · 单一币别，未做汇率折算"));
        csv.writeRow(List.of("统计时区", nvl(ctx.getTimezone()) + " · 日切 00:00，环比同比依同一时区"));
        csv.writeRow(List.of("统计区间", nvl(ctx.getSpanFrom()) + " ~ " + nvl(ctx.getSpanTo())));
        if (StringUtils.isNotEmpty(ctx.getCompareFrom()))
        {
            csv.writeRow(List.of("对比区间", ctx.getCompareFrom() + " ~ " + nvl(ctx.getCompareTo())));
        }
        if (ctx.getGranularity() != null)
        {
            csv.writeRow(List.of("粒度", ctx.getGranularity().name()));
        }
        csv.writeRow(List.of("数据截止", nvl(ctx.getAsOf())));
        csv.writeRow(List.of("计算完成", nvl(ctx.getUpdatedAt())));
        csv.writeRow(List.of("口径版本", nvl(ctx.getRegistryVersion())));
        if (ctx.isDelayed())
        {
            csv.writeRow(List.of("数据延迟",
                "本区间含约 " + ctx.getDelayWindowHours() + " 小时延迟数据，数值可能上调"));
        }
        if (ctx.getWarnings() != null && !ctx.getWarnings().isEmpty())
        {
            csv.writeRow(List.of("提示", String.join(" / ", ctx.getWarnings())));
        }
        csv.writeRow(List.of("空值说明", "空单元格表示无数据，请勿当作 0 参与计算"));
        csv.writeRow(List.of("免责声明", "本文件为业务参考，财务数据以财务系统为准"));
        csv.writeBlankLine();
    }

    private void writeColumns(CsvWriter csv, List<ColumnMetaVO> columns) throws IOException
    {
        List<Object> head = new ArrayList<>();
        for (ColumnMetaVO c : columns)
        {
            head.add(c.getLabel());
        }
        csv.writeRow(head);
    }

    /** 统一的写出入口，把受检异常收敛掉 */
    private void write(OutputStream out, CsvBody body)
    {
        try (CsvWriter csv = new CsvWriter(out))
        {
            body.write(csv);
            csv.flush();
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
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

    private String bool(Boolean value)
    {
        return value == null ? null : (value ? "是" : "否");
    }

    private String nvl(String value)
    {
        return StringUtils.isEmpty(value) ? "-" : value;
    }

    @FunctionalInterface
    private interface CsvBody
    {
        void write(CsvWriter csv) throws IOException;
    }
}
