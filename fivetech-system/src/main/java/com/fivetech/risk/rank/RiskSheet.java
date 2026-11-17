package com.fivetech.risk.rank;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
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
 * 风控导出的一张表：第 1 行标题（合并整行），第 2 行表头，第 3 行起数据（排行榜第一行数据为合计行）。
 * 数值写成数字：金额 {@code #,##0.00}、人数 {@code #,##0}、倍数 {@code 0.00}、比率 {@code 0.00%}（按百分数存，12.3 = 12.3%）；空留空。
 *
 * @author fivetech
 */
public final class RiskSheet
{
    public static final String TEXT = "TEXT";

    public static final String INT = "INT";

    public static final String MONEY = "MONEY";

    public static final String PCT = "PCT";

    public static final String DECIMAL = "DECIMAL";

    final String sheetName;

    public String title;

    public String fileName;

    final List<String> header = new ArrayList<>();

    final List<String> formats = new ArrayList<>();

    final List<Object[]> rows = new ArrayList<>();

    public RiskSheet(String sheetName, String title)
    {
        this.sheetName = sheetName == null || sheetName.isBlank() ? "Sheet1" : sheetName.replaceAll("[\\\\/?*\\[\\]:]", " ");
        this.title = title;
    }

    /** 指标管理的数值格式 → 导出格式 */
    public static String format(String valueFormat)
    {
        if (valueFormat == null)
        {
            return INT;
        }
        return switch (valueFormat)
        {
            case "MONEY" -> MONEY;
            case "PCT" -> PCT;
            case "MULTIPLE", "MIN" -> DECIMAL;
            default -> INT;
        };
    }

    public RiskSheet column(String name, String format)
    {
        header.add(name);
        formats.add(format);
        return this;
    }

    public Object[] newRow()
    {
        Object[] r = new Object[header.size()];
        rows.add(r);
        return r;
    }

    public List<String> getHeader()
    {
        return header;
    }

    public List<Object[]> getRows()
    {
        return rows;
    }

    public byte[] xlsx()
    {
        SXSSFWorkbook wb = new SXSSFWorkbook(500);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream())
        {
            Sheet sheet = wb.createSheet(sheetName);
            DataFormat df = wb.createDataFormat();
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle boldStyle = wb.createCellStyle();
            boldStyle.setFont(bold);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(df.getFormat("#,##0.00"));
            CellStyle integer = wb.createCellStyle();
            integer.setDataFormat(df.getFormat("#,##0"));
            CellStyle decimal = wb.createCellStyle();
            decimal.setDataFormat(df.getFormat("0.00"));
            CellStyle pct = wb.createCellStyle();
            pct.setDataFormat(df.getFormat("0.00%"));
            CellStyle text = wb.createCellStyle();
            text.setDataFormat(df.getFormat("@"));
            Row t = sheet.createRow(0);
            t.createCell(0).setCellValue(title == null ? "" : title);
            t.getCell(0).setCellStyle(boldStyle);
            if (header.size() > 1)
            {
                sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, header.size() - 1));
            }
            Row h = sheet.createRow(1);
            for (int i = 0; i < header.size(); i++)
            {
                Cell c = h.createCell(i);
                c.setCellValue(header.get(i));
                c.setCellStyle(boldStyle);
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
                    String f = formats.get(i);
                    if (v instanceof BigDecimal n && !TEXT.equals(f))
                    {
                        switch (f)
                        {
                            case PCT -> {
                                c.setCellValue(n.movePointLeft(2).doubleValue());
                                c.setCellStyle(pct);
                            }
                            case MONEY -> {
                                c.setCellValue(n.doubleValue());
                                c.setCellStyle(money);
                            }
                            case DECIMAL -> {
                                c.setCellValue(n.doubleValue());
                                c.setCellStyle(decimal);
                            }
                            default -> {
                                c.setCellValue(n.doubleValue());
                                c.setCellStyle(integer);
                            }
                        }
                    }
                    else
                    {
                        c.setCellValue(String.valueOf(v));
                        c.setCellStyle(text);
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
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
        finally
        {
            try
            {
                wb.close();
            }
            catch (IOException ignored)
            {
                // 临时文件关闭失败不影响结果
            }
        }
    }
}
