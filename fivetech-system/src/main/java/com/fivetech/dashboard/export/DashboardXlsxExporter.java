package com.fivetech.dashboard.export;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.poi.ss.usermodel.Sheet;
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
import com.fivetech.dashboard.domain.MetricDefinition;
import com.fivetech.dashboard.domain.query.BaseDashboardQuery;
import com.fivetech.dashboard.domain.query.BaseRecordQuery;
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
import com.fivetech.dashboard.enums.Granularity;
import com.fivetech.dashboard.export.ExportTemplate.Book;
import com.fivetech.dashboard.export.ExportTemplate.Col;
import com.fivetech.dashboard.service.IMetricSummaryService;
import com.fivetech.dashboard.service.IRecordQueryService;
import com.fivetech.dashboard.service.MetricRegistry;
import com.fivetech.system.domain.SysOperLog;
import com.fivetech.system.service.ISysOperLogService;

/**
 * 仪表板同步导出（XLSX）：指标汇总与四张明细表，按《导出模板样例-v1_5》输出。
 * <p>
 * 一个文件三个工作表：数据表（工作表名＝页面名）、口徑說明、導出說明。第 1 行即表头，表头冻结；
 * 表头与枚举用界面文案（本期繁体）；金额、比率写纯数值，时间写 Excel 日期时间值；空值为空单元格。
 * <p>
 * 导出走与页面<b>完全相同</b>的查询链路（权限、时间语义、筛选、白名单都复用），
 * 只是忽略分页，取当前筛选条件下的全部数据（上限 dashboard.export.max-rows）。
 * 明细用 SXSSF 流式写，整份生成完再返回——中途失败仍能回 JSON 错误体。
 *
 * @author fivetech
 */
@Component
public class DashboardXlsxExporter
{
    private static final Logger log = LoggerFactory.getLogger(DashboardXlsxExporter.class);

    public static final String PAGE_SUMMARY = "指標匯總";

    public static final String PAGE_MEMBER = "用戶明細";

    public static final String PAGE_DEPOSIT = "存款訂單";

    public static final String PAGE_WITHDRAW = "提款訂單";

    public static final String PAGE_BET = "投注明細";

    private static final String S_NOTES = "口徑說明";

    private static final String S_INFO = "導出說明";

    private static final String U_TEXT = "文字";

    private static final String U_ENUM = "文字（界面用詞）";

    private static final String U_TIME = "YYYY-MM-DD HH:mm:ss（IST）";

    private static final String U_INR = "INR，純數值，2 位小數";

    private static final String U_CUR = "原幣種，純數值，2 位小數";

    private static final String U_INT = "整數";

    private static final String U_MIN = "分鐘，1 位小數";

    private final DashboardProperties properties;

    private final IMetricSummaryService metricSummaryService;

    private final IRecordQueryService recordQueryService;

    private final ISysOperLogService operLogService;

    private final MetricRegistry metricRegistry;

    public DashboardXlsxExporter(DashboardProperties properties,
            IMetricSummaryService metricSummaryService, IRecordQueryService recordQueryService,
            ISysOperLogService operLogService, MetricRegistry metricRegistry)
    {
        this.properties = properties;
        this.metricSummaryService = metricSummaryService;
        this.recordQueryService = recordQueryService;
        this.operLogService = operLogService;
        this.metricRegistry = metricRegistry;
    }

    /**
     * 文件名：{页面}-yyyyMMdd-HHmmss.xlsx。传简体旧名（会员明细 / 存款明细 …）也能对上新页面名。
     */
    public String fileName(String label)
    {
        return ExportTemplate.fileName(properties, pageOf(label));
    }

    private static String pageOf(String label)
    {
        if (label == null)
        {
            return "導出";
        }
        switch (label)
        {
            case "会员明细":
            case "用户明细":
                return PAGE_MEMBER;
            case "存款明细":
            case "存款订单":
                return PAGE_DEPOSIT;
            case "提款明细":
            case "提款订单":
                return PAGE_WITHDRAW;
            case "投注明细":
                return PAGE_BET;
            case "指标汇总":
                return PAGE_SUMMARY;
            default:
                return label;
        }
    }

    // ===================== 指标汇总 =====================

