package com.fivetech.promo.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** FY-56 预算与消耗导入校验（6 列模板）：文件级 F1–F7、行级 V1–V17、结果页统计（不连库、不调接口） */
class BudgetImportValidatorTest
{
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    private static final LocalDate START = LocalDate.of(2026, 3, 1);

    private static final List<PromoChannelDictionary.Promoter> DICT = List.of(
        new PromoChannelDictionary.Promoter("1001", "hada", List.of(
            new PromoChannelDictionary.Channel("2001", "DSP-hada"), new PromoChannelDictionary.Channel("2002", "meta-hada"))),
        new PromoChannelDictionary.Promoter("1002", "jason", List.of(new PromoChannelDictionary.Channel("2003", "TG-jason"))));

    private static BudgetImportValidator.Context spend()
    {
        return new BudgetImportValidator.Context(BudgetKind.SPEND, TODAY, START, DICT);
    }

    private static BudgetImportValidator.Context budget()
    {
        return new BudgetImportValidator.Context(BudgetKind.BUDGET, TODAY, START, DICT);
    }

    private static byte[] csv(String... lines)
    {
        return String.join("\r\n", lines).getBytes(StandardCharsets.UTF_8);
    }

    private static BudgetImportResult run(BudgetImportValidator.Context ctx, String... lines)
    {
        return BudgetImportValidator.validate("消耗.csv", csv(lines), ctx).getResult();
    }

    private static final String H = "日期,推廣商 ID,推廣商名稱,推廣渠道 ID,推廣渠道名稱,消耗";

    // ------------------------------------------------------------------ 文件级

    @Test
    void f1NotXlsxOrCsv()
    {
        BudgetImportResult r = BudgetImportValidator.validate("消耗.pdf", new byte[] { 1 }, spend()).getResult();
        assertEquals("REJECTED", r.getStatus());
        assertEquals("F1", r.getFileCode());
        assertEquals("未匯入：文件格式不支持", r.getTitle());
    }

    @Test
    void f2BrokenXlsx()
    {
        BudgetImportResult r = BudgetImportValidator.validate("消耗.xlsx", "not a zip".getBytes(), spend()).getResult();
        assertEquals("F2", r.getFileCode());
    }

    @Test
    void f3BudgetFileOnSpendTab()
    {
        BudgetImportResult r = run(spend(), "日期,推廣商 ID,推廣商名稱,推廣渠道 ID,推廣渠道名稱,預算", "2026-11-01,1001,hada,2001,DSP-hada,50");
        assertEquals("F3", r.getFileCode());
        assertEquals("表頭是「預算」，當前在匯入消耗。", r.getFileMessage());
    }

    @Test
    void f4HeaderMismatch()
    {
        assertEquals("F4", run(spend(), "date,channel,amount", "2026-11-01,2001,50").getFileCode());
        assertEquals("F4", run(spend(), "日期,推廣商 ID,推廣商名稱,推廣渠道 ID,推廣渠道名稱,消耗,備註", "2026-11-01,1001,hada,2001,DSP-hada,50,x").getFileCode());
        assertEquals("F4", run(spend()).getFileCode());   // 空文件
    }

    @Test
    void f5OnlyHeaderAndBlankRows()
    {
        assertEquals("F5", run(spend(), H, ",,,,,", "").getFileCode());
    }

    @Test
    void f6TooManyRows()
    {
        String[] lines = new String[10_002];
        lines[0] = H;
        for (int i = 1; i < lines.length; i++)
        {
            lines[i] = "2026-11-01,1001,hada,2001,DSP-hada,1";
        }
        assertEquals("F6", run(spend(), lines).getFileCode());
    }

    @Test
    void f7SpanOver31DaysCountsOnlyValidDates()
    {
        assertEquals("F7", run(spend(), H, "2026-10-01,1001,hada,2001,DSP-hada,1", "2026-11-01,1001,hada,2001,DSP-hada,1").getFileCode());
        // 31 天（首尾都算）可以
        assertEquals("SUCCESS", run(spend(), H, "2026-10-02,1001,hada,2001,DSP-hada,1", "2026-11-01,1001,hada,2001,DSP-hada,1").getStatus());
        // 日期格式错的行不参与跨度
        BudgetImportResult r = run(spend(), H, "2026/9/01,1001,hada,2001,DSP-hada,1", "2026-11-01,1001,hada,2001,DSP-hada,1");
        assertEquals("PARTIAL", r.getStatus());
        assertEquals("V2", r.getFailures().get(0).getCode());
    }

