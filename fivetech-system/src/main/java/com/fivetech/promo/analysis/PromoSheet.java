package com.fivetech.promo.analysis;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * 推广分析导出的一张表：第 1 行标题（合并整行）、第 2 行表头、第 3 行起数据（导出说明 E1–E9 / FY-57）。
 *
 * <p>数值写成数字：金额 {@code #,##0.00}、人数 {@code #,##0}、比率 {@code 0.00%}（按小数存，12.3% 存 0.123）；
 * null 留空，不写 0。xlsx 生成失败时退回 csv（UTF-8 带 BOM，不带千分位，最多 4 位小数）。</p>
 *
 * @author fivetech
 */
final class PromoSheet
{
    static final String TEXT = "TEXT";
    static final String INT = "INT";
    static final String MONEY = "MONEY";
    static final String PCT = "PCT";

    /** 文本单元格里的「未到期」等 */
    static final String PENDING_TEXT = "未到期";

    final String sheetName;

    final String title;

    final List<String> header = new ArrayList<>();

    /** 每列的格式：TEXT / INT / MONEY / PCT */
    final List<String> formats = new ArrayList<>();

    /** 每行与 header 等长；元素为 String（文本）或 BigDecimal（数值，PCT 为百分数）或 null（空） */
    final List<Object[]> rows = new ArrayList<>();

    PromoSheet(String sheetName, String title)
    {
        this.sheetName = sheetName;
        this.title = title;
    }

    PromoSheet column(String name, String format)
    {
        header.add(name);
        formats.add(format);
        return this;
    }

    Object[] newRow()
    {
        Object[] r = new Object[header.size()];
        rows.add(r);
        return r;
    }

    byte[] xlsx() throws IOException
    {
        SXSSFWorkbook wb = new SXSSFWorkbook(500);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream())
        {
            Sheet sheet = wb.createSheet(sheetName);
            DataFormat df = wb.createDataFormat();
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle titleStyle = wb.createCellStyle();
            titleStyle.setFont(bold);
            CellStyle headStyle = wb.createCellStyle();
            headStyle.setFont(bold);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(df.getFormat("#,##0.00"));
            CellStyle integer = wb.createCellStyle();
            integer.setDataFormat(df.getFormat("#,##0"));
            CellStyle pct = wb.createCellStyle();
            pct.setDataFormat(df.getFormat("0.00%"));
            CellStyle idText = wb.createCellStyle();
            idText.setDataFormat(df.getFormat("@"));

            Row t = sheet.createRow(0);
            t.createCell(0).setCellValue(title);
            t.getCell(0).setCellStyle(titleStyle);
            if (header.size() > 1)
            {
                sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, header.size() - 1));
            }
            Row h = sheet.createRow(1);
            for (int i = 0; i < header.size(); i++)
            {
                Cell c = h.createCell(i);
                c.setCellValue(header.get(i));
                c.setCellStyle(headStyle);
            }
            int r = 2;
            for (Object[] row : rows)
            {
                Row x = sheet.createRow(r++);
                for (int i = 0; i < row.length; i++)
                {
                    Object v = row[i];
                    if (v == null)
                    {
                        continue;
                    }
                    Cell c = x.createCell(i);
                    if (v instanceof BigDecimal n && !TEXT.equals(formats.get(i)))
                    {
                        String f = formats.get(i);
                        if (PCT.equals(f))
                        {
                            c.setCellValue(n.movePointLeft(2).doubleValue());
                            c.setCellStyle(pct);
                        }
                        else
                        {
                            c.setCellValue(n.doubleValue());
                            c.setCellStyle(MONEY.equals(f) ? money : integer);
                        }
                    }
                    else
                    {
                        c.setCellValue(String.valueOf(v));
                        if (TEXT.equals(formats.get(i)))
                        {
                            c.setCellStyle(idText);
                        }
                    }
                }
            }
            for (int i = 0; i < header.size(); i++)
            {
                sheet.setColumnWidth(i, Math.min(40, Math.max(10, header.get(i).length() * 2 + 4)) * 256);
            }
            wb.write(out);
            return out.toByteArray();
        }
        finally
        {
            wb.close();
        }
    }

    byte[] csv()
    {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(cell(title)).append("\r\n");
        List<String> head = new ArrayList<>();
        header.forEach(x -> head.add(cell(x)));
        sb.append(String.join(",", head)).append("\r\n");
        for (Object[] row : rows)
        {
            List<String> cells = new ArrayList<>();
            for (int i = 0; i < row.length; i++)
            {
                Object v = row[i];
                if (v == null)
                {
                    cells.add("");
                }
                else if (v instanceof BigDecimal n && !TEXT.equals(formats.get(i)))
                {
                    BigDecimal x = PCT.equals(formats.get(i)) ? n.movePointLeft(2) : n;
                    cells.add(x.setScale(Math.min(4, Math.max(0, x.scale())), RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
                }
                else
                {
                    cells.add(cell(String.valueOf(v)));
                }
            }
            sb.append(String.join(",", cells)).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String cell(String s)
    {
        String v = s == null ? "" : s;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r"))
        {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