    public byte[] exportMetricSummary(MetricSummaryQuery query)
    {
        precheck(query);
        applyExportPaging(query);
        MetricSummaryVO vo = metricSummaryService.query(query);
        QueryContext ctx = vo.getContext();

        List<ColumnMetaVO> metricCols = new ArrayList<>();
        for (ColumnMetaVO c : vo.getColumns())
        {
            if (!"time".equals(c.getCode()))
            {
                metricCols.add(c);
            }
        }
        List<ExportMetricText.M> metrics = new ArrayList<>();
        for (ColumnMetaVO c : metricCols)
        {
            MetricDefinition def = metricRegistry.get(c.getCode());
            metrics.add(ExportMetricText.of(c.getCode(), c.getLabel(), def == null ? null : def.getGroup(),
                def == null ? null : def.getExpression(), c.getFormat()));
        }

        List<Col> cols = new ArrayList<>();
        cols.add(Col.text("類型", 8));
        cols.add(Col.slot("開始"));
        cols.add(Col.slot("結束"));
        metrics.forEach(m -> cols.add(Col.num(m.header(), m.format)));

        int rows = vo.getPage().getRows().size();
        try (Book book = new Book(false))
        {
            Sheet sheet = book.sheet(PAGE_SUMMARY);
            int r = book.header(sheet, cols);
            for (MetricSummaryRowVO row : vo.getPage().getRows())
            {
                book.row(sheet, r++, cols, summaryLine("時間片", row.getSlotFrom(), row.getSlotTo(), metricCols,
                    row.getValues()));
            }
            // 区间值：与页面同口径由服务端重算，不是上面各行的加总
            book.row(sheet, r, cols, summaryLine("區間值", ctx == null ? null : ctx.getSpanFrom(),
                ctx == null ? null : ctx.getSpanTo(), metricCols, vo.getTotalRow()));

            List<String[]> notes = ExportTemplate.commonNotes(false);
            notes.add(new String[] { PAGE_SUMMARY, "類型", "「時間片」為一行一個時間片；最後一行「區間值」為整個區間的值", "" });
            notes.add(new String[] { PAGE_SUMMARY, "開始 / 結束", "時間片或區間的起止；粒度按頁面「粒度」設定", "YYYY-MM-DD HH:mm" });
            notes.add(new String[] { PAGE_SUMMARY, "區間值",
                "不是逐行相加：比率、時長、倍數類指標按整個區間重算；去重人數（活躍人數、登錄人數）按整個區間去重", "" });
            metrics.forEach(m -> notes.add(new String[] { PAGE_SUMMARY, m.header(), m.note, m.unitNote }));
            book.notes(S_NOTES, notes);

            StringBuilder filter = new StringBuilder();
            if (ctx != null)
            {
                filter.append("時間範圍 ").append(ExportTemplate.range(ctx.getSpanFrom(), ctx.getSpanTo()));
                if (ctx.getGranularity() != null)
                {
                    filter.append(" · 粒度 ").append(granularityText(ctx.getGranularity()));
                }
            }
            if (Boolean.TRUE.equals(query.getOnlyCore()))
            {
                filter.append(" · 僅核心指標");
            }
            book.info(S_INFO, ExportTemplate.exportInfo(properties, siteOf(ctx, query), filter.toString(),
                tips(ctx, false, rows)));
            byte[] file = book.toBytes();
            audit(PAGE_SUMMARY, rows, false);
            return file;
        }
    }

    private static List<Object> summaryLine(String type, String from, String to, List<ColumnMetaVO> cols,
            Map<String, BigDecimal> values)
    {
        List<Object> line = new ArrayList<>();
        line.add(type);
        line.add(from);
        line.add(to);
        for (ColumnMetaVO c : cols)
        {
            line.add(values == null ? null : values.get(c.getCode()));
        }
        return line;
    }

    // ===================== 明细：列定义 =====================

    /** 一列：表头、口径、单位格式、取值 */
    private static final class Field<T>
    {
        final String code;

        final Col col;

        final String note;

        final String unit;

        final Function<T, Object> get;

        Field(String code, Col col, String note, String unit, Function<T, Object> get)
        {
            this.code = code;
            this.col = col;
            this.note = note;
            this.unit = unit;
            this.get = get;
        }
    }

