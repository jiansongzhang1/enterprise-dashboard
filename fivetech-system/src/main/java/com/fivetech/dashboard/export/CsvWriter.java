package com.fivetech.dashboard.export;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CSV 写出器。
 * <p>
 * 三个容易被忽略、但直接决定文件能不能用的细节：
 * <ol>
 *   <li><b>UTF-8 BOM</b>：不写 BOM，Excel 打开中文会乱码。</li>
 *   <li><b>CRLF 换行</b>：RFC 4180 要求，也是 Excel 的期望。</li>
 *   <li><b>公式注入防护</b>：以 {@code = + - @} 开头的值会被 Excel 当成公式执行。
 *       本系统的会员账号形如 {@code +91 70***1234}，<b>正好以加号开头</b>，
 *       不处理就会在打开时变成公式并报错，严重时可被构造成攻击载荷。</li>
 * </ol>
 *
 * @author fivetech
 */
public class CsvWriter implements AutoCloseable
{
    private static final String CRLF = "\r\n";

    private static final char[] BOM = { '﻿' };

    private final Writer writer;

    private boolean firstCell = true;

    public CsvWriter(OutputStream out) throws IOException
    {
        this.writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        // BOM 必须是文件的第一个字符，否则 Excel 仍按本地编码解析
        this.writer.write(BOM);
    }

    /**
     * 写一整行
     */
    public void writeRow(List<?> cells) throws IOException
    {
        if (cells != null)
        {
            for (Object cell : cells)
            {
                writeCell(cell);
            }
        }
        endRow();
    }

    /**
     * 写一个单元格
     */
    public void writeCell(Object value) throws IOException
    {
        if (!firstCell)
        {
            writer.write(',');
        }
        writer.write(escape(value));
        firstCell = false;
    }

    /**
     * 结束当前行
     */
    public void endRow() throws IOException
    {
        writer.write(CRLF);
        firstCell = true;
    }

    /**
     * 写一个空行，用于分隔说明区与数据区
     */
    public void writeBlankLine() throws IOException
    {
        writer.write(CRLF);
        firstCell = true;
    }

    public void flush() throws IOException
    {
        writer.flush();
    }

    @Override
    public void close() throws IOException
    {
        writer.flush();
        writer.close();
    }

    /**
     * 转义单元格。
     * <p>
     * null 写空串（不是字面量 "null"）；含逗号、引号、换行的值加双引号并把引号翻倍；
     * 可能被当成公式的值前置单引号。
     */
    static String escape(Object value)
    {
        if (value == null)
        {
            return "";
        }
        String text = String.valueOf(value);
        if (text.isEmpty())
        {
            return "";
        }
        if (isFormulaRisk(text))
        {
            text = "'" + text;
        }
        boolean needQuote = text.indexOf(',') >= 0 || text.indexOf('"') >= 0
            || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0
            || text.startsWith(" ") || text.endsWith(" ");
        if (!needQuote)
        {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    /**
     * 判断是否会被表格软件当作公式。
     * 注意会员账号以 {@code +} 开头，这条不是理论风险。
     */
    static boolean isFormulaRisk(String text)
    {
        char first = text.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@'
            || first == '\t' || first == '\r';
    }
}