    @Test
    void f7UsesConfiguredMaxDaysAndShowsActualRange()
    {
        BudgetImportValidator.Context week = new BudgetImportValidator.Context(BudgetKind.SPEND, TODAY, START, DICT, 7);
        // 7 天（首尾都算）可以，8 天拒绝
        assertEquals("SUCCESS", run(week, H, "2026-10-26,1001,hada,2001,DSP-hada,1", "2026-11-01,1001,hada,2001,DSP-hada,1").getStatus());
        BudgetImportResult r = run(week, H, "2026-10-25,1001,hada,2001,DSP-hada,1", "2026-11-01,1001,hada,2001,DSP-hada,1");
        assertEquals("F7", r.getFileCode());
        assertEquals("文件裡的日期從 2026-10-25 到 2026-11-01，共 8 天；單個文件最多 7 天，請拆分後分別匯入。", r.getFileMessage());
        // 配置不合法（< 1）按默认 31 天
        BudgetImportValidator.Context bad = new BudgetImportValidator.Context(BudgetKind.SPEND, TODAY, START, DICT, 0);
        assertEquals("SUCCESS", run(bad, H, "2026-10-02,1001,hada,2001,DSP-hada,1", "2026-11-01,1001,hada,2001,DSP-hada,1").getStatus());
    }

    @Test
    void maxDaysPropertyFallsBackToDefaultOutsideRange()
    {
        BudgetImportProperties p = new BudgetImportProperties();
        assertEquals(31, p.maxDays());
        p.setMaxDays(62);
        assertEquals(62, p.maxDays());
        p.setMaxDays(0);
        assertEquals(31, p.maxDays());
        p.setMaxDays(1000);
        assertEquals(31, p.maxDays());
    }

    // ------------------------------------------------------------------ 行级

    @Test
    void rowChecksInOrderWithReasons()
    {
        BudgetImportResult r = run(spend(), H,
            ",1001,hada,2001,DSP-hada,50",                // 2 V1
            "11-01,1001,hada,2001,DSP-hada,",             // 3 V2（金额为空也报 V2）
            "2026-02-30,1001,hada,2001,DSP-hada,50",      // 4 V3
            "2026-11-04,1001,hada,2001,DSP-hada,50",      // 5 V4 今天
            "2026-02-28,1001,hada,2001,DSP-hada,50",      // 6 V6
            "2026-11-01,,hada,2001,DSP-hada,50",          // 7 V7
            "2026-11-01,9999,hada,2001,DSP-hada,50",      // 8 V8
            "2026-11-01,1001,jason,2001,DSP-hada,50",     // 9 V9 名称与 ID 不一致
            "2026-11-01,1001,,2001,DSP-hada,50",          // 10 V9 名称为空
            "2026-11-01,1001,hada,,DSP-hada,50",          // 11 V10
            "2026-11-01,1001,hada,8888,DSP-hada,60",      // 12 V11
            "2026-11-01,1001,hada,2001,meta-hada,60",     // 13 V12
            "2026-11-01,1002,jason,2001,DSP-hada,60",     // 14 V13
            "2026-11-01,1001,hada,2001,DSP-hada,$280",    // 15 V14
            "2026-11-02,1001,hada,2001,DSP-hada,\"1,280\"",// 16 V14 千分位
            "2026-11-03,1001,hada,2001,DSP-hada,-20",     // 17 V15
            "2026-10-31,1001,hada,2001,DSP-hada,45.678",  // 18 V16
            "2026-10-30,1001.0, HADA ,2001.00, dsp-HADA ,45.6"); // 19 有效（ID 按值、名称去空格不区分大小写）
        List<String> codes = r.getFailures().stream().map(BudgetImportResult.Failure::getCode).toList();
        assertEquals(List.of("V1", "V2", "V3", "V4", "V6", "V7", "V8", "V9", "V9", "V10", "V11", "V12", "V13", "V14", "V14", "V15", "V16"),
            codes);
        assertEquals("消耗只能是今天以前的日期", r.getFailures().get(3).getReason());
        assertEquals("推廣商名稱與 ID 不一致（ID 1001 為「hada」）", r.getFailures().get(7).getReason());
        assertEquals("推廣渠道名稱與 ID 不一致（ID 2001 為「DSP-hada」）", r.getFailures().get(11).getReason());
        assertEquals("推廣渠道不屬於這個推廣商", r.getFailures().get(12).getReason());
        assertEquals(2, r.getFailures().get(0).getRow());
        assertNull(r.getFailures().get(0).getDate());          // 空值前端写「（空）」
        assertEquals("jason", r.getFailures().get(7).getPromoterName());
        assertEquals("1001", r.getFailures().get(7).getPromoterId());
        assertEquals("1,280", r.getFailures().get(14).getAmount());
        assertEquals(18, r.getTotal());
        assertEquals(1, r.getValid());
        assertEquals("已匯入 1 行，17 行失敗", r.getTitle());
    }