    private static <T> Field<T> f(String code, Col col, String note, String unit, Function<T, Object> get)
    {
        return new Field<>(code, col, note, unit, get);
    }

    /** 枚举导出界面文字：有名称用名称（转繁体），否则原样写编码 */
    private static String label(String labelText, String code)
    {
        return StringUtils.isNotEmpty(labelText) ? ExportTemplate.t(labelText) : code;
    }

    private static final Map<String, Field<MemberRecordVO>> MEMBER = new LinkedHashMap<>();

    private static final List<Field<DepositRecordVO>> DEPOSIT = new ArrayList<>();

    private static final List<Field<WithdrawRecordVO>> WITHDRAW = new ArrayList<>();

    private static final List<Field<BetRecordVO>> BET = new ArrayList<>();

    static
    {
        member(f("userId", Col.text("用戶ID", 34), "用戶唯一標識。", U_TEXT, MemberRecordVO::getUserId));
        member(f("username", Col.text("帳號名稱", 16), "用戶登錄名。", U_TEXT, MemberRecordVO::getUsername));
        member(f("status", Col.text("用戶狀態"), "帳號狀態：啟用 / 禁用。由人工或風控設定，與行為判定無關。", U_ENUM,
            r -> label(r.getStatusLabel(), r.getStatus())));
        member(f("userType", Col.text("用戶類型"), "正式用戶 / 試玩用戶 / 測試帳號 / 代理帳號。", U_ENUM,
            r -> label(r.getUserTypeLabel(), r.getUserType())));
        member(f("level", Col.text("用戶等級"), "老鐵 / 青銅 / 白銀 / 黃金 / 鉑金1 / 鉑金2。", U_ENUM,
            r -> label(r.getLevelLabel(), r.getLevel())));
        member(f("country", Col.text("國家"), "印度 / 尼泊爾 / 孟加拉 / 斯里蘭卡 / 巴基斯坦。", U_ENUM,
            r -> label(r.getCountryLabel(), r.getCountry())));
        member(f("registerTime", Col.dateTime("註冊時間"), "完成註冊的時刻。", U_TIME, MemberRecordVO::getRegisterTime));
        member(f("firstDepositTime", Col.dateTime("首存時間"), "生涯首筆存款的時刻。未首存為空。", U_TIME,
            MemberRecordVO::getFirstDepositTime));
        member(f("firstDepositAmount", Col.num("首存金額(INR)", "0.00"), "生涯首筆存款的金額。未首存為空。", U_INR,
            MemberRecordVO::getFirstDepositAmount));
        member(f("firstDepositChannel", Col.text("首存通道"), "生涯首筆存款使用的支付通道。未首存為空。", U_TEXT,
            MemberRecordVO::getFirstDepositChannel));
        member(f("regToFtdHours", Col.num("註冊→首存時長(小時)", null), "首存時間 − 註冊時間。未首存為空。", "小時，整數",
            MemberRecordVO::getRegToFtdHours));
        member(f("lastDepositTime", Col.dateTime("最近存款時間"), "最近一筆成功存款的時刻。", U_TIME,
            MemberRecordVO::getLastDepositTime));
        member(f("lastDepositAmount", Col.num("最近存款金額(INR)", "0.00"), "最近一筆成功存款的金額。", U_INR,
            MemberRecordVO::getLastDepositAmount));
        member(f("cumulativeDepositAmount", Col.num("歷史累計存款金額(INR)", "0.00"), "生涯累計成功存款金額。", U_INR,
            MemberRecordVO::getCumulativeDepositAmount));
        member(f("cumulativeDepositCount", Col.num("歷史累計存款筆數", null), "生涯累計成功存款筆數。", U_INT,
            MemberRecordVO::getCumulativeDepositCount));
        member(f("lastBetTime", Col.dateTime("最近投注時間"), "最近一筆投注的時刻。", U_TIME, MemberRecordVO::getLastBetTime));
        member(f("lastBetAmount", Col.num("最近投注金額(INR)", "0.00"), "最近一筆投注的金額。", U_INR,
            MemberRecordVO::getLastBetAmount));
        member(f("cumulativeBetAmount", Col.num("歷史累計投注金額(INR)", "0.00"), "生涯累計投注金額。", U_INR,
            MemberRecordVO::getCumulativeBetAmount));
        member(f("cumulativeBetCount", Col.num("歷史累計投注筆數", null), "生涯累計投注筆數。", U_INT,
            MemberRecordVO::getCumulativeBetCount));
        member(f("turnoverMultiple", Col.num("流水倍數(倍)", "0.00"), "歷史累計投注金額 ÷ 歷史累計存款金額。", "倍，2 位小數",
            MemberRecordVO::getTurnoverMultiple));
        member(f("lastWithdrawTime", Col.dateTime("最近提款時間"), "最近一筆成功提款的時刻。", U_TIME,
            MemberRecordVO::getLastWithdrawTime));
        member(f("lastWithdrawAmount", Col.num("最近提款金額(INR)", "0.00"), "最近一筆成功提款的金額。", U_INR,
            MemberRecordVO::getLastWithdrawAmount));
        member(f("cumulativeWithdrawAmount", Col.num("歷史累計提款金額(INR)", "0.00"), "生涯累計成功提款金額。", U_INR,
            MemberRecordVO::getCumulativeWithdrawAmount));
        member(f("cumulativeWithdrawCount", Col.num("歷史累計提款筆數", null), "生涯累計成功提款筆數。", U_INT,
            MemberRecordVO::getCumulativeWithdrawCount));
        member(f("cumulativeGgr", Col.num("歷史累計GGR(INR)", "0.00"), "生涯累計 GGR ＝ 歷史累計投注 − 歷史累計派彩。", U_INR,
            MemberRecordVO::getCumulativeGgr));
        member(f("cumulativeNgr", Col.num("歷史累計NGR(INR)", "0.00"), "生涯累計 NGR ＝ 歷史累計 GGR − 歷史累計發放贈金。",
            U_INR, MemberRecordVO::getCumulativeNgr));

        DEPOSIT.add(f("orderNo", Col.text("訂單號", 24), "平台內部訂單號。", U_TEXT, DepositRecordVO::getOrderNo));
        DEPOSIT.add(f("userId", Col.text("用戶ID", 34), "下單用戶的用戶ID。", U_TEXT, DepositRecordVO::getUserId));
        DEPOSIT.add(f("username", Col.text("帳號名稱", 16), "下單用戶的帳號名稱。", U_TEXT, DepositRecordVO::getUsername));
        DEPOSIT.add(f("amount", Col.num("存款金額", "0.00"), "按訂單原始幣種的純數值，幣種見「幣種」列；跨幣種不可相加。", U_CUR,
            DepositRecordVO::getAmount));
        DEPOSIT.add(f("currency", Col.text("幣種", 8), "INR / USD / USDT。訂單的原始交易幣種，不折算。", U_ENUM,
            DepositRecordVO::getCurrency));
        DEPOSIT.add(f("status", Col.text("狀態", 8), "成功 / 失敗。", U_ENUM, r -> label(r.getStatusLabel(), r.getStatus())));
        DEPOSIT.add(f("createTime", Col.dateTime("創建時間"), "下單時刻。", U_TIME, DepositRecordVO::getCreateTime));
        DEPOSIT.add(f("finishTime", Col.dateTime("完成時間"), "進入終態的時刻（入帳時間）。處理中的單據為空。", U_TIME,
            DepositRecordVO::getFinishTime));
        DEPOSIT.add(f("costMinutes", Col.num("耗時(分鐘)", "0.0"), "完成時間 − 創建時間。處理中的單據為空。", U_MIN,
            DepositRecordVO::getCostMinutes));

        WITHDRAW.add(f("orderNo", Col.text("訂單號", 24), "平台內部訂單號。", U_TEXT, WithdrawRecordVO::getOrderNo));
        WITHDRAW.add(f("userId", Col.text("用戶ID", 34), "下單用戶的用戶ID。", U_TEXT, WithdrawRecordVO::getUserId));
        WITHDRAW.add(f("username", Col.text("帳號名稱", 16), "下單用戶的帳號名稱。", U_TEXT, WithdrawRecordVO::getUsername));
        WITHDRAW.add(f("amount", Col.num("提款金額", "0.00"), "申請金額，按原始幣種的純數值，幣種見「幣種」列。", U_CUR,
            WithdrawRecordVO::getAmount));
        WITHDRAW.add(f("currency", Col.text("幣種", 8), "INR / USD / USDT。訂單的原始交易幣種，不折算。", U_ENUM,
            WithdrawRecordVO::getCurrency));
        WITHDRAW.add(f("status", Col.text("狀態", 8), "支付狀態。", U_ENUM, r -> label(r.getStatusLabel(), r.getStatus())));
        WITHDRAW.add(f("auditStatus", Col.text("審核", 8), "風控審核結果。", U_ENUM,
            r -> label(r.getAuditStatusLabel(), r.getAuditStatus())));
        WITHDRAW.add(f("auditor", Col.text("審核人", 12), "風控審核人帳號。審核為「待審核」的單據為空。", U_TEXT,
            WithdrawRecordVO::getAuditor));
        WITHDRAW.add(f("payer", Col.text("資金審批人", 12), "資金環節的操作人帳號。未進入資金環節的單據為空。", U_TEXT,
            WithdrawRecordVO::getPayer));
        WITHDRAW.add(f("payTime", Col.dateTime("資金操作時間"), "資金審批人操作的時刻。為空的條件同資金審批人。", U_TIME,
            WithdrawRecordVO::getPayTime));
        WITHDRAW.add(f("bankName", Col.text("銀行名稱", 20), "收款銀行名稱。USDT 訂單走鏈上、沒有銀行，為空。", U_TEXT,
            WithdrawRecordVO::getBankName));
        WITHDRAW.add(f("bankCode", Col.text("銀行代碼", 12), "收款銀行代碼。為空的條件同銀行名稱。", U_TEXT,
            WithdrawRecordVO::getBankCode));
        WITHDRAW.add(f("bankCountry", Col.text("銀行所在國家", 12), "收款銀行所在國家。為空的條件同銀行名稱。", U_ENUM,
            r -> label(r.getBankCountryLabel(), r.getBankCountry())));
        WITHDRAW.add(f("createTime", Col.dateTime("創建時間"), "下單時刻。", U_TIME, WithdrawRecordVO::getCreateTime));
        WITHDRAW.add(f("finishTime", Col.dateTime("完成時間"), "進入終態的時刻。待審核、出款中的單據為空。", U_TIME,
            WithdrawRecordVO::getFinishTime));
        WITHDRAW.add(f("costMinutes", Col.num("耗時(分鐘)", "0.0"), "完成時間 − 創建時間。待審核、出款中的單據為空。", U_MIN,
            WithdrawRecordVO::getCostMinutes));
        WITHDRAW.add(f("auditNote", Col.text("審核備註", 60), "風控或系統寫入的審核說明，不是每單都有。導出全文。", "文字（全文）",
            WithdrawRecordVO::getAuditNote));

        BET.add(f("orderNo", Col.text("訂單號", 24), "平台內部注單號。", U_TEXT, BetRecordVO::getOrderNo));
        BET.add(f("userId", Col.text("用戶ID", 34), "下注用戶的用戶ID。", U_TEXT, BetRecordVO::getUserId));
        BET.add(f("username", Col.text("帳號名稱", 16), "下注用戶的帳號名稱。", U_TEXT, BetRecordVO::getUsername));
        BET.add(f("vendorCode", Col.text("遊戲平台Code", 14), "遊戲平台的系統對接編碼（如 EVOLUTION）。", U_TEXT,
            BetRecordVO::getVendorCode));
        BET.add(f("vendorName", Col.text("平台廠商名", 16), "遊戲平台廠商的名稱（如 Evolution Gaming）。", U_TEXT,
            BetRecordVO::getVendorName));
        BET.add(f("gameType", Col.text("遊戲類型", 10), "老虎機 / 真人 / 小遊戲等，由廠商側提供。", U_ENUM,
            r -> label(r.getGameTypeLabel(), r.getGameType())));
        BET.add(f("gameId", Col.text("遊戲ID", 18), "遊戲的唯一編號。", U_TEXT, BetRecordVO::getGameId));
        BET.add(f("gameName", Col.text("遊戲名稱", 24), "遊戲名稱。", U_TEXT, BetRecordVO::getGameName));
        BET.add(f("betAmount", Col.num("投注金額(INR)", "0.00"), "下注金額。", U_INR, BetRecordVO::getBetAmount));
        BET.add(f("payout", Col.num("派彩(INR)", "0.00"), "結算派彩金額。未結算為空。", U_INR, BetRecordVO::getPayout));
        BET.add(f("winLoss", Col.num("輸贏(INR)", "0.00"), "投注金額 − 派彩，站點視角。正數為站點贏。未結算為空。", U_INR,
            BetRecordVO::getWinLoss));
        BET.add(f("settleStatus", Col.text("結算狀態", 10), "已結算 / 未結算 / 已取消。", U_ENUM,
            r -> label(r.getSettleStatusLabel(), r.getSettleStatus())));
        BET.add(f("betTime", Col.dateTime("投注時間"), "下注時刻。", U_TIME, BetRecordVO::getBetTime));
        BET.add(f("settleTime", Col.dateTime("結算時間"), "結算時刻。未結算為空。", U_TIME, BetRecordVO::getSettleTime));
    }

