package com.fivetech.promo.budget;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * 把上传的 .xlsx / .csv 读成「表头 + 数据行」，不做业务校验（FY-56 R8）。
 *
 * <ul>
 *   <li>行号 = 文件中的实际行号：表头为第 1 行，整行空白的行也占行号（空白行原样带出，由校验层跳过）。</li>
 *   <li>.xlsx 只读第一个工作表；日期类型、数值类型的单元格按值带出（{@link SheetCell#date} / {@link SheetCell#number}），
 *       不看显示格式；公式单元格取缓存结果；其余按文本。</li>
 *   <li>.csv 按 RFC 4180（逗号分隔、双引号转义）；编码先按 UTF-8（可带 BOM），不是合法 UTF-8 时按 GB18030 读。</li>
 * </ul>
 * 读不了（损坏、不是真的 xlsx 等）抛 {@link UnreadableException}，对应文件级 F2。
 *
 * @author fivetech
 */
final class BudgetFileReader
{
    /** 只关心前 4 列；表头另外记录第 4 列之后是否还有内容 */
    static final int COLUMNS = 6;

    private BudgetFileReader()
    {
    }

    /** 单元格：text 为原值（文本原样；日期 yyyy-MM-dd；数值不带千分位），date / number 只在按类型识别时有值 */
    static final class SheetCell
    {
        final String text;

        final LocalDate date;

        final BigDecimal number;

        SheetCell(String text, LocalDate date, BigDecimal number)
        {
            this.text = text == null ? "" : text;
            this.date = date;
            this.number = number;
        }

        static SheetCell text(String s)
        {
            return new SheetCell(s, null, null);
        }

        boolean isBlank()
        {
            return date == null && number == null && text.trim().isEmpty();
        }
    }

    static final class SheetRow
    {
        /** 文件中的实际行号（从 1 起，表头为 1） */
        final int line;

        final SheetCell[] cells;

        SheetRow(int line, SheetCell[] cells)
        {
            this.line = line;
            this.cells = cells;
        }

        SheetCell cell(int i)
        {
            return cells[i];
        }

        boolean isBlank()
        {
            for (SheetCell c : cells)
            {
                if (!c.isBlank())
                {
                    return false;
                }
            }
            return true;
        }
    }

    static final class SheetData
    {
        /** 表头（第 1 行）全部非空单元格之前的列，去掉首尾空格；文件完全为空时为空列表 */
        final List<String> header;

        /** 表头之后的行（含整行空白的行） */
        final List<SheetRow> rows;

        SheetData(List<String> header, List<SheetRow> rows)
        {
            this.header = header;
            this.rows = rows;
        }
    }

    static final class UnreadableException extends Exception
    {
        private static final long serialVersionUID = 1L;

        UnreadableException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }

    // ------------------------------------------------------------------ xlsx

    static SheetData readXlsx(byte[] bytes) throws UnreadableException
    {
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes)))
        {
            if (wb.getNumberOfSheets() == 0)
            {
                return new SheetData(List.of(), List.of());
            }
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            List<String> header = new ArrayList<>();
            List<SheetRow> rows = new ArrayList<>();
            int last = sheet.getLastRowNum();
            for (int r = 0; r <= last; r++)
            {
                Row row = sheet.getRow(r);
                if (r == 0)
                {
                    header = xlsxHeader(row, fmt);
                    continue;
                }
                SheetCell[] cells = new SheetCell[COLUMNS];
                for (int c = 0; c < COLUMNS; c++)
                {
                    cells[c] = xlsxCell(row == null ? null : row.getCell(c), fmt);
                }
                rows.add(new SheetRow(r + 1, cells));
            }
            return new SheetData(header, trimTrailingBlank(rows));
        }
        catch (IOException | RuntimeException e)
        {
            throw new UnreadableException("xlsx 无法读取：" + e.getMessage(), e);
        }
    }

    private static List<String> xlsxHeader(Row row, DataFormatter fmt)
    {
        List<String> out = new ArrayList<>();
        if (row == null)
        {
            return out;
        }
        for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++)
        {
            Cell cell = row.getCell(c);
            out.add(cell == null ? "" : fmt.formatCellValue(cell).trim());
        }
        return trimTrailingEmpty(out);
    }

    private static SheetCell xlsxCell(Cell cell, DataFormatter fmt)
    {
        if (cell == null)
        {
            return SheetCell.text("");
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == CellType.NUMERIC)
        {
            double v = cell.getNumericCellValue();
            if (DateUtil.isCellDateFormatted(cell))
            {
                LocalDateTime dt = cell.getLocalDateTimeCellValue();
                LocalDate d = dt == null ? null : dt.toLocalDate();
                return new SheetCell(d == null ? "" : d.toString(), d, null);
            }
            BigDecimal n = BigDecimal.valueOf(v).stripTrailingZeros();
            if (n.scale() < 0)
            {
                n = n.setScale(0);
            }
            return new SheetCell(n.toPlainString(), null, n);
        }
        if (type == CellType.STRING)
        {
            return SheetCell.text(cell.getRichStringCellValue() == null ? "" : cell.getRichStringCellValue().getString());
        }
        if (type == CellType.BLANK)
        {
            return SheetCell.text("");
        }
        // 布尔、错误值等：按显示文本校验（金额列会报「金額不是數字」）
        return SheetCell.text(fmt.formatCellValue(cell));
    }

    // ------------------------------------------------------------------ csv

    static SheetData readCsv(byte[] bytes) throws UnreadableException
    {
        String text = decode(bytes);
        List<List<String>> records = parseCsv(text);
        List<String> header = new ArrayList<>();
        List<SheetRow> rows = new ArrayList<>();
        for (int i = 0; i < records.size(); i++)
        {
            List<String> rec = records.get(i);
            if (i == 0)
            {
                for (String s : rec)
                {
                    header.add(s.trim());
                }
                header = trimTrailingEmpty(header);
                continue;
            }
            SheetCell[] cells = new SheetCell[COLUMNS];
            for (int c = 0; c < COLUMNS; c++)
            {
                cells[c] = SheetCell.text(c < rec.size() ? rec.get(c) : "");
            }
            rows.add(new SheetRow(i + 1, cells));
        }
        return new SheetData(header, trimTrailingBlank(rows));
    }

    /** UTF-8（去 BOM）；不是合法 UTF-8 时按 GB18030（Excel 中文版「CSV」默认编码） */
    static String decode(byte[] bytes) throws UnreadableException
    {
        int off = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        try
        {
            return strict(StandardCharsets.UTF_8).decode(ByteBuffer.wrap(bytes, off, bytes.length - off)).toString();
        }
        catch (CharacterCodingException e)
        {
            try
            {
                return strict(Charset.forName("GB18030")).decode(ByteBuffer.wrap(bytes)).toString();
            }
            catch (CharacterCodingException e2)
            {
                throw new UnreadableException("csv 编码无法识别", e2);
            }
        }
    }

    private static java.nio.charset.CharsetDecoder strict(Charset cs)
    {
        return cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    /**
     * RFC 4180：逗号分隔，双引号包裹的字段可含逗号、换行，"" 表示一个双引号。每条记录对应文件中的一行（引号内换行除外），
     * 空行是一条只有一个空字段的记录，保证行号与文件一致。
     */
    static List<List<String>> parseCsv(String text) throws UnreadableException
    {
        List<List<String>> out = new ArrayList<>();
        List<String> rec = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        int n = text.length();
        for (int i = 0; i < n; i++)
        {
            char ch = text.charAt(i);
            any = true;
            if (quoted)
            {
                if (ch == '"')
                {
                    if (i + 1 < n && text.charAt(i + 1) == '"')
                    {
                        field.append('"');
                        i++;
                    }
                    else
                    {
                        quoted = false;
                    }
                }
                else
                {
                    field.append(ch);
                }
                continue;
            }
            if (ch == '"' && field.length() == 0)
            {
                quoted = true;
            }
            else if (ch == ',')
            {
                rec.add(field.toString());
                field.setLength(0);
            }
            else if (ch == '\r' || ch == '\n')
            {
                if (ch == '\r' && i + 1 < n && text.charAt(i + 1) == '\n')
                {
                    i++;
                }
                rec.add(field.toString());
                field.setLength(0);
                out.add(rec);
                rec = new ArrayList<>();
                any = false;
            }
            else
            {
                field.append(ch);
            }
        }
        if (quoted)
        {
            throw new UnreadableException("csv 引号不成对", null);
        }
        if (any)
        {
            rec.add(field.toString());
            out.add(rec);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> trimTrailingEmpty(List<String> list)
    {
        int end = list.size();
        while (end > 0 && list.get(end - 1).isEmpty())
        {
            end--;
        }
        return new ArrayList<>(list.subList(0, end));
    }

    /** 文件末尾的空白行不算（Excel 常带格式化过的空行），中间的空白行保留占行号 */
    private static List<SheetRow> trimTrailingBlank(List<SheetRow> rows)
    {
        int end = rows.size();
        while (end > 0 && rows.get(end - 1).isBlank())
        {
            end--;
        }
        return new ArrayList<>(rows.subList(0, end));
    }
}