    @Test
    void oldFourColumnTemplateRejectedWithHint()
    {
        BudgetImportResult r = run(spend(), "日期,推廣商,推廣渠道,消耗", "2026-11-01,hada,DSP-hada,50");
        assertEquals("F4", r.getFileCode());
        assertEquals("第 1 行應為「日期｜推廣商 ID｜推廣商名稱｜推廣渠道 ID｜推廣渠道名稱｜消耗」，請下載新模板。", r.getFileMessage());
        // 表头空格不影响
        assertEquals("SUCCESS", run(spend(), "日期,推廣商ID, 推廣商名稱 ,推廣渠道  ID,推廣渠道名稱,消耗", "2026-11-01,1001,hada,2001,DSP-hada,1").getStatus());
    }

    @Test
    void budgetUpToTodayPlus90()
    {
        BudgetImportResult r = run(budget(), "日期,推廣商 ID,推廣商名稱,推廣渠道 ID,推廣渠道名稱,預算",
            "2027-02-02,1001,hada,2001,DSP-hada,50",       // 今天＋90 天 → 可以
            "2027-02-03,1001,hada,2001,DSP-hada,50",       // V5
            "2027-01-20,1001,hada,2001,DSP-hada,50");      // 同一文件跨度不超过 31 天
        assertEquals(1, r.getFailed());
        assertEquals("V5", r.getFailures().get(0).getCode());
        assertEquals("預算最遠到今天起 90 天", r.getFailures().get(0).getReason());
    }

    @Test
    void emptyAmountNotImportedNotFailedAndBlankRowsKeepLineNumbers()
    {
        BudgetImportResult r = run(spend(), H, "2026-11-01,1001,hada,2001,DSP-hada,50", "", "2026-11-01,1001,hada,2002,meta-hada,", "2026-11-02,1001,hada,2001,DSP-hada,abc");
        assertEquals(3, r.getTotal());       // 空白行不计
        assertEquals(1, r.getValid());
        assertEquals(1, r.getEmpty());
        assertEquals(1, r.getFailed());
        assertEquals(5, r.getFailures().get(0).getRow());   // 第 3 行空白也占行号
        assertEquals("2026-11-01", r.getDateFrom());
        assertEquals(1, r.getChannelCount());
    }

    @Test
    void v17DuplicatesByChannelIdIncludeEmptyAmountRows()
    {
        BudgetImportResult r = run(spend(), H,
            "2026-11-01,1001,hada,2001,DSP-hada,50",       // 2
            "2026-11-01,1001,hada,2002,meta-hada,1",       // 3
            "2026-11-01,1001,hada,2001.0,dsp-hada,",     // 4 金额为空也参与判重；ID 按值
            "11-01,1001,hada,2001,DSP-hada,7");            // 5 V2，不参与判重
        assertEquals(1, r.getValid());
        assertEquals(0, r.getEmpty());
        assertEquals(3, r.getFailed());
        assertEquals("同一天同一渠道重複（第 2、4 行）", r.getFailures().get(0).getReason());
        assertEquals("V17", r.getFailures().get(1).getCode());
        assertEquals(4, r.getFailures().get(1).getRow());
        assertEquals("V2", r.getFailures().get(2).getCode());

        BudgetImportResult ok = run(spend(), H, "2026-11-01,1001,hada,2001,DSP-hada,50", "11-01,1001,hada,2001,DSP-hada,");
        assertEquals(1, ok.getValid());
        assertEquals("V2", ok.getFailures().get(0).getCode());
    }

    @Test
    void resultStates()
    {
        assertEquals("已匯入 1 行", run(spend(), H, "2026-11-01,1001,hada,2001,DSP-hada,0").getTitle());
        BudgetImportResult allFailed = run(spend(), H, "2026-11-01,1001,hada,2001,DSP-hada,x");
        assertEquals("ALL_FAILED", allFailed.getStatus());
        assertEquals("未匯入：沒有有效的行", allFailed.getTitle());
        assertNull(allFailed.getDateFrom());
        BudgetImportResult allEmpty = run(spend(), H, "2026-11-01,1001,hada,2001,DSP-hada,");
        assertEquals("ALL_EMPTY", allEmpty.getStatus());
        assertEquals("未匯入：所有行的金額都為空", allEmpty.getTitle());
    }