    private static void member(Field<MemberRecordVO> field)
    {
        MEMBER.put(field.code, field);
    }

    // ===================== 明细 =====================

    public byte[] exportMembers(MemberRecordQuery query)
    {
        precheck(query);
        applyExportPaging(query);
        RecordResultVO<MemberRecordVO> vo = recordQueryService.queryMembers(query);
        // 导出页面当前显示的列：传了 columns 就用它们（必选列总会带上），否则用预设列；顺序按注册表
        List<Field<MemberRecordVO>> fields = new ArrayList<>();
        for (ColumnMetaVO c : selectColumns(vo.getColumns(), query.getColumns()))
        {
            Field<MemberRecordVO> field = MEMBER.get(c.getCode());
            fields.add(field != null ? field
                : f(c.getCode(), Col.text(ExportTemplate.t(c.getLabel())), ExportTemplate.t(c.getLabel()), U_TEXT, r -> null));
        }
        List<String[]> extraNotes = new ArrayList<>();
        extraNotes.add(new String[] { PAGE_MEMBER, "（列範圍）", "導出頁面當前顯示的列，含下鑽臨時補上的列；本表只列本檔包含的列", "" });
        return details(PAGE_MEMBER, vo, fields, extraNotes, memberFilter(query));
    }

    public byte[] exportDeposits(DepositRecordQuery query)
    {
        precheck(query);
        applyExportPaging(query);
        RecordResultVO<DepositRecordVO> vo = recordQueryService.queryDeposits(query);
        return details(PAGE_DEPOSIT, vo, DEPOSIT, null, orderFilter(query, "創建時間", depositFilters(query)));
    }

    public byte[] exportWithdrawals(WithdrawRecordQuery query)
    {
        precheck(query);
        applyExportPaging(query);
        RecordResultVO<WithdrawRecordVO> vo = recordQueryService.queryWithdrawals(query);
        return details(PAGE_WITHDRAW, vo, WITHDRAW, null, orderFilter(query, "創建時間", withdrawFilters(query)));
    }

    public byte[] exportBets(BetRecordQuery query)
    {
        precheck(query);
        applyExportPaging(query);
        RecordResultVO<BetRecordVO> vo = recordQueryService.queryBets(query);
        return details(PAGE_BET, vo, BET, null, orderFilter(query, "投注時間", betFilters(query)));
    }

    /** 明细通用：数据表 → 口径说明 → 导出说明 */
    private <T> byte[] details(String page, RecordResultVO<T> vo, List<Field<T>> fields, List<String[]> extraNotes,
            String filter)
    {
        List<Col> cols = new ArrayList<>();
        fields.forEach(f -> cols.add(f.col));
        List<T> rows = vo.getPage().getRows();
        boolean truncated = vo.getPage().isTruncated();
        try (Book book = new Book(true))
        {
            Sheet sheet = book.sheet(page);
            int r = book.header(sheet, cols);
            for (T row : rows)
            {
                List<Object> line = new ArrayList<>(fields.size());
                for (Field<T> f : fields)
                {
                    line.add(f.get.apply(row));
                }
                book.row(sheet, r++, cols, line);
            }
            List<String[]> notes = ExportTemplate.commonNotes(true);
            if (extraNotes != null)
            {
                notes.addAll(extraNotes);
            }
            for (Field<T> f : fields)
            {
                notes.add(new String[] { page, f.col.getHeader(), f.note, f.unit });
            }
            book.notes(S_NOTES, notes);
            book.info(S_INFO, ExportTemplate.exportInfo(properties, siteOf(vo.getContext(), null), filter,
                tips(vo.getContext(), truncated, rows.size())));
            byte[] file = book.toBytes();
            audit(page, rows.size(), truncated);
            return file;
        }
    }