    @Test
    void legacyUsdtHeaderImportsWithNote()
    {
        BudgetImportResult r = run(spend(), "日期,推廣商 ID,推廣商名稱,推廣渠道 ID,推廣渠道名稱,消耗 (USDT)", "2026-11-01,1001,hada,2001,DSP-hada,50");
        assertEquals("SUCCESS", r.getStatus());
        assertEquals("金額按站點默認幣種匯入，不做換算（文件表頭寫的是「消耗 (USDT)」）。", r.getCurrencyNote());
        assertNull(run(spend(), H, "2026-11-01,1001,hada,2001,DSP-hada,50").getCurrencyNote());
    }

    @Test
    void csvWithBomAndGb18030()
    {
        byte[] bom = ("﻿" + H + "\r\n2026-11-01,1001,hada,2001,DSP-hada,50").getBytes(StandardCharsets.UTF_8);
        assertEquals("SUCCESS", BudgetImportValidator.validate("a.csv", bom, spend()).getResult().getStatus());
        byte[] gb = (H + "\r\n2026-11-01,1001,hada,2001,DSP-hada,50").getBytes(Charset.forName("GB18030"));
        assertEquals("SUCCESS", BudgetImportValidator.validate("a.CSV", gb, spend()).getResult().getStatus());
    }

    @Test
    void xlsxTypedCellsReadByValueTextCellsByFormat() throws Exception
    {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream())
        {
            Sheet s = wb.createSheet();
            String[] h = { "日期", "推廣商 ID", "推廣商名稱", "推廣渠道 ID", "推廣渠道名稱", "消耗" };
            Row hr = s.createRow(0);
            for (int i = 0; i < h.length; i++)
            {
                hr.createCell(i).setCellValue(h[i]);
            }
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.createDataFormat().getFormat("mm-dd"));
            CellStyle thousands = wb.createCellStyle();
            thousands.setDataFormat(wb.createDataFormat().getFormat("#,##0"));
            // 第 2 行：日期单元格显示为 11-01、数值显示为 1,280、ID 为数值单元格 → 按值识别，有效
            Row r2 = s.createRow(1);
            Cell d = r2.createCell(0);
            d.setCellValue(LocalDate.of(2026, 11, 1));
            d.setCellStyle(dateStyle);
            r2.createCell(1).setCellValue(1001);
            r2.createCell(2).setCellValue("hada");
            r2.createCell(3).setCellValue(2001);
            r2.createCell(4).setCellValue("DSP-hada");
            Cell a = r2.createCell(5);
            a.setCellValue(1280);
            a.setCellStyle(thousands);
            // 第 3 行：文本单元格「11-01」→ V2；第 4 行：文本「1,280」→ V14
            Row r3 = s.createRow(2);
            r3.createCell(0).setCellValue("11-01");
            r3.createCell(1).setCellValue("1001");
            r3.createCell(2).setCellValue("hada");
            r3.createCell(3).setCellValue("2002");
            r3.createCell(4).setCellValue("meta-hada");
            r3.createCell(5).setCellValue(5);
            Row r4 = s.createRow(3);
            r4.createCell(0).setCellValue("2026-11-02");
            r4.createCell(1).setCellValue("1001");
            r4.createCell(2).setCellValue("hada");
            r4.createCell(3).setCellValue("2002");
            r4.createCell(4).setCellValue("meta-hada");
            r4.createCell(5).setCellValue("1,280");
            // 第 5 行：数值单元格 45.678 → V16
            Row r5 = s.createRow(4);
            r5.createCell(0).setCellValue("2026/11/03");
            r5.createCell(1).setCellValue(1002);
            r5.createCell(2).setCellValue("jason");
            r5.createCell(3).setCellValue(2003);
            r5.createCell(4).setCellValue("TG-jason");
            r5.createCell(5).setCellValue(45.678);
            wb.write(out);
            bytes = out.toByteArray();
        }
        BudgetImportValidator.Outcome o = BudgetImportValidator.validate("消耗.xlsx", bytes, spend());
        BudgetImportResult r = o.getResult();
        assertEquals(1, r.getValid());
        assertEquals(new java.math.BigDecimal("1280"), o.getRecords().get(0).getAmount());
        assertEquals(LocalDate.of(2026, 11, 1), o.getRecords().get(0).getDate());
        assertEquals("DSP-hada", o.getRecords().get(0).getChannelName());
        assertEquals("2001", o.getRecords().get(0).getChannelId());
        assertEquals(List.of("V2", "V14", "V16"), r.getFailures().stream().map(BudgetImportResult.Failure::getCode).toList());
        assertEquals("45.678", r.getFailures().get(2).getAmount());
        assertFalse(r.isWritten());
        assertTrue(r.getFailures().get(0).getRow() == 3);
    }
}