    /** 按用户选择的列编码挑列：保持注册表顺序，忽略白名单外的编码，必选列总是保留；没选时用预设列 */
    private static List<ColumnMetaVO> selectColumns(List<ColumnMetaVO> all, List<String> wanted)
    {
        List<ColumnMetaVO> result = new ArrayList<>();
        boolean none = wanted == null || wanted.isEmpty();
        for (ColumnMetaVO c : all)
        {
            if (c.isLocked() || (none ? c.isDefaultVisible() : wanted.contains(c.getCode())))
            {
                result.add(c);
            }
        }
        return result.isEmpty() ? all : result;
    }

    // ===================== 导出说明：筛选条件 =====================

    private static String timeRange(BaseDashboardQuery q)
    {
        if (StringUtils.isNotEmpty(q.getSlotFrom()) && StringUtils.isNotEmpty(q.getSlotTo()))
        {
            return ExportTemplate.range(q.getSlotFrom(), q.getSlotTo());
        }
        if (StringUtils.isNotEmpty(q.getFrom()) && StringUtils.isNotEmpty(q.getTo()))
        {
            // from / to 是按天的闭区间
            return q.getFrom() + " – " + q.getTo();
        }
        return null;
    }

    private String orderFilter(BaseRecordQuery q, String timeLabel, List<String> extra)
    {
        List<String> parts = new ArrayList<>();
        String range = timeRange(q);
        if (range != null)
        {
            parts.add(timeLabel + " " + range);
        }
        parts.addAll(extra);
        common(q, parts);
        return String.join("；", parts);
    }

    private void common(BaseRecordQuery q, List<String> parts)
    {
        if (StringUtils.isNotEmpty(q.getKeyword()))
        {
            parts.add("關鍵字 " + q.getKeyword().trim());
        }
        if (StringUtils.isNotEmpty(q.getSourceMetricCode()))
        {
            MetricDefinition def = metricRegistry.get(q.getSourceMetricCode());
            String name = ExportMetricText.ALL.containsKey(q.getSourceMetricCode())
                ? ExportMetricText.ALL.get(q.getSourceMetricCode()).label
                : ExportTemplate.t(def == null ? q.getSourceMetricCode() : def.getLabel());
            parts.add("下鑽自指標 " + name);
        }
    }

    private static List<String> depositFilters(DepositRecordQuery q)
    {
        List<String> l = new ArrayList<>();
        if (StringUtils.isNotEmpty(q.getStatus()))
        {
            l.add("狀態 " + ("succ".equals(q.getStatus()) ? "成功" : "fail".equals(q.getStatus()) ? "失敗" : q.getStatus()));
        }
        return l;
    }

    private static List<String> withdrawFilters(WithdrawRecordQuery q)
    {
        List<String> l = new ArrayList<>();
        if (StringUtils.isNotEmpty(q.getStatus()))
        {
            l.add("狀態 " + q.getStatus());
        }
        if (StringUtils.isNotEmpty(q.getAuditStatus()))
        {
            l.add("審核 " + q.getAuditStatus());
        }
        return l;
    }

    private static List<String> betFilters(BetRecordQuery q)
    {
        List<String> l = new ArrayList<>();
        if (StringUtils.isNotEmpty(q.getVendorCode()))
        {
            l.add("遊戲平台Code " + q.getVendorCode());
        }
        if (StringUtils.isNotEmpty(q.getGameType()))
        {
            l.add("遊戲類型 " + q.getGameType());
        }
        if (StringUtils.isNotEmpty(q.getGame()))
        {
            l.add("遊戲 " + q.getGame());
        }
        if (StringUtils.isNotEmpty(q.getSettleStatus()))
        {
            String s = q.getSettleStatus();
            l.add("結算狀態 " + ("done".equals(s) ? "已結算" : "open".equals(s) ? "未結算" : "cancel".equals(s) ? "已取消" : s));
        }
        String amount = between(q.getBetAmountMin(), q.getBetAmountMax());
        if (amount != null)
        {
            l.add("投注金額 " + amount);
        }
        return l;
    }

    private String memberFilter(MemberRecordQuery q)
    {
        List<String> l = new ArrayList<>();
        addRange(l, "註冊時間", q.getRegisterTimeFrom(), q.getRegisterTimeTo());
        addRange(l, "首存時間", q.getFirstDepositTimeFrom(), q.getFirstDepositTimeTo());
        addRange(l, "最近投注時間", q.getLastBetTimeFrom(), q.getLastBetTimeTo());
        String range = timeRange(q);
        if (range != null)
        {
            l.add("時間範圍 " + range);
        }
        addEnum(l, "用戶狀態", q.getStatus(), com.fivetech.dashboard.service.MemberDict.STATUS);
        addEnum(l, "用戶類型", q.getUserType(), com.fivetech.dashboard.service.MemberDict.USER_TYPE);
        addEnum(l, "用戶等級", q.getLevel(), com.fivetech.dashboard.service.MemberDict.LEVEL);
        addEnum(l, "國家", q.getCountry(), com.fivetech.dashboard.service.MemberDict.COUNTRY);
        String dep = between(q.getCumulativeDepositMin(), q.getCumulativeDepositMax());
        if (dep != null)
        {
            l.add("歷史累計存款金額 " + dep);
        }
        common(q, l);
        return String.join("；", l);
    }

    private static void addRange(List<String> l, String name, String from, String to)
    {
        if (StringUtils.isNotEmpty(from) || StringUtils.isNotEmpty(to))
        {
            l.add(name + " " + (from == null ? "" : from) + " – " + (to == null ? "" : to));
        }
    }

    private static void addEnum(List<String> l, String name, String code, Map<String, String> dict)
    {
        if (StringUtils.isNotEmpty(code))
        {
            l.add(name + " " + ExportTemplate.t(dict.getOrDefault(code, code)));
        }
    }

    private static String between(BigDecimal min, BigDecimal max)
    {
        if (min == null && max == null)
        {
            return null;
        }
        return (min == null ? "" : min.stripTrailingZeros().toPlainString()) + " – "
            + (max == null ? "" : max.stripTrailingZeros().toPlainString());
    }

    private static String granularityText(Granularity g)
    {
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

    private String siteOf(QueryContext ctx, BaseDashboardQuery q)
    {
        if (ctx != null && StringUtils.isNotEmpty(ctx.getSiteCode()))
        {
            return ctx.getSiteCode();
        }
        return q == null ? null : q.getSiteCode();
    }

    /** 导出说明里的提示：截断、上下文警告 */
    private Map<String, String> tips(QueryContext ctx, boolean truncated, int rows)
    {
        Map<String, String> extra = new LinkedHashMap<>();
        List<String> tips = new ArrayList<>();
        if (truncated)
        {
            tips.add("數據超過導出上限，僅導出前 " + rows + " 行，請縮小篩選範圍");
        }
        if (ctx != null && ctx.isDelayed())
        {
            tips.add("本區間含約 " + ctx.getDelayWindowHours() + " 小時延遲數據，數值可能上調");
        }
        if (ctx != null && ctx.getWarnings() != null)
        {
            ctx.getWarnings().forEach(w -> tips.add(ExportTemplate.t(w)));
        }
        if (!tips.isEmpty())
        {
            extra.put("提示", String.join("；", tips));
        }
        return extra;
    }

    // ===================== 校验 / 分页 / 审计 =====================

    /**
     * 导出前的校验：开关、权限。
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
     * 导出忽略分页，取当前筛选条件下的全部数据，上限 {@code dashboard.export.max-rows}；
     * 超过上限时下层把 truncated 置为 true，导出说明里会提示。
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
        else if (query instanceof BaseRecordQuery)
        {
            BaseRecordQuery q = (BaseRecordQuery) query;
            q.setPageNum(1);
            q.setPageSize(max);
        }
    }

    /**
     * 记录导出审计：只在真正导出时记录。明细含个人信息，谁在什么条件下导走了多少行必须可追溯。
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
}
